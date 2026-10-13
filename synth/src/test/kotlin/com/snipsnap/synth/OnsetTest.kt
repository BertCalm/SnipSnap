package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnsetTest {
    @Test fun `settings share kick fundamental decay and hat transposition across blend`() {
        for (voice in OnsetVoice.entries) {
            val settings = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map {
                Onset.settingsFor(voice, mapOf("BLEND" to it))
            }
            assertEquals(1, settings.map { it.t60Seconds }.distinct().size)
            assertEquals(1, settings.map { it.electronicGain }.distinct().size)
            assertEquals(1, settings.map { it.physicalGain }.distinct().size)
            for (s in settings) {
                if (voice == OnsetVoice.KICK) {
                    assertEquals(s.electronicRootHz, s.physicalRootHz)
                    assertEquals(s.rootHz, s.electronicRootHz)
                } else {
                    assertEquals(null, s.rootHz)
                    assertEquals(6500f / 445f, s.physicalRootHz / s.electronicRootHz, 0.00001f)
                }
            }
        }
    }

    @Test fun `blend stays coherent and has a distinct midpoint`() {
        for (voice in OnsetVoice.entries) {
            val taps = listOf(0f, 0.5f, 1f).map { Onset.probe(voice, mapOf("BLEND" to it, "SNAP" to 0f)) }
            assertContentEquals(taps[0].electronic, taps[1].electronic)
            assertContentEquals(taps[1].physical, taps[2].physical)
            assertEquals(taps[0].electronic.size, taps[0].physical.size)
            assertContentEquals(taps[0].electronic, taps[0].mixed)
            assertContentEquals(taps[2].physical, taps[2].mixed)
            // Listenability is still an audition decision. This guards against an
            // implementation that mode-switches or drops one source in the middle.
            for (endpoint in listOf(taps[0].mixed, taps[2].mixed)) {
                val residual = rms(FloatArray(endpoint.size) { endpoint[it] - taps[1].mixed[it] })
                assertTrue(residual > rms(endpoint) * 0.1f, "$voice midpoint collapsed onto an endpoint")
            }
        }
    }

    @Test fun `every macro corner is finite audible safe and deterministic`() {
        for (voice in OnsetVoice.entries) {
            val names = Onset.macrosFor(voice).map { it.name }
            for (bits in 0 until (1 shl names.size)) {
                val macros = names.mapIndexed { i, name -> name to if (bits and (1 shl i) == 0) 0f else 1f }.toMap()
                val snip = Onset.render(voice, macros)
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice corner $bits unsafe")
                assertTrue(snip.peak() > 0.005f, "$voice corner $bits inaudible: ${snip.peak()}")
                assertTrue(snip.peak() < 0.99f, "$voice corner $bits exhausted fixed headroom: ${snip.peak()}")
                assertTrue(snip.durationSeconds < 1.5f, "$voice corner $bits grew into a loop")
            }
            assertContentEquals(Onset.render(voice).samples, Onset.render(voice).samples)
        }
    }

    @Test fun `actual kick fundamental remains shared after the strike`() {
        for (tune in listOf(0f, 0.5f, 1f)) {
            val p = Onset.probe(OnsetVoice.KICK, mapOf("TUNE" to tune, "DECAY" to 1f, "SNAP" to 0f))
            val root = p.settings.rootHz!!
            for (source in listOf(p.electronic, p.physical)) {
                val from = (0.08f * Onset.RATE * Dsp.OVERSAMPLE).toInt()
                val to = (0.4f * Onset.RATE * Dsp.OVERSAMPLE).toInt()
                val crossings = (from + 1 until to).count { source[it - 1] <= 0f && source[it] > 0f }
                val measured = crossings / 0.32f
                assertEquals(root, measured, 4f, "source lost fundamental: $root vs $measured")
            }
        }
    }

    @Test fun `shared kick pitch drop keeps the hybrid fundamental in phase`() {
        // Merely sharing the final Hz is insufficient: different drop trajectories
        // rotate the carrier phase and can hollow out a hybrid's low-frequency tail.
        val p = Onset.probe(OnsetVoice.KICK, mapOf("DECAY" to 1f, "SNAP" to 1f))
        val range = (Onset.RATE * Dsp.OVERSAMPLE * 0.15f).toInt() until
            (Onset.RATE * Dsp.OVERSAMPLE * 0.4f).toInt()
        var xy = 0.0
        var xx = 0.0
        var yy = 0.0
        for (i in range) {
            val x = p.electronic[i].toDouble()
            val y = p.physical[i].toDouble()
            xy += x * y
            xx += x * x
            yy += y * y
        }
        assertTrue(xy / sqrt(xx * yy) > 0.97, "fundamental phase drifted between layers")
    }

    @Test fun `fixed source calibration keeps default endpoints comparably loud`() {
        for (voice in OnsetVoice.entries) {
            val electronic = Loudness.of(Onset.render(voice, mapOf("BLEND" to 0f)))
            val physical = Loudness.of(Onset.render(voice, mapOf("BLEND" to 1f)))
            val ratio = electronic / physical
            // A broad ±3.7 dB guard catches omitted/wrong source calibration without
            // pretending RMS or this small loudness model replaces the listening gate.
            assertTrue(ratio in 0.65f..1.55f, "$voice endpoints unbalanced: $electronic / $physical")
        }
    }

    private fun rms(samples: FloatArray): Float =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size).toFloat()
}
