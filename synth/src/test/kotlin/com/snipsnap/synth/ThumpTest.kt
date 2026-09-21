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

    // ---------- trimSnareTail is channel-aware (Task 3a) ----------

    @Test
    fun `trimSnareTail never cuts mid-frame`() {
        // An odd-length truncation transposes L and R for the whole buffer -
        // a silent channel swap that nothing downstream would flag.
        val rate = Dsp.RATE
        val frames = rate / 4
        val stereo = FloatArray(frames * 2)
        // Loud for the first tenth, silent after, so there is a real cut to make.
        for (f in 0 until frames / 10) { stereo[f * 2] = 0.8f; stereo[f * 2 + 1] = 0.2f }
        val out = Thump.trimSnareTailForTest(stereo, rate, channels = 2)
        assertEquals(0, out.size % 2, "cut at an odd sample index - L and R are now swapped")
        assertTrue(out.size < stereo.size, "nothing was trimmed, so the test proves nothing")
        // Orientation must survive the trim: left was the loud channel.
        var l = 0.0; var r = 0.0
        var f = 0
        while (f < out.size) { l += out[f] * out[f]; r += out[f + 1] * out[f + 1]; f += 2 }
        assertTrue(l > r * 2, "channels came back transposed: L=$l R=$r")
    }

    @Test
    fun `trimSnareTail gives a stereo buffer the same margin as a mono one`() {
        val rate = Dsp.RATE
        val frames = rate / 4
        val mono = FloatArray(frames)
        val stereo = FloatArray(frames * 2)
        for (f in 0 until frames / 10) { mono[f] = 0.8f; stereo[f * 2] = 0.8f; stereo[f * 2 + 1] = 0.8f }
        val mOut = Thump.trimSnareTailForTest(mono, rate, channels = 1)
        val sOut = Thump.trimSnareTailForTest(stereo, rate, channels = 2)
        assertEquals(
            mOut.size, sOut.size / 2,
            "stereo kept a different number of frames than mono - the margin is in frames",
        )
    }

    // ---------- SNARE WIDTH (Task 3b) ----------

    @Test
    fun `SNARE stays mono at WIDTH 0 and goes stereo above it`() {
        val mono = Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to 0f))
        assertEquals(1, mono.channels, "WIDTH 0 must stay mono - presets were auditioned there")
        val wide = Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to 0.8f))
        assertEquals(2, wide.channels, "WIDTH above zero should render stereo")
    }

    @Test
    fun `a wide SNARE folds down to the mono render, up to one gain`() {
        // Swept across SNAP x DECAY, 11 steps each (121 pairs) - the same
        // grid final-review.md measured. `abs(frameCount diff) <= 1` used
        // to be asserted at the DEFAULT macros only, and it passed there
        // only because trimSnareTail's lastFrame happens to coincide for
        // mono and wide at exactly that one point. trimSnareTail thresholds
        // off the per-sample peak (see its own KDoc): a stereo render's
        // per-sample peak is a different fraction of its own signal than
        // mono's, so the -60dB crossing moves by an amount that depends on
        // SNAP/DECAY, not just WIDTH. MEASURED (this exact sweep, gradle
        // exit 0): 42 of 121 pairs exceed a 1-frame difference; worst case
        // 599 frames (SNAP 0.1, DECAY 0.9). The behaviour itself is
        // inaudible - everything trimmed sits below -60dB and fadeTail runs
        // after (finding 4, final-review.md) - so this bounds the true
        // range instead of chasing trimSnareTail's own arithmetic. 600 is
        // 599's measured worst plus one frame of slack, not a separate
        // guess.
        for (si in 0..10) {
            val snap = si / 10f
            for (di in 0..10) {
                val decay = di / 10f
                val macros = mapOf("SNAP" to snap, "DECAY" to decay)
                val mono = Thump.render(ThumpVoice.SNARE, macros + ("WIDTH" to 0f))
                val wide = Thump.render(ThumpVoice.SNARE, macros + ("WIDTH" to 1f))
                val n = kotlin.math.min(mono.frameCount, wide.frameCount)
                assertTrue(n > 1000, "SNAP=$snap DECAY=$decay: not enough frames to judge: $n")
                assertTrue(
                    kotlin.math.abs(mono.frameCount - wide.frameCount) <= 600,
                    "SNAP=$snap DECAY=$decay: width changed the render length past the measured worst case: " +
                        "${mono.frameCount} vs ${wide.frameCount}",
                )
                // Fold-down residual over the COMMON prefix only - stays as
                // strong as it was, and is untouched by the length
                // difference bounded above since it never compares past `n`.
                val fold = FloatArray(n) { wide.samples[it * 2] + wide.samples[it * 2 + 1] }
                // Best-fit single scalar between fold-down and mono. Linear
                // panning means one gain should explain the whole
                // difference; comb notching would leave a frequency-
                // dependent residual that no gain can absorb.
                var num = 0.0
                var den = 0.0
                for (i in 0 until n) { num += fold[i].toDouble() * mono.samples[i]; den += mono.samples[i].toDouble() * mono.samples[i] }
                assertTrue(den > 1e-9, "SNAP=$snap DECAY=$decay: mono render was silent")
                val alpha = num / den
                var resid = 0.0
                var energy = 0.0
                for (i in 0 until n) {
                    val d = fold[i] - alpha * mono.samples[i]
                    resid += d * d
                    energy += fold[i].toDouble() * fold[i]
                }
                val rel = kotlin.math.sqrt(resid / (energy + 1e-12))
                assertTrue(
                    rel < 1e-3,
                    "SNAP=$snap DECAY=$decay: fold-down is not the mono signal scaled: relative residual $rel, alpha $alpha",
                )
            }
        }
    }

    @Test
    fun `a wide SNARE is actually wider than a narrow one`() {
        fun sideRatio(width: Float): Float {
            val s = Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to width, "SNAP" to 0.2f))
            if (s.channels != 2) return 0f
            var mid = 0.0
            var side = 0.0
            var f = 0
            while (f < s.samples.size) {
                val m = (s.samples[f] + s.samples[f + 1]) * 0.5
                val d = (s.samples[f] - s.samples[f + 1]) * 0.5
                mid += m * m; side += d * d; f += 2
            }
            return kotlin.math.sqrt(side / (mid + 1e-12)).toFloat()
        }
        val points = listOf(0.25f, 0.5f, 0.75f, 1f).map { sideRatio(it) }
        for (i in 0 until points.size - 1) {
            assertTrue(points[i + 1] > points[i] * 1.15f, "WIDTH did nothing from step $i to ${i + 1}: $points")
        }
    }

    @Test
    fun `left and right are not interchangeable`() {
        // Anchors the COMPOSED render path's channel ORIENTATION - a real
        // Thump.render(SNARE, WIDTH>0), not Modes.ringStereo in isolation
        // (ModesTest's `a hard-panned mode lands on the side it was panned
        // to` already pins ringStereo's own pan arithmetic, one layer
        // below). The old version of this test asserted both channels
        // non-empty plus render determinism - both survive an L/R swap
        // unchanged: final-review.md's Mutation A swapped Thump.snare's
        // rendered channels right after Modes.ringStereo and the ENTIRE
        // repo-wide suite stayed green. It was a determinism test wearing
        // an orientation test's name, on the exact hazard (an L/R
        // transposition) trimSnareTail actually shipped once.
        //
        // Modes.spread assigns pan deterministically by mode index - side
        // is -1 for even index, +1 for odd, and mode 0's reach is always 0
        // so it never leaves centre - so for the snare's real MEMBRANE bank
        // (5 modes) the odd-index modes (1, 3) land right and the even
        // ones past 0 (2, 4) land left. That is a small, real asymmetry
        // (mode 0's centred mass dominates), not a hard pan, so this
        // measures the composed render's total per-channel energy rather
        // than predicting an exact ratio from first principles.
        //
        // MEASURED (not invented), this exact render, this exact macro map,
        // gradle exit 0: L=135.14056288461143 R=146.97442039787816,
        // R/L=1.0875670284381675 - the right channel carries ~8.8% more
        // energy than the left. 1.03 sits well clear of both that measured
        // value and of 1.0 (a swapped render would measure R/L=1/1.0876
        // =0.919, comfortably on the other side of this threshold).
        val wide = Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to 1f, "SNAP" to 0.2f))
        assertEquals(2, wide.channels)
        var l = 0.0
        var r = 0.0
        var f = 0
        while (f < wide.samples.size) { l += wide.samples[f] * wide.samples[f]; r += wide.samples[f + 1] * wide.samples[f + 1]; f += 2 }
        assertTrue(l > 0.0 && r > 0.0, "a channel was empty: L=$l R=$r")
        val ratio = r / l
        assertTrue(
            ratio > 1.03,
            "expected the right channel to carry measurably more energy than the left (R/L=$ratio, measured baseline 1.0876) - orientation may have flipped",
        )
        val rendered = Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to 1f, "SNAP" to 0.2f))
        for (i in wide.samples.indices) {
            assertEquals(wide.samples[i], rendered.samples[i], 0f, "render is not deterministic at $i")
        }
    }

    @Test
    fun `WIDTH is inert at SNAP 1, because the body is gone there`() {
        // snareBodyGain(1f) == 0, so the output is entirely the mono wire
        // layer. This PINS a deliberate behaviour: SNAP=1 is the static
        // burst the user asked to keep reaching. If someone later widens the
        // wires, this test should be updated deliberately, not deleted.
        fun side(width: Float): Float {
            val s = Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to width, "SNAP" to 1f))
            // Fails loudly, not vacuously: returning 0f here for a render
            // that stopped being stereo would let `side(1f) < 1e-3f` pass
            // while claiming to have verified an inertness it never
            // actually measured.
            assertEquals(2, s.channels, "WIDTH $width at SNAP 1 should still render stereo")
            var mid = 0.0; var sd = 0.0
            var f = 0
            while (f < s.samples.size) {
                val m = (s.samples[f] + s.samples[f + 1]) * 0.5
                val d = (s.samples[f] - s.samples[f + 1]) * 0.5
                mid += m * m; sd += d * d; f += 2
            }
            return kotlin.math.sqrt(sd / (mid + 1e-12)).toFloat()
        }
        assertTrue(side(1f) < 1e-3f, "SNAP 1 produced width ${side(1f)}; the body gain is zero there, so this is unexpected")
    }

    @Test
    fun `every existing SNARE preset still renders mono and unchanged`() {
        for (p in ThumpPresets.forVoice(ThumpVoice.SNARE)) {
            assertEquals(1, p.render().channels, "${p.name} silently went stereo")
        }
    }

    @Test
    fun `PUNCH still renders clean, in-range audio when combined with WIDTH`() {
        // Punch.applyOversampled's saturate/boostEnvelope stay image-safe on
        // the stereo path (see their own KDoc: a per-frame linear gain for
        // boostEnvelope, fold-then-redistribute for saturate's
        // nonlinearity) rather than being skipped - this just proves the
        // combination renders without going out of range or losing a
        // channel, at PUNCH's full extent.
        val s = Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to 0.8f, "PUNCH" to 1f))
        assertEquals(2, s.channels)
        for (v in s.samples) {
            assertTrue(v.isFinite(), "PUNCH+WIDTH produced a non-finite sample")
            assertTrue(v in -1f..1f, "PUNCH+WIDTH produced an out-of-range sample: $v")
        }
    }
}
