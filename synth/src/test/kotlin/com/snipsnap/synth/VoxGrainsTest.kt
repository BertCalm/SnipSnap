package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoxGrainsTest {

    // ---------- VOX ----------

    @Test
    fun `every vox renders clean audio at defaults and corners`() {
        for (voice in VoxVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Vox.macrosFor(voice).associate { it.name to 0f },
                Vox.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Vox.render(voice, macros)
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke at $macros")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds <= Vox.MAX_SECONDS + 0.01f, "$voice must stay a one-shot: ${snip.durationSeconds} s")
                assertEquals(Vox.channelsFor(voice), snip.channels, "$voice channel count")
            }
        }
    }

    @Test
    fun `vox render actually dispatches through the oversampled path, not directly at RATE`() {
        // U6 (docs/SYNTH_UPGRADE.md): render() computes at RATE *
        // Dsp.OVERSAMPLE via synthesize() and decimates, rather than
        // calling synthesize(voice, macros, RATE) directly. Same mean-abs-
        // diff proof as VELVET/FATHOM/TONEWHEEL (see VelvetTest) - the
        // formant bandpasses ringing on a naive saw/square source make a
        // clean band-energy comparison as unreliable here as it was for
        // VELVET's resonant filter.
        //
        // `direct` has to finish through the same Dsp.levelTo render() now
        // does (Task 4), at the same target - voice offsets are all 0 today,
        // so Dsp.MELODIC_LOUDNESS_TARGET alone matches what render() uses.
        // Finishing `direct` with the old Dsp.normalize(0.95) instead left
        // this assertion passing even with render()'s own decimate step
        // deleted (checked directly) - the gain gap between a loudness
        // target and a peak target was enough to clear avgDiff on its own,
        // silently defeating the one thing this test is for.
        for (voice in VoxVoice.entries) {
            val actual = Vox.render(voice)
            val direct = Vox.synthesize(voice, emptyMap(), Dsp.RATE)
            val channels = Vox.channelsFor(voice)
            Dsp.levelTo(direct, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET, channels = channels)
            Dsp.fadeTail(direct, channels = channels)
            var diff = 0.0
            val n = minOf(actual.samples.size, direct.size)
            for (i in 0 until n) diff += kotlin.math.abs((actual.samples[i] - direct[i]).toDouble())
            val avgDiff = diff / n
            assertTrue(
                avgDiff > 0.0005,
                "$voice: Vox.render should differ meaningfully from a direct native-rate " +
                    "synthesize() - got avgDiff=$avgDiff, which would happen if render() stopped " +
                    "dispatching through the oversampled path",
            )
        }
    }

    @Test
    fun `vox scrambles are reproducible`() {
        for (voice in VoxVoice.entries) {
            assertEquals(Vox.scramble(voice, Random(5)), Vox.scramble(voice, Random(5)))
        }
    }

    @Test
    fun `vox scrambles never come back as mud`() {
        // SCRAMBLE rolls near a preset (docs/SYNTH_UPGRADE.md, U2). Since
        // round 1 a long DECAY holds, so a long roll reads LOOP: a held
        // note, which is VOX being a pad (the app files VOX as TONAL). What
        // a roll must never be is a kick-shaped thud or unclassifiable mud.
        // Measured at round 1: CHOIR 14 LOOP, 13 SNARE, 3 PERC of 30.
        for (voice in VoxVoice.entries) {
            repeat(30) { seed ->
                val c = Classifier.classify(Vox.render(voice, Vox.scramble(voice, Random(seed))))
                assertTrue(c.drumClass != DrumClass.KICK && c.drumClass != DrumClass.UNKNOWN, "$voice roll $seed read ${c.drumClass}")
            }
        }
    }

    @Test
    fun `vox scramble honors temperature and near`() {
        // The Dsp.scrambleNear boundary contract, proven end-to-end through
        // Vox's own wiring: see DspTest for the central proof.
        for (voice in VoxVoice.entries) {
            val preset = VoxPresets.forVoice(voice).first()
            assertEquals(
                Vox.defaults(voice) + preset.macros,
                Vox.scramble(voice, Random(1), temperature = 0f, near = preset),
                "$voice: temperature 0 should return the seed untouched",
            )
            val flat = Vox.scramble(voice, Random(1), temperature = 1f, near = preset)
            assertTrue(flat.values.all { it in 0f..1f }, "$voice: temperature 1 left the 0..1 range")

            // Copilot's review of this PR: at temperature >= 1 with no
            // `near`, scramble must not spend a random draw picking a
            // preset first - see ThumpTest's own version of this test.
            assertEquals(
                Dsp.scrambleNear(Vox.defaults(voice), 1f, Random(2)),
                Vox.scramble(voice, Random(2), temperature = 1f),
                "$voice: temperature 1 with no near must not consume a preset-selection draw",
            )
        }
    }

    @Test
    fun `a short vox is a hit, a long one is held`() {
        // PERC for the tonal throats; a breathy vocal honestly reads
        // snare-shaped to a drum classifier. Up to DECAY 0.6 (about 1.4 s)
        // every voice is a playable hit, so VOX works for drum hits and
        // chops; from 0.8 it holds and reads LOOP, a pad, which is what
        // DECAY's top half is for.
        for (voice in VoxVoice.entries) {
            for (decay in listOf(0f, 0.2f, 0.4f, 0.6f)) {
                val c = Classifier.classify(Vox.render(voice, mapOf("DECAY" to decay)))
                assertTrue(c.drumClass in setOf(DrumClass.PERC, DrumClass.SNARE), "$voice at DECAY $decay read as ${c.drumClass}")
            }
            assertEquals(DrumClass.LOOP, Classifier.classify(Vox.render(voice, mapOf("DECAY" to 1f))).drumClass, "$voice at DECAY 1 should hold")
        }
    }

    @Test
    fun `VOWEL moves the mouth`() {
        // A (formants high) vs U (formants low): the centroid must follow.
        val aah = FeatureExtractor.extract(Vox.render(VoxVoice.CHOIR, mapOf("VOWEL" to 0f, "BREATH" to 0f)))
        val ooh = FeatureExtractor.extract(Vox.render(VoxVoice.CHOIR, mapOf("VOWEL" to 1f, "BREATH" to 0f)))
        assertTrue(
            aah.centroidHz > ooh.centroidHz * 1.2f,
            "aah should be brighter than ooh: ${aah.centroidHz} vs ${ooh.centroidHz}",
        )
        // And the morph is continuous: the midpoint differs from both ends.
        val mid = Vox.formantsAt(0.5f)
        assertTrue(mid[0] != Vox.formantsAt(0f)[0] && mid[0] != Vox.formantsAt(1f)[0])
    }

    @Test
    fun `BREATH airs it out and TUNE tunes, snapped`() {
        // BREATH 1 is all breath: noisier, and no note left to find. Since
        // round 1 BREATH 0 carries the throat's own puffs of breath, so it
        // is not as clean as it was (flatness 0.22, was lower); measured,
        // BREATH 1 reads 0.30, and the pitch detector finds nothing there.
        val cleanSnip = Vox.render(VoxVoice.CHOIR, mapOf("BREATH" to 0f))
        val airySnip = Vox.render(VoxVoice.CHOIR, mapOf("BREATH" to 1f))
        val clean = FeatureExtractor.extract(cleanSnip)
        val airy = FeatureExtractor.extract(airySnip)
        assertTrue(airy.flatness > clean.flatness * 1.25f, "breath is noise: ${clean.flatness} -> ${airy.flatness}")
        assertNotNull(Pitch.detect(cleanSnip), "BREATH 0 is a note")
        assertEquals(null, Pitch.detect(airySnip), "BREATH 1 has no note left in it")

        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Vox.frequencyFor(VoxVoice.CHOIR, i / 100f))
        assertEquals(Vox.TUNE_SEMITONES + 1, distinct.size)

        val lowNote = Pitch.detect(Vox.render(VoxVoice.CHOIR, mapOf("TUNE" to 0f, "BREATH" to 0f)))
        val highNote = Pitch.detect(Vox.render(VoxVoice.CHOIR, mapOf("TUNE" to 1f, "BREATH" to 0f)))
        assertNotNull(lowNote); assertNotNull(highNote)
        assertTrue(
            highNote.hz > lowNote.hz * 3f && highNote.hz < lowNote.hz * 5f,
            "two octaves: ${lowNote.hz} -> ${highNote.hz}",
        )
    }

    @Test
    fun `vox patches dispatch like every other engine`() {
        val patch = VoxPatch("Mall Choir", VoxVoice.CHOIR, mapOf("VOWEL" to 0.3f))
        val back = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertTrue(back.render().samples.contentEquals(patch.render().samples))
    }

    // ---------- VOX round 1 ----------

    private fun window(s: Snip, from: Float, to: Float, channel: Int = 0): Snip {
        val a = (from * s.sampleRate).toInt().coerceIn(0, s.frameCount)
        val b = (to * s.sampleRate).toInt().coerceIn(a, s.frameCount)
        return Snip(FloatArray(b - a) { s.samples[(a + it) * s.channels + channel] }, 1, s.sampleRate)
    }

    private fun rms(s: Snip): Double = kotlin.math.sqrt(s.samples.sumOf { (it * it).toDouble() } / s.samples.size.coerceAtLeast(1))

    @Test
    fun `CHOIR sings in stereo across the field, the lone voices stay mono`() {
        val choir = Vox.render(VoxVoice.CHOIR, mapOf("DECAY" to 0.8f))
        assertEquals(2, choir.channels)
        val l = window(choir, 0.1f, 1.0f, 0)
        val r = window(choir, 0.1f, 1.0f, 1)
        var lr = 0.0; var ll = 0.0; var rr = 0.0
        for (i in l.samples.indices) { lr += l.samples[i] * r.samples[i]; ll += l.samples[i] * l.samples[i]; rr += r.samples[i] * r.samples[i] }
        val correlation = lr / kotlin.math.sqrt(ll * rr)
        assertTrue(correlation < 0.9, "the choir should be wide, L/R correlation $correlation")
        assertEquals(1, Vox.render(VoxVoice.ROBOT).channels)
        assertEquals(1, Vox.render(VoxVoice.GHOST).channels)
    }

    /** Pitch across a note in 60 ms steps, cents from its first reading. */
    private fun pitchTrack(s: Snip, from: Float, to: Float): List<Float> {
        val out = ArrayList<Float>()
        var t = from
        while (t + 0.06f <= to) {
            Pitch.detect(window(s, 0f, s.durationSeconds), t, 0.06f)?.let { out += it.hz }
            t += 0.06f
        }
        return out.map { 1200f * kotlin.math.ln(it / out.first()) / kotlin.math.ln(2f) }
    }

    @Test
    fun `GHOST and CHOIR sing with vibrato, ROBOT holds dead steady`() {
        val ghost = pitchTrack(Vox.render(VoxVoice.GHOST, mapOf("TUNE" to 0f, "BREATH" to 0f, "DECAY" to 1f)), 0.2f, 1.4f)
        val robot = pitchTrack(Vox.render(VoxVoice.ROBOT, mapOf("TUNE" to 0f, "BREATH" to 0f, "DECAY" to 1f)), 0.2f, 1.4f)
        val ghostSwing = ghost.max() - ghost.min()
        val robotSwing = robot.max() - robot.min()
        assertTrue(ghostSwing > 60f, "GHOST should swing with vibrato, $ghostSwing cents")
        assertTrue(robotSwing < 15f, "ROBOT should hold steady, $robotSwing cents")
    }

    @Test
    fun `a long DECAY holds the note, a short one falls`() {
        assertEquals(0f, Vox.holdFractionFor(0.5f))
        assertEquals(0.6f, Vox.holdFractionFor(1f), 1e-6f)
        for (voice in listOf(VoxVoice.ROBOT, VoxVoice.GHOST)) {
            val held = Vox.render(voice, mapOf("DECAY" to 1f))
            val early = rms(window(held, 0.05f, 0.25f))
            val middle = rms(window(held, held.durationSeconds * 0.4f, held.durationSeconds * 0.5f))
            val heldDb = 20 * log10(middle / early)
            val short = Vox.render(voice, mapOf("DECAY" to 0.3f))
            val shortDb = 20 * log10(rms(window(short, short.durationSeconds * 0.4f, short.durationSeconds * 0.5f)) / rms(window(short, 0.02f, 0.06f)))
            assertTrue(heldDb > -4.0, "$voice DECAY 1 should still be held at 40%: $heldDb dB")
            assertTrue(shortDb < -12.0, "$voice DECAY 0.3 should have fallen: $shortDb dB")
        }
    }

    @Test
    fun `SIZE scales the throat, chipmunk to giant`() {
        for (voice in listOf(VoxVoice.ROBOT, VoxVoice.GHOST)) {
            val small = FeatureExtractor.extract(Vox.render(voice, mapOf("SIZE" to 0f, "BREATH" to 0f))).centroidHz
            val large = FeatureExtractor.extract(Vox.render(voice, mapOf("SIZE" to 1f, "BREATH" to 0f))).centroidHz
            assertTrue(small > large * 1.5f, "$voice: a small throat should ring higher, $small Hz vs $large Hz")
        }
        assertEquals(1f, Vox.throatScale(0.5f), 1e-6f)
    }

    @Test
    fun `GLIDE moves the vowel during the note, and the middle stays put`() {
        fun lift(glide: Float): Float {
            val s = Vox.render(VoxVoice.ROBOT, mapOf("VOWEL" to 1f, "GLIDE" to glide, "BREATH" to 0f, "DECAY" to 1f))
            val early = FeatureExtractor.extract(window(s, 0.03f, 0.1f)).centroidHz
            val late = FeatureExtractor.extract(window(s, 0.6f, 0.8f)).centroidHz
            return late / early
        }
        val still = lift(0.5f)
        val wah = lift(0f)
        assertTrue(wah > 1.3f, "U gliding to A should open up, $wah")
        assertTrue(still in 0.8f..1.2f, "GLIDE 0.5 should hold the vowel, $still")
        assertEquals(0f, Vox.glideTarget(1f, 0f))
        assertEquals(0.3f, Vox.glideTarget(0.3f, 0.5f))
    }

    @Test
    fun `the vocal-cord pulse opens softly, snaps shut and carries no DC`() {
        val n = 10_000
        val cycle = FloatArray(n) { Vox.glottal(it.toDouble() / n) }
        assertEquals(0.0, cycle.average(), 1e-3, "no DC")
        assertEquals(-1f, cycle.min(), 1e-3f, "the closure is the peak")
        assertTrue(cycle.max() < 0.5f, "the opening is softer than the closure")
        assertTrue(cycle.drop((n * 0.6).toInt()).all { it == 0f }, "the folds rest closed")
    }

    // ---------- GRAINS ----------

    private val bell = Tines.render(TinesVoice.BELL, mapOf("DECAY" to 0.8f))

    @Test
    fun `a cloud is a loop, deterministic per seed, clean on any roll`() {
        val a = Grains.render(bell, seed = 4)
        val b = Grains.render(bell, seed = 4)
        assertTrue(a.samples.contentEquals(b.samples), "same seed, same cloud")
        assertTrue(!Grains.render(bell, seed = 5).samples.contentEquals(a.samples), "new seed, new cloud")
        assertEquals(DrumClass.LOOP, Classifier.classify(a).drumClass, "a texture is honestly a loop")

        repeat(6) { seed ->
            val out = Grains.render(bell, Grains.scramble(Random(seed)), seconds = 1.8f, seed = seed)
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "roll $seed broke")
            assertTrue(out.peak() > 0.5f, "roll $seed went quiet")
            assertEquals((1.8f * 44_100).toInt(), out.frameCount)
        }
    }

    @Test
    fun `PITCH re-pitches the cloud, snapped to semitones`() {
        val tone = Snip(
            FloatArray(44_100) { (0.6 * sin(2.0 * PI * 220.0 * it / 44_100)).toFloat() },
            1, 44_100,
        )
        // Big grains, no drift, no shine: the cloud is nearly the tone.
        val calm = mapOf("SIZE" to 1f, "SMEAR" to 1f, "DRIFT" to 0f, "SHINE" to 0f)
        val native = assertNotNull(Pitch.detect(Grains.render(tone, calm + mapOf("PITCH" to 0.5f), seconds = 1f)))
        val up = assertNotNull(Pitch.detect(Grains.render(tone, calm + mapOf("PITCH" to 1f), seconds = 1f)))
        assertTrue(abs(native.hz - 220f) < 8f, "native pitch should hold: ${native.hz}")
        assertTrue(abs(up.hz - 440f) < 16f, "+12 semis should double it: ${up.hz}")
        assertEquals(0, Grains.semitonesFor(0.5f))
        assertEquals(12, Grains.semitonesFor(1f))
        assertEquals(-12, Grains.semitonesFor(0f))
    }

    @Test
    fun `SIZE changes the texture and SHINE adds sparkle`() {
        val stutter = Grains.render(bell, mapOf("SIZE" to 0f), seconds = 1.5f)
        val wash = Grains.render(bell, mapOf("SIZE" to 1f), seconds = 1.5f)
        assertTrue(!stutter.samples.contentEquals(wash.samples))

        // Measure the octave layer where it must appear: granulate a 220 Hz
        // tone and look for 440 Hz energy directly (a decaying bell hides
        // the shimmer - its 2x-speed read lands on faded material).
        val tone = Snip(
            FloatArray(44_100) { (0.6 * sin(2.0 * PI * 220.0 * it / 44_100)).toFloat() },
            1, 44_100,
        )
        fun magAt(s: Snip, hz: Int): Double {
            var re = 0.0
            var im = 0.0
            for (i in 0 until s.frameCount) {
                val w = 2.0 * PI * hz * i / s.sampleRate
                re += s.samples[i] * kotlin.math.cos(w)
                im += s.samples[i] * kotlin.math.sin(w)
            }
            return Math.sqrt(re * re + im * im) / s.frameCount
        }
        val calm = mapOf("SIZE" to 1f, "SMEAR" to 0.5f, "DRIFT" to 0f, "PITCH" to 0.5f)
        val dullMag = magAt(Grains.render(tone, calm + mapOf("SHINE" to 0f), seconds = 1f), 440)
        val shinyMag = magAt(Grains.render(tone, calm + mapOf("SHINE" to 1f), seconds = 1f), 440)
        assertTrue(shinyMag > dullMag * 10, "the octave layer should appear at 440 Hz: $dullMag -> $shinyMag")
    }

    @Test
    fun `grains eat captures - the whole point`() {
        // A stand-in capture: noisy, messy, stereo. The cloud must still be
        // clean, and the granulated result flows through the kit pipeline
        // like any other sound.
        var s = 9
        val capture = Snip(
            FloatArray(88_200) { i ->
                s = (s * 1103515245 + 12345) and 0x7fffffff
                (0.4f * ((s.toFloat() / 0x3fffffff) - 1f) * sin(i / 300.0).toFloat())
            },
            2, 44_100,
        )
        val cloud = Grains.render(capture, mapOf("SIZE" to 0.7f, "DRIFT" to 0.6f))
        assertTrue(cloud.samples.all { it.isFinite() && it in -1f..1f })
        assertTrue(cloud.peak() > 0.5f)
    }
}
