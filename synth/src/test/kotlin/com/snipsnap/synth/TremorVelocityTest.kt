package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Touch must survive sample levelling while the established full-strength instrument stays fixed. */
class TremorVelocityTest {
    @Test
    fun `soft middle and hard contact retain distinct spectral articulation after levelling`() {
        for (voice in listOf(TremorVoice.HIDE, TremorVoice.WIRE)) {
            val clips = listOf(0.20f, 0.55f, 1f).map { Tremor.render(voice, velocity = it).samples }
            val upper = clips.map { upperShare(it, Tremor.frequencyFor(voice, 0.5f) * 1.5) }
            // A gain-only velocity law cannot pass a power-share test. The old implementation
            // put effectively the same fraction in the upper modes at all three touches.
            assertTrue(upper[1] > upper[0] * 1.5, "$voice soft/middle upper shares $upper")
            assertTrue(upper[2] > upper[1] * 1.5, "$voice middle/hard upper shares $upper")
            assertTrue(alignedDifference(clips[2], clips[0]) > 0.05, "$voice soft touch is still a gain change")
            assertTrue(alignedDifference(clips[2], clips[1]) > 0.02, "$voice middle touch is still a gain change")
        }
    }

    @Test
    fun `a longer soft contact cannot notch away the high register root`() {
        for (voice in TremorVoice.entries) for (velocity in listOf(0.20f, 0.55f)) {
            val r = Tremor.play(voice, mapOf("TUNE" to 1f), velocity)
            val pitch = assertNotNull(Pitch.detect(r.snip), "$voice at $velocity lost its note")
            val wanted = Tremor.frequencyFor(voice, 1f)
            val cents = 1200.0 * ln(pitch.hz / wanted) / ln(2.0)
            assertTrue(abs(cents) < 15.0, "$voice at $velocity moved the C4 anchor by $cents cents")
            assertTrue(pitch.confidence > 0.8f, "$voice at $velocity has uncertain C4 pitch")
            assertEquals(0, r.railHits)
            assertTrue(r.rawPeak < 4f && r.rawPeak.isFinite())
            assertTrue(r.snip.samples.all { it.isFinite() && it in -1f..1f })
        }
    }

    @Test
    fun `soft and middle contacts keep the global spectral anchor across the register`() {
        for (voice in TremorVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) for (velocity in listOf(0.20f, 0.55f)) {
            val snip = Tremor.render(voice, mapOf("TUNE" to tune), velocity)
            val got = globalPeakHz(snip.samples)
            val wanted = Tremor.frequencyFor(voice, tune)
            val cents = 1200.0 * ln(got / wanted) / ln(2.0)
            assertTrue(abs(cents) < 15.0, "$voice TUNE $tune velocity $velocity spectral anchor moved $cents cents")
        }
    }

    @Test
    fun `touch changes contact without rerolling the coordinated strikers`() {
        val hard = Tremor.play(TremorVoice.UNISON, mapOf("ENSEMBLE" to 1f))
        for (velocity in listOf(0.20f, 0.55f)) {
            val soft = Tremor.play(TremorVoice.UNISON, mapOf("ENSEMBLE" to 1f), velocity)
            assertEquals(hard.strikes.map { it.sample }, soft.strikes.map { it.sample })
            assertEquals(hard.strikes.map { it.index }, soft.strikes.map { it.index })
        }
    }

    @Test
    fun `soft held contacts stay deterministic and retain the constructed join`() {
        for (voice in listOf(TremorVoice.HIDE, TremorVoice.ROLL, TremorVoice.WIRE, TremorVoice.CHARGE)) {
            val m = mapOf("HOLD" to 1f)
            val a = Tremor.play(voice, m, 0.20f)
            val b = Tremor.play(voice, m, 0.20f)
            assertContentEquals(a.snip.samples, b.snip.samples)
            // This checks the production crossfade's join, not natural periodic convergence.
            assertTrue(Keys.seamError(a.snip.samples, a.loopStart) < Keys.MAX_SEAM_ERROR)
            assertEquals(0, a.railHits)
        }
    }

