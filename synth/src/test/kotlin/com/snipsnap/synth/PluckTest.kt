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
        // This test measures the delay line, not the body: BODY forced to 0
        // here, because a fixed body mode sitting on the second harmonic can
        // fool this unhinted detector into the octave above (BANJO measured
        // 787.5Hz against an expected ~392.0Hz once BODY's default rose).
        // The hinted sweeps in TuningAccuracyTest guard the note with the
        // body on, at the same TUNE/DAMP settings.
        for (voice in PluckVoice.entries) {
            val expected = Pluck.frequencyFor(voice, 0.5f)
            val measured = TestPitch.estimate(Pluck.render(voice, mapOf("TUNE" to 0.5f, "DAMP" to 0.2f, "BODY" to 0f)))
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
        // guard counts KICK and UNKNOWN: UNKNOWN is the classifier's own
        // silent/zero-peak class (Classifier.classify short-circuits to it
        // whenever peak or duration is <= 0), which is "unplayable" by this
        // test's own words just as much as a fake kick is. Only LOOP stays
        // excluded: a long ring reading as LOOP is the decay-following
        // render working, not the DC-thump it guards against.
        for (voice in PluckVoice.entries) {
            var misses = 0
            val rolls = 30
            repeat(rolls) { seed ->
                val c = Classifier.classify(Pluck.render(voice, Pluck.scramble(voice, Random(seed))))
                if (c.drumClass == DrumClass.KICK || c.drumClass == DrumClass.UNKNOWN) misses++
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
    fun `factory defaults classify as percussion, and the banjo may read as a snare`() {
        // Classifier has no pitch feature: it reads the attack window's share
        // of energy above 2 kHz and calls anything over half a snare. A banjo
        // picked near the bridge over a taut head puts two thirds of its
        // attack up there (measured 0.67 at the default; the string the gate
        // chose), so the classifier's word for it is SNARE. Shuffle has no
        // PLUCK slot, so nothing in the app acts on that reading today; a
        // harmonicity feature is the Phase 3 item that would let the
        // classifier tell a bright pluck from a drum. Every other voice must
        // still read PERC. SITAR's default reads PERC (measured highRatio 0.41
        // over a 1.30 s render) since the DAMP default moved to 0.5 and the
        // sympathetic strings landed; the margin under the 0.5 snare line is
        // 0.09, and a regression over it should fail here.
        for (voice in PluckVoice.entries) {
            val c = Classifier.classify(Pluck.render(voice))
            val allowed = if (voice == PluckVoice.BANJO) setOf(DrumClass.PERC, DrumClass.SNARE) else setOf(DrumClass.PERC)
            assertTrue(c.drumClass in allowed, "$voice default read as ${c.drumClass}")
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
        // BODY forced to 0: this measures the exciter, and at any nonzero
        // BODY the attack window (FeatureExtractor's centroid) also
        // contains the body's own free ring, which is not what PICK does.
        val soft = FeatureExtractor.extract(Pluck.render(PluckVoice.NYLON, mapOf("PICK" to 0.05f, "BODY" to 0f)))
        val hard = FeatureExtractor.extract(Pluck.render(PluckVoice.NYLON, mapOf("PICK" to 0.95f, "BODY" to 0f)))
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
    fun `DOUBLE on the sitar is the sympathetic strings, not the detune`() {
        val f0 = Pluck.frequencyFor(PluckVoice.SITAR, 12)
        val dry = Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 0f))
        val wet = Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 1f))
        // The drone loop rings at 0.5·f0 for the whole note - the octave
        // below, something the dry string cannot make at any time, no
        // matter when it's measured. So the window sits where both
        // renders exist: `late` skips the attack, and the window itself
        // is sized to whatever's actually left after it on the shorter of
        // the two renders (never more than 0.25 s) - a hardcoded 0.25 s
        // requirement broke here once already when the dry render's own
        // trimmed length moved (trimToDecay's -60dB-from-peak cut is
        // sensitive to the peak, not just the decay rate, so its exact
        // length isn't a stable thing to assume in seconds; the window
        // this test actually needs is relative to what came back, not a
        // fixed count).
        val late = 0.3f
        val windowSeconds = minOf(0.25f, dry.durationSeconds - late, wet.durationSeconds - late)
        require(windowSeconds > 0f) {
            "no post-$late-s window available: dry ${dry.durationSeconds} s, wet ${wet.durationSeconds} s"
        }
        fun energyAt(s: com.snipsnap.audio.Snip, hz: Float): Double {
            val from = (late * s.sampleRate).toInt()
            val n = (windowSeconds * s.sampleRate).toInt()
            require(s.frameCount - from >= n) { "render leaves ${(s.frameCount - from) / s.sampleRate.toFloat()} s after $late s, under the $windowSeconds s window" }
            val slice = com.snipsnap.audio.Snip(s.samples.copyOfRange(from, from + n), channels = 1, sampleRate = s.sampleRate)
            return PluckSpectra.toneEnergy(slice, hz, windowSeconds)
        }
        fun droneEnergy(s: com.snipsnap.audio.Snip): Double = energyAt(s, 0.5f * f0)
        val gain = 10.0 * kotlin.math.log10(droneEnergy(wet) / (droneEnergy(dry) + 1e-12))
        println("SITAR drone energy at half the note, in the $late-${late + windowSeconds} s window, DOUBLE 1 over DOUBLE 0: $gain dB")
        assertTrue(gain >= 6.0, "DOUBLE 1 should ring the drone at half the note by 6 dB in the $late-${late + windowSeconds} s window, got $gain dB")

        val fifthGain = 10.0 * kotlin.math.log10(energyAt(wet, 1.5f * f0) / (energyAt(dry, 1.5f * f0) + 1e-12))
        println("SITAR drone energy at the fifth, in the $late-${late + windowSeconds} s window, DOUBLE 1 over DOUBLE 0: $fifthGain dB")
        assertTrue(fifthGain >= 6.0, "DOUBLE 1 should ring the fifth by 6 dB too, the fifth, which no detuned second string can add either, got $fifthGain dB")

        assertTrue(!dry.samples.contentEquals(wet.samples), "DOUBLE 1 must change the render")
    }

    @Test
    fun `the scale tuning rings where the note has nothing`() {
        // The series tuning (the shipped default) coincides with the note's
        // own partials, so it never proves audibility where the string has
        // no energy. The scale tuning's ratios (9/8, 5/4, 4/3, 5/3) do not
        // sit on the note's series - the major third and the sixth below
        // are the clearest of those to measure, since neither is anywhere
        // near a harmonic of f0.
        val f0 = Pluck.frequencyFor(PluckVoice.SITAR, 12)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        fun renderAt(double: Float, sympathetic: Pluck.Sympathetic?): Snip {
            val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("DOUBLE" to double), rate, sympatheticOverride = sympathetic)
            return Snip(Dsp.decimate(raw, Dsp.RATE), channels = 1, sampleRate = Dsp.RATE)
        }
        val dry = renderAt(0f, Pluck.SYMPATHETIC_SCALE)
        val scale = renderAt(1f, Pluck.SYMPATHETIC_SCALE)
        val series = renderAt(1f, Pluck.SYMPATHETIC_SERIES)
        println(
            "SITAR scale-tuning renders: dry ${dry.durationSeconds} s, scale ${scale.durationSeconds} s, " +
                "series ${series.durationSeconds} s",
        )

        // `late` skips the attack; the window itself is sized to whatever
        // is actually left after it on the shortest of the three renders
        // (never more than 0.25 s), not a hardcoded 0.25 s requirement -
        // see `DOUBLE on the sitar is the sympathetic strings, not the
        // detune`'s identical comment for why a fixed second count broke
        // here once already (trimToDecay's cut moves with the peak, not
        // only with the decay rate, so its length in seconds isn't stable
        // to assume).
        val late = 0.3f
        val windowSeconds = minOf(0.25f, dry.durationSeconds - late, scale.durationSeconds - late, series.durationSeconds - late)
        require(windowSeconds > 0f) {
            "no post-$late-s window available: dry ${dry.durationSeconds} s, scale ${scale.durationSeconds} s, series ${series.durationSeconds} s"
        }
        fun energyAt(s: Snip, hz: Float): Double {
            val from = (late * s.sampleRate).toInt()
            val n = (windowSeconds * s.sampleRate).toInt()
            require(s.frameCount - from >= n) { "render leaves ${(s.frameCount - from) / s.sampleRate.toFloat()} s after $late s, under the $windowSeconds s window" }
            val slice = Snip(s.samples.copyOfRange(from, from + n), channels = 1, sampleRate = s.sampleRate)
            return PluckSpectra.toneEnergy(slice, hz, windowSeconds)
        }

        val thirdHz = 5f / 4f * f0
        val sixthHz = 5f / 3f * f0

        val thirdOverDry = 10.0 * kotlin.math.log10(energyAt(scale, thirdHz) / (energyAt(dry, thirdHz) + 1e-12))
        println("SITAR scale tuning energy at the major third, in the $late-${late + windowSeconds} s window, over the dry string: $thirdOverDry dB")
        assertTrue(thirdOverDry >= 10.0, "the scale tuning should ring the major third by 10 dB over the dry string, got $thirdOverDry dB")

        val sixthOverDry = 10.0 * kotlin.math.log10(energyAt(scale, sixthHz) / (energyAt(dry, sixthHz) + 1e-12))
        println("SITAR scale tuning energy at the sixth, in the $late-${late + windowSeconds} s window, over the dry string: $sixthOverDry dB")
        assertTrue(sixthOverDry >= 10.0, "the scale tuning should ring the sixth by 10 dB over the dry string, got $sixthOverDry dB")

        val thirdOverSeries = 10.0 * kotlin.math.log10(energyAt(scale, thirdHz) / (energyAt(series, thirdHz) + 1e-12))
        println("SITAR scale tuning energy at the major third, over the series tuning at DOUBLE 1: $thirdOverSeries dB")
        assertTrue(thirdOverSeries >= 6.0, "the scale tuning should ring the major third by 6 dB over the series tuning, where the series has nothing, got $thirdOverSeries dB")

        val sixthOverSeries = 10.0 * kotlin.math.log10(energyAt(scale, sixthHz) / (energyAt(series, sixthHz) + 1e-12))
        println("SITAR scale tuning energy at the sixth, over the series tuning at DOUBLE 1: $sixthOverSeries dB")
        assertTrue(sixthOverSeries >= 6.0, "the scale tuning should ring the sixth by 6 dB over the series tuning, where the series has nothing, got $sixthOverSeries dB")
    }

    @Test
    fun `DOUBLE below its threshold renders the sitar string alone`() {
        val a = Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 0f)).samples
        val b = Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 0.005f)).samples
        assertTrue(a.contentEquals(b), "DOUBLE under 0.01 should render nothing extra")
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
            // The jawari makes even harmonics on purpose (the buzz), so this
            // comb notch is proven on the sitar's string with the bridge
            // limiter off - left on, it would swamp the notch being measured.
            fun renderAt(strike: Float): Snip = if (voice == PluckVoice.SITAR) {
                val raw = Pluck.synthesize(voice, mapOf("TUNE" to 0.5f, "STRIKE" to strike, "DOUBLE" to 0f), Dsp.RATE * Dsp.OVERSAMPLE, jawariOverride = 0f)
                Snip(Dsp.decimate(raw, Dsp.RATE), channels = 1, sampleRate = Dsp.RATE)
            } else {
                Pluck.render(voice, mapOf("TUNE" to 0.5f, "STRIKE" to strike, "DOUBLE" to 0f))
            }
            val bridge = renderAt(0f)
            val centre = renderAt(1f)
            val h2Bridge = PluckSpectra.toneEnergy(bridge, 2 * f0)
            val h2Centre = PluckSpectra.toneEnergy(centre, 2 * f0)
            assertTrue(
                h2Centre < h2Bridge * 0.1,
                "$voice: 2nd harmonic at the centre ($h2Centre) should be 20 dB under the bridge ($h2Bridge)",
            )
        }
    }

    @Test
    fun `the default STRIKE keeps the comb-less balance`() {
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

    /** A 440 Hz tone decaying exponentially to -60 dB at [t60] seconds, [seconds] long, at [rate]. */
    private fun decayingTone(seconds: Float, t60: Float, rate: Int): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i ->
            val t = i.toFloat() / rate
            (Math.exp(-6.9078 * t / t60) * Math.sin(2.0 * Math.PI * 440.0 * t)).toFloat()
        }
    }

    @Test
    fun `trimToDecay leaves silence and sub-block buffers alone`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val silent = FloatArray(rate)
        assertTrue(Pluck.trimToDecay(silent, rate) === silent, "an all-silent buffer is returned as is")
        val tiny = FloatArray(100) { 0.5f }
        assertTrue(Pluck.trimToDecay(tiny, rate).size == 100, "a buffer shorter than one block keeps its length")
    }

    @Test
    fun `trimToDecay cuts where the string stopped, never under the floor`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val cut = Pluck.trimToDecay(decayingTone(seconds = 1.0f, t60 = 0.5f, rate = rate), rate)
        val cutSeconds = cut.size.toFloat() / rate
        assertTrue(cutSeconds > 0.45f && cutSeconds < 0.6f, "the cut should land near the -60 dB point at 0.5 s, got ${cutSeconds}s")
        val fast = Pluck.trimToDecay(decayingTone(seconds = 1.0f, t60 = 0.05f, rate = rate), rate)
        assertTrue(fast.size.toFloat() / rate >= Pluck.RING_FLOOR_SECONDS - 0.01f, "a fast decay is held at the floor, got ${fast.size.toFloat() / rate}s")
    }

    @Test
    fun `trimToDecay fades only the end of a budget cut and the last 400 ms at the ceiling`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        // A budget cut: still ringing at 0.35 s. The first 80% must be untouched.
        val budget = decayingTone(seconds = 0.35f, t60 = 2.0f, rate = rate)
        val reference = budget.copyOf()
        val trimmed = Pluck.trimToDecay(budget, rate)
        assertTrue(trimmed.size == reference.size, "a budget cut keeps its length")
        val untouched = (reference.size * 0.8f).toInt()
        for (i in 0 until untouched) assertTrue(trimmed[i] == reference[i], "sample $i was re-enveloped by the budget-cut fade")
        assertTrue(kotlin.math.abs(trimmed[trimmed.size - 1]) < 1e-4f, "the budget cut must end at silence")
        // The ceiling: still ringing at 4.0 s. Length kept, last 10 ms at least 55 dB down.
        val ceiling = decayingTone(seconds = Pluck.RING_CEILING_SECONDS, t60 = 20f, rate = rate)
        val faded = Pluck.trimToDecay(ceiling, rate)
        assertTrue(faded.size == ceiling.size, "the ceiling keeps its length")
        var tail = 0f
        for (i in faded.size - (0.010f * rate).toInt() until faded.size) tail = maxOf(tail, kotlin.math.abs(faded[i]))
        assertTrue(20f * kotlin.math.log10(tail + 1e-9f) < -55f, "the ceiling fade should leave the last 10 ms inaudible, got ${20f * kotlin.math.log10(tail + 1e-9f)} dB")
    }

    /** Level of the last 10 ms against the render's peak, in dB. */
    private fun tailDb(snip: Snip): Float {
        val peak = snip.peak()
        val from = (snip.frameCount - (0.010f * snip.sampleRate).toInt()).coerceAtLeast(0)
        var tail = 0f
        for (i in from until snip.frameCount) tail = maxOf(tail, kotlin.math.abs(snip.samples[i]))
        return 20f * kotlin.math.log10(tail / peak + 1e-9f)
    }

    @Test
    fun `PICK moves the centroid at every step of its travel`() {
        // The sweep is the precondition for routing velocity through PICK,
        // the same sweep the snare's SNAP had to pass.
        for (voice in PluckVoice.entries) {
            val points = (0..10).map { it / 10f }
            val measured = points.map { p ->
                FeatureExtractor.extract(Pluck.render(voice, mapOf("PICK" to p))).centroidHz
            }
            for (i in 0 until measured.size - 1) {
                assertTrue(
                    measured[i + 1] > measured[i] * 0.98f,
                    "$voice: PICK fell between ${points[i]} and ${points[i + 1]}: $measured",
                )
                assertTrue(
                    kotlin.math.abs(measured[i + 1] - measured[i]) > 1f,
                    "$voice: PICK is dead between ${points[i]} and ${points[i + 1]}: $measured",
                )
            }
        }
    }

    @Test
    fun `a soft PLUCK is re-synthesised through PICK, not low-passed`() {
        val patch = PluckPresets.forVoice(PluckVoice.NYLON).first()
        val soft = Velocity.atVelocity(patch, 0.2f)
        val hard = Velocity.atVelocity(patch, 1f)
        val softened = Velocity.soften(patch.render(), 0.8f)
        assertTrue(
            FeatureExtractor.extract(soft).centroidHz < FeatureExtractor.extract(hard).centroidHz,
            "a soft strike should be darker than a hard one",
        )
        assertTrue(
            !soft.samples.contentEquals(softened.samples),
            "soft velocity must be a re-render through PICK, not the soften() fallback",
        )
    }

    @Test
    fun `BANJO is a bright string with a short default ring`() {
        // The spec's BANJO row: root G3, brighter loop than HARP, picked
        // near the bridge, short notes. Pinned here as reach, not taste.
        assertEquals(196f, Pluck.frequencyFor(PluckVoice.BANJO, 0f))
        val banjo = FeatureExtractor.extract(Pluck.render(PluckVoice.BANJO))
        val nylon = FeatureExtractor.extract(Pluck.render(PluckVoice.NYLON))
        assertTrue(banjo.centroidHz > nylon.centroidHz, "a banjo should read brighter than a nylon string: ${banjo.centroidHz} vs ${nylon.centroidHz}")
        val strike = Pluck.defaults(PluckVoice.BANJO).getValue("STRIKE")
        assertTrue(strike < 0.5f, "the default pick sits near the bridge, got STRIKE $strike")
    }

    @Test
    fun `BODY is a macro on every voice with a body table`() {
        for (voice in PluckVoice.entries) {
            assertTrue(Pluck.macrosFor(voice).any { it.name == "BODY" }, "$voice has no BODY")
            val table = Pluck.bodyFor(voice)
            // SITAR has no body table yet (plan 2026-09-26-pluck-sitar.md): the test's own name scopes it to voices with a table.
            if (table.isEmpty()) continue
            // Two is KOTO's count: only its 85 Hz air mode and 100 Hz plate
            // mode are in a source the research note's verifier could open.
            assertTrue(table.size >= 2, "$voice: a body needs at least two sourced modes, got ${table.size}")
            var lastHz = 0f
            for (mode in table) {
                assertTrue(mode.ratio > lastHz, "$voice: body rows must ascend, ${mode.ratio} after $lastHz")
                assertTrue(mode.ratio < 20_000f, "$voice: ${mode.ratio} Hz is not a body mode")
                assertTrue(mode.gain > 0f && mode.t60 > 0f, "$voice: ${mode.ratio} Hz has a non-positive gain or decay")
                lastHz = mode.ratio
            }
        }
    }

    @Test
    fun `BODY zero is the string, byte for byte`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val string = decayingTone(seconds = 0.5f, t60 = 0.4f, rate = rate)
        assertTrue(Pluck.withBody(string, PluckVoice.NYLON, 0f, rate) === string, "amount 0 must skip the stage and return the same buffer")
        for (voice in PluckVoice.entries) {
            val a = Pluck.render(voice, mapOf("BODY" to 0f))
            val b = Pluck.render(voice, mapOf("BODY" to 0f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice: BODY 0 must be deterministic")
        }
    }

    @Test
    fun `BODY carries its share`() {
        // The body layer is RMS-matched to the string and scaled by the
        // amount, so (out - string) carries `amount` times the string's RMS.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val string = decayingTone(seconds = 0.5f, t60 = 0.4f, rate = rate)
        for (voice in PluckVoice.entries) {
            // SITAR has no body table yet (plan 2026-09-26-pluck-sitar.md): withBody is a no-op, so there is no share to measure.
            if (Pluck.bodyFor(voice).isEmpty()) continue
            val out = Pluck.withBody(string, voice, 1f, rate)
            var body = 0.0
            var dry = 0.0
            for (i in string.indices) {
                val d = (out[i] - string[i]).toDouble()
                body += d * d
                dry += string[i].toDouble() * string[i]
            }
            val share = kotlin.math.sqrt(body / dry)
            assertTrue(share > 0.8 && share < 1.2, "$voice: body share at amount 1 should be ~1, got $share")
        }
    }

    @Test
    fun `the velocity drive knocks no more than driving the body with the string itself`() {
        // The audition's thump came from the spike driving the body with the
        // string's displacement. The bridge force follows the string's
        // velocity, so the body is driven by the first difference; this pins
        // that the velocity drive leaves no more low-frequency swing at the
        // onset than the displacement drive, and prints both ratios so the
        // report can carry the measurement to the gate. Measured on the wet
        // layer alone (out - string), not the rendered note: the string
        // itself carries low-frequency energy of its own that would
        // otherwise swamp the difference the drive choice actually makes.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (voice in PluckVoice.entries) {
            val string = Pluck.synthesize(voice, mapOf("BODY" to 0f), rate)
            val velocityDriven = Pluck.withBody(string, voice, 1f, rate)
            val displacementDriven = Pluck.withBody(string, voice, 1f, rate, differentiate = false)
            val wetV = FloatArray(string.size) { velocityDriven[it] - string[it] }
            val wetD = FloatArray(string.size) { displacementDriven[it] - string[it] }
            val onsetV = PluckSpectra.lowPassPeak(wetV, rate, 200f, 0.03f) / PluckSpectra.peak(string)
            val onsetD = PluckSpectra.lowPassPeak(wetD, rate, 200f, 0.03f) / PluckSpectra.peak(string)
            println("$voice: onset low-band ratio velocity=$onsetV displacement=$onsetD")
            assertTrue(onsetV <= onsetD * 1.01f, "$voice: the velocity drive should not knock more than the displacement drive: $onsetV vs $onsetD")

            // Printed only, for the gate: the sub-200 Hz onset on real
            // renders, BODY 1 over BODY 0.
            val body1 = Pluck.render(voice, mapOf("BODY" to 1f))
            val body0 = Pluck.render(voice, mapOf("BODY" to 0f))
            val subRatio = PluckSpectra.lowPassPeak(body1.samples, Dsp.RATE, 200f, 0.03f) /
                PluckSpectra.lowPassPeak(body0.samples, Dsp.RATE, 200f, 0.03f)
            println("$voice: sub-200 Hz onset, BODY 1 over BODY 0 = $subRatio")
        }
    }

    @Test
    fun `BODY reaches the ugly end`() {
        // BODY 1 is three times the string's RMS - the spike's "dominant",
        // which read CLOSER on two voices and must stay reachable.
        for (voice in PluckVoice.entries) {
            // SITAR has no body table yet (plan 2026-09-26-pluck-sitar.md): BODY is inert, so the centroid cannot move.
            if (Pluck.bodyFor(voice).isEmpty()) continue
            val plain = FeatureExtractor.extract(Pluck.render(voice, mapOf("BODY" to 0f)))
            val full = FeatureExtractor.extract(Pluck.render(voice, mapOf("BODY" to 1f)))
            assertTrue(
                kotlin.math.abs(full.centroidHz - plain.centroidHz) > plain.centroidHz * 0.05f,
                "$voice: BODY 1 should move the centroid by more than 5%: ${plain.centroidHz} -> ${full.centroidHz}",
            )
        }
    }

    @Test
    fun `every voice renders deterministically at the oversampled rate`() {
        // The audition fingerprints (plan 2026-09-26-pluck-sitar.md, Task 2)
        // only mean something if two renders of the same voice agree.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (voice in PluckVoice.entries) {
            val a = Pluck.synthesize(voice, mapOf("DOUBLE" to 0f), rate)
            val b = Pluck.synthesize(voice, mapOf("DOUBLE" to 0f), rate)
            assertTrue(a.contentEquals(b), "$voice is not deterministic")
        }
    }

    @Test
    fun `the wrap adds harmonics over the fundamental at the shipped depth`() {
        // The buzz-content assertion the original brief deferred until a
        // depth was actually settled: `harmonicsOverFundamental` (2nd-8th
        // harmonic over the fundamental, past onset, the measure
        // PluckSpectra's own KDoc names as what a jawari should be judged
        // against, not a >2kHz share) is what the final A/B gate's pick
        // and the measured peak agreed on (spec, "The jawari") - 0.015
        // measured LOWER here than no wrap at all, which is why that depth
        // was never a genuine buzz claim; 0.010 is where this metric
        // actually peaks, and this is that claim, written for real instead
        // of deferred again.
        val f0 = Pluck.frequencyFor(PluckVoice.SITAR, 12)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        fun hofAt(jawari: Float): Double {
            val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("DOUBLE" to 0f), rate, velocity = 1f, jawariOverride = jawari)
            val snip = Snip(Dsp.decimate(raw, Dsp.RATE), channels = 1, sampleRate = Dsp.RATE)
            return PluckSpectra.harmonicsOverFundamental(snip, f0, 2, 8, 0.15f, 0.20f)
        }
        val dry = hofAt(0f)
        val shipped = hofAt(Pluck.SITAR_JAWARI)
        println("SITAR harmonicsOverFundamental: jawari 0 = $dry, jawari ${Pluck.SITAR_JAWARI} (shipped) = $shipped")
        assertTrue(
            shipped > dry,
            "the wrap should add harmonic content over the fundamental at the shipped depth: $dry (dry) vs $shipped (shipped)",
        )
    }

    @Test
    fun `the jawari buzz follows velocity`() {
        // Harder plucks wrap further on the bridge: the high band's share of
        // the first 200 ms must rise with velocity on SITAR.
        val shares = listOf(0.3f, 0.65f, 1.0f).map { v ->
            PluckSpectra.highShare(Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 0f), velocity = v).samples, Dsp.RATE, 2000f, 0.2f)
        }
        println("SITAR high-band share by velocity: $shares")
        assertTrue(shares[0] < shares[1] && shares[1] < shares[2], "buzz should rise with velocity: $shares")
    }

    @Test
    fun `the jawari leaves no offset`() {
        val out = Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 0f), velocity = 1f).samples
        var mean = 0.0
        for (v in out) mean += v
        mean /= out.size
        val peak = PluckSpectra.peak(out)
        assertTrue(kotlin.math.abs(mean) <= 1e-4 * peak, "DC after the jawari: mean $mean against peak $peak")

        val withTarab = Pluck.render(PluckVoice.SITAR, emptyMap(), velocity = 1f).samples
        var tarabMean = 0.0
        for (v in withTarab) tarabMean += v
        tarabMean /= withTarab.size
        val tarabPeak = PluckSpectra.peak(withTarab)
        assertTrue(kotlin.math.abs(tarabMean) <= 1e-4 * tarabPeak, "DC after the jawari with the tarab on (default DOUBLE 0.4): mean $tarabMean against peak $tarabPeak")
    }

    @Test
    fun `the jawari never raises the loop's gain`() {
        // |z| <= |y| by construction; this pins the sign so a later edit
        // cannot turn the limiter into a boost inside feedback.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (tenth in 0..10) {
            val damp = tenth / 10f
            val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("DAMP" to damp, "DOUBLE" to 0f), rate, velocity = 1f, jawariOverride = 0.6f)
            assertTrue(raw.all { it.isFinite() }, "non-finite sample at DAMP $damp")
            val onset = PluckSpectra.peak(raw.copyOfRange(0, minOf(raw.size, (0.01f * rate).toInt())))
            val whole = PluckSpectra.peak(raw)
            assertTrue(whole <= 2f * onset, "DAMP $damp: the note grew past twice its onset ($whole > 2 * $onset)")
            if (damp >= 0.3f) {
                // This render is at DOUBLE 0, so the string alone must decay
                // under its budget (the spec's stability clause).
                val budget = Dsp.expMap(1f - damp, 0.3f * 1.4f, Pluck.RING_CEILING_SECONDS).coerceIn(Pluck.RING_FLOOR_SECONDS, Pluck.RING_CEILING_SECONDS)
                assertTrue(raw.size < (budget * rate).toInt(), "DAMP $damp: the string alone should hit the -60 dB cut before its budget of $budget s, got ${raw.size / rate.toFloat()} s")
            }
        }
    }

    @Test
    fun `velocity reaches the sitar as a number through the velocity path`() {
        val patch = PluckPresets.forVoice(PluckVoice.SITAR).first()
        val soft = Velocity.atVelocity(patch, 0.3f).samples
        val hard = Velocity.atVelocity(patch, 1.0f).samples
        val softShare = PluckSpectra.highShare(soft, Dsp.RATE, 2000f, 0.2f)
        val hardShare = PluckSpectra.highShare(hard, Dsp.RATE, 2000f, 0.2f)
        // The velocity path should reach Pluck.render with the number (PICK moves too; the jawari's own effect is isolated by 'the jawari buzz follows velocity').
        assertTrue(softShare < hardShare, "the velocity path should reach Pluck.render with the number (PICK moves too; the jawari's own effect is isolated by 'the jawari buzz follows velocity'): $softShare vs $hardShare")
    }
}
