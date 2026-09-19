package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VelvetTest {

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        // The corners are also the SVF stability test: CHIP's bright
        // defaults rendered NaN silence before the cutoff and damping
        // bounds were pulled inside the filter's stable region.
        for (voice in VelvetVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Velvet.macrosFor(voice).associate { it.name to 0f },
                Velvet.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Velvet.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 1.5f, "$voice must stay a one-shot")
            }
        }
    }

    @Test
    fun `render actually dispatches through the oversampled path, not directly at RATE`() {
        // U6 (docs/SYNTH_UPGRADE.md): render() computes at RATE *
        // Dsp.OVERSAMPLE via synthesize() and decimates, rather than
        // calling synthesize(voice, macros, RATE) directly. Reverting that
        // dispatch would leave every other VELVET test in this file green
        // (they only check generic playability/macro properties) - this
        // proves render()'s actual output is not the same as a naive
        // native-rate synthesize() call reaching the same normalize/
        // fadeTail finish. Not a spectral aliasing measurement (VELVET's
        // resonant, self-limiting SVF makes a clean band-energy comparison
        // ambiguous - measured and discarded, see this PR's own notes):
        // just a stable, direct proof that render() is actually taking the
        // oversample-then-decimate detour Dsp.decimate's own resampling
        // kernel leaves a real fingerprint on, not the "same as before"
        // path a regression would silently fall back to.
        for (voice in VelvetVoice.entries) {
            val actual = Velvet.render(voice)
            val direct = Velvet.synthesize(voice, emptyMap(), Dsp.RATE)
            Dsp.normalize(direct)
            Dsp.fadeTail(direct)
            var diff = 0.0
            val n = minOf(actual.samples.size, direct.size)
            for (i in 0 until n) diff += kotlin.math.abs((actual.samples[i] - direct[i]).toDouble())
            val avgDiff = diff / n
            assertTrue(
                avgDiff > 0.002,
                "$voice: Velvet.render should differ meaningfully from a direct native-rate " +
                    "synthesize() - got avgDiff=$avgDiff, which would happen if render() stopped " +
                    "dispatching through the oversampled path",
            )
        }
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        for (voice in VelvetVoice.entries) {
            assertEquals(Velvet.scramble(voice, Random(3)), Velvet.scramble(voice, Random(3)))
            repeat(8) { seed ->
                val snip = Velvet.render(voice, Velvet.scramble(voice, Random(seed)))
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $seed broke")
            }
        }
    }

    @Test
    fun `scrambled hits usually still read as playable percussion`() {
        // SCRAMBLE now rolls near a preset (docs/SYNTH_UPGRADE.md, U2), so a
        // roll can land close to a classifier boundary the same way a
        // preset itself can - BASS's darkest presets (low TUNE, low CUTOFF)
        // measured ~17% of rolls landing on KICK there. "Most of the time",
        // not "always", is the contract the doc itself sets for a roll.
        for (voice in VelvetVoice.entries) {
            var misses = 0
            val rolls = 30
            repeat(rolls) { seed ->
                val c = Classifier.classify(Velvet.render(voice, Velvet.scramble(voice, Random(seed))))
                if (c.drumClass == DrumClass.KICK || c.drumClass == DrumClass.LOOP || c.drumClass == DrumClass.UNKNOWN) misses++
            }
            assertTrue(misses <= rolls / 3, "$voice: $misses/$rolls scrambled rolls came back unplayable")
        }
    }

    @Test
    fun `scramble honors temperature and near`() {
        // The Dsp.scrambleNear boundary contract, proven end-to-end through
        // Velvet's own wiring: see DspTest for the central proof.
        for (voice in VelvetVoice.entries) {
            val preset = VelvetPresets.forVoice(voice).first()
            assertEquals(
                preset.macros,
                Velvet.scramble(voice, Random(1), temperature = 0f, near = preset),
                "$voice: temperature 0 should return the seed untouched",
            )
            val flat = Velvet.scramble(voice, Random(1), temperature = 1f, near = preset)
            assertTrue(flat.values.all { it in 0f..1f }, "$voice: temperature 1 left the 0..1 range")

            // Copilot's review of this PR: at temperature >= 1 with no
            // `near`, scramble must not spend a random draw picking a
            // preset first - see ThumpTest's own version of this test.
            assertEquals(
                Dsp.scrambleNear(Velvet.defaults(voice), 1f, Random(2)),
                Velvet.scramble(voice, Random(2), temperature = 1f),
                "$voice: temperature 1 with no near must not consume a preset-selection draw",
            )
        }
    }

    @Test
    fun `is deterministic`() {
        for (voice in VelvetVoice.entries) {
            assertTrue(
                Velvet.render(voice).samples.contentEquals(Velvet.render(voice).samples),
                "$voice not deterministic",
            )
        }
    }

    @Test
    fun `factory defaults all classify as percussion`() {
        // These read as tonal stabs to a human; PERC is the classifier's
        // honest shelf for a harmonic hit. The CLAP-vs-stab distinction is
        // covered by the flatness gate this engine forced into Classifier.
        for (voice in VelvetVoice.entries) {
            val c = Classifier.classify(Velvet.render(voice))
            assertEquals(DrumClass.PERC, c.drumClass, "$voice default read as ${c.drumClass}")
        }
    }

    @Test
    fun `a stab is harmonic, not noise - the thing that separates it from a clap`() {
        for (voice in VelvetVoice.entries) {
            val f = FeatureExtractor.extract(Velvet.render(voice))
            assertTrue(f.flatness < 0.2f, "$voice should measure harmonic, got flatness ${f.flatness}")
        }
    }

    @Test
    fun `CUTOFF opens`() {
        val dark = FeatureExtractor.extract(Velvet.render(VelvetVoice.BASS, mapOf("CUTOFF" to 0.05f)))
        val open = FeatureExtractor.extract(Velvet.render(VelvetVoice.BASS, mapOf("CUTOFF" to 0.95f)))
        assertTrue(
            open.centroidHz > dark.centroidHz * 1.5f,
            "CUTOFF up should brighten: ${dark.centroidHz} -> ${open.centroidHz}",
        )
    }

    @Test
    fun `SHAPE walks saw to pulse audibly`() {
        val sawSide = Velvet.render(VelvetVoice.BRASS, mapOf("SHAPE" to 0f))
        val pulseSide = Velvet.render(VelvetVoice.BRASS, mapOf("SHAPE" to 1f))
        assertEquals(sawSide.frameCount, pulseSide.frameCount)
        var diff = 0.0
        var level = 0.0
        for (i in sawSide.samples.indices) {
            diff += Math.abs((sawSide.samples[i] - pulseSide.samples[i]).toDouble())
            level += Math.abs(sawSide.samples[i].toDouble())
        }
        assertTrue(diff > level * 0.3, "SHAPE ends should sound different")
    }

    @Test
    fun `FAT thickens audibly`() {
        val thin = Velvet.render(VelvetVoice.BRASS, mapOf("FAT" to 0f))
        val fat = Velvet.render(VelvetVoice.BRASS, mapOf("FAT" to 1f))
        var diff = 0.0
        var level = 0.0
        for (i in thin.samples.indices) {
            diff += Math.abs((thin.samples[i] - fat.samples[i]).toDouble())
            level += Math.abs(thin.samples[i].toDouble())
        }
        assertTrue(diff > level * 0.3, "the unison spread should be audible")
    }

    @Test
    fun `minBeatDetune delivers the number of beat cycles it is asked for`() {
        // 82.4 Hz over a 0.47 s note: the old fixed 1.00395 gave a 3.07 s
        // beat period, under a sixth of a cycle in the note. That's the
        // primitive's general contract - ask it for cycles and it delivers
        // them. Velvet itself asks for far less (see the two tests below):
        // completing a FULL cycle in a short note takes more detune than
        // the FAT macro is allowed to grant, which is why this test passes
        // cycles explicitly instead of relying on Velvet's own default.
        val d = Dsp.minBeatDetune(baseHz = 82.4f, seconds = 0.47f, cycles = 1f)
        val beatHz = 82.4f * (d - 1f)
        // cycles=1f makes this an exact-boundary check (beatHz * seconds ==
        // cycles algebraically); Float rounding lands a hair under 1.0, so
        // the assertion allows that epsilon rather than the beat itself.
        assertTrue(beatHz * 0.47f >= 0.999f, "expected ~1 beat cycle in the note, got ${beatHz * 0.47f}")
    }

    @Test
    fun `a long note does not get forced wider than asked`() {
        val d = Dsp.minBeatDetune(baseHz = 82.4f, seconds = 8f)
        assertTrue(d < 1.005f, "a long note needs no detune floor, got $d")
    }

    @Test
    fun `the beat floor never swallows FAT - the macro still has its own range`() {
        // Round 1 shipped cycles=1.5f, which forced BASS's detune floor
        // above the FAT macro's own maximum ask (1.012) at every DECAY
        // setting - FAT stopped doing anything. Deleting `asked` from
        // Velvet.detuneFor's maxOf left every OTHER test in this file
        // green, which is exactly how that got past round 1: nothing
        // asserted the macro's own contribution survives the floor. This
        // does, against the real production function, not a
        // reimplementation of its formula.
        //
        // The 1.002f threshold guards the PROPERTY - the floor must never
        // swallow the macro - not the current cycles=0.25f value. BASS at
        // its factory DECAY is the tightest of the four voices (see
        // Velvet.detuneFor's KDoc: it's the canary, lowest voice, floor
        // binds hardest there); its floor only fully swallows FAT's range
        // once cycles >= ~0.4651. 1.002f leaves room to retune cycles up
        // to ~0.39 without breaking this test, while still failing hard at
        // 0.465 and beyond (and at the old 0.5, 1.0, 1.5).
        for (voice in listOf(VelvetVoice.BASS, VelvetVoice.BRASS)) {
            val defaults = Velvet.defaults(voice)
            val base = Velvet.frequencyFor(voice, defaults.getValue("TUNE"))
            val t60 = Dsp.expMap(defaults.getValue("DECAY"), 0.15f, 0.9f)
            val atZero = Velvet.detuneFor(base, fat = 0f, t60 = t60)
            val atOne = Velvet.detuneFor(base, fat = 1f, t60 = t60)
            assertTrue(
                atOne > atZero * 1.002f,
                "$voice: FAT=1 ($atOne) should meaningfully out-detune FAT=0 ($atZero) at its factory DECAY",
            )
        }
    }

    @Test
    fun `DECAY lengthens`() {
        val short = FeatureExtractor.extract(Velvet.render(VelvetVoice.SQUELCH, mapOf("DECAY" to 0.1f)))
        val long = FeatureExtractor.extract(Velvet.render(VelvetVoice.SQUELCH, mapOf("DECAY" to 0.9f)))
        assertTrue(
            long.decayMs > short.decayMs * 1.5f,
            "DECAY should stretch the stab: ${short.decayMs}ms -> ${long.decayMs}ms",
        )
    }

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Velvet.frequencyFor(VelvetVoice.BRASS, i / 100f))
        assertEquals(Velvet.TUNE_SEMITONES + 1, distinct.size)

        // The estimator locks onto the sub oscillator an octave under the
        // base — fine, it halves both ends, so the two-octave ratio holds.
        val lowNote = TestPitch.estimate(Velvet.render(VelvetVoice.BRASS, mapOf("TUNE" to 0f)), fromSec = 0.05f, windowSec = 0.2f)
        val highNote = TestPitch.estimate(Velvet.render(VelvetVoice.BRASS, mapOf("TUNE" to 1f)), fromSec = 0.05f, windowSec = 0.2f)
        assertTrue(
            highNote > lowNote * 3f && highNote < lowNote * 5f,
            "TUNE 0 -> 1 is two octaves: measured $lowNote Hz -> $highNote Hz",
        )
    }
}