    @Test
    fun `full touch keeps the pre-articulation one shots and held voices exactly`() {
        // SHA-256 over big-endian float32 samples, captured from b17c3bd before the contact edit.
        // Full-touch factories are the existing listening candidate. A softer touch must not
        // silently revoice those defaults or the powered-feedback repair already under review.
        val expected = listOf(
            "0cd7838fbc58e827f495d8022c262583e752edc61aa595504c58eac9f457799f" to "19a1f811e25e51f675d569a1c35a74af812408637915b5bc180afee928db1164",
            "3f8be83b78f63580f0aaec8f8b0abc7cbe84ee341354bfc26da9227f56dc83cc" to "ef1398860ca7a732e32671e1acd6a35b2997735dc877dbcd4e97f08ddc37029b",
            "c9eddc399dd0cf2e37b30fc2100c3d39d1f1363a3888cc7da6c78f8c2b537e4a" to "734d062abdcd936e21ed0ea9e7aa9e882324efe92fc27c6b8eb418f97ce7689f",
            "d143097cbec45c9a335313a13264c87343414a84f1df1e0e9016a48c41b70ec4" to "076c2242bc9e468e1e4db1be24992820e9f6b1f353bf8fa77c03a4d01d1f5875",
            "90d17e21ab2537fc0d1c9169a2a85390fadb31b68ee280dd98a6f5f237e4c6a2" to "34c501205d8eb9768f31107f7ae47a9639c13fd2e81e7682898d6137294f1f56",
            "5b9e48bb614f476a05d2311c782f1af286bbb02e1903d0019a69db7cd43db112" to "87f925f8bec730f02e28b89ed12c8b36ec1ab15dc7aa37375e919d6a2d8fbc0c",
        )
        for ((i, voice) in TremorVoice.entries.withIndex()) {
            assertEquals(expected[i].first, hash(Tremor.render(voice).samples), "$voice one-shot changed")
            assertEquals(expected[i].second, hash(Tremor.render(voice, mapOf("HOLD" to 1f)).samples), "$voice held changed")
        }
    }

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4)
        for (sample in samples) bytes.putFloat(sample)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun upperShare(samples: FloatArray, fromHz: Double): Double {
        val n = 8192
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until minOf(samples.size, n)) re[i] = (samples[i] * (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)))).toFloat()
        Fft.forward(re, im)
        var upper = 0.0
        var all = 0.0
        for (k in 1 until n / 2) {
            val power = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            all += power
            if (k * Dsp.RATE.toDouble() / n > fromHz) upper += power
        }
        return upper / all.coerceAtLeast(1e-30)
    }

    /** Find the strongest spectral peak without restricting the search to the expected note. */
    private fun globalPeakHz(samples: FloatArray): Double {
        val n = 16384
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until minOf(samples.size, n)) re[i] = (samples[i] * (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)))).toFloat()
        Fft.forward(re, im)
        fun magnitude(k: Int) = sqrt(re[k].toDouble() * re[k] + im[k].toDouble() * im[k])
        // The open search includes sub/body modes and several octaves above the drum. A root
        // notch that promotes an unrelated body mode must fail, even if a little root remains.
        val from = (20.0 * n / Dsp.RATE).toInt().coerceAtLeast(1)
        val to = (4000.0 * n / Dsp.RATE).toInt()
        val bin = (from..to).maxBy { magnitude(it) }
        val a = ln(magnitude(bin - 1).coerceAtLeast(1e-30))
        val b = ln(magnitude(bin).coerceAtLeast(1e-30))
        val c = ln(magnitude(bin + 1).coerceAtLeast(1e-30))
        val offset = (0.5 * (a - c) / (a - 2.0 * b + c)).coerceIn(-0.5, 0.5)
        return (bin + offset) * Dsp.RATE / n
    }

    private fun alignedDifference(reference: FloatArray, other: FloatArray): Double {
        val n = minOf(reference.size, other.size)
        var dot = 0.0
        var energy = 0.0
        var otherEnergy = 0.0
        for (i in 0 until n) {
            dot += reference[i].toDouble() * other[i]
            energy += reference[i].toDouble() * reference[i]
            otherEnergy += other[i].toDouble() * other[i]
        }
        val gain = dot / otherEnergy.coerceAtLeast(1e-30)
        var difference = 0.0
        for (i in 0 until n) {
            val d = reference[i] - gain * other[i]
            difference += d * d
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }
}
