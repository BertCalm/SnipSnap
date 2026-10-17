package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compare the bank at one common performance, so pitch or level cannot fake new timbres. */
class LacquerPresetBankTest {
    @Test
    fun `each voice supplies six distinct level matched gestures at the same note`() {
        var closest = Double.POSITIVE_INFINITY
        var closestNames = ""
        val weakPairs = mutableListOf<String>()
        for (voice in LacquerVoice.entries) {
            val group = comparisons.filter { it.patch.voice == voice }
            assertEquals(6, group.size, "$voice comparison coverage")
            var voiceClosest = Double.POSITIVE_INFINITY
            var voiceNames = ""
            for (a in 0 until group.lastIndex) for (b in a + 1 until group.size) {
                val distance = matchedDistance(group[a].probe.samples, group[b].probe.samples)
                val names = "${group[a].patch.name} / ${group[b].patch.name}"
                if (distance < voiceClosest) {
                    voiceClosest = distance
                    voiceNames = names
                }
                if (distance < closest) {
                    closest = distance
                    closestNames = names
                }
                if (distance < .08) weakPairs += "$voice $names: $distance"
            }
            println("LACQUER $voice closest factory pair at MIDI48, velocity1, TUNE.5, HOLD0: $voiceNames = $voiceClosest RMS-matched distance")
        }
        println("LACQUER bank closest matched pair: $closestNames = $closest")
        assertTrue(weakPairs.isEmpty(), "factory variants differ by less than 8% after matching pitch, performance and RMS: ${weakPairs.joinToString()}")
    }

