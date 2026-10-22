package xyz.bodyparts.home

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Spots every press of the remote's Home button by watching the system log for
 * Fire OS starting Amazon's home screen.
 *
 * Accessibility events can't do this when an Amazon screen (its home, or Settings,
 * which lives inside it) is already showing: nothing new appears, so no event fires.
 * The log line is written every time.
 *
 * Needs READ_LOGS, which only adb can grant:
 * `adb shell pm grant xyz.bodyparts.home android.permission.READ_LOGS`
 */
class HomePressWatcher(private val onHomePressed: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var process: Process? = null

    fun start() {
        if (running) return
        running = true
        Thread(::watch, "HomePressWatcher").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        process?.destroy()
    }

    private fun watch() {
        while (running) {
            try {
                // -T 1: only lines from now on, not the history
                val p = ProcessBuilder("logcat", "-b", "system", "-T", "1", "-s", "ActivityManager:I")
                    .redirectErrorStream(true)
                    .start()
                process = p
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!running) break
                        if (HOME_PRESS.containsMatchIn(line)) main.post(onHomePressed)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "log watcher stopped", e)
            }
            // logcat exited (or couldn't start): try again shortly
            if (running) SystemClock.sleep(RESTART_DELAY_MS)
        }
    }

    private companion object {
        const val TAG = "HomeCatch"
        const val RESTART_DELAY_MS = 2000L

        // Home button: the system (uid 1000) starting Amazon's home screen. Other apps
        // starting it (Settings, our Fire TV tile) show their own uid and are ignored.
        val HOME_PRESS = Regex(
            """START u0 \{act=android\.intent\.action\.MAIN cat=\[android\.intent\.category\.HOME].*cmp=""" +
                Regex.escape(STOCK_LAUNCHER) + """/.*\} from uid 1000\b"""
        )
    }
}
