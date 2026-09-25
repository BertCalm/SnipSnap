package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluckTest {

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in PluckVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Pluck.macrosFor(voice).associate { it.name to 0f },
                Pluck.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Pluck.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(
                    snip.durationSeconds <= Pluck.RING_CEILING_SECONDS + 0.05f,
                    "$voice must stay inside the ring ceiling",
                )
            }
        }
    }

    @Test
    fun `render actually dispatches through the oversampled path, not directly at RATE`() {
        // U6 (docs/SYNTH_UPGRADE.md): render() computes at RATE *
        // Dsp.OVERSAMPLE via synthesize() and decimates, rather than
        // calling synthesize(voice, macros, RATE) directly. Same mean-abs-
        // diff proof as the other engines (see VelvetTest). Does not by
        // itself guard the delay line's n being sized off rate rather than
        // the native RATE - OnePole(rate) and Dsp.decimate alone would
        // still make this pass even if n regressed; the next test covers
        // that specifically.
        for (voice in PluckVoice.entries) {
            val actual = Pluck.render(voice)
            val direct = Pluck.synthesize(voice, emptyMap(), Dsp.RATE)
            Dsp.normalize(direct)
            Dsp.fadeTail(direct)
            var diff = 0.0
            val n = minOf(actual.samples.size, direct.size)
            for (i in 0 until n) diff += kotlin.math.abs((actual.samples[i] - direct[i]).toDouble())
            val avgDiff = diff / n
            assertTrue(
                avgDiff > 0.0005,
                "$voice: Pluck.render should differ meaningfully from a direct native-rate " +
                    "synthesize() - got avgDiff=$avgDiff, which would happen if render() stopped " +
                    "dispatching through the oversampled path",
            )
        }
    }

    @Test
    fun `the oversampled delay line still lands on pitch, not just on some different render`() {
        // The mean-abs-diff test above only proves render() differs from a
        // native-rate synthesize() call - it would still pass if ks()'s
        // delay line n were silently pinned back
        // to RATE (rather than the threaded rate param) while OnePole(rate)
        // and Dsp.decimate stayed correct, since those alone would still
        // make render() differ. n being wrong at the oversampled rate would
        // be dramatic and specific: at 4x rate with n computed from the
        // native RATE instead, the delay line would be 4x too short for
        // that rate, so the string would ring exactly two octaves sharp
        // once decimated back down. Pin TUNE's pitch directly against
        // frequencyFor to catch that regression.
        for (voice in PluckVoice.entries) {
            val expected = Pluck.frequencyFor(voice, 0.5f)
            val measured = TestPitch.estimate(Pluck.render(voice, mapOf("TUNE" to 0.5f, "DAMP" to 0.2f)))
            assertTrue(
                measured > expected * 0.9f && measured < expected * 1.1f,
                "$voice: expected ~${expected}Hz, measured ${measured}Hz - two octaves sharp (4x) " +
                    "would mean the delay line reverted to sizing off the native RATE",
            )
        }
    }

    @Test
    fun `the oversampled render's decay time matches a direct native-rate render`() {
        // The pitch test above proves n scales correctly, but not that fb
        // (applied once per generated sample) still produces the same
        // real-time decay at 4x rate as at native rate - a rate/feedback
        // change could preserve pitch while still shortening or lengthening
        // the tail, and DAMP damps (same render path both sides) wouldn't
        // catch that. It doesn't need correcting for rate: the delay line
        // closes its feedback path once per full n-sample traversal (one
        // period, at any rate), so decay per real second is fb^(periods
        // elapsed) - a function of freq and elapsed time only. Measured
        // ratios across the four voices: 0.67-1.22, nowhere near the ~0.25
        // (or ~4.0) a genuine 4x-per-second feedback error would produce -
        // wide tolerance here is deliberate headroom for that normal
        // rate-dependent variation (filter coefficient warping, the delay
        // line's integer rounding), not an admission of a real effect.
        for (voice in PluckVoice.entries) {
            val actual = Pluck.render(voice, mapOf("DAMP" to 0.3f))
            val direct = Pluck.synthesize(voice, mapOf("DAMP" to 0.3f), Dsp.RATE)
            Dsp.normalize(direct)
            Dsp.fadeTail(direct)
            val directSnip = Snip(direct, channels = 1, sampleRate = Dsp.RATE)
            val actualDecay = FeatureExtractor.extract(actual).decayMs
            val directDecay = FeatureExtractor.extract(directSnip).decayMs
            assertTrue(
                actualDecay > directDecay * 0.4f && actualDecay < directDecay * 2.5f,
                "$voice: oversampled decay ${actualDecay}ms vs native-rate decay ${directDecay}ms - " +
                    "a ~4x compression would mean fb needs rate-correcting after all",
            )
        }
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        for (voice in PluckVoice.entries) {
            assertEquals(Pluck.scramble(voice, Random(2)), Pluck.scramble(voice, Random(2)))
            repeat(8) { seed ->
                val snip = Pluck.render(voice, Pluck.scramble(voice, Random(seed)))
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $seed broke")
            }
        }
    }

    @Test
    fun `scrambled hits usually avoid the DC-thump fake-kick decay`() {
        // The DC-thump regression guard: a dark, damped pluck must usually
        // not decay into a fake kick. SCRAMBLE now rolls near a preset
        // (docs/SYNTH_UPGRADE.md, U2), so - like a preset itself can - a
        // roll can land close to that boundary; "most of the time", not
        // "always", is the doc's own contract for a scrambled roll. The
        // guard now counts only KICK: a long ring reading as LOOP is the
        // decay-following render working, not the DC-thump it guards
        // against.
        for (voice in PluckVoice.entries) {
            var misses = 0
            val rolls = 30
            repeat(rolls) { seed ->
                val c = Classifier.classify(Pluck.render(voice, Pluck.scramble(voice, Random(seed))))
                if (c.drumClass == DrumClass.KICK) misses++
            }
            assertTrue(misses <= rolls / 3, "$voice: $misses/$rolls scrambled rolls came back unplayable")
        }
    }

    @Test
    fun `scramble honors temperature and near`() {
        // The Dsp.scrambleNear boundary contract, proven end-to-end through
        // Pluck's own wiring: see DspTest for the central proof.
        for (voice in PluckVoice.entries) {
            val preset = PluckPresets.forVoice(voice).first()
            // Presets may omit macros added after they were authored (STRIKE
            // is the first): the seed is the defaults under the preset's own
            // macros, not the preset's macro map alone.
            assertEquals(
                Pluck.defaults(voice) + preset.macros,
                Pluck.scramble(voice, Random(1), temperature = 0f, near = preset),
                "$voice: temperature 0 should return the seed untouched",
            )
            val flat = Pluck.scramble(voice, Random(1), temperature = 1f, near = preset)
            assertTrue(flat.values.all { it in 0f..1f }, "$voice: temperature 1 left the 0..1 range")

            // Copilot's review of this PR: at temperature >= 1 with no
            // `near`, scramble must not spend a random draw picking a
            // preset first - see ThumpTest's own version of this test.
            assertEquals(
                Dsp.scrambleNear(Pluck.defaults(voice), 1f, Random(2)),
                Pluck.scramble(voice, Random(2), temperature = 1f),
                "$voice: temperature 1 with no near must not consume a preset-selection draw",
            )
        }
    }

    @Test
    fun `is deterministic`() {
        for (voice in PluckVoice.entries) {
            assertTrue(
                Pluck.render(voice).samples.contentEquals(Pluck.render(voice).samples),
                "$voice not deterministic",
            )
        }
    }

    @Test
    fun `factory defaults all classify as percussion`() {
        for (voice in PluckVoice.entries) {
            val c = Classifier.classify(Pluck.render(voice))
            assertEquals(DrumClass.PERC, c.drumClass, "$voice default read as ${c.drumClass}")
        }
    }

    @Test
    fun `DAMP damps`() {
        val ringing = FeatureExtractor.extract(Pluck.render(PluckVoice.HARP, mapOf("DAMP" to 0.05f)))
        val muted = FeatureExtractor.extract(Pluck.render(PluckVoice.HARP, mapOf("DAMP" to 0.95f)))
        assertTrue(
            ringing.decayMs > muted.decayMs * 1.5f,
            "DAMP up should choke the ring: ${ringing.decayMs}ms -> ${muted.decayMs}ms",
        )
    }

    @Test
    fun `PICK brightens the attack`() {
        val soft = FeatureExtractor.extract(Pluck.render(PluckVoice.NYLON, mapOf("PICK" to 0.05f)))
        val hard = FeatureExtractor.extract(Pluck.render(PluckVoice.NYLON, mapOf("PICK" to 0.95f)))
        assertTrue(
            hard.centroidHz > soft.centroidHz * 1.2f,
            "PICK should brighten: ${soft.centroidHz} -> ${hard.centroidHz}",
        )
    }

    @Test
    fun `TUNE snaps to semitones`() {
        // 101 knob positions must land on exactly 25 notes (two octaves
        // inclusive) - pads get notes, not frequencies.
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Pluck.frequencyFor(PluckVoice.NYLON, i / 100f))
        assertEquals(Pluck.TUNE_SEMITONES + 1, distinct.size)
        assertEquals(110f, Pluck.frequencyFor(PluckVoice.NYLON, 0f))
        assertEquals(440f, Pluck.frequencyFor(PluckVoice.NYLON, 1f))
    }

    @Test
    fun `TUNE up raises the pitch of the render`() {
        // Pitch, not centroid: KS loses its highs faster at higher tunings,
        // so brightness actually *falls* two octaves up while the note
        // unmistakably rises. Autocorrelation reads the note.
        val lowNote = TestPitch.estimate(Pluck.render(PluckVoice.NYLON, mapOf("TUNE" to 0f)))
        val highNote = TestPitch.estimate(Pluck.render(PluckVoice.NYLON, mapOf("TUNE" to 1f)))
        assertTrue(
            highNote > lowNote * 3f && highNote < lowNote * 5f,
            "TUNE 0 -> 1 is two octaves: measured $lowNote Hz -> $highNote Hz",
        )
    }

    @Test
    fun `DOUBLE thickens audibly`() {
        val single = Pluck.render(PluckVoice.KOTO, mapOf("DOUBLE" to 0f))
        val doubled = Pluck.render(PluckVoice.KOTO, mapOf("DOUBLE" to 1f))
        var diff = 0.0
        var level = 0.0
        // The two renders end where their own strings do, so the comparison
        // runs over the frames both have.
        for (i in 0 until minOf(single.frameCount, doubled.frameCount)) {
            diff += Math.abs((single.samples[i] - doubled.samples[i]).toDouble())
            level += Math.abs(single.samples[i].toDouble())
        }
        // Relative to the note's own level - a pluck is mostly quiet tail,
        // so an absolute threshold would only ever measure the attack.
        assertTrue(diff > level * 0.3, "the second string should be audible: diff/level = ${diff / level}")
    }

    @Test
    fun `a loop length below the KS minimum fails loudly instead of going unstable`() {
        // The real voice table never gets close to this (measured minimum
        // `exact` across every voice x TUNE semitone x DAMP is 175.93
        // samples, at KALIMBA TUNE=1/DAMP=1), so this drives Pluck.ks
        // directly with a synthetic freq/rate pair
        // that pushes the loop length under 2 samples - the old
        // `.coerceAtLeast(2)` produced a negative `frac` here, and `a =
        // (1-frac)/(1+frac)` with frac=-0.7 comes out ~5.67, an
        // unconditionally unstable feedback allpass. It must now fail the
        // require instead of silently returning something that blows up.
        assertFailsWith<IllegalArgumentException> {
            Pluck.ks(freq = 70_000f, seconds = 0.2f, damp = 0f, bodyLoopHz = 2600f, pickHz = 3000f, seed = 1, rate = 176_400)
        }
    }

    @Test
    fun `a loop length safely above the KS minimum still renders`() {
        // The boundary itself: exact ~2.68 samples here (comfortably above
        // MIN_LOOP_SAMPLES) must NOT throw and must produce a finite,
        // in-range buffer - the require must not be so conservative it
        // rejects legitimate high notes.
        val out = Pluck.ks(freq = 50_000f, seconds = 0.05f, damp = 0f, bodyLoopHz = 2600f, pickHz = 3000f, seed = 1, rate = 176_400)
        assertTrue(out.isNotEmpty() && out.all { it.isFinite() }, "a valid near-boundary loop length should still render cleanly")
    }

    @Test
    fun `STRIKE is a macro on every voice`() {
        for (voice in PluckVoice.entries) {
            assertTrue(Pluck.macrosFor(voice).any { it.name == "STRIKE" }, "$voice has no STRIKE")
        }
    }

    @Test
    fun `STRIKE at the bridge thins the fundamental against the harmonics`() {
        // The comb's gain at harmonic k is 2*sin(pi*k*p): near the bridge (p
        // small) the fundamental is the most attenuated harmonic, at the
        // centre (p = 0.5) the least. The audition read both ends as CLOSER
        // to the instrument; this pins that they are ends.
        for (voice in PluckVoice.entries) {
            val f0 = Pluck.frequencyFor(voice, 0.5f)
            val bridge = Pluck.render(voice, mapOf("TUNE" to 0.5f, "STRIKE" to 0f, "DOUBLE" to 0f))
            val centre = Pluck.render(voice, mapOf("TUNE" to 0.5f, "STRIKE" to 1f, "DOUBLE" to 0f))
            val atBridge = PluckSpectra.fundamentalShare(bridge, f0)
            val atCentre = PluckSpectra.fundamentalShare(centre, f0)
            assertTrue(
                atBridge < atCentre,
                "$voice: fundamental share at the bridge ($atBridge) should sit below the centre ($atCentre)",
            )
        }
    }

    @Test
    fun `STRIKE at the centre removes the second harmonic`() {
        for (voice in PluckVoice.entries) {
            val f0 = Pluck.frequencyFor(voice, 0.5f)
            val bridge = Pluck.render(voice, mapOf("TUNE" to 0.5f, "STRIKE" to 0f, "DOUBLE" to 0f))
            val centre = Pluck.render(voice, mapOf("TUNE" to 0.5f, "STRIKE" to 1f, "DOUBLE" to 0f))
            val h2Bridge = PluckSpectra.toneEnergy(bridge, 2 * f0)
            val h2Centre = PluckSpectra.toneEnergy(centre, 2 * f0)
            assertTrue(
                h2Centre < h2Bridge * 0.1,
                "$voice: 2nd harmonic at the centre ($h2Centre) should be 20 dB under the bridge ($h2Bridge)",
            )
        }
    }

    @Test
    fun `the default STRIKE keeps the shipped balance`() {
        // The audition read the quarter position as SAME as the shipped
        // engine, so the default lands there: within a factor of two of the
        // comb-less exciter on the fundamental's share.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        fun share(position: Float): Double {
            val raw = Pluck.ks(
                freq = 220f, seconds = 0.6f, damp = 0.4f, bodyLoopHz = 3400f, pickHz = 2500f,
                seed = 11, rate = rate, position = position,
            )
            val snip = Snip(Dsp.decimate(raw, Dsp.RATE), channels = 1, sampleRate = Dsp.RATE)
            return PluckSpectra.fundamentalShare(snip, 220f)
        }
        val plain = share(0f)
        val quarter = share(Dsp.expMap(0.75f, Pluck.STRIKE_BRIDGE, Pluck.STRIKE_CENTRE))
        assertTrue(
            quarter > plain * 0.5 && quarter < plain * 2.0,
            "default STRIKE share $quarter should be within 2x of the comb-less $plain",
        )
    }

    @Test
    fun `DAMP at zero rings past three and a half seconds and fades out clean`() {
        // The three string voices at their default notes (196-350 Hz) lose
        // under 9 dB per second through the loop filter at DAMP 0 and reach
        // the ceiling. KALIMBA's default is 440 Hz, where the same filter
        // costs ~29 dB per second and the string is gone by ~2 s; it leaves
        // PLUCK in Phase 2 and is covered by the reach test below instead.
        for (voice in listOf(PluckVoice.NYLON, PluckVoice.KOTO, PluckVoice.HARP)) {
            val snip = Pluck.render(voice, mapOf("DAMP" to 0f))
            assertTrue(snip.durationSeconds >= 3.5f, "$voice: ${snip.durationSeconds}s is not a ring")
            assertTrue(snip.durationSeconds <= Pluck.RING_CEILING_SECONDS + 0.05f, "$voice: past the ceiling")
            assertTrue(tailDb(snip) < -55f, "$voice: last 10 ms at ${tailDb(snip)} dB should be inaudible")
        }
    }

    @Test
    fun `DAMP zero rings at least twice as long as DAMP half on every voice`() {
        for (voice in PluckVoice.entries) {
            val open = Pluck.render(voice, mapOf("DAMP" to 0f)).durationSeconds
            val half = Pluck.render(voice, mapOf("DAMP" to 0.5f)).durationSeconds
            assertTrue(open >= half * 2f, "$voice: DAMP 0 ${open}s vs DAMP 0.5 ${half}s is not enough reach")
            assertTrue(tailDb(Pluck.render(voice, mapOf("DAMP" to 0f))) < -55f, "$voice: DAMP 0 tail is audible")
        }
    }

    @Test
    fun `DAMP at one is a short thud`() {
        for (voice in PluckVoice.entries) {
            val snip = Pluck.render(voice, mapOf("DAMP" to 1f))
            assertTrue(snip.durationSeconds < 0.5f, "$voice: ${snip.durationSeconds}s is not a thud")
        }
    }

    @Test
    fun `the render ends where the string does, not at the budget`() {
        // HARP at DAMP 0.6 gets a budget near a second and stops ringing well
        // before it; the file must follow the string, and the cut must land
        // on inaudible signal.
        val snip = Pluck.render(PluckVoice.HARP, mapOf("DAMP" to 0.6f))
        assertTrue(snip.durationSeconds >= Pluck.RING_FLOOR_SECONDS, "under the floor: ${snip.durationSeconds}s")
        assertTrue(snip.durationSeconds < 0.9f, "padded to the budget: ${snip.durationSeconds}s")
        assertTrue(tailDb(snip) < -50f, "tail at ${tailDb(snip)} dB: the cut landed on audible signal")
    }

    /** Level of the last 10 ms against the render's peak, in dB. */
    private fun tailDb(snip: Snip): Float {
        val peak = snip.peak()
        val from = (snip.frameCount - (0.010f * snip.sampleRate).toInt()).coerceAtLeast(0)
        var tail = 0f
        for (i in from until snip.frameCount) tail = maxOf(tail, kotlin.math.abs(snip.samples[i]))
        return 20f * kotlin.math.log10(tail / peak + 1e-9f)
    }
}
