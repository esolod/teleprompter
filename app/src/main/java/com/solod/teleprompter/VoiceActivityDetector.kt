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
 *
 * На частині пристроїв (помічено на деяких Samsung/One UI) сире джерело
 * MediaRecorder.AudioSource.MIC віддає технічно робочий, але повністю
 * "мовчазний" потік (усі семпли ~0), хоча дозвіл видано і читання не падає
 * з помилкою. Тому при старті ми по черзі пробуємо кілька джерел і
 * лишаємо перше, яке реально дає ненульовий сигнал за ~200мс тесту.
 */
class VoiceActivityDetector(
    private val onStateChanged: (speaking: Boolean, levelDb: Float) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // Порядок спроб: MIC найточніший, якщо працює; далі більш "оброблені"
        // джерела, які OEM-прошивки рідше глушать.
        private val SOURCE_CANDIDATES = listOf(
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT"
        )

        // Поріг "хоч щось реальне" під час автотесту джерела -- значно нижчий
        // за звичайний VAD-поріг, головне відрізнити повну цифрову тишу (0)
        // від будь-якого реального шуму приміщення.
        private const val PROBE_RMS_THRESHOLD = 8.0
    }

    @Volatile var thresholdDb: Float = -30f
    @Volatile var hangoverMs: Long = 650L

    @Volatile var activeSourceName: String? = null
        private set

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var speaking = false
    private var lastVoiceAt = 0L

    @Volatile var lastError: String? = null
        private set

    private fun rmsOf(chunk: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val s = chunk[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / len)
    }

    private fun dbOf(rms: Double): Float =
        if (rms < 1.0) -90f else (20.0 * log10(rms / 32767.0)).toFloat()

    /** Пробує відкрити джерело і за ~200мс перевіряє, чи є реальний (ненульовий) сигнал. */
    @SuppressLint("MissingPermission")
    private fun probeSource(source: Int, minBuf: Int): Pair<AudioRecord, Double>? {
        val record = try {
            AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, minBuf * 2)
        } catch (e: Exception) {
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            return null
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return null
        }

        val chunk = ShortArray(SAMPLE_RATE / 25)
        var maxRms = 0.0
        // ~5 чанків по 40мс = 200мс прослуховування
        repeat(5) {
            val read = record.read(chunk, 0, chunk.size)
            if (read > 0) {
                val rms = rmsOf(chunk, read)
                if (rms > maxRms) maxRms = rms
            }
        }
        return record to maxRms
    }

    /** true, якщо запис реально стартував. false -> дивись [lastError]. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        lastError = null
        activeSourceName = null
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            lastError = "getMinBufferSize повернув $minBuf — пристрій не підтримує 16kHz mono PCM16"
            return false
        }

        var chosen: AudioRecord? = null
        var chosenName: String? = null
        val probeResults = StringBuilder()

        for ((source, name) in SOURCE_CANDIDATES) {
            val result = probeSource(source, minBuf)
            if (result == null) {
                probeResults.append("$name=не відкрився; ")
                continue
            }
            val (record, maxRms) = result
            probeResults.append("$name=rms${maxRms.toInt()}; ")
            if (maxRms >= PROBE_RMS_THRESHOLD) {
                chosen = record
                chosenName = name
                break
            } else {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }

        // Якщо жодне джерело не дало реального сигналу -- все одно беремо
        // перше робоче (MIC), щоб додаток не завис у стані "нема мікрофона
        // взагалі"; але lastError понесе повний звіт по всіх джерелах.
        if (chosen == null) {
            val fallback = probeSource(MediaRecorder.AudioSource.MIC, minBuf)
            if (fallback == null) {
                lastError = "Жодне джерело не відкрилось. Проби: $probeResults"
                return false
            }
            chosen = fallback.first
            chosenName = "MIC (fallback, тиша на всіх джерелах)"
            lastError = "Усі джерела мовчать. Проби: $probeResults"
        }

        val record = chosen
        activeSourceName = chosenName
        audioRecord = record
        running = true

        thread = Thread {
            val chunk = ShortArray(SAMPLE_RATE / 25) // ~40ms chunks
            var consecutiveErrors = 0
            while (running) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    consecutiveErrors++
                    lastError = "AudioRecord.read() = $read (спроба #$consecutiveErrors), джерело=$chosenName"
                    onStateChanged(speaking, -99f)
                    continue
                }
                consecutiveErrors = 0

                val rms = rmsOf(chunk, read)
                val db = dbOf(rms)

                val now = System.currentTimeMillis()
                if (db > thresholdDb) {
                    lastVoiceAt = now
                    if (!speaking) speaking = true
                } else if (speaking && now - lastVoiceAt > hangoverMs) {
                    speaking = false
                }
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

        var record: AudioRecord? = null
        for ((source, _) in SOURCE_CANDIDATES) {
            val result = probeSource(source, minBuf)
            if (result != null) {
                record = result.first
                break
            }
        }
        if (record == null) return thresholdDb

        val chunk = ShortArray(SAMPLE_RATE / 25)
        var maxDb = -90f
        val end = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < end) {
            val read = record.read(chunk, 0, chunk.size)
            if (read <= 0) continue
            val rms = rmsOf(chunk, read)
            val db = dbOf(rms)
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
