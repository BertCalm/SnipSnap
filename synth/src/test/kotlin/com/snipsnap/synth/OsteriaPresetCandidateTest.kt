package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
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

/** The audition proposals must be useful saved notes before they become factory choices. */
class OsteriaPresetCandidateTest {
    private val candidates = OsteriaPresetCandidates.candidates()

    @Test
    fun `eight original proposals retain a controlled dry press after factory promotion`() {
        assertEquals(8, candidates.size)
        assertEquals(8, candidates.map { it.id }.distinct().size)
        assertEquals(8, candidates.map { it.patch.name }.distinct().size)
        val byVoice = candidates.groupBy { it.patch.voice }
        assertEquals(setOf(OsteriaVoice.BASS, OsteriaVoice.PLUCK, OsteriaVoice.REED, OsteriaVoice.PAD), byVoice.keys)
        assertTrue(byVoice.values.all { it.size == 2 })
        assertTrue(OsteriaPresets.original().all { it.voice == OsteriaVoice.QUARTET })
        assertTrue(candidates.all { it.patch in OsteriaPresets.selected() })
        for (candidate in candidates) {
            val patch = candidate.patch
            assertTrue(candidate.id.matches(Regex("[a-z]+(?:-[a-z]+)+")))
            assertTrue(candidate.description.isNotBlank())
            assertEquals(Osteria.defaults(patch.voice).keys, patch.macros.keys)
            assertEquals(48, patch.midi)
            assertEquals(.8f, patch.velocity)
            assertEquals(173, patch.config.seed)
            assertEquals(2, patch.config.model)
            assertEquals(0f, patch.macros.getValue("ROOM"))
            assertEquals(0f, patch.macros.getValue("HOLD"))
            assertTrue(abs(patch.config.seconds - patch.config.releaseSeconds - OsteriaPresetCandidates.GATE_SECONDS) < 1e-9)
        }
    }

