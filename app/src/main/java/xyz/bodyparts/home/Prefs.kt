package xyz.bodyparts.home

import android.content.Context
import android.content.Intent

/** Amazon's own home screen package on Fire TV. On Fire OS 7 it also hosts the Settings screens. */
const val STOCK_LAUNCHER = "com.amazon.tv.launcher"

/** Amazon's settings packages. Their entry points hand straight over to STOCK_LAUNCHER. */
val STOCK_SETTINGS = setOf("com.amazon.tv.settings.v2", "com.amazon.tv.settings")

fun stockHomeIntent(): Intent = Intent(Intent.ACTION_MAIN)
    .addCategory(Intent.CATEGORY_HOME)
    .setPackage(STOCK_LAUNCHER)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** In-memory state shared by the home screen and the Home-button service (same process). */
object HomeState {
    /** True while the user has deliberately gone to Amazon's home screen. */
    @Volatile var bypass = false
    /** Leave Amazon's screens alone until this time (uptime ms), while Settings opens. */
    @Volatile var bypassUntil = 0L
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** Whether the Home button should open this launcher. */
    var catchEnabled: Boolean
        get() = sp.getBoolean("catch", true)
        set(v) = sp.edit().putBoolean("catch", v).apply()

    /** Whether the cat wanders along the bottom of the home screen. */
    var catEnabled: Boolean
        get() = sp.getBoolean("cat", true)
        set(v) = sp.edit().putBoolean("cat", v).apply()

    /** App order, as package names. Apps not listed are added alphabetically at the end. */
    var order: List<String>
        get() = (sp.getString("order", "") ?: "").split(',').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("order", v.joinToString(",")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()
}
