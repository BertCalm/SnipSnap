package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Material identity belongs to the driven body, while the established population drives it. */
class ShoalMaterialTest {
    private val bodies = listOf(ShoalMaterial.BOWL, ShoalMaterial.WOOD, ShoalMaterial.WIRE)
    private val timbral = listOf("DENSITY", "CURRENT", "COHESION", "STRAY", "GLOW")

    @Test
    fun `model two and the current harmonic branch reproduce published PCM including held and soft edge notes`() {
        // Captured from the published model-2 pack before material integration. PCM data hashes
        // exclude WAV metadata; the source revision was a6f5ef0db57407dad751d41e3f87cd466f3c3624.
        data class Fixture(val voice: ShoalVoice, val midi: Int, val velocity: Float, val hold: Float,
            val frames: Int, val hash: String)
        val fixtures = listOf(
            Fixture(ShoalVoice.GLASS, 48, 1f, 0f, 165816, "27446a07c724f1b1cacf635fcd2aa6d4db4cfd4880b4c504c2c0e7d5a73339cf"),
            Fixture(ShoalVoice.RIPPLE, 48, 1f, 0f, 255780, "869c20f88d51a251f84f1218bb81e46c0448ea746addf533553d2f22656ebd3d"),
            Fixture(ShoalVoice.GLASS, 48, 1f, 1f, 153728, "c09e1728525e0f9cb4130804a919bee32d31393eba698e109c0c4bf7e4c5e0e4"),
            Fixture(ShoalVoice.RIPPLE, 48, 1f, 1f, 177664, "47322507d1ac718340fc084bedb5ecfccaf5aa01374c212b3468dacb3418ceda"),
            Fixture(ShoalVoice.GLASS, 84, .25f, 0f, 165816, "f14c7fec2de722fddea8100aedde92a9daec80c636eada1f01a2201e66ab8e49"),
            Fixture(ShoalVoice.RIPPLE, 24, .25f, 0f, 255780, "ec7908855c183b0a15bb283712b091661524935fb7cabf1b94dce4e0a7ef07ad"),
        )
        for (fixture in fixtures) {
            val macros = if (fixture.voice == ShoalVoice.GLASS) mapOf("DENSITY" to .3f, "CURRENT" to .2f,
                "COHESION" to .7f, "STRAY" to .05f, "GLOW" to .4f, "HOLD" to fixture.hold)
            else mapOf("DENSITY" to .45f, "CURRENT" to .7f, "COHESION" to .6f, "STRAY" to .15f,
                "GLOW" to .5f, "HOLD" to fixture.hold)
            val old = Shoal.probe(fixture.voice, macros, fixture.midi, fixture.velocity,
                model = 2, material = ShoalMaterial.HARMONIC)
            val current = Shoal.probe(fixture.voice, macros, fixture.midi, fixture.velocity,
                model = 3, material = ShoalMaterial.HARMONIC)
            assertEquals(fixture.frames, old.samples.size, "$fixture legacy duration")
            assertEquals(fixture.hash, pcm24Hash(old.samples), "$fixture published model-2 audio changed")
            assertContentEquals(old.raw, current.raw, "$fixture current harmonic source no longer delegates to model 2")
            assertContentEquals(old.samples, current.samples, "$fixture current harmonic conditioning changed")
        }
    }

