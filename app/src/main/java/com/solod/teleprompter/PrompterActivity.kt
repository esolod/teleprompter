package com.solod.teleprompter

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PrompterActivity : AppCompatActivity() {

    private lateinit var scrollView: ScrollView
    private lateinit var textScript: TextView
    private lateinit var textStatus: TextView
    private lateinit var btnManualPause: ImageButton

    private lateinit var vad: VoiceActivityDetector
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speedPxPerSec = 60f
    private var manualPause = false
    private var speaking = false
    private var pixelAccumulator = 0f
    private var lastFrameNanos = 0L

    private var lastLevelDb: Float = -90f
    private var thresholdDbForDisplay: Float = -30f

    private val scrollRunnable = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) {
                val dtSec = (now - lastFrameNanos) / 1_000_000_000f
                if (speaking && !manualPause) {
                    pixelAccumulator += speedPxPerSec * dtSec
                    val whole = pixelAccumulator.toInt()
                    if (whole > 0) {
                        scrollView.scrollBy(0, whole)
                        pixelAccumulator -= whole
                    }
                }
            }
            lastFrameNanos = now
            mainHandler.postDelayed(this, 16L) // ~60fps
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_prompter)
        hideSystemBars()

        scrollView = findViewById(R.id.scrollView)
        textScript = findViewById(R.id.textScript)
        textStatus = findViewById(R.id.textStatus)
        btnManualPause = findViewById(R.id.btnManualPause)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val fontSp = intent.getIntExtra(EXTRA_FONT_SP, 22)
        speedPxPerSec = intent.getFloatExtra(EXTRA_SPEED_PX_S, 60f)
        val thresholdDb = intent.getFloatExtra(EXTRA_THRESHOLD_DB, -30f)
        val mirror = intent.getBooleanExtra(EXTRA_MIRROR, false)

        textScript.text = text
        textScript.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat())

        if (mirror) {
            scrollView.scaleX = -1f
            textScript.scaleX = -1f
        }

        btnManualPause.setOnClickListener {
            manualPause = !manualPause
            updateStatus()
        }

        // тап по тексту теж працює як пауза/продовжити вручну
        scrollView.setOnClickListener {
            manualPause = !manualPause
            updateStatus()
        }

        vad = VoiceActivityDetector { isSpeaking, levelDb ->
            mainHandler.post {
                speaking = isSpeaking
                lastLevelDb = levelDb
                updateStatus()
            }
        }
        vad.thresholdDb = thresholdDb
        thresholdDbForDisplay = thresholdDb
    }

    private fun updateStatus() {
        val base = when {
            manualPause -> getString(R.string.status_manual_pause)
            speaking -> getString(R.string.status_speaking)
            else -> getString(R.string.status_silent)
        }
        val levelText = if (lastLevelDb <= -99f) {
            "ПОМИЛКА ЧИТАННЯ: ${vad.lastError ?: "?"}"
        } else {
            "рівень: ${lastLevelDb.toInt()}dB  поріг: ${thresholdDbForDisplay.toInt()}dB"
        }
        textStatus.text = "$base  |  $levelText"
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    override fun onResume() {
        super.onResume()
        lastFrameNanos = 0L
        mainHandler.post(scrollRunnable)
        val ok = vad.start()
        if (!ok) {
            android.widget.Toast.makeText(
                this,
                "Мікрофон не запустився: ${vad.lastError}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(scrollRunnable)
        vad.stop()
    }

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_FONT_SP = "extra_font_sp"
        const val EXTRA_SPEED_PX_S = "extra_speed_px_s"
        const val EXTRA_THRESHOLD_DB = "extra_threshold_db"
        const val EXTRA_MIRROR = "extra_mirror"
    }
}