    @Test
    fun `complete proposal and pad recipes replay the same finite note`() {
        for ((_, _, patch) in candidates) {
            val restored = OsteriaPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored)
            assertEquals(patch.toJsonText(), restored.toJsonText())
            val pad = PadRecipe.fromJsonText(PadRecipe(patch = restored).toJsonText())
            assertEquals(patch, pad.patch)
            val first = patch.render()
            assertContentEquals(first.samples, pad.render().samples, patch.name)
            assertTrue(first.samples.all { it.isFinite() && abs(it) <= .990001f }, "${patch.name} has invalid production PCM")
            assertTrue(first.samples.takeLast(2).all { abs(it) < .0001f }, "${patch.name} has no finite ending")
        }
    }

    @Test
    fun `pairs retain distinct normalized tone at onset and through the held note`() {
        for ((voice, pair) in candidates.groupBy { it.patch.voice }) {
            val renders = pair.map { candidate ->
                val patch = candidate.patch
                Osteria.probe(voice, patch.macros, patch.midi, patch.velocity, patch.config).samples
            }
            // The original threshold of .01 admitted nearly identical bass colors
            // and pads which converged to the same held tone. Require a useful
            // redistribution in both windows, independently of level or release.
            // Pad's deliberate slow opening needs to finish before comparing
            // its early sustained color; its earlier contrast is articulation.
            val early = if (voice == OsteriaVoice.PAD) 1.20 to 1.80 else .10 to 1.45
            for ((start, end) in listOf(early, 4.2 to 4.9)) {
                val spectra = renders.map { spectrum(it, start, end) }
                val distance = spectra[0].indices.sumOf { abs(spectra[0][it] - spectra[1][it]) } / 2.0
                assertTrue(distance > .055, "$voice pair converges at $start seconds: $distance")
            }
        }
    }

    @Test
    fun `remembered proposals change color without changing their actual journey`() {
        val remembered = candidates.filter { it.patch.macros.getValue("MEMORY") > 0f }
        assertEquals(setOf(OsteriaVoice.BASS, OsteriaVoice.REED, OsteriaVoice.PAD), remembered.map { it.patch.voice }.toSet())
        for ((_, _, patch) in remembered) {
            val on = Osteria.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config)
            val off = Osteria.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config,
                Osteria.ProbeOptions(memoryEnabled = false))
            assertEquals(on.shoreTrace.size, off.shoreTrace.size)
            for (index in on.shoreTrace.indices) {
                assertEquals(on.shoreTrace[index].timeSeconds, off.shoreTrace[index].timeSeconds)
                assertEquals(on.shoreTrace[index].positions, off.shoreTrace[index].positions,
                    "${patch.name} memory changed the current journey")
            }
            val onLevel = AuditionLevel.level(Snip(on.samples, 2, Dsp.RATE)).samples
            val offLevel = AuditionLevel.level(Snip(off.samples, 2, Dsp.RATE)).samples
            val difference = relativeDifference(onLevel, offLevel, .5, 4.9)
            assertTrue(difference > .01, "${patch.name} memory has no color effect after matching: $difference")
        }
    }

    @Test
    fun `revised remembered presets develop a fuller colored body instead of settling near their start`() {
        fun bands(id: String, start: Double, end: Double): DoubleArray {
            val patch = candidates.single { it.id == id }.patch
            val raw = Osteria.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config)
            return spectrum(raw.samples, start, end)
        }
        // Roaming Bass previously began and ended at the same full-body color.
        // Its new hollow-to-full progression must survive level normalization.
        val bassEarly = bands("roaming-bass", .85, 1.45)
        val bassLate = bands("roaming-bass", 4.2, 4.9)
        val bassDistance = bassEarly.indices.sumOf { abs(bassEarly[it] - bassLate[it]) } / 2.0
        assertTrue(bassDistance > .07, "Roaming Bass has no defined held-note color journey: $bassDistance")

        // The previous Pad barely redistributed harmonic power across its held note.
        // The revision must make a substantial color journey at equal energy.
        val padEarly = bands("memory-drift", .85, 1.45)
        val padLate = bands("memory-drift", 4.2, 4.9)
        val distance = padEarly.indices.sumOf { abs(padEarly[it] - padLate[it]) } / 2.0
        assertTrue(distance > .15, "Memory Drift has no substantial held-note color change: $distance")

        // Reed now opens into its upper body rather than finishing mostly on the root.
        val reedEarly = bands("remembered-reed", .85, 1.45)
        val reedLate = bands("remembered-reed", 4.2, 4.9)
        fun centroid(values: DoubleArray) = values.indices.sumOf { (it + 1) * values[it] }
        assertTrue(1.0 - reedLate[0] > .30, "Remembered Reed lost its late upper body")
        // The softer source deliberately removes the old upper formants. Require
        // substantial opening without forcing the previous high-harmonic centroid.
        assertTrue(centroid(reedLate) > centroid(reedEarly) + .30, "Remembered Reed does not open through the held note")
    }

    @Test
    fun `soft practical registers retain the requested root and mono body`() {
        for ((_, _, base) in candidates) {
            val midi = if (base.voice == OsteriaVoice.BASS) 36 else 60
            val patch = base.copy(midi = midi, velocity = .35f)
            val snip = patch.render()
            assertTrue(snip.samples.all { it.isFinite() && abs(it) <= .990001f }, "${patch.name} soft note has invalid PCM")
            var stereoEnergy = 0.0
            var foldedEnergy = 0.0
            for (frame in 0 until snip.samples.size / 2) {
                val left = snip.samples[frame * 2].toDouble()
                val right = snip.samples[frame * 2 + 1].toDouble()
                stereoEnergy += (left * left + right * right) * .5
                foldedEnergy += ((left + right) * .5).let { it * it }
            }
            assertTrue(stereoEnergy > 1e-8 && foldedEnergy / stereoEnergy > .80,
                "${patch.name} loses its body in the phone's mono preview")
            for (time in floatArrayOf(.90f, 1.80f)) {
                val pitch = Pitch.detect(snip, time, .30f)
                assertNotNull(pitch, "${patch.name} lost the root at $time")
                val cents = 1200 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
                assertTrue(abs(cents) < 20 && pitch.confidence >= .3f,
                    "${patch.name} soft MIDI $midi at $time: $cents cents, confidence ${pitch.confidence}")
            }
        }
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray, start: Double, end: Double): Double {
        val first = (start * Dsp.RATE).toInt() * 2
        val last = minOf(a.size, b.size, (end * Dsp.RATE).toInt() * 2)
        var difference = 0.0
        var energy = 0.0
        for (i in first until last) {
            val delta = a[i].toDouble() - b[i]
            difference += delta * delta
            energy += .5 * (a[i].toDouble() * a[i] + b[i].toDouble() * b[i])
        }
        return sqrt(difference / energy.coerceAtLeast(1e-24))
    }

    /** Normalize each harmonic frame, so gain alone cannot pass the pair test. */
    private fun spectrum(stereo: FloatArray, startSeconds: Double, endSeconds: Double): DoubleArray {
        val size = 8192
        val root = Keys.midiHz(48).toDouble()
        val result = DoubleArray(16)
        val start = (startSeconds * Dsp.RATE).toInt()
        val end = minOf(stereo.size / 2, (endSeconds * Dsp.RATE).toInt())
        var count = 0
        for (frame in start..end - size step size / 2) {
            val real = FloatArray(size) { offset ->
                ((stereo[(frame + offset) * 2] + stereo[(frame + offset) * 2 + 1]) * .5 *
                    (.5 - .5 * cos(2 * PI * offset / (size - 1)))).toFloat()
            }
            val imaginary = FloatArray(size)
            Fft.forward(real, imaginary)
            val bands = DoubleArray(result.size)
            for (bin in 1..size / 2) {
                val harmonic = (bin * Dsp.RATE / (size * root) + .5).toInt()
                if (harmonic in 1..bands.size) {
                    bands[harmonic - 1] += real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                }
            }
            val total = bands.sum()
            if (total > 1e-24) {
                for (i in result.indices) result[i] += bands[i] / total
                count++
            }
        }
        check(count > 0)
        return DoubleArray(result.size) { result[it] / count }
    }
}
