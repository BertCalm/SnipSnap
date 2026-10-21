package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                if (voice == OnsetVoice.KICK || voice == OnsetVoice.SNARE) {
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
            val names = Onset.macrosFor(voice).map { it.name }.filter { it != "BLEND" }
            for (bits in 0 until (1 shl names.size)) {
                for (blend in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                    val macros = names.mapIndexed { i, name -> name to if (bits and (1 shl i) == 0) 0f else 1f }.toMap() + ("BLEND" to blend)
                    val snip = Onset.render(voice, macros)
                    val context = "$voice corner $bits blend=$blend"
                    assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$context unsafe")
                    assertTrue(snip.peak() > 0.005f, "$context inaudible: ${snip.peak()}")
                    assertTrue(snip.peak() < 0.99f, "$context exhausted fixed headroom: ${snip.peak()}")
                    assertTrue(snip.durationSeconds < 1.5f, "$context grew into a loop")
                }
            }
            assertContentEquals(Onset.render(voice).samples, Onset.render(voice).samples)
        }
    }

    @Test fun `actual kick fundamental remains shared after the strike`() {
        for (tune in listOf(0f, 0.5f, 1f)) {
            val p = Onset.probe(OnsetVoice.KICK, mapOf("TUNE" to tune, "DECAY" to 1f, "SNAP" to 0f))
            val root = p.settings.rootHz!!
            for (source in listOf(p.electronic, p.physicalFundamental!!)) {
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
            val y = p.physicalFundamental!![i].toDouble()
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

    @Test fun `membrane modes have body authority after fifty milliseconds across default pitches`() {
        // R1's endpoints were almost identical after 50 ms; a whole-hit numerical
        // distance let an onset/level change masquerade as a material difference.
        // Check frequency-resolved inharmonic body, beyond the shared transient.
        for (tune in listOf(0f, 0.35f, 0.5f, 1f)) {
            val p = Onset.probe(OnsetVoice.KICK, mapOf("TUNE" to tune))
            val old = Onset.probe(OnsetVoice.KICK, mapOf("TUNE" to tune), modelVersion = 1)
            val root = p.settings.rootHz!!
            fun materialEnergy(source: FloatArray): Double = listOf(1.5933f, 2.1354f, 2.9172f, 3.5001f)
                .sumOf { ratio -> projectedAmplitude(source, root * ratio).let { it * it } }
            val fundamentalEnergy = projectedAmplitude(p.physical, root).let { it * it }
            val physicalModes = materialEnergy(p.physical)
            val originalModes = materialEnergy(old.physical)
            assertTrue(physicalModes / fundamentalEnergy > 0.25, "membrane lost body modes at TUNE=$tune")
            assertTrue(physicalModes > originalModes * 10.0, "material change stayed confined to onset at TUNE=$tune")
            val physical = Onset.render(OnsetVoice.KICK, mapOf("TUNE" to tune, "BLEND" to 1f))
            assertEquals(DrumClass.KICK, Classifier.classify(physical).drumClass, "membrane left kick territory at TUNE=$tune")
        }
    }

    @Test fun `revision leaves electronic kick and all hat renders identical to version one`() {
        for (blend in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val macros = mapOf("BLEND" to blend)
            assertContentEquals(Onset.render(OnsetVoice.HAT_CLOSED, macros, modelVersion = 1).samples,
                Onset.render(OnsetVoice.HAT_CLOSED, macros, modelVersion = 2).samples)
        }
        for (tune in listOf(0f, 0.35f, 1f)) {
            val macros = mapOf("BLEND" to 0f, "TUNE" to tune)
            assertContentEquals(Onset.render(OnsetVoice.KICK, macros, modelVersion = 1).samples,
                Onset.render(OnsetVoice.KICK, macros, modelVersion = 2).samples)
        }
    }

    @Test fun `model three preserves both accepted voices across all blend positions`() {
        for (voice in listOf(OnsetVoice.KICK, OnsetVoice.HAT_CLOSED)) {
            for (blend in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                for (edge in listOf(0f, 0.5f, 1f)) {
                    val macros = mapOf("BLEND" to blend, "EDGE" to edge)
                    assertContentEquals(Onset.render(voice, macros, modelVersion = 2).samples,
                        Onset.render(voice, macros, modelVersion = 3).samples)
                }
            }
        }
        assertEquals(0, OnsetVoice.KICK.ordinal)
        assertEquals(1, OnsetVoice.HAT_CLOSED.ordinal)
        for (voice in listOf(OnsetVoice.SNARE, OnsetVoice.HAT_OPEN)) {
            assertFailsWith<IllegalArgumentException> { Onset.render(voice, modelVersion = 2) }
        }
    }

    @Test fun `FM snare retains post attack sidebands and body energy alongside common wires`() {
        for (tune in listOf(0f, 0.4f, 1f)) {
            val p = Onset.probe(OnsetVoice.SNARE, mapOf("TUNE" to tune))
            val root = p.settings.rootHz!!
            val fmSideband = projectedAmplitude(p.physical, root * 3.76f, 0.025f, 0.075f)
            val electronicAtSideband = projectedAmplitude(p.electronic, root * 3.76f, 0.025f, 0.075f)
            val carrier = projectedAmplitude(p.physical, root, 0.025f, 0.075f)
            assertTrue(fmSideband > electronicAtSideband * 5.0, "FM body lost sideband authority at TUNE=$tune")
            assertTrue(fmSideband > carrier * 0.2, "FM color vanished immediately after the strike at TUNE=$tune")
            // Raw 4x white noise contains ultrasonic power that the delivered
            // audition removes. Judge body balance on the same audible bandwidth.
            val audibleBody = Dsp.decimate(p.physical, Onset.RATE)
            val audibleWires = Dsp.decimate(p.sharedWires!!, Onset.RATE)
            val rate = Onset.RATE
            val range = (0.025f * rate).toInt() until (0.10f * rate).toInt()
            val bodyEnergy = range.sumOf { audibleBody[it].toDouble() * audibleBody[it] }
            val wireEnergy = range.sumOf { audibleWires[it].toDouble() * audibleWires[it] }
            println("ONSET3 SNARE TUNE=$tune postattackBody/Wires=${bodyEnergy / wireEnergy} sideband/carrier=${fmSideband / carrier}")
            assertTrue(bodyEnergy > wireEnergy * 0.30, "shared hiss masked the authored snare body at TUNE=$tune")
        }
    }

    @Test fun `new voices retain their default drum families through every blend`() {
        for (voice in listOf(OnsetVoice.SNARE, OnsetVoice.HAT_OPEN)) {
            val family = if (voice == OnsetVoice.SNARE) DrumClass.SNARE else DrumClass.HAT_OPEN
            val lengths = mutableSetOf<Int>()
            for (blend in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val snip = Onset.render(voice, mapOf("BLEND" to blend))
                lengths += snip.frameCount
                val classification = Classifier.classify(snip)
                println("ONSET3 $voice blend=$blend peak=${snip.peak()} loud=${Loudness.of(snip)} features=${classification.features}")
                assertEquals(family, classification.drumClass, "$voice left its family at blend=$blend")
            }
            assertEquals(1, lengths.size, "BLEND changed $voice length")
        }
        val closed = Onset.render(OnsetVoice.HAT_CLOSED)
        val open = Onset.render(OnsetVoice.HAT_OPEN)
        assertTrue(open.durationSeconds > closed.durationSeconds * 4f)
        assertTrue(Classifier.classify(open).features.decayMs > Classifier.classify(closed).features.decayMs * 3f)
    }

    private fun projectedAmplitude(source: FloatArray, hz: Float, startSeconds: Float = 0.05f, durationSeconds: Float = 0.10f): Double {
        val rate = Onset.RATE * Dsp.OVERSAMPLE
        val from = (startSeconds * rate).toInt()
        val count = (durationSeconds * rate).toInt()
        var real = 0.0
        var imaginary = 0.0
        var weight = 0.0
        for (n in 0 until count) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * n / (count - 1))
            val angle = 2.0 * PI * hz * (from + n) / rate
            val sample = source[from + n] * w
            real += sample * cos(angle)
            imaginary += sample * sin(angle)
            weight += w
        }
        return 2.0 * sqrt(real * real + imaginary * imaginary) / weight
    }

    private fun rms(samples: FloatArray): Float =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size).toFloat()
}
