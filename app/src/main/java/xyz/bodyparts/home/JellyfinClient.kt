package xyz.bodyparts.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Just enough of the Jellyfin API for the Continue Watching row: Quick Connect
 * sign-in, the user's resume list, and artwork. Every call blocks, so run them off
 * the UI thread.
 */
class JellyfinClient(
    private val server: String,
    private val deviceId: String,
    private val token: String? = null,
) {

    data class QuickConnect(val code: String, val secret: String)
    data class Login(val token: String, val userId: String)

    data class ResumeItem(
        val id: String,
        /** Series name for episodes, otherwise the item's name. */
        val title: String,
        /** e.g. "S1:E2 · The Episode", or null for films. */
        val subtitle: String?,
        val imageUrl: String?,
        /** 0..1 */
        val progress: Float,
        val positionMs: Long,
        val seriesId: String?,
        /** UTC ISO timestamps from the server, e.g. "2026-10-21T22:13:05.123Z". */
        val lastPlayed: String?,
        val premiere: String?,
    )

    fun serverName(): String = get("/System/Info/Public").optString("ServerName", "Jellyfin")

    fun startQuickConnect(): QuickConnect {
        val r = request("POST", "/QuickConnect/Initiate")
        return QuickConnect(r.getString("Code"), r.getString("Secret"))
    }

    fun isQuickConnectApproved(secret: String): Boolean =
        get("/QuickConnect/Connect?secret=" + Uri.encode(secret)).optBoolean("Authenticated")

    fun finishQuickConnect(secret: String): Login {
        val r = request("POST", "/Users/AuthenticateWithQuickConnect", JSONObject().put("Secret", secret))
        return Login(r.getString("AccessToken"), r.getJSONObject("User").getString("Id"))
    }

    /**
     * Continue Watching the way Wholphin builds it: things in progress plus the next
     * episode of shows you've started ("Next Up"), most recently watched first.
     */
    fun continueWatching(userId: String, limit: Int = ROW_LIMIT): List<ResumeItem> {
        val resume = resumeItems(userId, limit)
        val removed = removedFromNextUp(userId)
        val nextUp = nextUp(userId, limit).filter { item ->
            // Series removed from Next Up in Wholphin stay hidden until watched again
            val removedAt = item.seriesId?.let { removed[idKey(it)] } ?: return@filter true
            val played = item.lastPlayed ?: return@filter false
            played.take(19) > removedAt.take(19)
        }
        val inResume = resume.map { it.id }.toSet()
        val playedAt = HashMap<String, String>()
        resume.forEach { playedAt[it.id] = it.lastPlayed ?: "" }
        nextUp.forEach { playedAt[it.id] = nextUpLastPlayed(userId, it) }
        return (resume + nextUp.filter { it.id !in inResume })
            .sortedByDescending { playedAt[it.id]?.take(19) ?: "" }
            .take(limit)
    }

    fun resumeItems(userId: String, limit: Int = ROW_LIMIT): List<ResumeItem> {
        val r = get(
            "/UserItems/Resume?userId=$userId&limit=$limit&mediaTypes=Video" +
                "&enableImageTypes=Primary,Backdrop,Thumb&imageTypeLimit=1&enableUserData=true"
        )
        val items = r.optJSONArray("Items") ?: JSONArray()
        return (0 until items.length()).map { toResumeItem(items.getJSONObject(it)) }
    }

    /** Next unwatched episode of each series in progress (Wholphin's defaults: no rewatching, no age limit). */
    fun nextUp(userId: String, limit: Int = ROW_LIMIT): List<ResumeItem> {
        val r = get(
            "/Shows/NextUp?userId=$userId&limit=$limit&enableResumable=false&enableRewatching=false" +
                "&enableUserData=true&enableImageTypes=Primary,Backdrop,Thumb&imageTypeLimit=1"
        )
        val items = r.optJSONArray("Items") ?: JSONArray()
        return (0 until items.length()).map { toResumeItem(items.getJSONObject(it)) }
    }

    /**
     * When a Next Up episode counts as "last played", as Wholphin works it out: when the
     * episode before it was watched, or its premiere if it came out after that.
     */
    private fun nextUpLastPlayed(userId: String, item: ResumeItem): String {
        val seriesId = item.seriesId ?: return item.premiere ?: ""
        val key = "$seriesId/${item.id}"
        lastPlayedCache[key]?.let { (value, at) ->
            if (System.currentTimeMillis() - at < LAST_PLAYED_CACHE_MS) return value
        }
        val previous = try {
            get("/Shows/$seriesId/Episodes?userId=$userId&adjacentTo=${item.id}&limit=1&enableUserData=true")
                .optJSONArray("Items")?.optJSONObject(0)
                ?.optJSONObject("UserData")?.optString("LastPlayedDate")?.takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            null
        } ?: ""
        val premiere = item.premiere ?: ""
        val value = if (premiere.take(19) > previous.take(19)) premiere else previous
        lastPlayedCache[key] = value to System.currentTimeMillis()
        return value
    }

    /** Series the user removed from Next Up in Wholphin, with when (ids without hyphens). */
    private fun removedFromNextUp(userId: String): Map<String, String> = try {
        val prefs = get("/DisplayPreferences/default?userId=$userId&client=Wholphin")
        val raw = prefs.optJSONObject("CustomPrefs")?.optString("removeNextUp")?.takeIf { it.isNotEmpty() }
        val ids = raw?.let { JSONObject(it).optJSONObject("value") }
        ids?.keys()?.asSequence()?.associate { idKey(it) to ids.getString(it) }.orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun idKey(id: String) = id.replace("-", "").lowercase()

    /** Downloads an image the server has already scaled to about [width] pixels wide. */
    fun image(url: String, width: Int): Bitmap? {
        val conn = open("GET", "$url&maxWidth=$width")
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun toResumeItem(o: JSONObject): ResumeItem {
        val id = o.getString("Id")
        val isEpisode = o.optString("Type") == "Episode"
        val user = o.optJSONObject("UserData")
        val subtitle = if (isEpisode) {
            val s = o.optInt("ParentIndexNumber", -1)
            val e = o.optInt("IndexNumber", -1)
            val number = if (s >= 0 && e >= 0) "S$s:E$e · " else ""
            number + o.optString("Name")
        } else null
        return ResumeItem(
            id = id,
            title = if (isEpisode) o.optString("SeriesName", o.optString("Name")) else o.optString("Name"),
            subtitle = subtitle,
            imageUrl = landscapeImage(o, isEpisode),
            progress = ((user?.optDouble("PlayedPercentage", 0.0) ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
            positionMs = (user?.optLong("PlaybackPositionTicks", 0L) ?: 0L) / 10_000,
            seriesId = o.optString("SeriesId").takeIf { it.isNotEmpty() },
            lastPlayed = user?.optString("LastPlayedDate")?.takeIf { it.isNotEmpty() },
            premiere = o.optString("PremiereDate").takeIf { it.isNotEmpty() },
        )
    }

    /** The best 16:9 picture: an episode's still, otherwise a thumb or backdrop. */
    private fun landscapeImage(o: JSONObject, isEpisode: Boolean): String? {
        val id = o.getString("Id")
        val tags = o.optJSONObject("ImageTags")
        fun own(type: String) = tags?.optString(type)?.takeIf { it.isNotEmpty() }?.let { imageUrl(id, type, it) }
        fun backdrop() = o.optJSONArray("BackdropImageTags")?.optString(0)?.takeIf { it.isNotEmpty() }
            ?.let { imageUrl(id, "Backdrop", it) }
        fun parentThumb() = o.optString("ParentThumbImageTag").takeIf { it.isNotEmpty() }
            ?.let { imageUrl(o.optString("ParentThumbItemId"), "Thumb", it) }
        fun parentBackdrop() = o.optJSONArray("ParentBackdropImageTags")?.optString(0)?.takeIf { it.isNotEmpty() }
            ?.let { imageUrl(o.optString("ParentBackdropItemId"), "Backdrop", it) }

        return if (isEpisode) own("Primary") ?: parentThumb() ?: parentBackdrop()
        else own("Thumb") ?: backdrop() ?: own("Primary")
    }

    private fun imageUrl(itemId: String, type: String, tag: String) =
        "$server/Items/$itemId/Images/$type?tag=$tag&quality=90"

    // ---------- HTTP ----------

    private fun get(path: String) = request("GET", path)

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = open(method, server + path)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from $path")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            val auth = StringBuilder("MediaBrowser Client=\"Simple Launcher\", Device=\"Fire TV\", ")
                .append("DeviceId=\"$deviceId\", Version=\"1.0\"")
            if (token != null) auth.append(", Token=\"$token\"")
            setRequestProperty("Authorization", auth.toString())
        }

    companion object {
        /** Wholphin's default number of items per home row. */
        const val ROW_LIMIT = 25
        private const val LAST_PLAYED_CACHE_MS = 2 * 60 * 60 * 1000L

        /** Same idea as Wholphin's 2-hour cache: one request per Next Up episode, not per refresh. */
        private val lastPlayedCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

        /**
         * Turns what someone might type or copy from a browser
         * ("192.168.1.20:8096/web/#/home") into a server base URL.
         */
        fun normalise(input: String): String {
            var s = input.trim()
            if (!s.contains("://")) s = "http://$s"
            val uri = Uri.parse(s)
            val path = (uri.path ?: "").substringBefore("/web").trimEnd('/')
            return "${uri.scheme}://${uri.encodedAuthority}$path"
        }
    }
}