    @Test
    fun `all factory gestures preserve pitched body finite headroom and shared melodic level`() {
        var worstCents = 0.0
        var largestRawPeak = 0f
        var largestDc = 0.0
        var smallestRootShare = 1.0
        var quietest = Float.POSITIVE_INFINITY
        var loudest = 0f
        val referenceNames = LacquerPresets.references().map { it.name }.toSet()
        for ((patch, probe) in comparisons) {
            assertTrue(probe.samples.all { it.isFinite() } && probe.raw.all { it.isFinite() }, "${patch.name}: nonfinite raw or output audio")
            assertTrue(probe.layerEndSeconds.values.all { it.isFinite() && it <= Lacquer.MAX_FINITE_SECONDS },
                "${patch.name}: a complete active layer tail exceeds the finite host budget")
            assertTrue(probe.onsetSeconds.values.all { it.isFinite() && it in 0.0..0.150001 },
                "${patch.name}: layer arrival escapes the bounded timing profile")
            val rawPeak = probe.raw.maxOf { abs(it) }
            val peak = probe.samples.maxOf { abs(it) }
            val dc = abs(probe.samples.sumOf { it.toDouble() } / probe.samples.size)
            val loudness = Loudness.of(Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE))
            val wanted = Keys.midiHz(48)
            val measured = FineTuning.measuredHz(probe.samples, Dsp.RATE, wanted, .2f, .6f)
            val cents = abs(FineTuning.cents(measured, wanted.toDouble()))
            val harmonicEnergy = harmonicEnergies(probe.samples, wanted.toDouble())
            val rootShare = harmonicEnergy[0] / harmonicEnergy.sum()
            worstCents = max(worstCents, cents)
            largestRawPeak = maxOf(largestRawPeak, rawPeak)
            largestDc = max(largestDc, dc)
            smallestRootShare = minOf(smallestRootShare, rootShare)
            quietest = minOf(quietest, loudness)
            loudest = maxOf(loudest, loudness)
            assertTrue(rawPeak <= 1f, "${patch.name}: raw peak $rawPeak relies on shared leveling for safety")
            assertTrue(peak <= .99001f, "${patch.name}: output peak $peak exceeds export headroom")
            assertTrue(dc < .005, "${patch.name}: output DC $dc")
            assertTrue(cents < 5.0, "${patch.name}: common requested root is $cents cents off")
            assertTrue(rootShare > .3, "${patch.name}: layered fundamental share $rootShare is too weak")
            if (patch.name !in referenceNames) {
                assertTrue(harmonicEnergy[0] >= harmonicEnergy.drop(1).max(),
                    "${patch.name}: a higher harmonic dominates the requested factory root")
            }
            assertEquals(Dsp.MELODIC_LOUDNESS_TARGET, loudness, .005f, "${patch.name}: shared melodic level")
        }
        println("LACQUER 36-preset common-performance bounds: raw peak $largestRawPeak; DC $largestDc; root error $worstCents cents; " +
            "minimum fundamental share $smallestRootShare; loudness $quietest..$loudest")
    }

    @Test
    fun `delayed factory recipes keep substantial roots across octaves and playing strengths`() {
        val weakRoots = mutableListOf<String>()
        for (name in listOf("Wire Flick", "Bright Lip", "Split Plectrum")) {
            val common = comparisons.single { it.patch.name == name }
            val patch = common.patch
            var smallestShare = 1.0
            for (midi in listOf(36, 48, 60, 72)) for (velocity in listOf(.35f, 1f)) {
                val probe = if (midi == 48 && velocity == 1f) common.probe else Lacquer.renderProbe(
                    patch.voice, patch.macros, midi = midi, velocity = velocity, profile = patch.profile,
                    seconds = 1.2f, normalize = true,
                )
                val energy = harmonicEnergies(probe.samples, Keys.midiHz(midi).toDouble())
                val share = energy[0] / energy.sum()
                val strongest = energy.indices.maxBy { energy[it] } + 1
                smallestShare = minOf(smallestShare, share)
                println("LACQUER ${patch.name} MIDI$midi velocity$velocity: fundamental share $share; strongest harmonic $strongest")
                // A narrow tuning estimate can find a quiet fundamental despite
                // cancellation between independently articulated root layers.
                // Require substantial root energy here; a bright upper harmonic
                // may legitimately be louder at some notes and velocities.
                if (share <= .3 || !share.isFinite()) weakRoots += "${patch.name} MIDI$midi velocity$velocity: $share"
            }
            println("LACQUER ${patch.name} register and velocity guard: minimum fundamental share $smallestShare")
        }
        assertTrue(weakRoots.isEmpty(), "delayed recipes have insufficient requested-root energy: ${weakRoots.joinToString()}")
    }

    @Test
    fun `every factory hold recipe closes its actual saved contour cycle at its requested pitch`() {
        val held = LacquerPresets.all().filter { Lacquer.isLoop(it.macros.getValue("HOLD")) }
        assertTrue(held.isNotEmpty(), "the bank lost its preserved held reference")
        var worstSeam = 0.0
        var worstUnwrapped = 0.0
        for (patch in held) {
            val probe = Lacquer.renderProbe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.profile, normalize = true)
            assertTrue(probe.samples.all { it.isFinite() } && probe.raw.all { it.isFinite() }, "${patch.name}: nonfinite held state")
            assertTrue(probe.previousCycle.size >= 256, "${patch.name}: no preceding sustain cycle")
            val seam = Keys.seamError(probe.previousCycle + probe.samples, probe.previousCycle.size)
            worstSeam = max(worstSeam, seam)
            worstUnwrapped = max(worstUnwrapped, probe.unwrappedSeamError)
            assertTrue(seam < Keys.MAX_SEAM_ERROR, "${patch.name}: actual saved HOLD seam $seam")
            assertTrue(probe.unwrappedSeamError < Keys.MAX_SEAM_ERROR, "${patch.name}: underlying held state does not repeat")
            assertTrue(abs(FineTuning.cents(probe.renderedHz, probe.requestedHz)) < .5, "${patch.name}: loop coarsely retunes the requested note")
            assertEquals(DrumClass.LOOP, Lacquer.drumClassFor(patch.voice, patch.macros))
        }
        println("LACQUER ${held.size} factory HOLD recipes: worst wrapped seam $worstSeam; unwrapped seam $worstUnwrapped")
    }

    private data class Comparison(val patch: LacquerPatch, val probe: Lacquer.Probe)

    private companion object {
        // The bank is rendered once for both timbral and numerical checks. Named
        // recipes keep their source/profile and all five timbral controls; only the
        // performance and loop switch are fixed for an honest same-note comparison.
        val comparisons by lazy {
            LacquerPresets.all().map { patch -> Comparison(patch, Lacquer.renderProbe(
                patch.voice, patch.macros + mapOf("TUNE" to .5f, "HOLD" to 0f),
                midi = 48, velocity = 1f, profile = patch.profile, seconds = 1.2f, normalize = true,
            )) }
        }

        fun matchedDistance(a: FloatArray, b: FloatArray): Double {
            val n = minOf(a.size, b.size)
            var aEnergy = 0.0
            var bEnergy = 0.0
            for (i in 0 until n) {
                aEnergy += a[i].toDouble() * a[i]
                bEnergy += b[i].toDouble() * b[i]
            }
            val aRms = sqrt(aEnergy / n)
            val bRms = sqrt(bEnergy / n)
            require(aRms > 1e-8 && bRms > 1e-8)
            var difference = 0.0
            for (i in 0 until n) {
                val delta = a[i] / aRms - b[i] / bRms
                difference += delta * delta
            }
            return sqrt(difference / n)
        }

        fun harmonicEnergies(samples: FloatArray, hz: Double): DoubleArray {
            val from = (.2 * Dsp.RATE).toInt()
            val length = (.6 * Dsp.RATE).toInt()
            return DoubleArray(8) { harmonic ->
                var real = 0.0
                var imaginary = 0.0
                val step = 2 * Math.PI * hz * (harmonic + 1) / Dsp.RATE
                for (i in 0 until length) {
                    val sample = samples[from + i] * (.5 - .5 * kotlin.math.cos(2 * Math.PI * i / (length - 1)))
                    real += sample * kotlin.math.cos(step * i)
                    imaginary += sample * kotlin.math.sin(step * i)
                }
                real * real + imaginary * imaginary
            }
        }
    }
}
