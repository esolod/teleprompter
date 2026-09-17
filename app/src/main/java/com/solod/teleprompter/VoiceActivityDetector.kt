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

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return
        val bufferSize = minBuf * 2

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        audioRecord = record
        running = true
        record.startRecording()

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
                    if (!speaking) {
                        speaking = true
                        onStateChanged(true, db)
                    }
                } else if (speaking && now - lastVoiceAt > hangoverMs) {
                    speaking = false
                    onStateChanged(false, db)
                }
            }
        }.apply {
            name = "vad-thread"
            isDaemon = true
            start()
        }
    }

    /** Синхронно записує [durationMs] мс тиші і повертає рекомендований поріг у dB. */
    @SuppressLint("MissingPermission")
    fun calibrateAmbientNoise(durationMs: Long = 2000L): Float {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return thresholdDb
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
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
