package xyz.bodyparts.home

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Fire OS won't let a third-party app become the default home screen, and it handles
 * the Home button before accessibility services can see it. So this opens ours when:
 *  - HomePressWatcher sees a Home press in the system log (instant, and works even
 *    from Amazon's own screens), if READ_LOGS has been granted;
 *  - Amazon's home screen comes to the front (after a restart, waking from standby,
 *    or as the fallback when READ_LOGS isn't granted).
 *
 * For 5 seconds after Home is pressed, Android holds back screens that apps open
 * ("app switch protection"), but not ones the system opens for them. So ours is
 * opened by an alarm, which the system delivers. Alarms normally can't fire sooner
 * than 5 seconds either; the install guide lowers that with
 * `settings put global alarm_manager_constants min_futurity=0`.
 *
 * Escape hatches:
 *  - double-press Home: stays on Amazon's home screen
 *  - the "Fire TV" tile sets HomeState.bypass
 *  - Menu > Home button: Off disables the redirect entirely
 */
class HomeCatchService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private val activityCache = HashMap<String, Boolean>()
    private var watcher: HomePressWatcher? = null

    private var lastHomePress = 0L
    private var lastStockShown = 0L
    private var lastRedirect = 0L
    private var ourShownAt = 0L

    private val openHomeIntent by lazy {
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        if (checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
            watcher = HomePressWatcher(::onHomePressed).also { it.start() }
            Log.i(TAG, "Home button catch connected (watching Home presses)")
        } else {
            Log.i(TAG, "Home button catch connected (no READ_LOGS: watching screens only)")
        }
        // After a restart, Amazon's home screen can appear before this service is running,
        // so we'd never see it. Open ours once if the TV has only just started.
        if (prefs.catchEnabled && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            Log.i(TAG, "just booted: opening home screen")
            openHome()
        }
    }

    override fun onDestroy() {
        watcher?.stop()
        super.onDestroy()
    }

    /** The remote's Home button, as seen by HomePressWatcher. */
    private fun onHomePressed() {
        if (!prefs.catchEnabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHomePress < DOUBLE_PRESS_MS) {
            Log.i(TAG, "double-press: staying on Amazon's home")
            lastHomePress = 0L
            HomeState.bypass = true
            cancelOpenHome()
            return
        }
        Log.d(TAG, "Home pressed")
        lastHomePress = now
        HomeState.bypass = false
        HomeState.bypassUntil = 0L
        openHome()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Only react to real screens, not toasts, keyboards or overlays.
        if (!isActivity(pkg, cls)) return

        Log.d(TAG, "screen: $pkg/$cls")
        val now = SystemClock.uptimeMillis()
        when (pkg) {
            packageName -> ourShownAt = now
            STOCK_LAUNCHER -> onStockHome(now)
            // Amazon's settings pass through these on their way into the launcher
            in STOCK_SETTINGS -> {}
            else -> {
                // The user moved on into an app: forget about Amazon's screen
                HomeState.bypass = false
                cancelOpenHome()
            }
        }
    }

    private fun onStockHome(now: Long) {
        if (!::prefs.isInitialized) prefs = Prefs(this)
        // Amazon's screen reports itself twice for one Home press
        val samePress = now - lastStockShown < SAME_PRESS_MS
        lastStockShown = now
        if (!prefs.catchEnabled || HomeState.bypass || now < HomeState.bypassUntil || samePress) return
        // Already handled by the Home-press watcher
        if (now - lastHomePress < HANDLED_MS) return

        // Without the watcher: a second Home press soon after we took over means
        // "take me to Amazon's home".
        if (watcher == null && ourShownAt > lastRedirect && now - lastRedirect < FALLBACK_DOUBLE_PRESS_MS) {
            Log.i(TAG, "double-press: letting Amazon's home through")
            HomeState.bypass = true
            return
        }

        lastRedirect = now
        openHome()
    }

    private fun openHome() {
        val alarms = getSystemService(AlarmManager::class.java)
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), openHomeIntent)
    }

    private fun cancelOpenHome() {
        getSystemService(AlarmManager::class.java).cancel(openHomeIntent)
    }

    private fun isActivity(pkg: String, cls: String): Boolean =
        activityCache.getOrPut("$pkg/$cls") {
            try {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

    override fun onInterrupt() {}

    private companion object {
        const val TAG = "HomeCatch"
        const val DOUBLE_PRESS_MS = 700L
        const val HANDLED_MS = 1500L
        const val SAME_PRESS_MS = 400L
        const val FALLBACK_DOUBLE_PRESS_MS = 1500L
        const val BOOT_WINDOW_MS = 3 * 60_000L
    }
}
