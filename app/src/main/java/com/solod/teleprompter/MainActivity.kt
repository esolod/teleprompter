package com.solod.teleprompter

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var editScript: EditText
    private lateinit var seekFont: SeekBar
    private lateinit var seekSpeed: SeekBar
    private lateinit var seekSensitivity: SeekBar
    private lateinit var switchMirror: SwitchCompat
    private lateinit var btnCalibrate: MaterialButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnOverlay: MaterialButton
    private lateinit var textVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("teleprompter_prefs", MODE_PRIVATE)

        editScript = findViewById(R.id.editScript)
        seekFont = findViewById(R.id.seekFont)
        seekSpeed = findViewById(R.id.seekSpeed)
        seekSensitivity = findViewById(R.id.seekSensitivity)
        switchMirror = findViewById(R.id.switchMirror)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnStart = findViewById(R.id.btnStart)
        btnOverlay = findViewById(R.id.btnOverlay)
        textVersion = findViewById(R.id.textVersion)
        // Видно на головному екрані -- дозволяє одразу перевірити, що на
        // телефоні реально стоїть щойно зібраний APK, а не старий кеш.
        textVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})  build: ${BuildConfig.BUILD_TIME}"

        editScript.setText(prefs.getString(KEY_SCRIPT, ""))
        seekFont.progress = prefs.getInt(KEY_FONT, 14)
        seekSpeed.progress = prefs.getInt(KEY_SPEED, 35)
        seekSensitivity.progress = prefs.getInt(KEY_SENSITIVITY, sensitivityFromDb(prefs.getFloat(KEY_THRESHOLD_DB, -30f)))
        switchMirror.isChecked = prefs.getBoolean(KEY_MIRROR, false)

        btnCalibrate.setOnClickListener { calibrate() }
        btnStart.setOnClickListener { start() }
        btnOverlay.setOnClickListener { startOverlay() }
    }

    private fun sensitivityFromDb(db: Float): Int {
        // inverse of thresholdFromSensitivity below
        return (((-15f - db) / 30f) * 100f).toInt().coerceIn(0, 100)
    }

    private fun thresholdFromSensitivity(progress: Int): Float {
        // 0 = less sensitive (-15dB), 100 = very sensitive (-45dB)
        return -15f - (progress / 100f) * 30f
    }

    private fun calibrate() {
        if (!hasMicPermission()) {
            requestMicPermission { calibrate() }
            return
        }
        Toast.makeText(this, R.string.calibrating, Toast.LENGTH_SHORT).show()
        btnCalibrate.isEnabled = false
        Thread {
            val vad = VoiceActivityDetector { _, _ -> }
            val db = vad.calibrateAmbientNoise(2000L)
            runOnUiThread {
                val progress = sensitivityFromDb(db)
                seekSensitivity.progress = progress
                prefs.edit().putFloat(KEY_THRESHOLD_DB, db).apply()
                btnCalibrate.isEnabled = true
                Toast.makeText(this, R.string.calibrated, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun start() {
        val text = editScript.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.hint_script, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasMicPermission()) {
            requestMicPermission { start() }
            return
        }

        prefs.edit()
            .putString(KEY_SCRIPT, text)
            .putInt(KEY_FONT, seekFont.progress)
            .putInt(KEY_SPEED, seekSpeed.progress)
            .putInt(KEY_SENSITIVITY, seekSensitivity.progress)
            .putFloat(KEY_THRESHOLD_DB, thresholdFromSensitivity(seekSensitivity.progress))
            .putBoolean(KEY_MIRROR, switchMirror.isChecked)
            .apply()

        val fontSp = (seekFont.progress + 8).coerceAtLeast(12)
        val speedPxPerSec = 20f + (seekSpeed.progress / 100f) * 240f
        val thresholdDb = thresholdFromSensitivity(seekSensitivity.progress)

        val intent = Intent(this, PrompterActivity::class.java).apply {
            putExtra(PrompterActivity.EXTRA_TEXT, text)
            putExtra(PrompterActivity.EXTRA_FONT_SP, fontSp)
            putExtra(PrompterActivity.EXTRA_SPEED_PX_S, speedPxPerSec)
            putExtra(PrompterActivity.EXTRA_THRESHOLD_DB, thresholdDb)
            putExtra(PrompterActivity.EXTRA_MIRROR, switchMirror.isChecked)
        }
        startActivity(intent)
    }

    private fun startOverlay() {
        val text = editScript.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.hint_script, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasMicPermission()) {
            requestMicPermission { startOverlay() }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        launchOverlayService(text)
    }

    private fun launchOverlayService(text: String) {
        prefs.edit()
            .putString(KEY_SCRIPT, text)
            .putInt(KEY_FONT, seekFont.progress)
            .putInt(KEY_SPEED, seekSpeed.progress)
            .putInt(KEY_SENSITIVITY, seekSensitivity.progress)
            .putFloat(KEY_THRESHOLD_DB, thresholdFromSensitivity(seekSensitivity.progress))
            .putBoolean(KEY_MIRROR, switchMirror.isChecked)
            .apply()

        val fontSp = (seekFont.progress + 8).coerceAtLeast(12)
        val speedPxPerSec = 20f + (seekSpeed.progress / 100f) * 240f
        val thresholdDb = thresholdFromSensitivity(seekSensitivity.progress)

        val intent = Intent(this, OverlayPrompterService::class.java).apply {
            putExtra(OverlayPrompterService.EXTRA_TEXT, text)
            putExtra(OverlayPrompterService.EXTRA_FONT_SP, fontSp)
            putExtra(OverlayPrompterService.EXTRA_SPEED_PX_S, speedPxPerSec)
            putExtra(OverlayPrompterService.EXTRA_THRESHOLD_DB, thresholdDb)
        }
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            launchOverlayService(editScript.text.toString())
        } else {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    // ---- permission handling without extra deps ----
    private fun hasMicPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private var pendingAction: (() -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingAction?.invoke()
        } else {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
        }
        pendingAction = null
    }

    private fun requestMicPermission(onGranted: () -> Unit) {
        pendingAction = onGranted
        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        private const val KEY_SCRIPT = "script"
        private const val KEY_FONT = "font"
        private const val KEY_SPEED = "speed"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_THRESHOLD_DB = "threshold_db"
        private const val KEY_MIRROR = "mirror"
    }
}
