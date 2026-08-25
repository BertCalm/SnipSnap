package com.snipsnap.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The contract the app's media decode must honour — how we verify
 * `MediaExtractor`/Media3 output against ground truth when the decoder
 * itself only exists on a device.
 *
 * The deal: [fixture] defines a deterministic test signal in code (so
 * ground truth needs no committed binaries); [writeFixtures] emits the
 * canonical WAV set that gets *encoded* into the compressed twins
 * (`.m4a`, `.mp3`, …) on a desktop; the app's instrumentation test
 * decodes a twin back to a [Snip] and hands both to [verify], which is
 * deliberately tolerant of what lossy codecs legitimately do — encoder
 * delay and padding (handled by click alignment), gentle spectral loss
 * (handled by correlation, not sample equality) — and intolerant of what
 * they must never do: wrong duration, wrong pitch, wrong channel count,
 * or content that isn't the signal.
 *
 * The fixture signal: silence, a single sharp click at [CLICK_AT_SEC]
 * (the alignment anchor), then per-channel sines — 440 Hz left, 554 Hz
 * right — from [TONE_FROM_SEC] to [TONE_TO_SEC], faded at both ends.
 */
object DecodeContract {

    const val CLICK_AT_SEC = 0.1f
    const val TONE_FROM_SEC = 0.2f
    const val TONE_TO_SEC = 0.9f
    const val DURATION_SEC = 1.0f

    /** Channel 0 tone; channel 1 (when stereo) gets [TONE_HZ_RIGHT]. */
    const val TONE_HZ_LEFT = 440.0
    const val TONE_HZ_RIGHT = 554.365 // C#5, deliberately not a 440 harmonic

    /** The rates the app must prove it decodes correctly. */
    val FIXTURE_RATES = intArrayOf(44_100, 48_000, 22_050)

    fun fixture(sampleRate: Int, channels: Int): Snip {
        require(channels in 1..2) { "fixtures are mono or stereo" }
        val frames = (DURATION_SEC * sampleRate).toInt()
        val out = FloatArray(frames * channels)
        val click = (CLICK_AT_SEC * sampleRate).toInt()
        val from = (TONE_FROM_SEC * sampleRate).toInt()
        val to = (TONE_TO_SEC * sampleRate).toInt()
        val fade = sampleRate / 100 // 10 ms
        for (ch in 0 until channels) {
            val hz = if (ch == 0) TONE_HZ_LEFT else TONE_HZ_RIGHT
            for (i in 0 until frames) {
                var s = 0f
                if (i in click until click + 8) s = if ((i - click) % 2 == 0) 0.9f else -0.9f
                if (i in from until to) {
                    val env = minOf(1f, (i - from).toFloat() / fade, (to - i).toFloat() / fade)
                    s += (0.5 * sin(2 * PI * hz * i / sampleRate)).toFloat() * env
                }
                out[i * channels + ch] = s
            }
        }
        return Snip(out, channels, sampleRate)
    }

    /** The canonical set: encode these, decode the twins, verify. */
    fun writeFixtures(dir: File): List<File> {
        dir.mkdirs()
        val written = mutableListOf<File>()
        for (rate in FIXTURE_RATES) {
            for (channels in intArrayOf(1, 2)) {
                val name = "decode_${rate}hz_${if (channels == 1) "mono" else "stereo"}.wav"
                val f = File(dir, name)
                // These are encode sources, not MPC exports — odd rates are
                // the whole point.
                WavWriter.write(f, fixture(rate, channels), allowNonMpcRate = true)
                written += f
            }
        }
        return written
    }

    data class Report(
        val pass: Boolean,
        val issues: List<String>,
        /** Frames the decode is offset by (encoder delay), post-alignment. */
        val offsetFrames: Int,
        /** Normalized cross-correlation over the tone region, 0..1. */
        val correlation: Double,
    )

    /**
     * Verify a decoded snip against the fixture it came from. [decoded]
     * may arrive at any sample rate (it is resampled to match); a lossy
     * round-trip passes, a wrong one doesn't.
     */
    fun verify(decoded: Snip, expected: Snip): Report {
        val issues = mutableListOf<String>()

        if (decoded.channels != expected.channels) {
            issues += "channel count ${decoded.channels}, expected ${expected.channels}"
        }

        val d = if (decoded.sampleRate == expected.sampleRate) decoded
        else Resampler.resample(decoded, expected.sampleRate)

        val durationDelta = abs(d.durationSeconds - expected.durationSeconds)
        if (durationDelta > 0.06f) {
            issues += "duration off by %.0f ms".format(durationDelta * 1000)
        }

        val rate = expected.sampleRate
        val dm = mono(d)
        val em = mono(expected)

        // Align on the click: the loudest sample in the first quarter.
        val offset = peakIn(dm, 0, rate / 4 + rate / 10) - peakIn(em, 0, rate / 4)

        // Correlate over the tone body, away from the fades.
        val from = ((TONE_FROM_SEC + 0.05f) * rate).toInt()
        val to = ((TONE_TO_SEC - 0.05f) * rate).toInt()
        val corr = correlation(em, dm, from, to, offset)
        if (corr < 0.85) {
            issues += "correlation %.3f over the tone (want >= 0.85) - not the same signal".format(corr)
        }

        return Report(issues.isEmpty(), issues, offset, corr)
    }

    private fun mono(snip: Snip): FloatArray =
        if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples

    private fun peakIn(s: FloatArray, from: Int, to: Int): Int {
        var best = from
        var bestAbs = -1f
        for (i in from until minOf(to, s.size)) {
            val a = abs(s[i])
            if (a > bestAbs) {
                bestAbs = a
                best = i
            }
        }
        return best
    }

    /** Normalized cross-correlation of expected[i] against got[i+offset]. */
    private fun correlation(expected: FloatArray, got: FloatArray, from: Int, to: Int, offset: Int): Double {
        var num = 0.0
        var ee = 0.0
        var gg = 0.0
        var n = 0
        for (i in from until to) {
            val j = i + offset
            if (j < 0 || j >= got.size || i >= expected.size) continue
            num += expected[i].toDouble() * got[j]
            ee += expected[i].toDouble() * expected[i]
            gg += got[j].toDouble() * got[j]
            n++
        }
        if (n == 0 || ee == 0.0 || gg == 0.0) return 0.0
        return abs(num) / sqrt(ee * gg)
    }
}