    @Test
    fun `materials change phase independent spectral shape with the same population note controls and velocity`() {
        for (voice in ShoalVoice.entries) for (midi in listOf(48, 60)) {
            val macros = Shoal.defaults(voice) + mapOf("STRAY" to 0f, "HOLD" to 0f)
            val probes = bodies.associateWith { Shoal.probe(voice, macros, midi, .8f, seconds = 1.1f, material = it) }
            for ((index, a) in bodies.withIndex()) for (b in bodies.drop(index + 1)) {
                val pa = probes.getValue(a)
                val pb = probes.getValue(b)
                val distance = spectralDistance(pa.samples, pb.samples)
                // The approved study's smallest pairwise distance at these notes was .558.
                // .20 allows refinement while rejecting materials distinguished only by gain/phase.
                assertTrue(distance > .20, "$voice MIDI$midi $a/$b have nearly the same normalized spectrum ($distance)")
                val da = assertNotNull(pa.materialDiagnostics)
                val db = assertNotNull(pb.materialDiagnostics)
                assertFalse(da.modeRatios.contentEquals(db.modeRatios), "$a/$b use the same modal topology")
                val ca = pa.energyTrace.filter { it.seconds <= .8 }
                val cb = pb.energyTrace.filter { it.seconds <= .8 }
                assertEquals(ca.size, cb.size)
                for ((left, right) in ca.zip(cb)) {
                    assertEquals(left.seconds, right.seconds)
                    assertContentEquals(left.energy, right.energy, "$a/$b changed the donor population instead of its body")
                    assertContentEquals(left.centers, right.centers)
                }
            }
        }
    }

    @Test
    fun `velocity changes contact and excitation timbre beyond a scalar while conserving the normalized population budget`() {
        for (material in bodies) {
            val soft = Shoal.probe(ShoalVoice.RIPPLE, midi = 60, velocity = .3f, seconds = .9f, material = material)
            val hard = Shoal.probe(ShoalVoice.RIPPLE, midi = 60, velocity = 1f, seconds = .9f, material = material)
            val sd = assertNotNull(soft.materialDiagnostics)
            val hd = assertNotNull(hard.materialDiagnostics)
            val residual = scalarResidual(hard.samples, soft.samples)
            assertTrue(residual > .005, "$material velocity only rescales the completed sound ($residual)")
            assertTrue(sd.contactSeconds > hd.contactSeconds, "$material soft contact does not take longer")
            assertFalse(sd.excitationWeights.contentEquals(hd.excitationWeights), "$material velocity does not alter modal excitation")
            assertTrue(soft.diagnostics.rawRms < hard.diagnostics.rawRms, "$material soft excitation became louder before output leveling")
            assertTrue(sd.velocityEnergyBudget < hd.velocityEnergyBudget)
            assertEquals(sd.velocityEnergyBudget, soft.energyTrace.first().energy.sum(), 1e-10,
                "$material soft spectral shaping changed the population energy budget")
            assertEquals(hd.velocityEnergyBudget, hard.energyTrace.first().energy.sum(), 1e-10,
                "$material hard spectral shaping changed the population energy budget")
        }
    }

