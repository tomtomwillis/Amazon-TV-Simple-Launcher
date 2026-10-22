package xyz.bodyparts.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Shows an HDMI or composite input full screen.
 *
 * Amazon's own input player needs a permission only Amazon's apps can hold, and the
 * generic "view this input" request just offers Live TV or Freeview. So this does what
 * Amazon's player does underneath: tunes a TvView to the input's passthrough channel.
 * Back returns to the home screen; the input is released whenever this leaves the screen.
 */
class InputPlayerActivity : Activity() {

    private lateinit var tvView: TvView
    private lateinit var status: TextView
    private var inputId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvView = TvView(this).apply { setCallback(callback) }
        status = TextView(this).apply {
            setTextColor(Color.argb(200, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(tvView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(status, FrameLayout.LayoutParams(MATCH, MATCH))
        })
        onNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inputId = intent.getStringExtra(EXTRA_INPUT_ID)
        status.text = intent.getStringExtra(EXTRA_LABEL) ?: ""
        status.visibility = View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        val id = inputId ?: return finish()
        Log.i(TAG, "tuning to $id")
        audioFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // TvView only sends a volume if one is set, and the TV's HDMI service treats
        // "none" as silent
        tvView.setStreamVolume(1f)
        tvView.tune(id, TvContract.buildChannelUriForPassthroughInput(id))
    }

    override fun onStop() {
        // Hand the input hardware and the audio back, and don't linger behind other apps
        tvView.reset()
        if (audioFocus) audio.abandonAudioFocusRequest(focusRequest)
        audioFocus = false
        super.onStop()
        finish()
    }

    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var audioFocus = false
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
    }

    private val callback = object : TvView.TvInputCallback() {
        override fun onVideoAvailable(inputId: String) {
            status.visibility = View.GONE
        }

        override fun onVideoUnavailable(inputId: String, reason: Int) {
            status.text = when (reason) {
                TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> intent.getStringExtra(EXTRA_LABEL) ?: ""
                else -> "No signal on ${intent.getStringExtra(EXTRA_LABEL) ?: "this input"}"
            }
            status.visibility = View.VISIBLE
        }

        override fun onDisconnected(inputId: String) {
            Log.w(TAG, "input disconnected: $inputId")
            status.text = "Couldn't show this input"
            status.visibility = View.VISIBLE
        }
    }

    companion object {
        private const val TAG = "InputPlayer"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val EXTRA_INPUT_ID = "inputId"
        private const val EXTRA_LABEL = "label"

        fun intent(context: Context, inputId: String, label: String): Intent =
            Intent(context, InputPlayerActivity::class.java)
                .putExtra(EXTRA_INPUT_ID, inputId)
                .putExtra(EXTRA_LABEL, label)
    }
}
