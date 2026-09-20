package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThumpTest {

    // ---------- the classifier is the judge ----------
    // A THUMP factory sound isn't done until our own analysis calls it what
    // it claims to be. This is the playability contract, executable.

    private fun classOf(voice: ThumpVoice) = Classifier.classify(Thump.render(voice)).drumClass

    @Test fun `factory kick is a kick`() = assertEquals(DrumClass.KICK, classOf(ThumpVoice.KICK))
    @Test fun `factory snare is a snare`() = assertEquals(DrumClass.SNARE, classOf(ThumpVoice.SNARE))
    @Test fun `factory closed hat is a closed hat`() =
        assertEquals(DrumClass.HAT_CLOSED, classOf(ThumpVoice.HAT_CLOSED))
    @Test fun `factory open hat is an open hat`() =
        assertEquals(DrumClass.HAT_OPEN, classOf(ThumpVoice.HAT_OPEN))
    @Test fun `factory clap is a clap`() = assertEquals(DrumClass.CLAP, classOf(ThumpVoice.CLAP))
    @Test fun `factory tom is a tom`() = assertEquals(DrumClass.TOM, classOf(ThumpVoice.TOM))

    // ---------- macros are audible and bounded ----------

    @Test
    fun `kick TUNE moves the pitch`() {
        val low = FeatureExtractor.extract(Thump.render(ThumpVoice.KICK, mapOf("TUNE" to 0f)))
        val high = FeatureExtractor.extract(Thump.render(ThumpVoice.KICK, mapOf("TUNE" to 1f)))
        assertTrue(high.centroidHz > low.centroidHz, "TUNE up should raise the centroid: ${low.centroidHz} -> ${high.centroidHz}")
    }

    @Test
    fun `kick DECAY moves the decay`() {
        val short = FeatureExtractor.extract(Thump.render(ThumpVoice.KICK, mapOf("DECAY" to 0f)))
        val long = FeatureExtractor.extract(Thump.render(ThumpVoice.KICK, mapOf("DECAY" to 1f)))
        assertTrue(long.decayMs > short.decayMs * 2, "DECAY should stretch: ${short.decayMs} -> ${long.decayMs}")
    }

    @Test
    fun `snare SNAP shifts tone toward noise`() {
        val tone = FeatureExtractor.extract(Thump.render(ThumpVoice.SNARE, mapOf("SNAP" to 0.05f)))
        val noise = FeatureExtractor.extract(Thump.render(ThumpVoice.SNARE, mapOf("SNAP" to 1f)))
        assertTrue(noise.flatness > tone.flatness, "SNAP up should be noisier: ${tone.flatness} -> ${noise.flatness}")
    }

    @Test
    fun `the snare body is a membrane, not two sines`() {
        // A membrane's partials sit at the Bessel ratios. Two sines at 1.83
        // cannot produce a peak near 2.135x the fundamental; a real head does.
        val snip = Thump.render(ThumpVoice.SNARE, mapOf("SNAP" to 0.1f, "DECAY" to 0.8f, "TUNE" to 0.4f))
        val fftSize = 4096
        val spectrum = com.snipsnap.audio.Fft.magnitudeSpectrum(snip.samples, fftSize)
        val f0 = Thump.snareFundamental(0.4f)
        fun energyNear(hz: Float): Float {
            val lo = hz * 0.94f; val hi = hz * 1.06f
            return spectrum.indices.filter {
                com.snipsnap.audio.Fft.binToHz(it, fftSize, Dsp.RATE) in lo..hi
            }.maxOfOrNull { spectrum[it] } ?: 0f
        }
        // (2,1) at 2.1354 is the mode two sines at 1.83 cannot fake.
        assertTrue(
            energyNear(f0 * 2.1354f) > energyNear(f0 * 1.83f) * 0.5f,
            "expected real membrane structure, not a 1.83 sine pair",
        )
    }

    @Test
    fun `STRIKE changes the snare's spectrum across its whole travel`() {
        // Swept, not spot-checked: a two-point test on this project once passed
        // a wrong implementation. Hitting nearer the rim wakes higher modes.
        val centroids = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { p ->
            val snip = Thump.render(ThumpVoice.SNARE, mapOf("STRIKE" to p, "SNAP" to 0.15f))
            com.snipsnap.audio.FeatureExtractor.extract(snip).centroidHz
        }
        for (i in 0 until centroids.size - 1) {
            assertTrue(
                kotlin.math.abs(centroids[i] - centroids[i + 1]) > 1f,
                "STRIKE did nothing between step $i and ${i + 1}: $centroids",
            )
        }
    }

    @Test
    fun `the snare still renders clean audio at every macro corner`() {
        val names = Thump.macrosFor(ThumpVoice.SNARE).map { it.name }
        for (corner in listOf(0f, 1f)) {
            val snip = Thump.render(ThumpVoice.SNARE, names.associateWith { corner })
            assertTrue(snip.samples.all { it.isFinite() }, "NaN/Inf at all-$corner")
            assertTrue(snip.samples.any { kotlin.math.abs(it) > 0.1f }, "silence at all-$corner")
        }
    }

    @Test
    fun `SNAP spans a real drum to a static burst`() {
        // Both ends must be REACHABLE - the static burst is a palette sound,
        // not a defect, and a range curated to only tasteful settings has
        // already made the user's decisions for them.
        // Spectral FLATNESS is the tonal-vs-noise measure :audio actually
        // exposes: a flat spectrum is noise, a peaky one is pitched. So the
        // drum end must be LOW and the static end HIGH - note the direction.
        fun flatnessAt(snap: Float): Float {
            val snip = Thump.render(ThumpVoice.SNARE, mapOf("SNAP" to snap, "DECAY" to 0.7f))
            return com.snipsnap.audio.FeatureExtractor.extract(snip).flatness
        }
        val drum = flatnessAt(0f)
        val static = flatnessAt(1f)
        assertTrue(static > drum * 2f, "SNAP=1 should be clearly noisier: drum=$drum static=$static")
    }

    @Test
    fun `SNAP moves at every step of its travel`() {
        // Swept. A crossfade that saturates early leaves half the knob dead,
        // which is this project's most-repeated defect.
        val points = (0..8).map { it / 8f }
        val measured = points.map { s ->
            val snip = Thump.render(ThumpVoice.SNARE, mapOf("SNAP" to s, "DECAY" to 0.7f))
            com.snipsnap.audio.FeatureExtractor.extract(snip).centroidHz
        }
        for (i in 0 until measured.size - 1) {
            assertTrue(
                kotlin.math.abs(measured[i] - measured[i + 1]) > 1f,
                "SNAP is dead between ${points[i]} and ${points[i + 1]}: $measured",
            )
        }
    }

    @Test
    fun `hat METAL brightens`() {
        val dull = FeatureExtractor.extract(Thump.render(ThumpVoice.HAT_CLOSED, mapOf("METAL" to 0f)))
        val bright = FeatureExtractor.extract(Thump.render(ThumpVoice.HAT_CLOSED, mapOf("METAL" to 1f)))
        assertTrue(bright.centroidHz > dull.centroidHz)
    }

    @Test
    fun `open hat rings longer than closed at defaults`() {
        val closed = FeatureExtractor.extract(Thump.render(ThumpVoice.HAT_CLOSED))
        val open = FeatureExtractor.extract(Thump.render(ThumpVoice.HAT_OPEN))
        assertTrue(open.decayMs > closed.decayMs * 2)
    }

    @Test
    fun `every corner of every macro space renders clean audio`() {
        // Bounded-by-construction, verified: all-zeros and all-ones must be
        // playable sounds, not silence, clipping or NaN.
        for (voice in ThumpVoice.entries) {
            for (value in floatArrayOf(0f, 1f)) {
                val macros = Thump.macrosFor(voice).associate { it.name to value }
                val snip = Thump.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice at $value rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() }, "$voice at $value produced non-finite samples")
                assertTrue(snip.samples.all { it in -1f..1f }, "$voice at $value clipped")
                assertTrue(snip.peak() > 0.5f, "$voice at $value is too quiet: ${snip.peak()}")
            }
        }
    }

    @Test
    fun `voices ramp in over the first millisecond instead of jumping to full level`() {
        // Regression for the U5 attack ramp (Dsp.Env, 1ms): proves the
        // ramped voices actually start at zero and rise, not just that
        // they're clean - reverting to the old instant onset would still
        // pass every other THUMP test in this file. CLAP and RIM are the
        // documented exceptions (their own envelopes don't fit the
        // primitive), so they're excluded here rather than asserted false.
        val ramped = ThumpVoice.entries - ThumpVoice.CLAP - ThumpVoice.RIM
        for (voice in ramped) {
            // Max CLICK so KICK's own click burst - which needed its own
            // fix for this same bug - is exercised too, not just its sine.
            val snip = Thump.render(voice, mapOf("CLICK" to 1f))
            // A small tolerance, not exact zero: U6's oversample/decimate
            // (docs/SYNTH_UPGRADE.md) runs every render through a linear-
            // phase resample filter, which pre-rings a hair ahead of any
            // sharp edge - including this envelope's own onset. That's an
            // unavoidable property of a band-limited filter, not a revival
            // of the instant-onset bug this test exists to catch.
            assertEquals(0f, snip.samples[0], 0.02f, "$voice: first sample should start at zero, not jump to full level")
            val earlyPeak = snip.samples.take((Dsp.RATE * 0.02f).toInt()).maxOf { kotlin.math.abs(it) }
            assertTrue(earlyPeak > 0.1f, "$voice: should audibly ramp up within the first 20ms, peaked at $earlyPeak")
        }
    }

    @Test
    fun `scramble is reproducible and always playable`() {
        for (voice in ThumpVoice.entries) {
            val a = Thump.scramble(voice, Random(42))
            val b = Thump.scramble(voice, Random(42))
            assertEquals(a, b, "same seed must roll the same patch")

            repeat(10) { roll ->
                val macros = Thump.scramble(voice, Random(roll))
                assertTrue(macros.values.all { it in 0f..1f })
                val snip = Thump.render(voice, macros)
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $roll broke")
                assertTrue(snip.peak() > 0.5f, "$voice roll $roll too quiet")
            }
        }
    }

    @Test
    fun `scramble honors temperature and near`() {
        // The Dsp.scrambleNear boundary contract, proven end-to-end through
        // Thump's own wiring: see DspTest for the central proof.
        for (voice in ThumpVoice.entries) {
            val preset = ThumpPresets.forVoice(voice).first()
            assertEquals(
                // A preset doesn't have to name every macro Thump knows about
                // (PUNCH, added after these presets were written, is exactly
                // such a gap) - `near`'s seed is always the full default
                // macro map with the preset's explicit values layered on
                // top, same as Thump.scramble's own seed construction.
                Thump.defaults(voice) + preset.macros,
                Thump.scramble(voice, Random(1), temperature = 0f, near = preset),
                "$voice: temperature 0 should return the seed untouched",
            )
            val flat = Thump.scramble(voice, Random(1), temperature = 1f, near = preset)
            assertTrue(flat.values.all { it in 0f..1f }, "$voice: temperature 1 left the 0..1 range")

            // Copilot's review of this PR: at temperature >= 1 with no
            // `near`, scramble must not spend a random draw picking a
            // preset first - Dsp.scrambleNear ignores the seed's values
            // there anyway, and a spent draw would shift a shared
            // Random's downstream sequence from the pre-U2 behaviour
            // this boundary promises.
            assertEquals(
                Dsp.scrambleNear(Thump.defaults(voice), 1f, Random(2)),
                Thump.scramble(voice, Random(2), temperature = 1f),
                "$voice: temperature 1 with no near must not consume a preset-selection draw",
            )
        }
    }

    @Test
    fun `scrambled kicks still read as kicks most of the time`() {
        // The whole point of bounded ranges: the dice land somewhere musical.
        var kicks = 0
        repeat(10) { roll ->
            val c = Classifier.classify(Thump.render(ThumpVoice.KICK, Thump.scramble(ThumpVoice.KICK, Random(roll))))
            if (c.drumClass == DrumClass.KICK) kicks++
        }
        assertTrue(kicks >= 7, "only $kicks/10 scrambled kicks classified as KICK")
    }

    @Test
    fun `unknown macros are ignored, known ones clamp`() {
        val snip = Thump.render(ThumpVoice.KICK, mapOf("WOBBLE" to 0.5f, "TUNE" to 9f))
        assertTrue(snip.frameCount > 0)
    }

    @Test
    fun `renders are deterministic`() {
        val a = Thump.render(ThumpVoice.SNARE)
        val b = Thump.render(ThumpVoice.SNARE)
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `renders are mpc-native`() {
        val snip = Thump.render(ThumpVoice.CLAP)
        assertEquals(44_100, snip.sampleRate)
        assertEquals(1, snip.channels)
    }

    // ---------- patches ----------

    @Test
    fun `patch round-trips through json`() {
        val patch = ThumpPatch("Basement Kick", ThumpVoice.KICK, mapOf("TUNE" to 0.2f, "DRIVE" to 0.8f))
        assertEquals(patch, ThumpPatch.fromJsonText(patch.toJsonText()))
    }

    @Test
    fun `patch render equals direct render`() {
        val patch = ThumpPatch("X", ThumpVoice.TOM, mapOf("TUNE" to 0.7f))
        assertTrue(patch.render().samples.contentEquals(Thump.render(ThumpVoice.TOM, mapOf("TUNE" to 0.7f)).samples))
    }

    @Test
    fun `patch validation refuses nonsense`() {
        assertFailsWith<IllegalArgumentException> { ThumpPatch("", ThumpVoice.KICK, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ThumpPatch("x", ThumpVoice.KICK, mapOf("CUTOFF" to 0.5f)) }
        assertFailsWith<IllegalArgumentException> { ThumpPatch("x", ThumpVoice.KICK, mapOf("TUNE" to 2f)) }
        assertFailsWith<com.snipsnap.json.JsonException> { ThumpPatch.fromJsonText("""{"engine":"VELVET"}""") }
    }

    @Test
    fun `PUNCH at its factory default roughly preserves loudness through the full render`() {
        // PunchTest proves Punch.apply's own loudness match in isolation;
        // this proves the ordering contract survives contact with render()'s
        // own Dsp.normalize/Dsp.limitPeak either side of it. Getting that
        // ordering backwards (normalize running again *after* Punch, silently
        // overwriting the level Punch just matched) would go uncaught by
        // PunchTest alone, since it never touches Thump.render at all.
        for (voice in ThumpVoice.entries) {
            val off = Loudness.of(Thump.render(voice, mapOf("PUNCH" to 0f)))
            val default = Loudness.of(Thump.render(voice))
            // 25%, not the 20% first measured: U6's oversample/decimate
            // (docs/SYNTH_UPGRADE.md) nudges every voice's exact sample
            // values a little, and CLAP - already the peakiest, most
            // safety-limiter-sensitive voice in the PUNCH design notes -
            // lands right at that new margin's edge.
            assertTrue(
                kotlin.math.abs(default - off) < off * 0.25f,
                "$voice: PUNCH off vs its factory default should stay close: $off -> $default",
            )
        }
    }
}
