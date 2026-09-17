package com.solod.teleprompter

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Простий детектор голосової активності (VAD) на основі RMS-амплітуди PCM-потоку.
 * Без сторонніх бібліотек і без хмарного розпізнавання мови — все офлайн.
 *
 * Логіка:
 *  - читаємо мікрофон невеликими буферами (~30-40 мс)
 *  - рахуємо RMS амплітуду буфера і переводимо в приблизні dBFS
 *  - якщо рівень вище порогу -> "говорить" одразу
 *  - якщо нижче порогу -> "говорить" залишається true ще [hangoverMs],
 *    щоб не смикати скрол на паузах між словами/реченнями
 */
class VoiceActivityDetector(
    private val onStateChanged: (speaking: Boolean, levelDb: Float) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile var thresholdDb: Float = -30f
    @Volatile var hangoverMs: Long = 650L

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var speaking = false
    private var lastVoiceAt = 0L

    @Volatile var lastError: String? = null
        private set

    /** true, якщо запис реально стартував. false -> дивись [lastError]. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        lastError = null
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            lastError = "getMinBufferSize повернув $minBuf — пристрій не підтримує 16kHz mono PCM16"
            return false
        }
        val bufferSize = minBuf * 2

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
            )
        } catch (e: SecurityException) {
            lastError = "Немає дозволу RECORD_AUDIO: ${e.message}"
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            lastError = "AudioRecord не ініціалізувався (state=${record.state}) — мікрофон зайнятий іншим додатком?"
            record.release()
            return false
        }
        audioRecord = record
        running = true
        try {
            record.startRecording()
        } catch (e: Exception) {
            lastError = "startRecording() впав: ${e.message}"
            running = false
            record.release()
            audioRecord = null
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            lastError = "startRecording() пройшов, але recordingState != RECORDING"
            running = false
            record.release()
            audioRecord = null
            return false
        }

        thread = Thread {
            val chunk = ShortArray(SAMPLE_RATE / 25) // ~40ms chunks
            while (running) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                var sum = 0.0
                for (i in 0 until read) {
                    val s = chunk[i].toDouble()
                    sum += s * s
                }
                val rms = sqrt(sum / read)
                val db = if (rms < 1.0) -90f else (20.0 * log10(rms / 32767.0)).toFloat()

                val now = System.currentTimeMillis()
                if (db > thresholdDb) {
                    lastVoiceAt = now
                    if (!speaking) speaking = true
                } else if (speaking && now - lastVoiceAt > hangoverMs) {
                    speaking = false
                }
                // кожен чанк (~40мс) — щоб на екрані був живий індикатор рівня,
                // а не тільки в момент переходу тиша/мова
                onStateChanged(speaking, db)
            }
        }.apply {
            name = "vad-thread"
            isDaemon = true
            start()
        }
        return true
    }

    /** Синхронно записує [durationMs] мс тиші і повертає рекомендований поріг у dB. */
    @SuppressLint("MissingPermission")
    fun calibrateAmbientNoise(durationMs: Long = 2000L): Float {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return thresholdDb
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING, minBuf * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return thresholdDb
        }
        record.startRecording()
        val chunk = ShortArray(SAMPLE_RATE / 25)
        var maxDb = -90f
        val end = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < end) {
            val read = record.read(chunk, 0, chunk.size)
            if (read <= 0) continue
            var sum = 0.0
            for (i in 0 until read) {
                val s = chunk[i].toDouble()
                sum += s * s
            }
            val rms = sqrt(sum / read)
            val db = if (rms < 1.0) -90f else (20.0 * log10(rms / 32767.0)).toFloat()
            if (db > maxDb) maxDb = db
        }
        record.stop()
        record.release()
        return (maxDb + 10f).coerceAtMost(-12f) // запас 10dB над фоном, але не вище -12dB
    }

    fun stop() {
        running = false
        thread?.let {
            try { it.join(200) } catch (_: InterruptedException) {}
        }
        thread = null
        audioRecord?.let {
            try {
                if (it.state == AudioRecord.STATE_INITIALIZED) it.stop()
            } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
        speaking = false
    }
}
