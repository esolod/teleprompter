package com.solod.teleprompter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Плаваюче вікно телесуфлера поверх інших додатків (наприклад, Iriun Webcam,
 * коли телефон одночасно працює як камера для ПК і як окремий телесуфлер
 * прямо перед лінзою). Реалізовано як foreground-сервіс з
 * TYPE_APPLICATION_OVERLAY вікном -- перетягується, змінює розмір,
 * керується голосом так само, як повноекранний режим (PrompterActivity),
 * але не займає весь екран і не забирає фокус в іншого додатку.
 */
class OverlayPrompterService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private lateinit var vad: VoiceActivityDetector
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speedPxPerSec = 60f
    private var speaking = false
    private var pixelAccumulator = 0f
    private var lastFrameNanos = 0L
    private var fontSp = 20f

    private lateinit var scrollView: ScrollView
    private lateinit var textScript: TextView
    private lateinit var overlayDot: View

    private val scrollRunnable = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) {
                val dtSec = (now - lastFrameNanos) / 1_000_000_000f
                if (speaking) {
                    pixelAccumulator += speedPxPerSec * dtSec
                    val whole = pixelAccumulator.toInt()
                    if (whole > 0) {
                        scrollView.scrollBy(0, whole)
                        pixelAccumulator -= whole
                    }
                }
            }
            lastFrameNanos = now
            mainHandler.postDelayed(this, 16L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (overlayView == null) {
            setupOverlay(intent)
        } else {
            // сервіс вже показує вікно -- просто оновимо текст/параметри
            val text = intent?.getStringExtra(EXTRA_TEXT)
            if (text != null) textScript.text = text
        }
        return START_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Телесуфлер (плаваюче вікно)",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, OverlayPrompterService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Телесуфлер активний")
            .setContentText("Плаваюче вікно поверх інших додатків. Натисніть ✕ на вікні, щоб закрити.")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .addAction(0, "Закрити", stopPending)
            .build()
    }

    private fun setupOverlay(intent: Intent?) {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""
        fontSp = (intent?.getIntExtra(EXTRA_FONT_SP, 20) ?: 20).toFloat()
        speedPxPerSec = intent?.getFloatExtra(EXTRA_SPEED_PX_S, 60f) ?: 60f
        val thresholdDb = intent?.getFloatExtra(EXTRA_THRESHOLD_DB, -30f) ?: -30f

        val inflater = android.view.LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_prompter, null)
        overlayView = view

        scrollView = view.findViewById(R.id.overlayScrollView)
        textScript = view.findViewById(R.id.overlayTextScript)
        overlayDot = view.findViewById(R.id.overlayDot)
        val header = view.findViewById<View>(R.id.overlayHeader)
        val resizeHandle = view.findViewById<View>(R.id.overlayResizeHandle)
        val btnMinus = view.findViewById<TextView>(R.id.btnFontMinus)
        val btnPlus = view.findViewById<TextView>(R.id.btnFontPlus)
        val btnClose = view.findViewById<TextView>(R.id.btnOverlayClose)

        textScript.text = text
        textScript.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)

        val displayMetrics = resources.displayMetrics
        val defaultWidth = (displayMetrics.widthPixels * 0.92f).toInt()
        val defaultHeight = (280 * displayMetrics.density).toInt()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            defaultWidth,
            defaultHeight,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (displayMetrics.widthPixels * 0.04f).toInt()
        params.y = (displayMetrics.heightPixels * 0.08f).toInt()

        windowManager.addView(view, params)

        // --- перетягування вікна за заголовок ---
        var touchStartX = 0f
        var touchStartY = 0f
        var paramsStartX = 0
        var paramsStartY = 0
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    paramsStartX = params.x
                    paramsStartY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = paramsStartX + (event.rawX - touchStartX).toInt()
                    params.y = paramsStartY + (event.rawY - touchStartY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        // --- зміна розміру за нижній правий кут ---
        var resizeStartX = 0f
        var resizeStartY = 0f
        var widthStart = 0
        var heightStart = 0
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    widthStart = params.width
                    heightStart = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val minWidth = (200 * displayMetrics.density).toInt()
                    val minHeight = (150 * displayMetrics.density).toInt()
                    params.width = (widthStart + (event.rawX - resizeStartX).toInt())
                        .coerceAtLeast(minWidth)
                    params.height = (heightStart + (event.rawY - resizeStartY).toInt())
                        .coerceAtLeast(minHeight)
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        btnMinus.setOnClickListener {
            fontSp = (fontSp - 2f).coerceAtLeast(10f)
            textScript.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        }
        btnPlus.setOnClickListener {
            fontSp = (fontSp + 2f).coerceAtMost(60f)
            textScript.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        }
        btnClose.setOnClickListener { stopSelf() }

        vad = VoiceActivityDetector { isSpeaking, _ ->
            mainHandler.post {
                speaking = isSpeaking
                (overlayDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                    if (isSpeaking) 0xFF2ECC71.toInt() else 0xFF888888.toInt()
                )
            }
        }
        vad.thresholdDb = thresholdDb
        vad.start()

        lastFrameNanos = 0L
        mainHandler.post(scrollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(scrollRunnable)
        if (::vad.isInitialized) vad.stop()
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Якщо користувач змахнув застосунок з недавніх -- закриваємо і
        // плаваюче вікно, щоб не лишався сирітський overlay без керування.
        stopSelf()
    }

    companion object {
        const val EXTRA_TEXT = "overlay_extra_text"
        const val EXTRA_FONT_SP = "overlay_extra_font_sp"
        const val EXTRA_SPEED_PX_S = "overlay_extra_speed_px_s"
        const val EXTRA_THRESHOLD_DB = "overlay_extra_threshold_db"
        const val ACTION_STOP = "com.solod.teleprompter.action.STOP_OVERLAY"
        private const val CHANNEL_ID = "teleprompter_overlay"
        private const val NOTIFICATION_ID = 42
    }
}
