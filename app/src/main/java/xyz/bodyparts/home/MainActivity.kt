package xyz.bodyparts.home

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.Collections
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val component: ComponentName,
        val category: String,
        val banner: Drawable?,
        val icon: Drawable?,
    )

    /** Label and artwork for a package; slow to load, so cached until the package changes. */
    private data class AppLook(val label: String, val banner: Drawable?, val icon: Drawable?)

    private lateinit var prefs: Prefs
    private lateinit var appsRow: LinearLayout
    private lateinit var inputsRow: LinearLayout
    private lateinit var cat: CatView
    private lateinit var moveHint: TextView

    /** The app being moved along the row with left/right, if any. */
    private var movingPkg: String? = null

    /** Every launchable app (including hidden ones), and the visible ones in display order. */
    private var allApps: List<AppEntry> = emptyList()
    private var visibleApps: List<AppEntry> = emptyList()
    private var appsLoaded = false

    /** App loading runs here, off the UI thread. lookCache is only touched on this thread. */
    private val loader = Executors.newSingleThreadExecutor()
    private val lookCache = HashMap<String, AppLook>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart
            loader.execute { pkg?.let { lookCache.remove(it) } }
            loadApps()
        }
    }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = keepingFocus { buildInputsRow() }
        override fun onInputRemoved(inputId: String) = keepingFocus { buildInputsRow() }
        override fun onInputUpdated(inputId: String) = keepingFocus { buildInputsRow() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        tvInputManager()?.registerCallback(inputCallback, Handler(Looper.getMainLooper()))
        buildInputsRow()
        inputsRow.getChildAt(0)?.requestFocus()
        loadApps()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        tvInputManager()?.unregisterCallback(inputCallback)
        loader.shutdownNow()
        super.onDestroy()
    }

    // Rows are only rebuilt when apps, inputs or settings change, so coming back
    // to the home screen just shows what's already there.
    override fun onResume() {
        super.onResume()
        HomeState.bypass = false
    }

    // ---------- Layout ----------

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { clipChildren = false }
        // Black with drifting glows, behind everything else
        root.addView(AmbientBackgroundView(this), FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(TextClock(this).apply {
            format12Hour = "EEEE d MMMM"
            format24Hour = "EEEE d MMMM"
            setTextColor(Color.argb(170, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(64), dp(44), 0, 0)
        })

        root.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(34), dp(64), 0)
        })

        moveHint = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.argb(150, 60, 62, 78))
            }
            visibility = View.GONE
        }
        root.addView(moveHint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(110)
        })

        // Added before the rows so tiles and their names draw over the cat
        cat = CatView(this).apply { visibility = if (prefs.catEnabled) View.VISIBLE else View.GONE }
        root.addView(cat, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            bottomMargin = dp(6)
        })

        appsRow = newRow()
        inputsRow = newRow()
        // Hold the apps row's space while apps load, so the inputs row doesn't jump.
        appsRow.minimumHeight = dp(16 + APP_H + 14 + 22 + 2)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(sectionLabel("Apps"))
            addView(scroller(appsRow))
            addView(sectionLabel("TV Inputs"))
            addView(scroller(inputsRow))
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            bottomMargin = dp(36)
        })
        return root
    }

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(64), dp(16), dp(64), dp(2))
    }

    private fun scroller(row: LinearLayout) = RowScroller(this).apply { addView(row) }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.argb(130, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(64), dp(10), 0, 0)
    }

    private fun spaced(tile: TileView): TileView {
        tile.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(26) }
        return tile
    }

    /** Stops left/right at either end of a row from jumping into the other row. */
    private fun pinRowEnds(row: LinearLayout) {
        if (row.childCount == 0) return
        val first = row.getChildAt(0)
        val last = row.getChildAt(row.childCount - 1)
        for (v in listOf(first, last)) if (v.id == View.NO_ID) v.id = View.generateViewId()
        first.nextFocusLeftId = first.id
        last.nextFocusRightId = last.id
    }

    // ---------- Refresh ----------

    /** Rebuilds part of the screen, keeping the selection on the same tile if it's still there. */
    private fun keepingFocus(rebuild: () -> Unit) {
        val focusedKey = (currentFocus as? TileView)?.key
        rebuild()
        val target = focusedKey?.let { findTile(it) }
            ?: appsRow.getChildAt(0)
            ?: inputsRow.getChildAt(0)
        target?.requestFocus()
    }

    private fun findTile(key: String): View? {
        for (row in listOf(appsRow, inputsRow)) {
            for (i in 0 until row.childCount) {
                val v = row.getChildAt(i)
                if (v is TileView && v.key == key) return v
            }
        }
        return null
    }

    // ---------- Apps ----------

    /** Reads installed apps in the background, then rebuilds the apps row. */
    private fun loadApps() {
        loader.execute {
            val apps = queryApps()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allApps = apps
                if (appsLoaded) {
                    keepingFocus { buildAppsRow() }
                } else {
                    // First load: start on the first app, not on whichever input got focus meanwhile
                    appsLoaded = true
                    buildAppsRow()
                    (appsRow.getChildAt(0) ?: inputsRow.getChildAt(0))?.requestFocus()
                }
            }
        }
    }

    /** Runs on the loader thread. */
    private fun queryApps(): List<AppEntry> {
        val pm = packageManager
        val leanback = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
        )
        val phone = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val picked = LinkedHashMap<String, Pair<android.content.pm.ResolveInfo, String>>()
        for (ri in leanback) picked.putIfAbsent(ri.activityInfo.packageName, ri to Intent.CATEGORY_LEANBACK_LAUNCHER)
        for (ri in phone) picked.putIfAbsent(ri.activityInfo.packageName, ri to Intent.CATEGORY_LAUNCHER)

        return picked.values
            .filter { (ri, _) -> ri.activityInfo.packageName !in EXCLUDED && ri.activityInfo.packageName != packageName }
            .map { (ri, category) ->
                val ai = ri.activityInfo
                val look = lookCache.getOrPut(ai.packageName) {
                    val b = try { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) } catch (e: Exception) { null }
                    val ic = if (b == null) try { ri.loadIcon(pm) } catch (e: Exception) { null } else null
                    AppLook(
                        ri.loadLabel(pm).toString(),
                        b?.let { shrink(it, dp(APP_W), dp(APP_H)) },
                        ic?.let { shrink(it, dp(ICON), dp(ICON)) },
                    )
                }
                AppEntry(
                    pkg = ai.packageName,
                    label = look.label,
                    component = ComponentName(ai.packageName, ai.name),
                    category = category,
                    banner = look.banner,
                    icon = look.icon,
                )
            }
    }

    /**
     * Draws app artwork once at the size it's shown. Some apps ship banners many times
     * bigger than a tile, and keeping those around costs tens of MB on a 1.7 GB TV.
     */
    private fun shrink(d: Drawable, w: Int, h: Int): Drawable {
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw in 1..w && ih in 1..h) return d
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (iw > 0 && ih > 0) {
            // Scale to cover the tile and centre it, like CENTER_CROP
            val scale = maxOf(w.toFloat() / iw, h.toFloat() / ih)
            val sw = (iw * scale).toInt()
            val sh = (ih * scale).toInt()
            d.setBounds((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2)
        } else {
            d.setBounds(0, 0, w, h)
        }
        d.draw(Canvas(bmp))
        return BitmapDrawable(resources, bmp)
    }

    /** Lays out allApps using the saved order and hidden list. Doesn't re-read installed apps. */
    private fun buildAppsRow() {
        val byPkg = allApps.associateBy { it.pkg }
        val order = prefs.order
        val ordered = order.mapNotNull { byPkg[it] } +
            allApps.filter { it.pkg !in order }.sortedBy { it.label.lowercase() }
        val hidden = prefs.hidden
        visibleApps = ordered.filter { it.pkg !in hidden }

        appsRow.removeAllViews()
        for (app in visibleApps) {
            val tile = TileView(this, "app:${app.pkg}", app.label, dp(APP_W), dp(APP_H))
            if (app.banner != null) tile.setBanner(app.banner) else tile.setIconAndName(app.icon, app.label)
            tile.setOnClickListener { launchApp(app) }
            if (app.pkg == movingPkg) tile.setMoving(true)
            appsRow.addView(spaced(tile))
        }
        pinRowEnds(appsRow)
    }

    private fun launchApp(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(app.category)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        start(intent, app.label)
    }

    private fun moveApp(pkg: String, delta: Int) {
        val keys = visibleApps.map { it.pkg }.toMutableList()
        val i = keys.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in keys.indices) return
        Collections.swap(keys, i, j)
        prefs.order = keys + prefs.order.filter { it !in keys }
        keepingFocus { buildAppsRow() }
    }

    private fun hideApp(pkg: String) {
        prefs.hidden = prefs.hidden + pkg
        keepingFocus { buildAppsRow() }
    }

    /** Picks the app up: left/right now move it along the row until OK is pressed. */
    private fun startMoving(pkg: String) {
        val label = visibleApps.firstOrNull { it.pkg == pkg }?.label ?: return
        movingPkg = pkg
        buildAppsRow()
        findTile("app:$pkg")?.requestFocus()
        moveHint.text = "Moving $label  ·  ◀ ▶ to move  ·  OK when done"
        moveHint.visibility = View.VISIBLE
    }

    private fun stopMoving() {
        movingPkg = null
        moveHint.visibility = View.GONE
        keepingFocus { buildAppsRow() }
    }

    // ---------- TV inputs ----------

    /**
     * The TV's physical inputs: HDMI ports, then composite, then the aerial tuner.
     * Apps like Channel 5 or Plex register their own "inputs" too; only hardware
     * inputs have ids ending in /HW<n>, so that's what's kept.
     */
    private fun loadInputs(): List<TvInputInfo> {
        val tim = tvInputManager() ?: return emptyList()
        return try {
            tim.tvInputList
                .filter { HARDWARE_INPUT_ID.containsMatchIn(it.id) && !it.isHidden(this) }
                .sortedWith(compareBy<TvInputInfo>({ inputRank(it) }, { inputLabel(it).length }, { inputLabel(it) }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun inputRank(info: TvInputInfo) = when (info.type) {
        TvInputInfo.TYPE_HDMI -> 0
        TvInputInfo.TYPE_TUNER -> 2
        else -> 1
    }

    private fun inputLabel(info: TvInputInfo): String =
        (info.loadCustomLabel(this) ?: info.loadLabel(this))?.toString() ?: "Input"

    private fun buildInputsRow() {
        inputsRow.removeAllViews()
        for (info in loadInputs()) {
            val label = inputLabel(info)
            val sub = if (info.type == TvInputInfo.TYPE_TUNER) "Live TV" else null
            val tile = TileView(this, "input:${info.id}", label, dp(INPUT_W), dp(INPUT_H))
            tile.setText(label, sub, Color.rgb(58, 60, 72), Color.rgb(32, 33, 40))
            tile.setOnClickListener { launchInput(info) }
            inputsRow.addView(spaced(tile))
        }

        val stock = TileView(this, "special:firetv", "Amazon home screen", dp(INPUT_W), dp(INPUT_H))
        stock.setText("Fire TV", "Amazon home", Color.rgb(35, 47, 62), Color.rgb(18, 24, 32))
        stock.setOnClickListener { openStockHome() }
        inputsRow.addView(spaced(stock))

        val settings = TileView(this, "special:settings", "Settings", dp(INPUT_W), dp(INPUT_H))
        settings.setText("Settings", null, Color.rgb(58, 60, 72), Color.rgb(32, 33, 40))
        settings.setOnClickListener {
            openAmazonSettings(Intent(Settings.ACTION_SETTINGS), "Settings")
        }
        inputsRow.addView(spaced(settings))
        pinRowEnds(inputsRow)
    }

    /**
     * HDMI and composite play in our own InputPlayerActivity: Amazon's input player is
     * locked to Amazon's apps, and a plain "view this input" request offers Live TV or
     * Freeview instead. The aerial goes to Amazon's Live TV app, which has the channels.
     */
    private fun launchInput(info: TvInputInfo) {
        if (info.isPassthroughInput) {
            start(InputPlayerActivity.intent(this, info.id, inputLabel(info)), inputLabel(info))
            return
        }
        val attempts = mutableListOf(
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(info.id)).setPackage(AMAZON_LIVE_TV),
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(info.id)),
        )
        info.serviceInfo?.packageName?.let { p ->
            packageManager.getLeanbackLaunchIntentForPackage(p)?.let { attempts += it }
        }
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next way of opening it
            } catch (e: SecurityException) {
            }
        }
        toast("Couldn't open ${inputLabel(info)}")
    }

    /** Amazon's home screen, staying there until the user opens an app or presses Home twice. */
    private fun openStockHome() {
        HomeState.bypass = true
        if (!start(stockHomeIntent(), "Fire TV home")) HomeState.bypass = false
    }

    /**
     * Fire OS 7 shows Settings inside Amazon's home screen app, so the Home-button
     * service is told to leave it alone while it opens. Moving around inside Settings
     * doesn't look like Amazon's home appearing again, so a short pause is enough,
     * and pressing Home from Settings still comes back here.
     */
    private fun openAmazonSettings(intent: Intent, what: String) {
        HomeState.bypassUntil = SystemClock.uptimeMillis() + SETTINGS_GRACE_MS
        if (!start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), what)) HomeState.bypassUntil = 0L
    }

    // ---------- Remote buttons & menus ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val pkg = movingPkg ?: return super.dispatchKeyEvent(event)
        // While moving, every key belongs to the move. Finishing waits for the key to come
        // up, so the same press of OK doesn't also open the app.
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.action == KeyEvent.ACTION_DOWN) {
                moveApp(pkg, if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> if (event.action == KeyEvent.ACTION_UP) stopMoving()
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> return true // it's the home screen; nowhere to go back to
            KeyEvent.KEYCODE_MENU -> {
                showMenu()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showMenu() {
        val tile = currentFocus as? TileView
        if (tile != null && tile.key.startsWith("app:")) {
            appMenu(tile.key.removePrefix("app:"), tile.title)
        } else {
            settingsMenu()
        }
    }

    private fun appMenu(pkg: String, label: String) {
        val items = arrayOf("Move", "Hide from home screen", "Choose apps to show…", "App info", "Home screen settings…")
        dialog().setTitle(label).setItems(items) { _, which ->
            when (which) {
                0 -> startMoving(pkg)
                1 -> hideApp(pkg)
                2 -> chooseAppsMenu()
                3 -> openAmazonSettings(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")), "App info"
                )
                4 -> settingsMenu()
            }
        }.show()
    }

    private fun settingsMenu() {
        val catchOn = prefs.catchEnabled
        val catOn = prefs.catEnabled
        val items = arrayOf(
            if (catchOn) "Home button opens this screen: On" else "Home button opens this screen: Off",
            "Choose apps to show (${visibleApps.size} of ${allApps.size})",
            "Reorder apps…",
            "Reset app order",
            if (catOn) "Cat: On" else "Cat: Off",
            "Open Amazon home screen",
        )
        val title = if (isCatchServiceEnabled()) "Home screen"
        else "Home screen (Home button catch not enabled yet, see README)"
        dialog().setTitle(title).setItems(items) { _, which ->
            when (which) {
                0 -> {
                    prefs.catchEnabled = !catchOn
                    toast(if (!catchOn) "Home button: On" else "Home button: Off")
                }
                1 -> chooseAppsMenu()
                2 -> reorderMenu()
                3 -> {
                    prefs.order = emptyList()
                    keepingFocus { buildAppsRow() }
                }
                4 -> {
                    prefs.catEnabled = !catOn
                    cat.visibility = if (!catOn) View.VISIBLE else View.GONE
                }
                5 -> openStockHome()
            }
        }.show()
    }

    /** Lists the row in order; picking an app starts moving it. */
    private fun reorderMenu() {
        if (visibleApps.isEmpty()) {
            toast("No apps on the home screen")
            return
        }
        val labels = visibleApps.mapIndexed { i, app -> "${i + 1}.  ${app.label}" }.toTypedArray()
        dialog().setTitle("Choose an app to move").setItems(labels) { _, which ->
            startMoving(visibleApps[which].pkg)
        }.show()
    }

    /** A checklist of every app; ticked ones appear in the apps row. */
    private fun chooseAppsMenu() {
        if (allApps.isEmpty()) {
            toast("No apps found yet")
            return
        }
        val apps = allApps.sortedBy { it.label.lowercase() }
        val hidden = prefs.hidden
        val shown = BooleanArray(apps.size) { apps[it].pkg !in hidden }
        dialog().setTitle("Apps on the home screen")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), shown) { _, which, checked ->
                shown[which] = checked
            }
            .setPositiveButton("Done") { _, _ ->
                // Keep hidden entries for apps not listed (e.g. uninstalled), so they stay hidden if reinstalled
                val listed = apps.map { it.pkg }.toSet()
                prefs.hidden = (hidden - listed) + apps.filterIndexed { i, _ -> !shown[i] }.map { it.pkg }
                keepingFocus { buildAppsRow() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isCatchServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    // ---------- Helpers ----------

    private fun tvInputManager() = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun start(intent: Intent, what: String): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        toast("Couldn't open $what")
        false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val APP_W = 208
        const val APP_H = 117
        const val INPUT_W = 176
        const val INPUT_H = 99
        const val ICON = 52

        const val SETTINGS_GRACE_MS = 3000L

        const val AMAZON_LIVE_TV = "com.amazon.tv.livetv"

        /** Shown elsewhere (Fire TV / Settings tiles) or not useful as app tiles. */
        val EXCLUDED = STOCK_SETTINGS + STOCK_LAUNCHER

        val HARDWARE_INPUT_ID = Regex("/HW\\d+$")
    }
}
