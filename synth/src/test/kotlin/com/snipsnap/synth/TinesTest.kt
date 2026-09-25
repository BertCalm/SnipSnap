package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TinesTest {

    // ---------- the playability contract ----------

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in TinesVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Tines.macrosFor(voice).associate { it.name to 0f },
                Tines.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Tines.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
            }
        }
    }

    @Test
    fun `voices ramp in over the first millisecond instead of jumping to full level`() {
        // Regression for the U5 attack ramp (Dsp.Env, 1ms), applied here via
        // the shared strike() primitive plus ZAP and TOY's own inline loops:
        // proves every voice actually starts at zero and rises, not just
        // that it's clean - reverting to the old instant onset would still
        // pass every other TINES test in this file.
        for (voice in TinesVoice.entries) {
            val snip = Tines.render(voice)
            // A small tolerance, not exact zero: U6's oversample/decimate
            // (docs/SYNTH_UPGRADE.md) runs every render through a linear-
            // phase resample filter, which pre-rings a hair ahead of any
            // sharp edge - including this envelope's own onset. That's an
            // unavoidable property of a band-limited filter, not a revival
            // of the instant-onset bug this test exists to catch (see
            // ThumpTest's own version of this same fix).
            assertEquals(0f, snip.samples[0], 0.02f, "$voice: first sample should start at zero, not jump to full level")
            val earlyPeak = snip.samples.take((Dsp.RATE * 0.02f).toInt()).maxOf { kotlin.math.abs(it) }
            assertTrue(earlyPeak > 0.1f, "$voice: should audibly ramp up within the first 20ms, peaked at $earlyPeak")
        }
    }

    @Test
    fun `render actually dispatches oversampled before decimating - CHIME's own aliasing regression`() {
        // CHIME at full BRIGHT drives its modulation index up to 4 at a
        // carrier around 1500Hz with ratio 3.5 (modHz ~5.25kHz) - FM
        // sidebands spread roughly (index+1) x modHz either side of the
        // carrier, well past 44.1kHz's 22.05kHz Nyquist even at these
        // fairly ordinary settings. Reverting Tines.render's dispatch back
        // to synthesizing directly at RATE (skipping renderRate +
        // Dsp.decimate) would leave every other TINES test in this file
        // green - this is the one that would catch it (Copilot's review of
        // this PR: "a focused regression through Tines.render").
        val bright = Tines.render(TinesVoice.CHIME, mapOf("BRIGHT" to 1f, "TUNE" to 1f, "DECAY" to 1f))

        // A direct, non-oversampled reference at the same carrier/index/t60
        // CHIME's own bright() computes at these macro settings (one
        // strike, not chime()'s two-detuned-strikes blend - close enough to
        // isolate the ordering, not an attempt to reproduce chime() exactly),
        // synthesized with the same strike() primitive Tines.kt wraps, at
        // Dsp.RATE directly rather than through render()'s oversample step.
        val carrier = Dsp.expMap(1f, 520f, 1500f)
        val index = Dsp.lin(1f, 0.6f, 4f)
        val t60 = Dsp.expMap(1f, 0.2f, 0.9f)
        val direct = FloatArray((t60 * 1.3f * Dsp.RATE).toInt().coerceAtLeast(64))
        Tines.strike(direct, carrier, 3.5f, index, t60, bite = 3f, gain = 0.6f, rate = Dsp.RATE)
        Dsp.normalize(direct)

        val fftSize = 4096
        val brightSpectrum = com.snipsnap.audio.Fft.magnitudeSpectrum(bright.samples, fftSize)
        val directSpectrum = com.snipsnap.audio.Fft.magnitudeSpectrum(direct, fftSize)
        val binHz = Dsp.RATE.toFloat() / fftSize

        // Just under Nyquist: real FM sideband energy that only lands here
        // if it was never properly band-limited during synthesis - measured
        // ~6x higher for the direct render than for Tines.render's own
        // output.
        val lo = (19_000 / binHz).toInt()
        val hi = (22_000 / binHz).toInt()
        fun bandEnergy(spectrum: FloatArray) = (lo..hi).sumOf { (spectrum[it] * spectrum[it]).toDouble() }
        val brightEnergy = bandEnergy(brightSpectrum)
        val directEnergy = bandEnergy(directSpectrum)
        assertTrue(
            directEnergy > brightEnergy * 3,
            "a direct RATE-native render should show detectably more near-Nyquist energy than " +
                "Tines.render's own oversampled-then-decimated output: bright=$brightEnergy direct=$directEnergy",
        )
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        for (voice in TinesVoice.entries) {
            assertEquals(Tines.scramble(voice, Random(4)), Tines.scramble(voice, Random(4)))
            repeat(8) { seed ->
                val snip = Tines.render(voice, Tines.scramble(voice, Random(seed)))
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $seed broke")
            }
        }
    }

    @Test
    fun `scrambled hits usually still land off the kick slot and loop shelf`() {
        // A scrambled hit may land on a neighbouring pad, but never on the
        // two placements that would actually hurt: the kick slot (these
        // have no sub content) or the loop shelf (they are hits). SCRAMBLE
        // now rolls near a preset (docs/SYNTH_UPGRADE.md, U2), so - like a
        // preset itself can - a roll can land close to that boundary;
        // "most of the time", not "always", is the doc's own contract.
        for (voice in TinesVoice.entries) {
            var misses = 0
            val rolls = 30
            repeat(rolls) { seed ->
                val c = Classifier.classify(Tines.render(voice, Tines.scramble(voice, Random(seed))))
                if (c.drumClass == DrumClass.KICK || c.drumClass == DrumClass.LOOP || c.drumClass == DrumClass.UNKNOWN) misses++
            }
            assertTrue(misses <= rolls / 3, "$voice: $misses/$rolls scrambled rolls came back unplayable")
        }
    }

    @Test
    fun `scramble honors temperature and near`() {
        // The Dsp.scrambleNear boundary contract, proven end-to-end through
        // Tines's own wiring: see DspTest for the central proof.
        for (voice in TinesVoice.entries) {
            val preset = TinesPresets.forVoice(voice).first()
            assertEquals(
                preset.macros,
                Tines.scramble(voice, Random(1), temperature = 0f, near = preset),
                "$voice: temperature 0 should return the seed untouched",
            )
            val flat = Tines.scramble(voice, Random(1), temperature = 1f, near = preset)
            assertTrue(flat.values.all { it in 0f..1f }, "$voice: temperature 1 left the 0..1 range")

            // Copilot's review of this PR: at temperature >= 1 with no
            // `near`, scramble must not spend a random draw picking a
            // preset first - see ThumpTest's own version of this test.
            assertEquals(
                Dsp.scrambleNear(Tines.defaults(voice), 1f, Random(2)),
                Tines.scramble(voice, Random(2), temperature = 1f),
                "$voice: temperature 1 with no near must not consume a preset-selection draw",
            )
        }
    }

    @Test
    fun `every voice is a one-shot, not a phrase`() {
        for (voice in TinesVoice.entries) {
            val full = Tines.macrosFor(voice).associate { it.name to 1f }
            assertTrue(
                Tines.render(voice, full).durationSeconds < 1.5f,
                "$voice at full DECAY must stay under the loop threshold",
            )
        }
    }

    @Test
    fun `is deterministic`() {
        for (voice in TinesVoice.entries) {
            assertTrue(
                Tines.render(voice).samples.contentEquals(Tines.render(voice).samples),
                "$voice not deterministic",
            )
        }
    }

    // ---------- identity, judged by the classifier ----------

    @Test
    fun `factory defaults all classify as percussion`() {
        for (voice in TinesVoice.entries) {
            val c = Classifier.classify(Tines.render(voice))
            assertEquals(DrumClass.PERC, c.drumClass, "$voice default read as ${c.drumClass}")
        }
    }

    // ---------- macros do what their names promise ----------

    @Test
    fun `BRIGHT brightens`() {
        val dull = FeatureExtractor.extract(Tines.render(TinesVoice.BELL, mapOf("BRIGHT" to 0.05f)))
        val bright = FeatureExtractor.extract(Tines.render(TinesVoice.BELL, mapOf("BRIGHT" to 0.95f)))
        assertTrue(
            bright.centroidHz > dull.centroidHz * 1.3f,
            "BRIGHT should push the centroid up: ${dull.centroidHz} -> ${bright.centroidHz}",
        )
    }

    @Test
    fun `TUNE tunes`() {
        val lowHit = FeatureExtractor.extract(Tines.render(TinesVoice.BLOCK, mapOf("TUNE" to 0.1f)))
        val highHit = FeatureExtractor.extract(Tines.render(TinesVoice.BLOCK, mapOf("TUNE" to 0.9f)))
        assertTrue(
            highHit.centroidHz > lowHit.centroidHz * 1.3f,
            "TUNE up should raise the pitch centre: ${lowHit.centroidHz} -> ${highHit.centroidHz}",
        )
    }

    @Test
    fun `DECAY lengthens`() {
        val short = FeatureExtractor.extract(Tines.render(TinesVoice.BELL, mapOf("DECAY" to 0.1f)))
        val long = FeatureExtractor.extract(Tines.render(TinesVoice.BELL, mapOf("DECAY" to 0.9f)))
        assertTrue(
            long.decayMs > short.decayMs * 1.5f,
            "DECAY should stretch the ring: ${short.decayMs}ms -> ${long.decayMs}ms",
        )
    }

    @Test
    fun `RATIO snaps to characters, not a continuum`() {
        // 25 knob positions must collapse onto exactly the snapped set - the
        // whole point of RATIO is that every position is a character.
        val distinct = HashSet<List<Float>>()
        for (i in 0 until 25) {
            val snip = Tines.render(TinesVoice.BELL, mapOf("RATIO" to i / 24f))
            distinct.add(snip.samples.take(512))
        }
        assertEquals(Tines.RATIOS.size, distinct.size)
    }

    @Test
    fun `WOBBLE moves the pitch during the note`() {
        val steady = Tines.render(TinesVoice.TOY, mapOf("WOBBLE" to 0f))
        val wobbly = Tines.render(TinesVoice.TOY, mapOf("WOBBLE" to 1f))
        // Same length, audibly different waveform - the LFO is baked in.
        assertEquals(steady.frameCount, wobbly.frameCount)
        var diff = 0.0
        for (i in steady.samples.indices) diff += Math.abs((steady.samples[i] - wobbly.samples[i]).toDouble())
        assertTrue(diff / steady.samples.size > 0.05, "WOBBLE at full should audibly modulate")
    }

    // ---------- patches ----------

    @Test
    fun `patch round-trips through JSON`() {
        val patch = TinesPatch("Glass Half Full", TinesVoice.CHIME, mapOf("SHIMMER" to 0.8f, "DECAY" to 0.6f))
        val back = TinesPatch.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertTrue(back.render().samples.contentEquals(patch.render().samples))
    }

    @Test
    fun `a THUMP patch refuses to load as TINES`() {
        val thump = ThumpPatch("Basement Kick", ThumpVoice.KICK, mapOf("TUNE" to 0.2f))
        assertFailsWith<com.snipsnap.json.JsonException> {
            TinesPatch.fromJsonText(thump.toJsonText())
        }
    }

    @Test
    fun `patch validates macro names and ranges`() {
        assertFailsWith<IllegalArgumentException> {
            TinesPatch("Bad", TinesVoice.BELL, mapOf("CUTOFF" to 0.5f))
        }
        assertFailsWith<IllegalArgumentException> {
            TinesPatch("Bad", TinesVoice.BELL, mapOf("TUNE" to 1.5f))
        }
    }

    // ---------- KALIMBA ----------

    @Test
    fun `KALIMBA TUNE snaps to semitones from A3`() {
        // Two octaves inclusive, so the melodic kit's pads land on notes
        // that a pad recipe can replay as a macro value.
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Tines.frequencyFor(TinesVoice.KALIMBA, i / 100f))
        assertEquals(Tines.KALIMBA_TUNE_SEMITONES + 1, distinct.size)
        assertEquals(220f, Tines.frequencyFor(TinesVoice.KALIMBA, 0f))
        assertEquals(880f, Tines.frequencyFor(TinesVoice.KALIMBA, 1f))
    }

    @Test
    fun `only KALIMBA snaps`() {
        assertFailsWith<IllegalArgumentException> { Tines.frequencyFor(TinesVoice.BELL, 0.5f) }
    }

    @Test
    fun `KALIMBA rings the bar's partials, not a harmonic series`() {
        // A clamped-free bar's second partial sits at 6.267 f0, between the
        // sixth and seventh harmonics; the voice must put energy there and
        // not on the harmonics either side.
        val f0 = Tines.frequencyFor(TinesVoice.KALIMBA, 0.5f)
        val snip = Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to 0.5f, "BRIGHT" to 0.8f, "BUZZ" to 0f))
        val atBar = PluckSpectra.toneEnergy(snip, f0 * Tines.KALIMBA_PARTIALS[1], seconds = 0.1f)
        val atSixth = PluckSpectra.toneEnergy(snip, f0 * 6f, seconds = 0.1f)
        val atSeventh = PluckSpectra.toneEnergy(snip, f0 * 7f, seconds = 0.1f)
        assertTrue(
            atBar > atSixth * 4 && atBar > atSeventh * 4,
            "second partial should sit at 6.267 f0: bar=$atBar h6=$atSixth h7=$atSeventh",
        )
    }

    @Test
    fun `BUZZ rattles`() {
        val clean = FeatureExtractor.extract(Tines.render(TinesVoice.KALIMBA, mapOf("BUZZ" to 0f)))
        val buzzed = FeatureExtractor.extract(Tines.render(TinesVoice.KALIMBA, mapOf("BUZZ" to 1f)))
        assertTrue(
            buzzed.flatness > clean.flatness * 1.5f,
            "BUZZ should add noise: flatness ${clean.flatness} -> ${buzzed.flatness}",
        )
    }

    @Test
    fun `KALIMBA BRIGHT moves at every step of its travel`() {
        // The engine's velocity path is BRIGHT; the new voice has to keep it
        // monotonic, the way the snare's SNAP sweep is written.
        val points = (0..8).map { it / 8f }
        val measured = points.map { b ->
            FeatureExtractor.extract(Tines.render(TinesVoice.KALIMBA, mapOf("BRIGHT" to b, "BUZZ" to 0f))).centroidHz
        }
        for (i in 0 until measured.size - 1) {
            assertTrue(
                measured[i + 1] > measured[i] + 1f,
                "BRIGHT is dead or reversed between ${points[i]} and ${points[i + 1]}: $measured",
            )
        }
    }
}
