package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkinTest {

    // ---------- the classifier is the judge ----------
    // Same contract as ThumpTest: a factory sound isn't done until our own
    // analysis calls it what it claims to be.

    private fun classOf(voice: SkinVoice) = Classifier.classify(Skin.render(voice)).drumClass

    @Test fun `factory kick is a kick`() = assertEquals(DrumClass.KICK, classOf(SkinVoice.KICK))
    @Test fun `factory snare is a snare`() = assertEquals(DrumClass.SNARE, classOf(SkinVoice.SNARE))
    @Test fun `factory closed hat is a closed hat`() =
        assertEquals(DrumClass.HAT_CLOSED, classOf(SkinVoice.HAT_CLOSED))
    @Test fun `factory open hat is an open hat`() =
        assertEquals(DrumClass.HAT_OPEN, classOf(SkinVoice.HAT_OPEN))
    @Test fun `factory tom is a tom`() = assertEquals(DrumClass.TOM, classOf(SkinVoice.TOM))

    // RIDE measures as HAT_OPEN, not a guess: it's built from hat()'s exact
    // recipe (continuous noise through a resonant bank), just denser and
    // longer - the same sonic category, so this is a real structural
    // kinship, not a coincidence. SHAKER and STICK have no dedicated
    // DrumClass the way COWBELL/RIM don't for THUMP - see their own
    // SynthScreen.kt mapping comment for why they're PERC by the same
    // established convention, untested here for the identical reason
    // ThumpTest carries no COWBELL/RIM classifier assertions.
    @Test fun `factory ride is an open hat`() = assertEquals(DrumClass.HAT_OPEN, classOf(SkinVoice.RIDE))

    // ---------- macros are audible and bounded ----------

    @Test
    fun `kick TUNE moves the pitch`() {
        val low = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("TUNE" to 0f)))
        val high = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("TUNE" to 1f)))
        assertTrue(high.centroidHz > low.centroidHz, "TUNE up should raise the centroid: ${low.centroidHz} -> ${high.centroidHz}")
    }

    @Test
    fun `kick DECAY moves the decay`() {
        val short = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("DECAY" to 0f)))
        val long = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("DECAY" to 1f)))
        assertTrue(long.decayMs > short.decayMs * 2, "DECAY should stretch: ${short.decayMs} -> ${long.decayMs}")
    }

    @Test
    fun `kick's overtone partials register as real spectral energy, not just a color`() {
        // Regression for the KICK partial-level boost: TONE-monotonicity,
        // classifier, and clean-audio checks all still pass if the boosted
        // 1.59x/2.14x partials (0.25-0.9, was 0.1-0.6) are reverted back to
        // their old, too-quiet-to-hear levels - confirmed by temporarily
        // reverting kick() to the old formula and rerunning this exact
        // measurement, which read 0.0122 there and 0.0251 here. Centroid
        // alone can't tell the two apart (both overtones sit under 130Hz,
        // the same low neighbourhood as the fundamental), so this measures
        // spectral energy above the fundamental directly instead: the
        // fraction of all under-200Hz energy that sits above 65Hz, which
        // only the overtones - not the fundamental itself - contribute to.
        val snip = Skin.render(SkinVoice.KICK)
        val head = snip.samples.copyOfRange(0, minOf(snip.samples.size, 4096))
        val spectrum = Fft.magnitudeSpectrum(head, 4096)
        var above65 = 0f
        var under200 = 0f
        for (bin in spectrum.indices) {
            val hz = Fft.binToHz(bin, 4096, snip.sampleRate)
            if (hz < 200f) {
                under200 += spectrum[bin] * spectrum[bin]
                if (hz >= 65f) above65 += spectrum[bin] * spectrum[bin]
            }
        }
        val ratio = above65 / under200
        assertTrue(ratio > 0.017f, "KICK's overtone energy above 65Hz should be a real share of its low-band total: $ratio")
    }

    @Test
    fun `kick TONE brightens`() {
        val dark = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("TONE" to 0f)))
        val bright = FeatureExtractor.extract(Skin.render(SkinVoice.KICK, mapOf("TONE" to 1f)))
        assertTrue(bright.centroidHz > dark.centroidHz, "TONE up should raise the centroid: ${dark.centroidHz} -> ${bright.centroidHz}")
    }

    @Test
    fun `snare SNAP shifts tone toward noise`() {
        val tone = FeatureExtractor.extract(Skin.render(SkinVoice.SNARE, mapOf("SNAP" to 0.05f)))
        val noise = FeatureExtractor.extract(Skin.render(SkinVoice.SNARE, mapOf("SNAP" to 1f)))
        assertTrue(noise.flatness > tone.flatness, "SNAP up should be noisier: ${tone.flatness} -> ${noise.flatness}")
    }

    @Test
    fun `hat TONE brightens`() {
        val dull = FeatureExtractor.extract(Skin.render(SkinVoice.HAT_CLOSED, mapOf("TONE" to 0f)))
        val bright = FeatureExtractor.extract(Skin.render(SkinVoice.HAT_CLOSED, mapOf("TONE" to 1f)))
        assertTrue(bright.centroidHz > dull.centroidHz, "TONE up should raise the centroid: ${dull.centroidHz} -> ${bright.centroidHz}")
    }

    @Test
    fun `open hat rings longer than closed at defaults`() {
        val closed = FeatureExtractor.extract(Skin.render(SkinVoice.HAT_CLOSED))
        val open = FeatureExtractor.extract(Skin.render(SkinVoice.HAT_OPEN))
        assertTrue(open.decayMs > closed.decayMs * 2)
    }

    @Test
    fun `tom TUNE moves the pitch`() {
        val low = FeatureExtractor.extract(Skin.render(SkinVoice.TOM, mapOf("TUNE" to 0f)))
        val high = FeatureExtractor.extract(Skin.render(SkinVoice.TOM, mapOf("TUNE" to 1f)))
        assertTrue(high.centroidHz > low.centroidHz, "TUNE up should raise the centroid: ${low.centroidHz} -> ${high.centroidHz}")
    }

    @Test
    fun `tom DECAY moves the decay`() {
        val short = FeatureExtractor.extract(Skin.render(SkinVoice.TOM, mapOf("DECAY" to 0f)))
        val long = FeatureExtractor.extract(Skin.render(SkinVoice.TOM, mapOf("DECAY" to 1f)))
        assertTrue(long.decayMs > short.decayMs * 2, "DECAY should stretch: ${short.decayMs} -> ${long.decayMs}")
    }

    @Test
    fun `tom's overtone reaches into the mid band, not just the low band`() {
        // Regression for the TOM partial-level boost (1.5x/0.1-0.5 to
        // 1.63x/0.25-0.8): unlike KICK's overtones, TOM's sit above 200Hz
        // at defaults, so the shift shows up directly in FeatureExtractor's
        // own midRatio - measured 0.089 with the old formula, 0.163 with
        // this one (confirmed the same way as KICK's own regression test,
        // by temporarily reverting tom() and rerunning this exact
        // assertion; 0.12 sits with real margin on both sides of that gap).
        val f = FeatureExtractor.extract(Skin.render(SkinVoice.TOM))
        assertTrue(f.midRatio > 0.12f, "TOM's overtone should read as real mid-band energy: midRatio=${f.midRatio}")
    }

    @Test
    fun `tom is higher-pitched than kick at defaults`() {
        // The whole reason TOM and KICK are separate voices: proves the two
        // frequency ranges don't overlap into "the same shell renamed".
        val kick = FeatureExtractor.extract(Skin.render(SkinVoice.KICK))
        val tom = FeatureExtractor.extract(Skin.render(SkinVoice.TOM))
        assertTrue(tom.centroidHz > kick.centroidHz, "TOM should sit above KICK: kick ${kick.centroidHz}Hz, tom ${tom.centroidHz}Hz")
    }

    @Test
    fun `ride DECAY moves the decay`() {
        val short = FeatureExtractor.extract(Skin.render(SkinVoice.RIDE, mapOf("DECAY" to 0f)))
        val long = FeatureExtractor.extract(Skin.render(SkinVoice.RIDE, mapOf("DECAY" to 1f)))
        assertTrue(long.decayMs > short.decayMs * 1.5f, "DECAY should stretch: ${short.decayMs} -> ${long.decayMs}")
    }

    @Test
    fun `ride TONE brightens`() {
        val dull = FeatureExtractor.extract(Skin.render(SkinVoice.RIDE, mapOf("TONE" to 0f)))
        val bright = FeatureExtractor.extract(Skin.render(SkinVoice.RIDE, mapOf("TONE" to 1f)))
        assertTrue(bright.centroidHz > dull.centroidHz, "TONE up should raise the centroid: ${dull.centroidHz} -> ${bright.centroidHz}")
    }

    @Test
    fun `ride rings longer than either hat at defaults`() {
        val open = FeatureExtractor.extract(Skin.render(SkinVoice.HAT_OPEN))
        val ride = FeatureExtractor.extract(Skin.render(SkinVoice.RIDE))
        assertTrue(ride.decayMs > open.decayMs, "RIDE should outlast HAT_OPEN: hat ${open.decayMs}ms, ride ${ride.decayMs}ms")
    }

    @Test
    fun `shaker TONE brightens`() {
        val dull = FeatureExtractor.extract(Skin.render(SkinVoice.SHAKER, mapOf("TONE" to 0f)))
        val bright = FeatureExtractor.extract(Skin.render(SkinVoice.SHAKER, mapOf("TONE" to 1f)))
        assertTrue(bright.centroidHz > dull.centroidHz, "TONE up should raise the centroid: ${dull.centroidHz} -> ${bright.centroidHz}")
    }

    @Test
    fun `shaker DECAY moves the decay`() {
        val short = FeatureExtractor.extract(Skin.render(SkinVoice.SHAKER, mapOf("DECAY" to 0f)))
        val long = FeatureExtractor.extract(Skin.render(SkinVoice.SHAKER, mapOf("DECAY" to 1f)))
        assertTrue(long.decayMs > short.decayMs * 1.5f, "DECAY should stretch: ${short.decayMs} -> ${long.decayMs}")
    }

    @Test
    fun `shaker is noisier than a tonal voice`() {
        // Confirms "no tonal modes" by measurement, not just by design intent.
        val shaker = FeatureExtractor.extract(Skin.render(SkinVoice.SHAKER))
        val kick = FeatureExtractor.extract(Skin.render(SkinVoice.KICK))
        assertTrue(shaker.flatness > kick.flatness * 3, "SHAKER should measure far noisier than a modal voice: shaker ${shaker.flatness}, kick ${kick.flatness}")
    }

    @Test
    fun `stick TUNE moves the pitch`() {
        val low = FeatureExtractor.extract(Skin.render(SkinVoice.STICK, mapOf("TUNE" to 0f)))
        val high = FeatureExtractor.extract(Skin.render(SkinVoice.STICK, mapOf("TUNE" to 1f)))
        assertTrue(high.centroidHz > low.centroidHz, "TUNE up should raise the centroid: ${low.centroidHz} -> ${high.centroidHz}")
    }

    @Test
    fun `stick DECAY moves the decay`() {
        // The exact regression an impulse-excited Dsp.TptSvf mode would
        // fail silently: the filter's own natural ring at STICK's
        // frequency range finishes in a few ms regardless of what DECAY
        // asks for, so this is the test that actually catches it (Copilot's
        // review of this PR caught the bug itself; this closes the gap).
        val short = FeatureExtractor.extract(Skin.render(SkinVoice.STICK, mapOf("DECAY" to 0f)))
        val long = FeatureExtractor.extract(Skin.render(SkinVoice.STICK, mapOf("DECAY" to 1f)))
        assertTrue(long.decayMs > short.decayMs * 1.5f, "DECAY should stretch: ${short.decayMs} -> ${long.decayMs}")
    }

    @Test
    fun `stick is the shortest voice at defaults`() {
        val stick = FeatureExtractor.extract(Skin.render(SkinVoice.STICK))
        val snare = FeatureExtractor.extract(Skin.render(SkinVoice.SNARE))
        assertTrue(stick.decayMs < snare.decayMs, "STICK should be shorter than SNARE: stick ${stick.decayMs}ms, snare ${snare.decayMs}ms")
    }

    @Test
    fun `every corner of every macro space renders clean audio`() {
        for (voice in SkinVoice.entries) {
            for (value in floatArrayOf(0f, 1f)) {
                val macros = Skin.macrosFor(voice).associate { it.name to value }
                val snip = Skin.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice at $value rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() }, "$voice at $value produced non-finite samples")
                assertTrue(snip.samples.all { it in -1f..1f }, "$voice at $value clipped")
                assertTrue(snip.peak() > 0.5f, "$voice at $value is too quiet: ${snip.peak()}")
            }
        }
    }

    @Test
    fun `voices ramp in over the first millisecond instead of jumping to full level`() {
        // Regression for the 1ms Dsp.Env attack ramp every SKIN voice
        // carries (Skin.kt's own modalBody/hat doc comments): proves the
        // ramped voices actually start at zero and rise, not just that
        // they're clean — reverting to an instant onset would still pass
        // every other SkinTest in this file. Same shape as ThumpTest's own
        // regression for the identical bug. Every SKIN voice (STICK
        // included, unlike THUMP's CLAP/RIM) goes through modalBody or an
        // explicit Dsp.Env, so none needs excluding here.
        for (voice in SkinVoice.entries) {
            val snip = Skin.render(voice)
            // A small tolerance, not exact zero: U6's oversample/decimate
            // runs every render through a linear-phase resample filter,
            // which pre-rings a hair ahead of any sharp edge — the same
            // tolerance ThumpTest's own version of this test uses.
            assertEquals(0f, snip.samples[0], 0.02f, "$voice: first sample should start at zero, not jump to full level")
            val earlyPeak = snip.samples.take((Dsp.RATE * 0.02f).toInt()).maxOf { kotlin.math.abs(it) }
            assertTrue(earlyPeak > 0.1f, "$voice: should audibly ramp up within the first 20ms, peaked at $earlyPeak")
        }
    }

    @Test
    fun `no voice has a DC offset`() {
        for (voice in SkinVoice.entries) {
            val snip = Skin.render(voice)
            val dc = snip.samples.average().toFloat()
            assertTrue(kotlin.math.abs(dc) < 0.05f, "$voice has DC offset $dc")
        }
    }

    @Test
    fun `scramble is reproducible and always playable`() {
        for (voice in SkinVoice.entries) {
            val a = Skin.scramble(voice, Random(42))
            val b = Skin.scramble(voice, Random(42))
            assertEquals(a, b, "same seed must roll the same patch")

            repeat(10) { roll ->
                val macros = Skin.scramble(voice, Random(roll))
                assertTrue(macros.values.all { it in 0f..1f })
                val snip = Skin.render(voice, macros)
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $roll broke")
                assertTrue(snip.peak() > 0.5f, "$voice roll $roll too quiet")
            }
        }
    }

    @Test
    fun `scramble honors temperature`() {
        for (voice in SkinVoice.entries) {
            assertEquals(
                Skin.defaults(voice),
                Skin.scramble(voice, Random(1), temperature = 0f),
                "$voice: temperature 0 should return the default untouched",
            )
            val flat = Skin.scramble(voice, Random(1), temperature = 1f)
            assertTrue(flat.values.all { it in 0f..1f }, "$voice: temperature 1 left the 0..1 range")
            assertEquals(
                Dsp.scrambleNear(Skin.defaults(voice), 1f, Random(2)),
                Skin.scramble(voice, Random(2), temperature = 1f),
                "$voice: temperature 1 must match Dsp.scrambleNear's own flat-uniform contract",
            )
        }
    }

    @Test
    fun `unknown macros are ignored, known ones clamp`() {
        val snip = Skin.render(SkinVoice.KICK, mapOf("WOBBLE" to 0.5f, "TUNE" to 9f))
        assertTrue(snip.frameCount > 0)
    }

    @Test
    fun `renders are deterministic`() {
        val a = Skin.render(SkinVoice.SNARE)
        val b = Skin.render(SkinVoice.SNARE)
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `renders are mpc-native`() {
        val snip = Skin.render(SkinVoice.HAT_OPEN)
        assertEquals(44_100, snip.sampleRate)
        assertEquals(1, snip.channels)
    }

    // ---------- patches ----------

    @Test
    fun `patch round-trips through json`() {
        val patch = SkinPatch("Room Kick", SkinVoice.KICK, mapOf("TUNE" to 0.2f, "DECAY" to 0.8f))
        assertEquals(patch, SkinPatch.fromJsonText(patch.toJsonText()))
    }

    @Test
    fun `patch render equals direct render`() {
        val patch = SkinPatch("X", SkinVoice.SNARE, mapOf("SNAP" to 0.7f))
        assertTrue(patch.render().samples.contentEquals(Skin.render(SkinVoice.SNARE, mapOf("SNAP" to 0.7f)).samples))
    }

    @Test
    fun `patch validation refuses nonsense`() {
        assertFailsWith<IllegalArgumentException> { SkinPatch("", SkinVoice.KICK, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { SkinPatch("x", SkinVoice.KICK, mapOf("CUTOFF" to 0.5f)) }
        assertFailsWith<IllegalArgumentException> { SkinPatch("x", SkinVoice.KICK, mapOf("TUNE" to 2f)) }
        assertFailsWith<com.snipsnap.json.JsonException> { SkinPatch.fromJsonText("""{"engine":"VELVET"}""") }
    }

    @Test
    fun `PUNCH at its factory default roughly preserves loudness through the full render`() {
        // Same contract ThumpTest proves for THUMP: PunchTest verifies
        // Punch.apply's own loudness match in isolation, this proves the
        // ordering survives contact with render()'s own Dsp.normalize /
        // Dsp.limitPeak either side of it.
        for (voice in SkinVoice.entries) {
            val off = Loudness.of(Skin.render(voice, mapOf("PUNCH" to 0f)))
            val default = Loudness.of(Skin.render(voice))
            assertTrue(
                kotlin.math.abs(default - off) < off * 0.25f,
                "$voice: PUNCH off vs its factory default should stay close: $off -> $default",
            )
        }
    }
}
