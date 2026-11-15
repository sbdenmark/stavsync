package dk.sbdenmark.stavsync

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Simpelt andenordens filter (lavpas eller højpas). */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun p(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        private fun make(rate: Int, f: Double, high: Boolean): Biquad {
            val q = 0.707
            val w = 2 * PI * f / rate
            val c = cos(w); val s = sin(w)
            val al = s / (2 * q); val a0 = 1 + al
            val b0 = if (high) (1 + c) / 2 else (1 - c) / 2
            val b1 = if (high) -(1 + c) else 1 - c
            return Biquad(b0 / a0, b1 / a0, b0 / a0, (-2 * c) / a0, (1 - al) / a0)
        }
        fun lowpass(rate: Int, f: Double) = make(rate, f, false)
        fun highpass(rate: Int, f: Double) = make(rate, f, true)
    }
}

/**
 * Lytter på mikrofonen eller (med projection) direkte på telefonens egen lyd.
 * Kalder onFrame hver 20 ms med energien i bassen, hele lyden og diskanten.
 */
@SuppressLint("MissingPermission")
class AudioIn(private val ctx: Context, private val projection: MediaProjection?) {
    private var rec: AudioRecord? = null
    private var job: Job? = null
    var label = ""; private set

    fun start(scope: CoroutineScope, onFrame: (bass: Double, full: Double, treble: Double) -> Unit): String? {
        val rate = 44100
        val n = 882 // 20 ms
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = maxOf(minBuf, n * 2 * 4)
        val r = try {
            if (projection != null) {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                label = "Telefonens lyd"
                AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bytes)
                    .setAudioPlaybackCaptureConfig(cfg).build()
            } else {
                val am = ctx.getSystemService(AudioManager::class.java)
                val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
                label = if (unprocessed) "Mikrofon (ubehandlet)" else "Mikrofon"
                AudioRecord.Builder()
                    .setAudioSource(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(fmt).setBufferSizeInBytes(bytes).build()
            }
        } catch (e: Exception) {
            return "Kunne ikke starte lyden: ${e.message}"
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return "Lyden kunne ikke startes. Er mikrofonen optaget af en anden app?"
        }
        rec = r
        try { r.startRecording() } catch (e: Exception) { r.release(); rec = null; return "Kunne ikke starte optagelse: ${e.message}" }

        val lp = Biquad.lowpass(rate, 160.0)
        val hp = Biquad.highpass(rate, 2500.0)
        job = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(n)
            try {
                while (isActive) {
                    var got = 0
                    while (got < n && isActive) {
                        val k = r.read(buf, got, n - got)
                        if (k <= 0) break
                        got += k
                    }
                    if (got < n) { delay(5); continue }
                    var sb = 0.0; var sf = 0.0; var sh = 0.0
                    for (i in 0 until n) {
                        val x = buf[i] / 32768.0
                        val b = lp.p(x); val h = hp.p(x)
                        sf += x * x; sb += b * b; sh += h * h
                    }
                    onFrame(sb / n, sf / n, sh / n)
                }
            } finally {
                try { r.stop() } catch (_: Exception) {}
                r.release()
            }
        }
        return null
    }

    fun stop() {
        job?.cancel(); job = null
        try { rec?.stop() } catch (_: Exception) {} // får read() til at returnere, så tråden kan slutte
        rec = null
    }
}