    @Test
    fun `ordinary and extreme registers retain an audible bounded material body and the requested root`() {
        for (voice in ShoalVoice.entries) for (material in bodies) for (midi in listOf(24, 60, 84)) {
            for (high in listOf(false, true)) {
                val macros = Shoal.defaults(voice) + ("HOLD" to 0f) + if (high) timbral.associateWith { 1f } else emptyMap()
                val p = Shoal.probe(voice, macros, midi, .8f, seconds = .9f, material = material)
                val d = assertNotNull(p.materialDiagnostics)
                val label = "$voice/$material MIDI$midi high=$high"
                assertTrue(p.raw.all { it.isFinite() } && p.samples.all { it.isFinite() }, "$label invalid audio")
                assertTrue(p.diagnostics.rawPeak < 1.0, "$label raw source needs clipping rescue")
                assertTrue(rms(p.samples) > 1e-4, "$label collapsed into silence")
                assertTrue(abs(p.samples.average()) < .015, "$label accumulating DC")
                assertTrue(d.modeHz.isNotEmpty() && d.modeHz.all { it.isFinite() && it > 0 && it < Dsp.RATE / 2.0 },
                    "$label has an ineligible output mode")
                assertTrue(p.diagnostics.maxFrequencyHz < 18_000, "$label carrier/departure exceeds its output guard")
                assertTrue(d.retainedEnergyRatio >= .75, "$label loses more than a quarter of its material energy to the guard (${d.retainedEnergyRatio})")
                val wanted = Keys.midiHz(midi)
                val measured = FineTuning.measuredHz(Snip(p.samples, 1, Dsp.RATE), wanted, .12f, .55f)
                assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 5,
                    "$label protected root moved")
                val rootShare = coherentRootShare(p.samples, wanted.toDouble())
                assertTrue(rootShare >= .01, "$label preserves only an inaudible root reference ($rootShare)")
                assertTrue(rootShare <= .95, "$label loses the body and leaves a sine ($rootShare)")
                assertTrue(p.radiationTrace.all { frame -> frame.rootEnergy.isFinite() && frame.rootEnergy >= 0 &&
                    frame.energy.all { it.isFinite() && it >= 0 } }, "$label invalid material state")
            }
        }
    }

    @Test
    fun `root and upper material modes ring passively with their own lifetimes after the controller ends`() {
        for (material in bodies) {
            val p = Shoal.probe(ShoalVoice.RIPPLE, material = material)
            val d = assertNotNull(p.materialDiagnostics)
            // The controller rounds its final source interval up by less than one control hop.
            val tail = p.radiationTrace.filter { it.seconds > d.sourceDurationSeconds + 128.0 / Dsp.RATE }
            assertTrue(tail.size >= 3, "$material has no independently observed passive tail")
            assertTrue(tail.all { it.supply == 0.0 }, "$material tail is still powered")
            var comparedModes = 0
            for ((a, b) in tail.zipWithNext()) {
                assertTrue(b.rootEnergy <= a.rootEnergy + 1e-12, "$material unpowered root grows")
                if (a.rootEnergy > 1e-18) {
                    val expected = a.rootEnergy * exp(-2 * ln(1000.0) * (b.seconds - a.seconds) / d.rootT60Seconds)
                    assertEquals(expected, b.rootEnergy, a.rootEnergy * 1e-6 + 1e-20, "$material root bypasses its material damping")
                }
                for (i in a.energy.indices) {
                    assertTrue(b.energy[i] <= a.energy[i] + 1e-12, "$material unpowered mode $i grows")
                    if (a.energy[i] > 1e-18) {
                        val expected = a.energy[i] * exp(-2 * ln(1000.0) * (b.seconds - a.seconds) / d.modeT60Seconds[i])
                        assertEquals(expected, b.energy[i], a.energy[i] * 1e-6 + 1e-20, "$material mode $i has a shared output envelope instead of its own loss")
                        comparedModes++
                    }
                }
            }
            assertTrue(comparedModes > 0, "$material tail was already killed before it could be measured")
            val duration = p.samples.size.toDouble() / Dsp.RATE
            val longest = max(d.rootT60Seconds, d.modeT60Seconds.maxOrNull() ?: 0.0)
            assertTrue(duration <= d.sourceDurationSeconds + longest + .05 && duration <= Shoal.MAX_MATERIAL_SECONDS,
                "$material has an unbounded or unrelated render tail ($duration)")
            val tailRaw = p.raw.takeLast((.02 * Dsp.RATE * Dsp.OVERSAMPLE).toInt()).toFloatArray()
            assertTrue(rms(tailRaw) < rms(p.raw) * .01, "$material is abruptly cut before its final output fade")
            assertTrue(d.passiveTailSeconds > 0)
        }
    }

    @Test
    fun `early probes retain the natural full source and material calibration`() {
        val full = Shoal.probe(ShoalVoice.GLASS, velocity = .7f, material = ShoalMaterial.WOOD)
        val early = Shoal.probe(ShoalVoice.GLASS, velocity = .7f, seconds = .7f, material = ShoalMaterial.WOOD)
        assertTrue(early.raw.size < full.raw.size)
        assertContentEquals(full.raw.copyOf(early.raw.size), early.raw, "early evidence changes excitation, damping or source calibration")
        assertEquals(assertNotNull(full.materialDiagnostics).radiationEnergyScale,
            assertNotNull(early.materialDiagnostics).radiationEnergyScale)
    }

    @Test
    fun `material renders are deterministic and bounded cancellation stops finite synthesis`() {
        for (material in bodies) {
            val a = Shoal.probe(ShoalVoice.GLASS, seconds = .4f, velocity = .6f, material = material)
            val b = Shoal.probe(ShoalVoice.GLASS, seconds = .4f, velocity = .6f, material = material)
            assertContentEquals(a.raw, b.raw, "$material raw determinism")
            assertContentEquals(a.samples, b.samples, "$material conditioned determinism")
            var polls = 0
            assertFailsWith<CancellationException> {
                Shoal.probe(ShoalVoice.RIPPLE, cancelled = { ++polls >= 4 }, material = material)
            }
            assertEquals(4, polls, "$material continues rendering after cancellation")
            val silent = Shoal.render(ShoalVoice.RIPPLE, velocity = 0f, material = material)
            assertTrue(silent.samples.isNotEmpty() && silent.samples.all { it == 0f }, "$material sounds at zero velocity")
        }
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { Shoal.render(ShoalVoice.GLASS, material = ShoalMaterial.BOWL) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `inharmonic materials refuse hold explicitly while harmonic loops remain available`() {
        for (material in bodies) for (voice in ShoalVoice.entries) {
            var polls = 0
            val error = assertFailsWith<IllegalArgumentException> {
                Shoal.probe(voice, mapOf("HOLD" to 1f), cancelled = { polls++; false }, material = material)
            }
            assertTrue(error.message.orEmpty().contains("HOLD", ignoreCase = true), "$material refused an unrelated parameter")
            assertTrue(polls <= 1, "$material began the long render before rejecting unsupported HOLD")
            assertFalse(Shoal.supportsHold(3, material))
        }
        for (model in listOf(1, 2, 3)) assertTrue(Shoal.supportsHold(model, ShoalMaterial.HARMONIC))
    }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size.coerceAtLeast(1))

    private fun scalarResidual(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val gain = (0 until n).sumOf { a[it].toDouble() * b[it] } /
            (0 until n).sumOf { a[it].toDouble() * a[it] }.coerceAtLeast(1e-30)
        return sqrt((0 until n).sumOf { val d = b[it] - gain * a[it]; d * d } / n) / rms(b).coerceAtLeast(1e-15)
    }

    private fun normalizedSpectrum(x: FloatArray): DoubleArray {
        val size = 8192
        val from = (.12 * Dsp.RATE).toInt()
        val spectrum = Fft.magnitudeSpectrum(x.copyOfRange(from, from + size), size)
        val bands = DoubleArray(64)
        for (i in 1 until spectrum.size) {
            val hz = i.toDouble() * Dsp.RATE / size
            if (hz !in 30.0..18_000.0) continue
            val band = (ln(hz / 30) / ln(18000.0 / 30) * bands.size).toInt().coerceIn(0, bands.lastIndex)
            bands[band] += spectrum[i].toDouble() * spectrum[i]
        }
        val sum = bands.sum().coerceAtLeast(1e-30)
        return DoubleArray(bands.size) { bands[it] / sum }
    }

    private fun spectralDistance(a: FloatArray, b: FloatArray): Double {
        val sa = normalizedSpectrum(a)
        val sb = normalizedSpectrum(b)
        return sa.indices.sumOf { abs(sa[it] - sb[it]) } / 2
    }

    private fun coherentRootShare(samples: FloatArray, hz: Double): Double {
        val from = (.12 * Dsp.RATE).toInt()
        val count = minOf((.55 * Dsp.RATE).toInt(), samples.size - from)
        var re = 0.0
        var im = 0.0
        var energy = 0.0
        for (i in 0 until count) {
            val x = samples[from + i].toDouble()
            val phase = 2 * Math.PI * hz * i / Dsp.RATE
            re += x * kotlin.math.cos(phase)
            im += x * kotlin.math.sin(phase)
            energy += x * x
        }
        return 2 * (re * re + im * im) / (count * energy).coerceAtLeast(1e-30)
    }

    private fun pcm24Hash(samples: FloatArray): String {
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, Snip(samples, 1, Dsp.RATE), WavWriter.BitDepth.PCM_24) }.toByteArray()
        assertEquals("data", bytes.copyOfRange(36, 40).decodeToString())
        val size = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(44, 44 + size))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
