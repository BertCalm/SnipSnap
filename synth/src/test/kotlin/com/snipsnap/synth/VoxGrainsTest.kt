package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
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
                // Levelled by loudness, like TIDE: THROAT's whistle meets the target under a low peak (0.31-0.5
                // measured), a short hit or BEATBOX (peak-levelled to 0.95) meets the ceiling first.
                val loud = com.snipsnap.audio.Loudness.of(snip)
                assertTrue(loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() > 0.9f, "$voice too quiet at $macros: loudness $loud, peak ${snip.peak()}")
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
        // WRAITH is the exception, on purpose: pure tones have nothing to fold. Its own test below.
        for (voice in VoxVoice.entries - VoxVoice.WRAITH) {
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
        // Measured at round 1: CHOIR 14 LOOP, 13 SNARE, 3 PERC of 30; THROAT
        // at round 3, 18 LOOP, 11 PERC, 1 SNARE, its low growls never a kick;
        // WRAITH 14 LOOP, 9 PERC, 6 SNARE, 1 TONAL; SWARM 16 LOOP, 9 PERC, 5
        // SNARE, once its first mouth was kept on time (two came back UNKNOWN
        // before: two or three late talkers left the classifier's 93 ms silent);
        // SPEAK 19 PERC, 8 SNARE, 3 CLAP, every word a hit.
        // BEATBOX is a drum kit, where a kick is the point: its own test below.
        for (voice in listOf(VoxVoice.CHOIR, VoxVoice.ROBOT, VoxVoice.GHOST, VoxVoice.THROAT, VoxVoice.WRAITH, VoxVoice.SWARM, VoxVoice.SPEAK)) {
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
        // DECAY's top half is for. THROAT and WRAITH too (PERC to 0.6, LOOP from 0.8).
        for (voice in listOf(VoxVoice.CHOIR, VoxVoice.ROBOT, VoxVoice.GHOST, VoxVoice.THROAT, VoxVoice.WRAITH)) {
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

    // ---------- VOX round 2 ----------

    private val singers = listOf(VoxVoice.CHOIR, VoxVoice.ROBOT, VoxVoice.GHOST)

    private fun onset(c: Vox.Consonant) = c.ordinal / (Vox.Consonant.entries.size - 1f)

    /**
     * A click is a sudden jump between neighbouring samples. Low-passed at
     * ~1.5 kHz first (a cluck is a knock or a gap, which reaches down
     * there; the bright hiss of a breath does not), then the largest step
     * in the 25 ms around [release] over the RMS step of the vowel after
     * it. A plain vowel's own start scores 3.5-5.
     */
    private fun clickiness(s: Snip, release: Float): Float {
        val lp = FloatArray(s.frameCount)
        val k = 1f - kotlin.math.exp(-2f * PI.toFloat() * 1500f / s.sampleRate)
        var y1 = 0f
        var y2 = 0f
        for (i in 0 until s.frameCount) {
            y1 += k * (s.samples[i * s.channels] - y1)
            y2 += k * (y1 - y2)
            lp[i] = y2
        }
        fun step(i: Int) = abs(lp[i + 1] - lp[i])
        val r = (release * s.sampleRate).toInt()
        var peak = 0f
        for (i in maxOf(0, r - (0.01f * s.sampleRate).toInt()) until minOf(s.frameCount - 1, r + (0.015f * s.sampleRate).toInt())) peak = maxOf(peak, step(i))
        val a = r + (0.06f * s.sampleRate).toInt()
        val b = minOf(s.frameCount - 1, a + (0.05f * s.sampleRate).toInt())
        var e = 0.0
        for (i in a until b) e += step(i).toDouble().let { it * it }
        return peak / kotlin.math.sqrt(e / maxOf(1, b - a)).toFloat()
    }

    @Test
    fun `bah, dah, tah and mah open without a cluck`() {
        // The audition heard b, d and t "clucky". An envelope dropout at the
        // release, the hum's upper formants switching on in one sample and a
        // narrow "tok" of a burst were the causes; with the bursts gone and
        // the mouth opening over 90 ms these measured 2.2-5.5 (ROBOT's "ba"
        // was 28, CHOIR's 14). H and S are breath and hiss by nature: noise,
        // not a click, and not measured here.
        for (voice in singers) for (c in listOf(Vox.Consonant.M, Vox.Consonant.B, Vox.Consonant.D, Vox.Consonant.T)) {
            val spec = Vox.CONSONANTS.getValue(c)
            val s = Vox.render(voice, mapOf("VOWEL" to 0.05f, "DECAY" to 0.2f, "BREATH" to 0f, "ONSET" to onset(c)))
            val click = clickiness(s, spec.closure + spec.voiceDelay)
            assertTrue(click < 7f, "$voice $c clucks: $click")
        }
    }

    @Test
    fun `each consonant sounds like itself`() {
        for (voice in singers) {
            fun say(c: Vox.Consonant) = Vox.render(voice, mapOf("VOWEL" to 0.05f, "DECAY" to 0.5f, "BREATH" to 0f, "ONSET" to onset(c)))
            val plain = say(Vox.Consonant.NONE)
            // m: lips shut, a dark hum before the vowel.
            val m = say(Vox.Consonant.M)
            val hum = FeatureExtractor.extract(window(m, 0.01f, 0.08f)).centroidHz
            val vowel = FeatureExtractor.extract(window(plain, 0.03f, 0.1f)).centroidHz
            assertTrue(hum < vowel * 0.5f, "$voice: m should hum darker than the vowel, $hum Hz vs $vowel Hz")
            // s: a bright hiss with no note in it.
            val sHiss = say(Vox.Consonant.S)
            assertTrue(FeatureExtractor.extract(window(sHiss, 0.03f, 0.13f)).centroidHz > 5_000f, "$voice: s should hiss bright")
            assertEquals(null, Pitch.detect(window(sHiss, 0.02f, 0.15f), 0f, 0.1f), "$voice: there is no note in an s")
        }
        // b and d, with no pops, differ in the closure: d's tongue leaves the
        // front of the mouth bright. Measured: ROBOT 306 vs 420 Hz, GHOST 361
        // vs 507 (CHOIR's seven singers blur it, 457 vs 501).
        for (voice in listOf(VoxVoice.ROBOT, VoxVoice.GHOST)) {
            fun closure(c: Vox.Consonant): Float {
                val spec = Vox.CONSONANTS.getValue(c)
                val s = Vox.render(voice, mapOf("VOWEL" to 0.05f, "DECAY" to 0.5f, "BREATH" to 0f, "ONSET" to onset(c)))
                return FeatureExtractor.extract(window(s, 0.005f, spec.closure + 0.04f)).centroidHz
            }
            val b = closure(Vox.Consonant.B)
            val d = closure(Vox.Consonant.D)
            assertTrue(d > b * 1.2f, "$voice: d should open brighter than b, $b Hz vs $d Hz")
        }
    }

    @Test
    fun `a consonant never pulls the note out of tune`() {
        // ROBOT, the steady voice: every onset lands on the plain note's reading.
        val plain = Pitch.detect(Vox.render(VoxVoice.ROBOT, mapOf("VOWEL" to 0.05f, "DECAY" to 0.5f, "BREATH" to 0f)), 0.1f, 0.15f)!!.hz
        for (c in Vox.Consonant.entries) {
            val spec = Vox.CONSONANTS.getValue(c)
            val s = Vox.render(VoxVoice.ROBOT, mapOf("VOWEL" to 0.05f, "DECAY" to 0.5f, "BREATH" to 0f, "ONSET" to onset(c)))
            val got = Pitch.detect(s, spec.closure + spec.voiceDelay + 0.1f, 0.15f)
            assertNotNull(got, "$c: no note after the consonant")
            assertEquals(plain, got.hz, plain * 0.01f, "$c moved the note")
        }
    }

    @Test
    fun `BEATBOX is filed by its hit, and the singers stay notes`() {
        fun cls(hit: VoxBeatbox.Hit) = Vox.drumClassFor(VoxVoice.BEATBOX, mapOf("HIT" to hit.ordinal / (VoxBeatbox.Hit.entries.size - 1f)))
        assertEquals(DrumClass.KICK, cls(VoxBeatbox.Hit.KICK))
        for (h in listOf(VoxBeatbox.Hit.PF, VoxBeatbox.Hit.PSH, VoxBeatbox.Hit.K)) assertEquals(DrumClass.SNARE, cls(h), "$h")
        for (h in listOf(VoxBeatbox.Hit.TS, VoxBeatbox.Hit.T)) assertEquals(DrumClass.HAT_CLOSED, cls(h), "$h")
        assertEquals(DrumClass.HAT_OPEN, cls(VoxBeatbox.Hit.TSS))
        assertEquals(DrumClass.PERC, cls(VoxBeatbox.Hit.RIM))
        for (voice in singers + VoxVoice.THROAT + VoxVoice.WRAITH + VoxVoice.SWARM + VoxVoice.SPEAK) assertEquals(DrumClass.TONAL, Vox.drumClassFor(voice))
        // Every hit reachable, in order, from HIT's travel.
        assertEquals(VoxBeatbox.Hit.entries, (0..7).map { VoxBeatbox.hitFor(it / 7f) })
    }

    @Test
    fun `every BEATBOX hit is a clean mono drum at full level`() {
        for (hit in VoxBeatbox.Hit.entries) for (decay in listOf(0f, 0.5f, 1f)) {
            val s = Vox.render(VoxVoice.BEATBOX, mapOf("HIT" to hit.ordinal / 7f, "DECAY" to decay))
            assertEquals(1, s.channels)
            assertTrue(s.samples.all { it.isFinite() && it in -1f..1f }, "$hit broke range")
            assertEquals(0.95f, s.peak(), 0.01f, "$hit peak")
            assertTrue(s.durationSeconds < 1.5f, "$hit at DECAY $decay is a drum, not a pad: ${s.durationSeconds} s")
        }
    }

    /** Share of energy below 500 Hz, dB: the weight a hit carries. */
    private fun lowShareDb(s: Snip): Double {
        val n = 8192
        val re = FloatArray(n) { if (it < s.samples.size) s.samples[it] else 0f }
        val im = FloatArray(n)
        Fft.forward(re, im)
        var lo = 0.0
        var all = 1e-12
        for (b in 1 until n / 2) {
            val e = (re[b] * re[b] + im[b] * im[b]).toDouble()
            all += e
            if (b.toDouble() * s.sampleRate / n < 500) lo += e
        }
        return 10 * log10(lo / all + 1e-12)
    }

    /** How much the brightness moves during the hit: the spread of the centroid over 10 ms frames in its first 150 ms, as a fraction. */
    private fun movement(s: Snip): Double {
        val c = ArrayList<Double>()
        val n = (0.01f * s.sampleRate).toInt()
        var start = 0
        while (start + n <= minOf(s.frameCount, (0.15f * s.sampleRate).toInt())) {
            val w = window(s, start.toFloat() / s.sampleRate, (start + n).toFloat() / s.sampleRate)
            if (w.samples.sumOf { (it * it).toDouble() } > 1e-6) c += FeatureExtractor.extract(w).centroidHz.toDouble()
            start += n
        }
        val mean = c.average()
        return kotlin.math.sqrt(c.sumOf { (it - mean) * (it - mean) } / c.size) / mean
    }

    @Test
    fun `BEATBOX snares and hats are a mouth, with weight, not THUMP's noise`() {
        // The audition: the first snares and hats "sounded basically like the
        // hat and snare in THUMP", then "synthetic" and "thin". A mouth moves
        // while it sounds, and a close mic fattens it. Measured: the snares'
        // brightness moves 0.39-1.28 against THUMP's snare's 0.24, and carry
        // -0.9 to -2.6 dB of their energy under 500 Hz; the hats -14.5 to
        // -16.2 dB, where THUMP's hat carries -40.9.
        val thumpSnare = Thump.render(ThumpVoice.SNARE)
        val thumpSnareMono = if (thumpSnare.channels == 1) thumpSnare else Snip(FloatArray(thumpSnare.frameCount) { thumpSnare.samples[it * thumpSnare.channels] }, 1, thumpSnare.sampleRate)
        val thumpHat = Thump.render(ThumpVoice.HAT_CLOSED)
        val thumpHatMono = if (thumpHat.channels == 1) thumpHat else Snip(FloatArray(thumpHat.frameCount) { thumpHat.samples[it * thumpHat.channels] }, 1, thumpHat.sampleRate)
        fun hit(h: VoxBeatbox.Hit) = Vox.render(VoxVoice.BEATBOX, mapOf("HIT" to h.ordinal / 7f))
        val snareMove = movement(thumpSnareMono)
        for (h in listOf(VoxBeatbox.Hit.PF, VoxBeatbox.Hit.PSH, VoxBeatbox.Hit.K)) {
            val s = hit(h)
            assertTrue(movement(s) > snareMove * 1.4, "$h should move like a mouth: ${movement(s)} vs THUMP's $snareMove")
            assertTrue(lowShareDb(s) > -6.0, "$h is thin: ${lowShareDb(s)} dB under 500 Hz")
        }
        for (h in listOf(VoxBeatbox.Hit.TS, VoxBeatbox.Hit.T, VoxBeatbox.Hit.TSS)) {
            val s = hit(h)
            assertTrue(lowShareDb(s) > lowShareDb(thumpHatMono) + 15.0, "$h should carry a mouth's body, not a cymbal's: ${lowShareDb(s)} dB")
        }
    }

    // ---------- VOX round 3: THROAT ----------

    private val throatHz = Vox.frequencyFor(VoxVoice.THROAT, 0.5f)

    /** Each harmonic's level, from [from] s, 1st to 19th ([0] unused). */
    private fun harmonics(s: Snip, from: Float, size: Int = 8192): FloatArray {
        val a = (from * s.sampleRate).toInt()
        val mag = Fft.magnitudeSpectrum(s.samples.copyOfRange(a, a + size), size)
        return FloatArray(20) { h ->
            if (h == 0) 0f else Math.round(h * throatHz * size / s.sampleRate).let { b -> (b - 2..b + 2).maxOf { mag[it] } }
        }
    }

    /** The whistled harmonic: the loudest from the 3rd up. */
    private fun whistled(s: Snip, from: Float): Int = harmonics(s, from).let { l -> (3 until 20).maxBy { l[it] } }

    @Test
    fun `THROAT whistles the harmonic WHISTLE picks, far over its neighbours`() {
        // Measured: the 5th, 8th and 13th stand 29, 43 and 47 dB over theirs.
        for (w in listOf(0f, 0.4f, 1f)) {
            val s = Vox.render(VoxVoice.THROAT, mapOf("WHISTLE" to w, "MELODY" to 0f, "DECAY" to 0.9f))
            val n = VoxThroat.harmonicFor(w)
            assertEquals(n, whistled(s, 0.8f), "WHISTLE $w should whistle harmonic $n")
            val l = harmonics(s, 0.8f, 16384)
            val over = 20 * log10(l[n] / maxOf(l[n - 1], l[n + 1]))
            assertTrue(over > 20f, "harmonic $n stands only $over dB over its neighbours")
        }
        assertEquals(VoxThroat.LOWEST_WHISTLE, VoxThroat.harmonicFor(0f))
        assertEquals(VoxThroat.HIGHEST_WHISTLE, VoxThroat.harmonicFor(1f))
    }

    @Test
    fun `MELODY walks the whistle up the harmonics and home, and 0 holds it`() {
        fun walk(melody: Float): List<Int> {
            val s = Vox.render(VoxVoice.THROAT, mapOf("WHISTLE" to 0f, "MELODY" to melody, "DECAY" to 0.9f))
            return (0 until 20).map { whistled(s, 0.1f + it * 0.12f) }
        }
        val start = VoxThroat.LOWEST_WHISTLE
        assertEquals(List(20) { start }, walk(0f), "MELODY 0 holds the whistle")
        // Measured at 1: 6 8 8 10 11 11 10 9 9 8 7 7 8 8 7 6 5 5 5 5.
        val full = walk(1f)
        assertTrue(full.max() >= start + VoxThroat.MELODY_SPAN - 1, "MELODY 1 should climb about six harmonics: $full")
        assertTrue(full.toSet().size >= 5, "MELODY 1 should pass through the harmonics between: $full")
        assertEquals(start, full.last(), "and come home: $full")
    }

    @Test
    fun `DRONE brings up the body under the whistle`() {
        // The share of energy under 600 Hz, measured: -26, -20, -15, -11, -8 dB.
        val shares = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { d ->
            val s = Vox.render(VoxVoice.THROAT, mapOf("MELODY" to 0f, "DECAY" to 0.9f, "DRONE" to d))
            val size = 16384
            val a = (0.8f * s.sampleRate).toInt()
            val mag = Fft.magnitudeSpectrum(s.samples.copyOfRange(a, a + size), size)
            val below = (0 until Math.round(600f * size / s.sampleRate)).sumOf { (mag[it] * mag[it]).toDouble() }
            10 * log10(below / mag.sumOf { (it * it).toDouble() })
        }
        assertTrue(shares.zipWithNext().all { (a, b) -> b > a }, "the body should grow with DRONE: $shares")
        assertTrue(shares.last() - shares.first() > 12.0, "by a lot: $shares")
    }

    @Test
    fun `GROWL adds a rasp an octave under the note`() {
        // Every other pulse damped: the half harmonics (1.5x the note) rise
        // from nothing (-74 dB) to -12 at half and within 3 dB at full.
        fun halfOverNote(growl: Float): Float {
            val s = Vox.render(VoxVoice.THROAT, mapOf("GROWL" to growl, "MELODY" to 0f, "DRONE" to 1f, "DECAY" to 0.9f))
            val size = 32768
            val a = (0.4f * s.sampleRate).toInt()
            val mag = Fft.magnitudeSpectrum(s.samples.copyOfRange(a, a + size), size)
            fun at(hz: Float) = Math.round(hz * size / s.sampleRate).let { b -> (b - 3..b + 3).maxOf { mag[it] } }
            return 20 * log10(at(throatHz * 1.5f) / at(throatHz))
        }
        val none = halfOverNote(0f)
        val half = halfOverNote(0.5f)
        val full = halfOverNote(1f)
        assertTrue(none < -40f, "no growl, no half harmonics: $none dB")
        assertTrue(half > none + 20f && full > half, "GROWL should raise them: $none, $half, $full dB")
        assertTrue(full > -8f, "a full growl is nearly as loud as the note: $full dB")
    }

    @Test
    fun `YODEL flips the voice up a sixth and an octave, and back down`() {
        // In the chest the detector hears the whistle; while the voice is up
        // in its head the whistle steps aside and it hears the head notes,
        // measured at 185 and 221 Hz, a sixth and an octave over 110.
        fun heard(yodel: Float): List<Float> {
            val s = Vox.render(VoxVoice.THROAT, mapOf("YODEL" to yodel, "MELODY" to 0f, "DRONE" to 1f, "WHISTLE" to 0f, "DECAY" to 0.9f))
            return (0 until 26).mapNotNull { k ->
                val a = ((0.1f + k * 0.1f) * s.sampleRate).toInt()
                Pitch.detect(Snip(s.samples.copyOfRange(a, a + (0.08f * s.sampleRate).toInt()), 1, s.sampleRate))?.hz
            }
        }
        fun near(hz: Float, semis: Float) = abs(hz / (throatHz * Math.pow(2.0, semis / 12.0).toFloat()) - 1f) < 0.03f
        val still = heard(0f)
        assertTrue(still.none { hz -> VoxThroat.HEAD_SEMIS.any { near(hz, it) } }, "YODEL 0 never leaves the chest: $still")
        val yodel = heard(0.5f)
        for (semis in VoxThroat.HEAD_SEMIS) assertTrue(yodel.any { near(it, semis) }, "YODEL should reach $semis semitones up: $yodel")
        assertTrue(yodel.count { hz -> VoxThroat.HEAD_SEMIS.none { near(hz, it) } } > yodel.size / 2, "and spend most of the note back in the chest: $yodel")
    }

    // ---------- VOX round 3: WRAITH ----------

    private fun wraith(vararg macros: Pair<String, Float>) = Vox.render(VoxVoice.WRAITH, mapOf("BREATH" to 0f) + macros.toMap())

    /** The strongest frequency between [lo] and [hi] Hz, in a window from [from] s. */
    private fun strongest(s: Snip, from: Float, lo: Float, hi: Float, size: Int = 2048): Float {
        val a = (from * s.sampleRate).toInt()
        val mag = Fft.magnitudeSpectrum(s.samples.copyOfRange(a, a + size), size)
        val b = (Math.round(lo * size / s.sampleRate)..Math.round(hi * size / s.sampleRate)).maxBy { mag[it] }
        return b * s.sampleRate.toFloat() / size
    }

    @Test
    fun `WRAITH renders at RATE, pure tones having nothing to fold`() {
        val actual = Vox.render(VoxVoice.WRAITH)
        val direct = Vox.synthesize(VoxVoice.WRAITH, emptyMap(), Dsp.RATE)
        Dsp.levelTo(direct, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(direct)
        assertTrue(actual.samples.contentEquals(direct), "WRAITH should render straight at RATE")
    }

    @Test
    fun `WRAITH traces the word, why's second tone climbing from the w into the ee`() {
        // Measured, slowed (DECAY 0.9): 646 538 732 754 754 711 603 452, then 2196 2239.
        val why = wraith("WORD" to 0f, "DECAY" to 0.9f)
        val track = (0 until 12).map { strongest(why, 0.05f + it * 0.2f, 450f, 2800f) }
        assertTrue(track.take(6).all { it < 900f }, "the w and the ah sit low: $track")
        assertTrue(track.takeLast(3).all { it > 2000f }, "the ee lifts it: $track")
        assertEquals(VoxWraith.Word.entries, (0..5).map { VoxWraith.wordFor(it / 5f) })
        val renders = VoxWraith.Word.entries.map { wraith("WORD" to it.ordinal / 5f).samples }
        for (a in renders.indices) for (b in a + 1 until renders.size) assertTrue(!renders[a].contentEquals(renders[b]), "words $a and $b are the same")
    }

    @Test
    fun `TUNED steps every tone onto the key note's harmonics, and 0 glides free`() {
        // Measured at TUNED 1: every peak within 0.8% of a harmonic of 110.
        val note = Vox.frequencyFor(VoxVoice.WRAITH, 0.5f)
        fun offHarmonic(tuned: Float) = (0 until 10).map {
            val hz = strongest(wraith("WORD" to 0f, "DECAY" to 0.9f, "TUNED" to tuned), 0.05f + it * 0.2f, 450f, 2800f, 16384)
            abs(hz / note - Math.round(hz / note))
        }
        val tuned = offHarmonic(1f)
        assertTrue(tuned.all { it < 0.02f }, "TUNED 1 should sit on harmonics: $tuned")
        val free = offHarmonic(0f)
        assertTrue(free.any { it > 0.05f }, "TUNED 0 should glide between them: $free")
    }

    @Test
    fun `STUTTER grabs the opening up to four times before the word`() {
        fun bursts(s: Snip): Int {
            val hop = s.sampleRate / 200
            val env = (0 until s.samples.size / hop).map { k -> (k * hop until (k + 1) * hop).maxOf { abs(s.samples[it]) } }
            val top = env.max()
            var n = 0
            var on = false
            for (v in env) if (!on && v > 0.3f * top) { n++; on = true } else if (on && v < 0.05f * top) on = false
            return n
        }
        for (stutter in listOf(0f, 0.5f, 1f)) {
            val heard = bursts(wraith("WORD" to 0f, "STUTTER" to stutter, "DECAY" to 0.4f))
            assertEquals(VoxWraith.stuttersFor(stutter) + 1, heard, "STUTTER $stutter")
        }
        assertEquals(VoxWraith.MAX_STUTTERS, VoxWraith.stuttersFor(1f))
    }

    @Test
    fun `ALIEN moves the tones further than a mouth can`() {
        // The loudest tone's travel over why, measured: 452 Hz, then 2369 at half and 2412 at full.
        fun travel(alien: Float): Float {
            val s = wraith("WORD" to 0f, "DECAY" to 0.9f, "ALIEN" to alien)
            val track = (0 until 12).map { strongest(s, 0.05f + it * 0.2f, 100f, 6000f) }
            return track.max() - track.min()
        }
        val none = travel(0f)
        assertTrue(travel(1f) > none * 3f, "ALIEN should throw the tones about: $none -> ${travel(1f)}")
    }

    @Test
    fun `BREATH turns the tones to a whisper, and DECAY slows the word into a pad`() {
        // Flatness measured 0.0001, 0.09, 0.19.
        val flatness = listOf(0f, 0.5f, 1f).map { FeatureExtractor.extract(Vox.render(VoxVoice.WRAITH, mapOf("BREATH" to it, "DECAY" to 0.6f))).flatness }
        assertTrue(flatness[0] < 0.01f && flatness[1] > flatness[0] && flatness[2] > 0.1f, "breath is noise: $flatness")
        // Measured 0.30 s at DECAY 0, 3.05 at 1.
        val lengths = listOf(0f, 0.5f, 1f).map { wraith("DECAY" to it).durationSeconds }
        assertTrue(lengths.zipWithNext().all { (a, b) -> b > a } && lengths.first() < 0.5f && lengths.last() > 2.5f, "DECAY stretches the word: $lengths")
    }

    // ---------- VOX round 3: SWARM ----------

    private fun swarm(vararg macros: Pair<String, Float>) = Vox.render(VoxVoice.SWARM, macros.toMap())

    private fun monoOf(s: Snip) = FloatArray(s.frameCount) { (s.samples[2 * it] + s.samples[2 * it + 1]) / 2f }

    /** RMS of the mono mix in [ms]-long windows. */
    private fun rmsWindows(x: FloatArray, ms: Int): FloatArray {
        val w = Dsp.RATE * ms / 1000
        return FloatArray(x.size / w) { k ->
            var e = 0.0
            for (i in k * w until (k + 1) * w) e += x[i] * x[i]
            kotlin.math.sqrt(e / w).toFloat()
        }
    }

    @Test
    fun `SWARM is a crowd in stereo, and its first mouth is always on time`() {
        val crowd = swarm()
        assertEquals(2, crowd.channels)
        var lr = 0.0
        var ll = 0.0
        var rr = 0.0
        for (f in 0 until crowd.frameCount) {
            val l = crowd.samples[2 * f]
            val r = crowd.samples[2 * f + 1]
            lr += l * r; ll += l * l; rr += r * r
        }
        // Measured 0.69: the mouths are spread across the field.
        assertTrue(lr / kotlin.math.sqrt(ll * rr) < 0.9, "the crowd should be wide")
        // A pad that starts late sounds late: the smallest, loosest crowds still sound from the strike.
        for (tune in listOf(0.3f, 0.5f, 0.7f)) for (loose in listOf(0.5f, 1f)) {
            val head = monoOf(swarm("CROWD" to 0f, "LOOSE" to loose, "TUNE" to tune)).copyOfRange(0, (0.09f * Dsp.RATE).toInt())
            assertTrue(head.maxOf { abs(it) } > 0.01f, "two mouths at LOOSE $loose, TUNE $tune start silent")
        }
    }

    @Test
    fun `LOOSE smears the crowd's start, from one shout toward a murmuring room`() {
        // 10% to 90% of the opening's peak, measured 30, 95 and 250 ms.
        fun rise(loose: Float): Int {
            val e = rmsWindows(monoOf(swarm("LOOSE" to loose, "DECAY" to 0.6f)), 5).let { it.copyOfRange(0, minOf(it.size, 240)) }
            val peak = e.max()
            return (e.indexOfFirst { it > 0.9f * peak } - e.indexOfFirst { it > 0.1f * peak }) * 5
        }
        val tight = rise(0f)
        val ragged = rise(0.5f)
        assertTrue(tight < 60, "LOOSE 0 is one shout: $tight ms")
        assertTrue(ragged > 150, "LOOSE 0.5 is a ragged crowd: $ragged ms")
    }

    @Test
    fun `CROWD runs from a few people to a crowd`() {
        assertEquals(VoxSwarm.MIN_MOUTHS, VoxSwarm.mouthsFor(0f))
        assertEquals(VoxSwarm.MAX_MOUTHS, VoxSwarm.mouthsFor(1f))
        assertTrue((0..10).map { VoxSwarm.mouthsFor(it / 10f) }.zipWithNext().all { (a, b) -> b >= a })
        // A murmur's lumpiness over its held part, measured 0.38 for two mouths, 0.17 for sixteen:
        // you can pick out two people; past about ten it is a crowd.
        fun lumpiness(crowd: Float): Double {
            val e = rmsWindows(monoOf(swarm("CROWD" to crowd, "LOOSE" to 1f, "DECAY" to 1f)), 25).let { it.copyOfRange(16, 68) }
            val mean = e.average()
            return kotlin.math.sqrt(e.map { (it - mean) * (it - mean) }.average()) / mean
        }
        val few = lumpiness(0f)
        val many = lumpiness(1f)
        assertTrue(few > many * 1.6, "two mouths should be lumpier than sixteen: $few vs $many")
    }

    @Test
    fun `EFFORT goes hushed, talk, shout, and a hushed crowd is people, not ghosts`() {
        fun features(s: Snip) = FeatureExtractor.extract(Snip(monoOf(s), 1, Dsp.RATE))
        // The quiet end was a whisper, and a room of whispers sounded like a horror film ("still scary
        // sounding"): no voice at all. Hushed voices keep a soft one under the breath. Measured on a room:
        // a note found in 7 of 8 windows, flatness 0.36 against talk's 0.07.
        val hushed = swarm("EFFORT" to 0f, "DECAY" to 0.7f, "LOOSE" to 1f)
        val talk = swarm("EFFORT" to 0.5f, "DECAY" to 0.7f, "LOOSE" to 1f)
        val m = monoOf(hushed)
        val windows = (0 until 8).map { ((0.1f + it * 0.15f) * Dsp.RATE).toInt() }.filter { it + 4410 <= m.size }
        val voiced = windows.count { a -> Pitch.detect(Snip(m.copyOfRange(a, a + 4410), 1, Dsp.RATE)) != null }
        assertTrue(voiced >= windows.size * 5 / 8, "a hushed crowd still has voices in it: $voiced of ${windows.size}")
        assertTrue(features(hushed).flatness > features(talk).flatness * 2.5f, "hushed is breathier than talk")
        // A shout is brighter than talk: centres measured 1637 and 853 Hz on the chant.
        val shout = features(swarm("EFFORT" to 1f, "DECAY" to 0.7f)).centroidHz
        val said = features(swarm("EFFORT" to 0.5f, "DECAY" to 0.7f)).centroidHz
        assertTrue(shout > said * 1.5f, "a shout is brighter than talk: $shout vs $said")
    }

    @Test
    fun `STUTTER grabs the opening up to four times, silent between`() {
        // Counted as silent gaps (under 2% of the peak for 15 ms): the grabs themselves are the word's
        // quiet h, near a burst-counter's threshold. Exact across nine crowds when measured.
        fun gaps(s: Snip, within: Float): Int {
            val x = monoOf(s)
            val hop = Dsp.RATE / 200
            val env = (0 until minOf(x.size / hop, (within * 200).toInt())).map { k -> (k * hop until (k + 1) * hop).maxOf { abs(x[it]) } }
            val top = x.maxOf { abs(it) }
            var n = 0
            var run = 0
            var started = false
            for (v in env) {
                if (v > 0.1f * top) started = true
                if (started && v < 0.02f * top) run++ else { if (run >= 3) n++; run = 0 }
            }
            return n
        }
        for (crowd in listOf(0.2f, 1f)) for (stutter in listOf(0f, 0.5f, 1f)) {
            val n = VoxSwarm.stuttersFor(stutter)
            val s = swarm("STUTTER" to stutter, "LOOSE" to 0f, "DECAY" to 0.3f, "CROWD" to crowd)
            assertEquals(n, gaps(s, n * 0.125f + 0.03f), "STUTTER $stutter, CROWD $crowd")
        }
    }

    @Test
    fun `a short SWARM is a hit, a long one holds`() {
        // PERC or SNARE to DECAY 0.4, LOOP at 1. At 0.6 a crowd is 1.52 s, just past the classifier's
        // 1.5 s loop line (its mouths come in over a moment): filed TONAL all the same.
        for (decay in listOf(0f, 0.2f, 0.4f)) {
            val c = Classifier.classify(swarm("DECAY" to decay)).drumClass
            assertTrue(c in setOf(DrumClass.PERC, DrumClass.SNARE), "SWARM at DECAY $decay read as $c")
        }
        assertEquals(DrumClass.LOOP, Classifier.classify(swarm("DECAY" to 1f)).drumClass)
    }

    // ---------- VOX round 4: SPEAK ----------

    private fun speak(vararg macros: Pair<String, Float>) = Vox.render(VoxVoice.SPEAK, macros.toMap())

    private fun count(word: VoxSpeak.Word) = word.ordinal / (VoxSpeak.Word.entries.size - 1f)

    /** Pitch in cents from [note], every 30 ms where the detector is sure. */
    private fun centsAlong(s: Snip, note: Float, sure: Float = 0.8f): List<Float> {
        val out = mutableListOf<Float>()
        var t = 0f
        while (t + 0.06f < s.durationSeconds) {
            Pitch.detect(s, fromSec = t, windowSec = 0.06f)?.let { if (it.confidence > sure) out += 1200f * kotlin.math.log2(it.hz / note) }
            t += 0.03f
        }
        return out
    }

    /** Level over [hz] against the whole, dB, averaged over the render. */
    private fun overDb(s: Snip, hz: Float): Double {
        var total = 0.0
        var over = 0.0
        var i = 0
        while (i + 4096 <= s.samples.size) {
            val spec = Fft.magnitudeSpectrum(s.samples.copyOfRange(i, i + 4096), 4096)
            for ((k, m) in spec.withIndex()) {
                total += m * m
                if (Fft.binToHz(k, 4096, Dsp.RATE) > hz) over += m * m
            }
            i += 2048
        }
        return 10 * log10(over / total)
    }

    @Test
    fun `SPEAK counts one to eight, each word a hit at any length`() {
        assertEquals(VoxSpeak.Word.entries, (0..7).map { VoxSpeak.wordFor(it / 7f) })
        assertEquals(1, Vox.channelsFor(VoxVoice.SPEAK))
        val renders = VoxSpeak.Word.entries.map { speak("WORD" to count(it)) }
        assertEquals(8, renders.map { it.samples.toList() }.toSet().size, "eight different words")
        // Measured PERC for one, two and eight, SNARE for the hissing ones, at every DECAY: a spoken
        // word, even stretched to about a second, is a hit or a chop, never a pad or a kick.
        for (word in VoxSpeak.Word.entries) for (decay in listOf(0f, 0.5f, 1f)) {
            val c = Classifier.classify(speak("WORD" to count(word), "DECAY" to decay)).drumClass
            assertTrue(c in setOf(DrumClass.PERC, DrumClass.SNARE), "$word at DECAY $decay read as $c")
        }
    }

    @Test
    fun `HUMAN runs from a speech chip dead on the note to a person whose pitch moves`() {
        val note = Vox.frequencyFor(VoxVoice.SPEAK, 0.5f)
        for (word in listOf(VoxSpeak.Word.ONE, VoxSpeak.Word.EIGHT)) for (effort in listOf(0.5f, 1f)) {
            // Measured +5 cents throughout (the detector reads the bright buzz an octave up now and then).
            val machine = centsAlong(speak("WORD" to count(word), "HUMAN" to 0f, "EFFORT" to effort), note)
            assertTrue(machine.isNotEmpty() && machine.all { abs(it - 1200f * Math.round(it / 1200f)) < 15f }, "the machine holds the note: $machine")
        }
        // The person's pitch moves through the word: measured a 5.5 semitone fall on "one".
        val person = centsAlong(speak("WORD" to count(VoxSpeak.Word.ONE), "HUMAN" to 1f), note)
        assertTrue(person.max() - person.min() > 250f, "a person's pitch moves: $person")
        // The chip is bright to the top, the person round: measured −15.8 and −33.1 dB over 2 kHz.
        assertTrue(overDb(speak("HUMAN" to 0f), 2000f) > overDb(speak("HUMAN" to 1f), 2000f) + 10, "the chip is brighter than the person")
    }

    @Test
    fun `DECAY stretches the vowels, and a long word stays a one-shot`() {
        for (word in VoxSpeak.Word.entries) {
            val lengths = listOf(0f, 0.5f, 1f).map { speak("WORD" to count(word), "DECAY" to it).durationSeconds }
            assertTrue(lengths.zipWithNext().all { (a, b) -> b > a + 0.05f }, "$word: $lengths")
        }
        val longest = speak("WORD" to count(VoxSpeak.Word.SEVEN), "DECAY" to 1f, "STUTTER" to 1f)
        assertTrue(longest.durationSeconds < 2f, "the longest word is ${longest.durationSeconds} s")
    }

    @Test
    fun `SIZE runs child to giant, and the giant still rings rather than muffles`() {
        val centroids = listOf(0f, 0.5f, 1f).map { FeatureExtractor.extract(speak("SIZE" to it)).centroidHz }
        assertTrue(centroids[0] > centroids[1] * 1.5f && centroids[1] > centroids[2] * 1.3f, "child, normal, giant: $centroids")
        // VOX's own reach for SIZE (formants to 0.47) was heard "muffled rather than resonating", 8 dB under
        // the normal voice over 2 kHz. The giant stops at 0.7 and rings: measured 4.8 dB under.
        val normal = overDb(speak("SIZE" to 0.5f), 2000f)
        val giant = overDb(speak("SIZE" to 1f), 2000f)
        assertTrue(giant > normal - 6.5, "the giant is muffled: ${"%.1f".format(giant)} dB over 2 kHz against ${"%.1f".format(normal)}")
    }

    @Test
    fun `EFFORT goes hushed, talking, shouting, and a shout climbs where a calm word falls`() {
        val one = "WORD" to count(VoxSpeak.Word.ONE)
        val hushed = speak(one, "HUMAN" to 0.8f, "EFFORT" to 0f)
        val talk = speak(one, "HUMAN" to 0.8f, "EFFORT" to 0.5f)
        val shout = speak(one, "HUMAN" to 0.8f, "EFFORT" to 1f)
        // Hushed is breath over a soft voice, not a whisper (SWARM's whisper was heard as "demonic").
        assertTrue(FeatureExtractor.extract(hushed).flatness > FeatureExtractor.extract(talk).flatness * 1.3f, "hushed is breathier")
        assertTrue(centsAlong(hushed, Vox.frequencyFor(VoxVoice.SPEAK, 0.5f), sure = 0.3f).isNotEmpty(), "a hushed word still has a voice in it")
        // Measured centroids 271 and 374 Hz.
        assertTrue(FeatureExtractor.extract(shout).centroidHz > FeatureExtractor.extract(talk).centroidHz * 1.2f, "a shout is brighter")
        // INFLECT, folded in: measured "one" falling 5.5 semitones calm and climbing 4.4 shouted.
        val note = Vox.frequencyFor(VoxVoice.SPEAK, 0.5f)
        for (word in listOf(VoxSpeak.Word.ONE, VoxSpeak.Word.EIGHT)) {
            val calm = centsAlong(speak("WORD" to count(word), "HUMAN" to 1f, "EFFORT" to 0.5f), note)
            val called = centsAlong(speak("WORD" to count(word), "HUMAN" to 1f, "EFFORT" to 1f), note)
            assertTrue(calm.last() < calm.first() - 100f, "$word calm should fall: $calm")
            assertTrue(called.last() > called.first() + 100f, "$word shouted should climb: $called")
        }
    }

    @Test
    fun `STUTTER puts up to three quick false starts before the word`() {
        assertEquals(listOf(0, 1, 2, 3), listOf(0f, 0.34f, 0.67f, 1f).map { VoxSpeak.stuttersFor(it) })
        // Each false start is as quick as eight's, whatever the word opens on: the audition heard eight's
        // pace right and the hissing openings of five, six and seven slow. Measured 0.107-0.110 s each.
        for (word in listOf(VoxSpeak.Word.ONE, VoxSpeak.Word.FIVE, VoxSpeak.Word.SIX, VoxSpeak.Word.EIGHT)) {
            val lengths = (0..3).map { speak("WORD" to count(word), "STUTTER" to it / 3f).durationSeconds }
            for ((a, b) in lengths.zipWithNext()) assertTrue(b - a in 0.09f..0.125f, "$word: each false start ${b - a} s")
        }
    }

    @Test
    fun `SPEAK regenerates to the byte`() {
        val recipe = arrayOf("WORD" to 0.6f, "HUMAN" to 0.7f, "EFFORT" to 0.8f, "STUTTER" to 0.34f, "SIZE" to 0.8f)
        assertTrue(speak(*recipe).samples.contentEquals(speak(*recipe).samples))
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
