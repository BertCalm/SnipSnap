package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BORE's claims (docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md).
 * Every bound is on a number R1's probe *saw*, with room; the KDoc of the constant it
 * checks says what the number was. The measures read the raw loop ([Bore.blow]) so the
 * level stage cannot hide a silent render (see [BoreMeasure]).
 */
class BoreTest {

    private val rate = BoreMeasure.RAW_RATE

    private fun macros(
        voice: BoreVoice,
        tune: Float = 0.5f,
        breath: Float = 0.6f,
        lip: Float = 0.5f,
        chiff: Float = 0.4f,
        hold: Float = 0f,
    ) = Bore.defaults(voice) + mapOf("TUNE" to tune, "BREATH" to breath, "LIP" to lip, "CHIFF" to chiff, "HOLD" to hold)

    /** The raw note at TUNE, blown for [seconds] (the gate), at the frequency the render tunes it to. */
    private fun blow(
        voice: BoreVoice,
        tune: Float = 0.5f,
        breath: Float = 0.6f,
        lip: Float = 0.5f,
        chiff: Float = 0.4f,
        hold: Float = 0f,
        seconds: Float = 1.3f,
        pressure: Float? = null,
        pop: Float? = null,
        turbulence: Float? = null,
        seed: Float? = null,
    ): FloatArray {
        val hz = Bore.frequencyFor(voice, tune)
        return Bore.blow(voice, Bore.tunedHz(voice, hz), macros(voice, tune, breath, lip, chiff, hold), rate, turbulence = turbulence, gateSeconds = seconds, pressure = pressure, pop = pop, seed = seed)
    }

    // ---- speaking -----------------------------------------------------------

    @Test
    fun `every corner of the knobs speaks, and keeps speaking`() {
        // Playability rule 3, "sweet spots are wide by construction": BREATH and LIP are mapped
        // onto the *measured* speaking window, so no corner may be air or silence. The floors are
        // under the weakest note R1 measured (FLUTE 0.91, SAX 0.21 raw AC rms) with room; a note
        // that is still dying or still growing in the last quarter second is not "speaking".
        val floor = mapOf(BoreVoice.FLUTE to 0.5, BoreVoice.SAX to 0.12)
        for (voice in BoreVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) for (lip in listOf(0f, 0.5f, 1f)) for (breath in listOf(0f, 1f)) {
            val y = blow(voice, tune, breath, lip)
            val early = BoreMeasure.acRms(y, 0.9f, 1.1f)
            val late = BoreMeasure.acRms(y, 1.1f, 1.3f)
            val label = "$voice TUNE $tune LIP $lip BREATH $breath"
            assertTrue(late >= floor.getValue(voice), "$label does not speak: rms $late")
            assertTrue(late in 0.9 * early..1.1 * early, "$label is still moving after 0.9 s: $early -> $late")
        }
    }

    @Test
    fun `a reed below its threshold does not speak - the window has a real edge`() {
        // The negative control for the test above: with the pressure a third of the way to the
        // reed's closing pressure the loop's gain is under one and the note dies away. If this
        // spoke, "every corner speaks" would prove nothing - it would be true of any pressure.
        val y = blow(BoreVoice.SAX, pressure = 0.3f)
        assertTrue(BoreMeasure.acRms(y, 0.9f, 1.3f) < 0.03, "a sub-threshold reed spoke: ${BoreMeasure.acRms(y, 0.9f, 1.3f)}")
    }

    @Test
    fun `the loop stays bounded and finite at every extreme`() {
        // The reed table is passive by construction (|r| <= 1) and the jet's soft clip bounds its
        // own gain, but no pointwise bound follows from either (the design's small-gain argument),
        // so the ceiling is empirical: R1 saw 1.95 (FLUTE) and 2.36 (SAX) across the corners at
        // the hardest tongue. Three is a bound with room, not a claim of tightness.
        for (voice in BoreVoice.entries) for (tune in listOf(0f, 1f)) for (lip in listOf(0f, 1f)) for (breath in listOf(0f, 1f)) {
            val y = blow(voice, tune, breath, lip, chiff = 1f, hold = 0.98f, seconds = 2f)
            assertTrue(y.all { it.isFinite() }, "$voice TUNE $tune LIP $lip BREATH $breath went non-finite")
            assertTrue(y.maxOf { abs(it) } < 3f, "$voice TUNE $tune LIP $lip BREATH $breath peaked at ${y.maxOf { abs(it) }}")
        }
    }

    // ---- pitch --------------------------------------------------------------

    @Test
    fun `TUNE snaps to semitones over two octaves from each voice's root`() {
        for (voice in BoreVoice.entries) {
            val root = Bore.rootMidi(voice)
            assertEquals(root, Bore.midiFor(voice, 0f))
            assertEquals(root + 24, Bore.midiFor(voice, 1f))
            assertEquals(root + 12, Bore.midiFor(voice, 0.5f))
            assertEquals(root + 1, Bore.midiFor(voice, 0.04f), "0.04 of two octaves rounds to one semitone")
            val octave = Bore.frequencyFor(voice, 0.5f) / Bore.frequencyFor(voice, 0f)
            assertTrue(abs(octave - 2f) < 1e-3f, "$voice: an octave of TUNE is not a factor of two: $octave")
        }
    }

    @Test
    fun `both voices play within 7 cents of TUNE across the knobs`() {
        // The nonlinearity pulls the pitch (SAX flat, FLUTE sharp, worse at the corners) and the
        // tuning budget cannot charge for it, so [Bore.tunedHz] pins it. R1's sweep of all 175
        // combinations per voice: FLUTE -0.9..+3.5 cents, SAX -5.4..+6.1 at the old pin of 6, so
        // 7 is the pin's honest residual with room. (A LOOP retunes exactly and is tested below.)
        for (voice in BoreVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) for (lip in listOf(0f, 0.5f, 1f)) for (breath in listOf(0f, 1f)) {
            val hz = Bore.frequencyFor(voice, tune)
            val c = BoreMeasure.cents(blow(voice, tune, breath, lip), hz, 0.9f, 1.3f)
            assertTrue(abs(c) <= 7.0, "$voice TUNE $tune LIP $lip BREATH $breath plays $c cents from TUNE")
        }
    }

    // ---- what the knobs do --------------------------------------------------

    @Test
    fun `the flute's fundamental leads at every BREATH and LIP, down to C4`() {
        // The register hazard the design named and Phase 0 found: with a fixed 6 kHz bell a C4's
        // 3rd mode took over above p = 0.8 and the note jumped an octave and a fifth. The bell is
        // now a multiple of the note; R1 saw the 3rd harmonic 10 dB or more under the fundamental
        // everywhere. Six is the bound; the takeover it guards against reads 0.
        for (breath in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) for (lip in listOf(0f, 0.5f, 1f)) {
            val y = blow(BoreVoice.FLUTE, tune = 0f, breath = breath, lip = lip, seconds = 1.6f)
            val h = BoreMeasure.harmonicsDb(y, Bore.frequencyFor(BoreVoice.FLUTE, 0f), 1.0f, 1.6f)
            assertEquals(0.0, h[0], 1e-6, "FLUTE C4 BREATH $breath LIP $lip: the fundamental is not the strongest partial: $h")
            assertTrue(h[2] < -6.0, "FLUTE C4 BREATH $breath LIP $lip: the 3rd harmonic is ${h[2]} dB, a register jump in the making")
        }
    }

    @Test
    fun `LIP moves the tone monotonically on both voices`() {
        // SAX: a tighter reed (closer to beating) is purer - the 2nd harmonic falls, R1 saw
        // -8 dB at LIP 0 to -22 at LIP 1. FLUTE: LIP opens the jet and the bore, so the 3rd
        // harmonic *rises*, -15 to -11. Both must move at every step and in one direction, and
        // by an amount worth turning a knob for.
        val sax = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { lip ->
            BoreMeasure.harmonicsDb(blow(BoreVoice.SAX, breath = 0.5f, lip = lip, seconds = 1.6f), Bore.frequencyFor(BoreVoice.SAX, 0.5f), 1.0f, 1.6f)[1]
        }
        for ((a, b) in sax.zipWithNext()) assertTrue(b <= a + 1.5, "SAX's 2nd harmonic rose with LIP: $sax")
        assertTrue(sax.first() - sax.last() >= 8.0, "SAX LIP moves the 2nd harmonic by only ${sax.first() - sax.last()} dB: $sax")
        val flute = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { lip ->
            BoreMeasure.harmonicsDb(blow(BoreVoice.FLUTE, breath = 0.5f, lip = lip, seconds = 1.6f), Bore.frequencyFor(BoreVoice.FLUTE, 0.5f), 1.0f, 1.6f)[2]
        }
        for ((a, b) in flute.zipWithNext()) assertTrue(b >= a - 1.0, "FLUTE's 3rd harmonic fell as LIP rose: $flute")
        assertTrue(flute.last() - flute.first() >= 2.5, "FLUTE LIP moves the 3rd harmonic by only ${flute.last() - flute.first()} dB: $flute")
    }

    @Test
    fun `the reed has bite, and a looser lip has more of it`() {
        // Bite: the 1-4 kHz band against the fundamental, on the finished render (BoreMeasure.biteDb).
        // The R1 reed read -24 -22 -19 -16 -15 dB at C3 G3 C4 G4 C5 and the first listening note
        // asked for more of a reed's bite. The presence bell alone made it -18.7 -16.4 -13.4 -10.5
        // -9.7, the rasp (the second note: "buzzier and raspier") -9.5 -7.4 -5.4 -3.4 -2.6, and the
        // voicing (measured against a real tenor, where the fundamental is the weakest of the
        // strong partials) +6.0 +4.9 +3.9 +2.3 +1.8. The floors sit 2.5-3 dB under that, so taking
        // out the voicing, the rasp or the bell fails every one of them. LIP 0.1 against 0.9 read
        // 9.7 dB apart at C4; the floor is 8.
        val floors = listOf(0f to 3.0, 7f / 24 to 2.0, 0.5f to 1.0, 19f / 24 to -0.5, 1f to -1.0)
        for ((tune, floor) in floors) {
            val hz = Bore.frequencyFor(BoreVoice.SAX, tune)
            val note = Bore.render(BoreVoice.SAX, Bore.defaults(BoreVoice.SAX) + mapOf("TUNE" to tune, "HOLD" to 0.3f))
            val bite = BoreMeasure.biteDb(note, hz, fromSec = 0.5f)
            assertTrue(bite >= floor, "SAX at ${hz.toInt()} Hz has bite $bite dB, under the floor $floor")
        }
        val hz = Bore.frequencyFor(BoreVoice.SAX, 0.5f)
        fun biteAt(lip: Float) = BoreMeasure.biteDb(
            Bore.render(BoreVoice.SAX, Bore.defaults(BoreVoice.SAX) + mapOf("LIP" to lip, "HOLD" to 0.3f)), hz, fromSec = 0.5f,
        )
        val loose = biteAt(0.1f)
        val tight = biteAt(0.9f)
        assertTrue(loose - tight >= 8.0, "LIP 0.1 has only ${loose - tight} dB more bite than LIP 0.9 ($loose against $tight)")
    }

    @Test
    fun `the bite bell is the reed's, eases as LIP tightens, and never touches the flute`() {
        for (lip in listOf(0f, 0.3f, 0.7f, 1f)) assertEquals(0f, Bore.biteBoostDb(BoreVoice.FLUTE, lip), "the flute has bite at LIP $lip")
        val boosts = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { Bore.biteBoostDb(BoreVoice.SAX, it) }
        assertEquals(Bore.BITE_DB, boosts.first(), 1e-4f)
        assertEquals(Bore.BITE_DB * Bore.BITE_TIGHT_SHARE, boosts.last(), 1e-4f)
        assertEquals(boosts.sortedDescending(), boosts, "the bell's gain rose with LIP: $boosts")
    }

    @Test
    fun `the rasp makes harmonics the pipe did not, whatever the level, and none at amount 0`() {
        // A pure sine through the bend: amount 0 leaves it a sine (every harmonic under -100 dB), and
        // at the default knobs' amount (0.475) the 2nd, 3rd and 4th come up to -18.5, -12.4 and -22.1
        // dB against the fundamental. The bias is what makes the even ones: with none, the 2nd is
        // -33 dB. The same at a hundredth of the level and at half of it, because the wave is
        // scaled by its own loudness first.
        val rate = BoreMeasure.RAW_RATE
        val hz = 441f
        fun harmonics(amplitude: Float, amount: Float): List<Double> {
            val x = FloatArray(rate) { (amplitude * kotlin.math.sin(2 * Math.PI * hz * it / rate)).toFloat() }
            Bore.raspBend(x, rate, amount)
            return BoreMeasure.harmonicsDb(x, hz, 0.2f, 0.9f, 4, rate)
        }
        val straight = harmonics(0.5f, 0f)
        for (h in straight.drop(1)) assertTrue(h < -100.0, "amount 0 made a harmonic: $straight")
        val loud = harmonics(0.5f, 0.475f)
        val quiet = harmonics(0.01f, 0.475f)
        val floors = listOf(-22.0, -16.0, -26.0)
        for (i in floors.indices) assertTrue(loud[i + 1] >= floors[i], "the rasp's harmonic ${i + 2} is ${loud[i + 1]} dB, under ${floors[i]}: $loud")
        for (i in loud.indices) assertEquals(loud[i], quiet[i], 1.0, "the rasp depends on the level: $loud against $quiet")
    }

    @Test
    fun `the rasp is the loose, hard reed's, and never the flute's`() {
        for (lip in listOf(0f, 0.5f, 1f)) for (breath in listOf(0f, 0.5f, 1f)) {
            assertEquals(0f, Bore.raspAmount(BoreVoice.FLUTE, lip, breath), "the flute rasps at LIP $lip BREATH $breath")
        }
        assertEquals(1f, Bore.raspAmount(BoreVoice.SAX, 0f, 1f), 1e-5f)
        assertEquals(Bore.RASP_TIGHT_SHARE * Bore.RASP_SOFT_SHARE, Bore.raspAmount(BoreVoice.SAX, 1f, 0f), 1e-5f)
        val byLip = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { Bore.raspAmount(BoreVoice.SAX, it, 0.6f) }
        val byBreath = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { Bore.raspAmount(BoreVoice.SAX, 0.5f, it) }
        assertEquals(byLip.sortedDescending(), byLip, "the rasp rose as the lip tightened: $byLip")
        assertEquals(byBreath.sorted(), byBreath, "the rasp fell as the breath hardened: $byBreath")
    }

    @Test
    fun `the voicing puts the power where a saxophone has it, and is the reed's alone`() {
        // A real tenor (UNSW, written D4 = our C3) holds the 50-250 Hz band 16 dB under the whole sound
        // and has the 5th partial strongest; the merged reed held it at -1 dB. The voicing's cut of the
        // fundamental region puts ours at -9.5 dB (C3) and -7.9 (G3), so the bound is -5. (C4's
        // fundamental is over 250 Hz and the band is empty there; not measured.)
        for (tune in listOf(0f, 7f / 24)) {
            val note = Bore.render(BoreVoice.SAX, Bore.defaults(BoreVoice.SAX) + mapOf("TUNE" to tune, "HOLD" to 0.3f))
            val share = BoreMeasure.shareDb(note, 50f, 250f, fromSec = 0.5f)
            assertTrue(share <= -5.0, "the fundamental region holds $share dB of the C3/G3 reed (tune $tune), over -5: the voicing is not cutting it")
        }
        assertTrue(Bore.voicingFor(BoreVoice.FLUTE).none, "the flute has a voicing")
        assertEquals(Bore.Voicing(Bore.VOICE_LOW_DB, Bore.VOICE_HIGH_DB), Bore.voicingFor(BoreVoice.SAX))
    }

    @Test
    fun `the voicing opens with the note's loudness, so a note's first moments are not lifted`() {
        // The voicing follows the envelope (Bore.voice): nothing at the start of a note, all of it at the
        // loudest. Without that every SAX one-shot reads as a snare to the classifier (the head window is
        // the pop and the breath burst while the tone grows) and lands in a drum's choke group. A 130 Hz
        // and a 4 kHz tone ramped from nothing over 0.4 s: in the first 30 ms both pass within 2 dB
        // (measured -0.4 and -1.0); at the steady end the 130 Hz is cut (-11.7 dB at -20, deeper now) and
        // the 4 kHz lifted (+8.2). A third tone at 12 kHz, over the 9 kHz roll-off, is the exception on
        // purpose: the roll-off is a fixed band limit, not part of what opens (blended in by the loudness
        // instead, the onset's pop and breath burst keep their top end and BITE reads a SNARE), so it is
        // rolled off from the first moment (about -9 dB for two poles at 9 kHz) and stays under the 4 kHz at full level (+6.1 against +8.3, the
        // shelf's +18 still lifting it).
        val rate = Dsp.RATE
        val x = FloatArray(rate) { i ->
            val t = i.toDouble() / rate
            val env = (t / 0.4).coerceIn(0.0, 1.0)
            (env * (0.4 * kotlin.math.sin(2 * Math.PI * 130 * t) + 0.4 * kotlin.math.sin(2 * Math.PI * 4000 * t) + 0.4 * kotlin.math.sin(2 * Math.PI * 12000 * t))).toFloat()
        }
        val y = x.copyOf()
        Bore.voice(y, Bore.voicingFor(BoreVoice.SAX))
        fun amplitude(b: FloatArray, a: Double, z: Double, hz: Double): Double {
            var c = 0.0
            var s = 0.0
            for (i in (a * rate).toInt() until (z * rate).toInt()) { c += b[i] * kotlin.math.cos(2 * Math.PI * hz * i / rate); s += b[i] * kotlin.math.sin(2 * Math.PI * hz * i / rate) }
            return 2 * kotlin.math.sqrt(c * c + s * s) / ((z - a) * rate)
        }
        fun gainDb(hz: Double, a: Double, z: Double) = 20 * kotlin.math.log10(amplitude(y, a, z, hz) / amplitude(x, a, z, hz))
        assertTrue(
            abs(gainDb(130.0, 0.0, 0.03)) < 2.0 && abs(gainDb(4000.0, 0.0, 0.03)) < 2.0,
            "the voicing is already open in the first 30 ms: ${gainDb(130.0, 0.0, 0.03)} dB at 130 Hz, ${gainDb(4000.0, 0.0, 0.03)} dB at 4 kHz",
        )
        assertTrue(gainDb(12000.0, 0.0, 0.03) < -3.0, "the 9 kHz roll-off is not a fixed band limit: ${gainDb(12000.0, 0.0, 0.03)} dB at 12 kHz at the start")
        assertTrue(gainDb(12000.0, 0.6, 0.9) < gainDb(4000.0, 0.6, 0.9) - 1.0, "the 9 kHz roll-off is not there at full level: ${gainDb(12000.0, 0.6, 0.9)} dB at 12 kHz against ${gainDb(4000.0, 0.6, 0.9)} at 4 kHz")
        assertTrue(gainDb(130.0, 0.6, 0.9) <= -9.0, "the fundamental region is cut only ${gainDb(130.0, 0.6, 0.9)} dB at full level")
        assertTrue(gainDb(4000.0, 0.6, 0.9) >= 6.0, "the highs are lifted only ${gainDb(4000.0, 0.6, 0.9)} dB at full level")
    }

    @Test
    fun `the voicing blooms after the level and still reaches what it always did at the loudest`() {
        // The shelves go as the envelope's square relative to the note's own ceiling (Bore.VOICE_BLOOM), so a
        // fast attack's first moments are not a developed tone to the classifier (HIGH STAB read 0.63, a
        // SNARE, with the gains following the level linearly) while the plateau stays what it was fitted at.
        // The same 0.4 s ramp as above: halfway up (0.19-0.21 s) the 4 kHz lift was +3.4 dB and the 130 Hz
        // cut -7.8 dB with the linear follower, and is +0.3 and -2.6 dB now; the plateau is +8.2 and -14.7
        // either way. (Squaring the raw envelope instead took a low note's plateau to 0.51 of it: C3's bite
        // fell from +6.0 dB to +1.8.)
        val rate = Dsp.RATE
        val x = FloatArray(rate) { i ->
            val t = i.toDouble() / rate
            val env = (t / 0.4).coerceIn(0.0, 1.0)
            (env * (0.4 * kotlin.math.sin(2 * Math.PI * 130 * t) + 0.4 * kotlin.math.sin(2 * Math.PI * 4000 * t))).toFloat()
        }
        val y = x.copyOf()
        Bore.voice(y, Bore.voicingFor(BoreVoice.SAX))
        fun amplitude(b: FloatArray, a: Double, z: Double, hz: Double): Double {
            var c = 0.0
            var s = 0.0
            for (i in (a * rate).toInt() until (z * rate).toInt()) { c += b[i] * kotlin.math.cos(2 * Math.PI * hz * i / rate); s += b[i] * kotlin.math.sin(2 * Math.PI * hz * i / rate) }
            return 2 * kotlin.math.sqrt(c * c + s * s) / ((z - a) * rate)
        }
        fun gainDb(hz: Double, a: Double, z: Double) = 20 * kotlin.math.log10(amplitude(y, a, z, hz) / amplitude(x, a, z, hz))
        val highFull = gainDb(4000.0, 0.6, 0.9)
        val lowFull = gainDb(130.0, 0.6, 0.9)
        assertTrue(gainDb(4000.0, 0.19, 0.21) < 0.25 * highFull, "halfway up the highs are already lifted ${gainDb(4000.0, 0.19, 0.21)} dB of ${highFull}")
        assertTrue(gainDb(130.0, 0.19, 0.21) > 0.35 * lowFull, "halfway up the fundamental is already cut ${gainDb(130.0, 0.19, 0.21)} dB of $lowFull")
        assertTrue(highFull >= 7.5 && lowFull <= -13.5, "the plateau moved: $highFull dB at 4 kHz, $lowFull dB at 130 Hz")
    }

    @Test
    fun `BREATH adds breath noise inside the loop`() {
        // Loudness is levelled away, so BREATH's audible part is timbre and noise: the turbulence
        // is multiplicative on the pressure, inside the loop, and grows with BREATH. Measured as
        // aperiodicity (energy left after subtracting the note one period on): R1 saw the flute go
        // from about -50 to -28 dB over the knob and the reed from about -62 to -41.
        for ((voice, minRise) in listOf(BoreVoice.FLUTE to 12.0, BoreVoice.SAX to 10.0)) {
            val hz = Bore.frequencyFor(voice, 0.5f)
            val soft = BoreMeasure.aperiodicityDb(blow(voice, breath = 0f, seconds = 1.6f), hz, 1.0f, 1.6f)
            val hard = BoreMeasure.aperiodicityDb(blow(voice, breath = 1f, seconds = 1.6f), hz, 1.0f, 1.6f)
            assertTrue(hard - soft >= minRise, "$voice: BREATH raised the noise by only ${hard - soft} dB ($soft -> $hard)")
        }
    }

    @Test
    fun `every macro changes the sound`() {
        // No dead knob (playability rule 1): each of the five, moved alone from the default, makes
        // the finished render differ. LIP's and BREATH's differences are timbre, CHIFF's the onset,
        // HOLD's the length, TUNE's the pitch.
        for (voice in BoreVoice.entries) {
            val base = Bore.render(voice, Bore.defaults(voice)).samples
            for (spec in Bore.macrosFor(voice)) {
                val moved = Bore.defaults(voice) + (spec.name to if (spec.default > 0.5f) 0.1f else 0.9f)
                val other = Bore.render(voice, moved).samples
                val n = minOf(base.size, other.size)
                var diff = 0.0
                for (i in 0 until n) { val d = base[i] - other[i].toDouble(); diff += d * d }
                assertTrue(other.size != base.size || diff / n > 1e-6, "$voice ${spec.name} did nothing")
            }
        }
    }

    // ---- the start and the end of a note ------------------------------------

    @Test
    fun `a note starts speaking fast enough to be played`() {
        // The jet starts fast (R1: 0.04-0.31 s to 80% of steady across the whole grid). A reed's
        // onset is a fixed number of periods, so it is slow low down - 0.63 s at C3 with the
        // tightest lip and hardest breath, 0.35 s at F#4 - and fast up high; a tight reed at full
        // breath is the slow corner, since the turbulence that seeds it also pushes it under its
        // threshold now and then. The bounds are the measured worst with room, and
        // SAX_ROOT_MIDI's KDoc is why C3 and not lower.
        for (voice in BoreVoice.entries) for (lip in listOf(0f, 1f)) for (breath in listOf(0f, 1f)) for (tune in listOf(0f, 0.5f, 0.75f, 1f)) {
            val onset = BoreMeasure.onsetSeconds(blow(voice, tune, breath, lip, seconds = 1.4f), 1.0f, 1.4f)
            val bound = when {
                voice == BoreVoice.FLUTE -> 0.36f
                tune >= 0.75f -> 0.45f
                tune >= 0.5f -> 0.55f
                else -> 0.7f
            }
            assertTrue(onset <= bound, "$voice TUNE $tune LIP $lip BREATH $breath takes $onset s to speak (bound $bound)")
        }
    }

    @Test
    fun `the tongue's release speeds a reed's onset when nothing else seeds it`() {
        // The seed the turbulence gives a reed is about 1e-3 of the steady amplitude; a pulse of
        // area a seeds the fundamental at 2*a*f0, about a hundred times more. The test takes the
        // other seeds away (no turbulence, no chiff burst) so it measures the pop and nothing else:
        // with BREATH high enough to put much noise in the loop the noise seeds the reed too and the
        // pop is redundant, which is why it is for the soft end of the knob. R1 saw 0.43 s without
        // it and 0.28 with, at 185 Hz, on the first noise settings.
        val without = BoreMeasure.onsetSeconds(blow(BoreVoice.SAX, tune = 0.25f, breath = 0f, chiff = 0f, pop = 0f, turbulence = 0f, seconds = 1.6f), 1.2f, 1.6f)
        val with = BoreMeasure.onsetSeconds(blow(BoreVoice.SAX, tune = 0.25f, breath = 0f, chiff = 0f, turbulence = 0f, seconds = 1.6f), 1.2f, 1.6f)
        assertTrue(without - with >= 0.05f, "the pop saved only ${without - with} s of onset ($without -> $with)")
    }

    @Test
    fun `a tongued reed note speaks in about a tenth of a second at the default knobs, and the seed does it`() {
        // A real tenor reaches 80% of a note's level in 0.05-0.12 s (UNSW's recordings, a very soft note
        // 0.49 s); this reed's growth from a tiny seed took 58 periods whatever the pressure - 0.44 s at C3,
        // 0.11 s at C5 - so every one-shot swelled where a saxophone attacks. The tongue's seed (TONGUE_SEED)
        // puts a short tone into the bore at the release: 0.10 0.07 0.06 0.05 0.04 s at C3 G3 C4 G4 C5.
        // The bounds are the measured values with room; "without" is the same blow with the seed set to 0.
        val tunes = listOf(0f, 7f / 24, 0.5f, 19f / 24, 1f)
        for (tune in tunes) {
            val with = BoreMeasure.onsetSeconds(blow(BoreVoice.SAX, tune, seconds = 1.4f), 1.0f, 1.4f)
            val without = BoreMeasure.onsetSeconds(blow(BoreVoice.SAX, tune, seconds = 1.4f, seed = 0f), 1.0f, 1.4f)
            val bound = if (tune < 0.25f) 0.16f else 0.12f
            assertTrue(with <= bound, "SAX TUNE $tune takes $with s to speak at the default knobs (bound $bound)")
            assertTrue(without - with >= 0.05f, "the seed saved only ${without - with} s at TUNE $tune ($without -> $with)")
        }
    }

    @Test
    fun `CHIFF is the tongue, so the attack goes from a swell to a stab, and a soft breath is a slower note`() {
        // CHIFF 0 is the old swell (0.40 s at C3) and every step up the knob is quicker: 0.40 0.19 0.10
        // 0.06 0.04 s at CHIFF 0 .2 .4 .7 1. BREATH 0 is a soft note and stays slow (0.31 s) against the
        // default 0.10 and a hard breath's 0.07.
        fun onset(chiff: Float = 0.4f, breath: Float = 0.6f) = BoreMeasure.onsetSeconds(blow(BoreVoice.SAX, 0f, breath = breath, chiff = chiff, seconds = 1.4f), 1.0f, 1.4f)
        val steps = listOf(0f, 0.2f, 0.4f, 0.7f, 1f).map { onset(chiff = it) }
        for (i in 1 until steps.size) assertTrue(steps[i] < steps[i - 1], "CHIFF's onset did not shorten at step $i: $steps")
        assertTrue(steps.first() >= 0.3f, "CHIFF 0 is no swell now: ${steps.first()} s")
        assertTrue(steps.last() <= 0.08f, "CHIFF 1 is not a stab: ${steps.last()} s")
        val soft = onset(breath = 0f)
        assertTrue(soft >= 0.2f, "a BREATH 0 note speaks in $soft s, as fast as a played one")
        assertTrue(onset(breath = 1f) < soft, "a hard breath is not quicker than a soft one")
    }

    @Test
    fun `the tongue's seed is the reed's, and the flute does not take it`() {
        // The jet has its own onset (0.13 s at C3, seeded or not); a seed it does not use must not move a
        // sample of it, so a flute blown with the seed forced to 0.5 is the flute blown with none. The reed's
        // does move: forcing the seed to 0.5 changes it.
        val plain = blow(BoreVoice.FLUTE, 0f, seconds = 0.5f, seed = 0f)
        val forced = blow(BoreVoice.FLUTE, 0f, seconds = 0.5f, seed = 0.5f)
        assertContentEquals(plain, forced, "the flute took the tongue's seed")
        val reed = blow(BoreVoice.SAX, 0f, seconds = 0.5f, seed = 0f)
        val reedSeeded = blow(BoreVoice.SAX, 0f, seconds = 0.5f, seed = 0.5f)
        assertTrue(!reed.contentEquals(reedSeeded), "a forced seed did nothing to the reed")
    }

    @Test
    fun `vibrato is a pitch swing that grows with HOLD and is absent without it`() {
        // The spec's pressure vibrato moved the pitch by under a cent (R1's first build, and with
        // HOLD at 0 as well - it was dead code), so vibrato is the loop's length, retuned. Swings
        // are read on 70 ms windows so a 5.2 Hz cycle is resolved; R1 saw 25-35 cents peak to peak
        // at HOLD 0.98, 13-19 at 0.5 and 1-3 at 0 (the measurement's own floor).
        for (voice in BoreVoice.entries) {
            val hz = Bore.frequencyFor(voice, 0.25f)
            fun swing(hold: Float): Double {
                val y = blow(voice, tune = 0.25f, hold = hold, seconds = 3.2f)
                var lo = Double.MAX_VALUE
                var hi = -Double.MAX_VALUE
                var t = 0.6f
                while (t + 0.07f < 3.0f) {
                    val c = BoreMeasure.cents(y, hz, t, t + 0.07f)
                    lo = minOf(lo, c); hi = maxOf(hi, c)
                    t += 0.02f
                }
                return hi - lo
            }
            val none = swing(0f)
            val some = swing(0.5f)
            val most = swing(0.98f)
            assertTrue(none <= 6.0, "$voice: vibrato at HOLD 0 swings $none cents")
            assertTrue(some in 9.0..45.0 && some > none + 6.0, "$voice: vibrato at HOLD 0.5 swings $some cents (none $none)")
            assertTrue(most in 20.0..50.0 && most > some, "$voice: vibrato at HOLD 0.98 swings $most cents (half: $some)")
        }
    }

    @Test
    fun `CHIFF's top adds a 90 Hz key thump and its lower half does not`() {
        // The one part of the spec's tape keyboard with no relative in the tree, relocated to an
        // onset. A flute at C5 has no energy at 90 Hz, so what is there is the thump: R1 saw the
        // band's energy 1.4e3 at CHIFF 0.85 and 6.6e3 at 1.0 against 4e1 at the threshold.
        val flute = { chiff: Float ->
            val snip = Bore.render(BoreVoice.FLUTE, macros(BoreVoice.FLUTE, chiff = chiff))
            PluckSpectra.toneEnergy(snip, Bore.THUMP_HZ.toFloat(), 0.12f)
        }
        val quiet = flute(0.6f)
        assertTrue(flute(0.69f) < 5 * quiet, "a thump below THUMP_FROM: ${flute(0.69f)} against $quiet")
        assertTrue(flute(0.85f) > 10 * quiet, "CHIFF 0.85 has no thump: ${flute(0.85f)} against $quiet")
        assertTrue(flute(1f) > 50 * quiet, "CHIFF 1.0 has no thump: ${flute(1f)} against $quiet")
    }

    @Test
    fun `the thump ends under -60 dB, not cut at -30`() {
        // Cut at -30 dB the thump ends with a click. Its envelope must be under 1e-3 by THUMP_SECONDS.
        assertTrue(exp(-Bore.THUMP_SECONDS / Bore.THUMP_TAU_SECONDS) < 1e-3, "the thump is still above -60 dB when it is cut")
    }

    // ---- HOLD and the LOOP step ---------------------------------------------

    @Test
    fun `HOLD sets the length of the note, and its top step is a loop`() {
        assertEquals(Bore.HOLD_MIN_SECONDS, Bore.holdSeconds(0f), 1e-4f)
        assertTrue(abs(Bore.holdSeconds(Bore.LOOP_THRESHOLD - 1e-4f) - Bore.HOLD_MAX_SECONDS) < 0.02f)
        val steps = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 0.98f).map { Bore.holdSeconds(it) }
        assertEquals(steps.sorted(), steps, "HOLD is not monotonic: $steps")
        assertTrue(!Bore.isLoop(0.98f) && Bore.isLoop(0.99f) && Bore.isLoop(1f))
        for (voice in BoreVoice.entries) {
            val m = Bore.defaults(voice) + ("HOLD" to 0.3f)
            val snip = Bore.render(voice, m)
            assertEquals(Bore.renderFrames(m), snip.frameCount, "$voice: the predicted frame count is not what a render makes")
            val loop = Bore.render(voice, m + ("HOLD" to 1f))
            assertTrue(loop.frameCount >= 1.5f * loop.sampleRate, "$voice's LOOP is shorter than the classifier's 1.5 s line")
            assertTrue(loop.frameCount <= 2.2f * loop.sampleRate, "$voice's LOOP is ${loop.frameCount.toFloat() / loop.sampleRate} s, over the two seconds it is searched around")
        }
    }

    @Test
    fun `a LOOP holds steady - it neither swells nor decays`() {
        // A strike engine's loop is a tail; this one is a held breath. Its first quarter and its
        // last must be the same level, since a loop that swelled would pump at every wrap.
        for (voice in BoreVoice.entries) {
            val loop = Bore.render(voice, Bore.defaults(voice) + ("HOLD" to 1f)).samples
            val q = loop.size / 4
            fun rms(a: Int) = Math.sqrt((a until a + q).sumOf { loop[it].toDouble() * loop[it] } / q)
            val ratio = rms(0) / rms(loop.size - q)
            assertTrue(ratio in 0.98..1.02, "$voice: the loop's first quarter is $ratio of its last")
        }
    }

    // ---- the LOOP: cut from a held one-shot, closed by a blend ----------------

    /** The one-shot's own path held for [seconds] (what the loop is cut from, before it is cut): blown and conditioned, not levelled. */
    private fun heldShot(voice: BoreVoice, m: Map<String, Float>, hold: Float = Bore.LOOP_HOLD, seconds: Float = 3.2f): FloatArray {
        val hz = Bore.tunedHz(voice, Bore.frequencyFor(voice, m.getValue("TUNE")))
        val raw = Bore.blow(voice, hz, m + ("HOLD" to hold), rate, gateSeconds = seconds)
        return Bore.condition(raw, rate, Bore.biteBoostDb(voice, m.getValue("LIP")), Bore.raspAmount(voice, m.getValue("LIP"), m.getValue("BREATH")), Bore.voicingFor(voice))
    }

    /** The step across a loop's wrap over the 99.9th percentile of its steps, from the samples alone (not the join the engine reports). */
    private fun wrapOverSteepest(loop: FloatArray): Double {
        val steps = FloatArray(loop.size - 1) { abs(loop[it + 1] - loop[it]) }
        steps.sort()
        return abs(loop[0] - loop[loop.size - 1]).toDouble() / steps[((steps.size - 1) * 0.999).toInt()]
    }

    private fun centsSpread(x: FloatArray, hz: Float, from: Float, to: Float): Pair<Double, Double> {
        val track = BoreMeasure.centsTrack(x, hz, from, to, Dsp.RATE)
        val mean = track.average()
        return Math.sqrt(track.sumOf { (it - mean) * (it - mean) } / track.size) to (track.max() - track.min())
    }

    @Test
    fun `a LOOP closes by a blend - no step at the wrap, no dip in it - across the range, the corners the periodic loop could not close included`() {
        // The periodic loop of Rounds 1.2 to 1.6 refused four SAX corners (TUNE, LIP, BREATH below); a loop cut from a held
        // note and blended takes them. Each loop is read from its samples alone: the step across the wrap against the steepest
        // ordinary step, the length, the first sample on a zero crossing (a pad plays it once through), the level.
        val unclosable = listOf(Triple(0.25f, 1f, 0.75f), Triple(0f, 0.25f, 0.75f), Triple(0f, 0.5f, 1f), Triple(0.25f, 0.25f, 0.5f))
        val corners = ArrayList<Triple<BoreVoice, Float, Pair<Float, Float>>>() // voice, tune, lip to breath
        for (voice in BoreVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) for (pair in listOf(0.5f to 0.6f, 0f to 1f, 1f to 0f)) corners.add(Triple(voice, tune, pair))
        for ((tune, lip, breath) in unclosable) corners.add(Triple(BoreVoice.SAX, tune, lip to breath))
        for ((voice, tune, pair) in corners) {
            val (lip, breath) = pair
            val label = "$voice TUNE $tune LIP $lip BREATH $breath"
            val r = Bore.renderLoopMeasured(voice, macros(voice, tune, breath, lip, hold = 1f))
            val j = r.join
            Bore.checkJoin(label, j)
            assertTrue(wrapOverSteepest(r.loop) <= 1.1, "$label: the step across the wrap is ${wrapOverSteepest(r.loop)} times the steepest ordinary step")
            assertTrue(r.loop.size in (1.9f * Dsp.RATE).toInt()..(2.15f * Dsp.RATE).toInt(), "$label: the loop is ${r.loop.size.toFloat() / Dsp.RATE} s long")
            assertTrue(j.startFrames >= (Bore.LOOP_START_SECONDS * Dsp.RATE).toInt() && j.startFrames >= j.settledFrames, "$label: the stretch starts at ${j.startFrames} before the note settled (${j.settledFrames})")
            val steepest = (0 until r.loop.size - 1).maxOf { abs(r.loop[it + 1] - r.loop[it]) }
            assertTrue(abs(r.loop[0]) <= steepest, "$label: the loop starts on a step, not on a crossing (${r.loop[0]})")
            assertTrue(r.loop.all { it.isFinite() && abs(it) <= 1f }, "$label: the loop clips")
            val loudness = Loudness.of(Snip(r.loop, channels = 1, sampleRate = Dsp.RATE))
            assertTrue(loudness >= 0.9f * Dsp.MELODIC_LOUDNESS_TARGET || r.loop.maxOf { abs(it) } >= 0.95f, "$label: the loop is at $loudness, under the loudness target")
        }
        // The check can fail: the same loop cut short so that it ends on its own largest peak (the loop starts on a zero
        // crossing) has a step of the whole amplitude at its wrap.
        val loop = Bore.renderLoopMeasured(BoreVoice.SAX, macros(BoreVoice.SAX, hold = 1f)).loop
        val peak = (loop.size / 2 until loop.size).maxByOrNull { abs(loop[it]) }!!
        val ratio = wrapOverSteepest(loop.copyOf(peak + 1))
        assertTrue(ratio > 2.0, "the loop cut short to end on its peak still reads $ratio times the steepest step at its wrap, so the wrap check cannot fail")
    }

    @Test
    fun `a LOOP has the life of its one-shot - its pitch moves and its breath is in it, which a periodic wave has neither of`() {
        // The owner heard the periodic loop as a cheap keyboard and the one-shot of the same note as good. Measured on the
        // same note (SOLO LOOP's, 233 Hz), the periodic loop read 0.00 cents of pitch movement and -80 dB between its harmonics
        // against the one-shot's 5 cents and -42; this loop reads what its one-shot reads. The measures are read the same way
        // on both: a pitch track on 70 ms windows, and the energy between the harmonics (BoreMeasure).
        for ((voice, tune) in listOf(BoreVoice.SAX to 0f, BoreVoice.SAX to 0.5f, BoreVoice.SAX to 1f, BoreVoice.FLUTE to 0.25f, BoreVoice.FLUTE to 0.75f)) {
            val m = macros(voice, tune)
            val hz = Bore.frequencyFor(voice, tune)
            val loop = Bore.render(voice, m + ("HOLD" to 1f)).samples
            val loopThree = loop + loop + loop
            val shot = heldShot(voice, m)
            val (loopStd, _) = centsSpread(loopThree, hz, 0.4f, 1.9f)
            val (shotStd, _) = centsSpread(shot, hz, 0.9f, 2.4f)
            val label = "$voice TUNE $tune"
            assertTrue(loopStd > 2.0, "$label: the loop's pitch moves only $loopStd cents (a periodic wave reads 0.00)")
            assertTrue(loopStd in 0.7 * shotStd..1.4 * shotStd, "$label: the loop's pitch moves $loopStd cents, its one-shot's $shotStd")
            val loopGap = BoreMeasure.betweenHarmonicsDb(loopThree, hz, 0.4f, Dsp.RATE)
            val shotGap = BoreMeasure.betweenHarmonicsDb(shot, hz, 0.9f, Dsp.RATE)
            assertTrue(loopGap > -50.0, "$label: only $loopGap dB between the loop's harmonics (a periodic wave reads -80)")
            assertTrue(abs(loopGap - shotGap) <= 4.0, "$label: $loopGap dB between the loop's harmonics, its one-shot's $shotGap")
        }
        // The instruments can tell: a perfectly periodic wave reads no pitch movement and almost nothing between its harmonics.
        val hz = 233.08f
        val frozen = harmonicWave(3 * Dsp.RATE, Dsp.RATE / hz.toDouble())
        val (frozenStd, _) = centsSpread(frozen, hz, 0.4f, 1.9f)
        assertTrue(frozenStd < 0.5, "a periodic wave reads $frozenStd cents of pitch movement, so the check cannot fail")
        assertTrue(BoreMeasure.betweenHarmonicsDb(frozen, hz, 0.4f, Dsp.RATE) < -60.0, "a periodic wave reads ${BoreMeasure.betweenHarmonicsDb(frozen, hz, 0.4f, Dsp.RATE)} dB between its harmonics, so the check cannot fail")
    }

    @Test
    fun `a LOOP carries the one-shot's rasp and voicing - its bite is the one-shot's, not the bell's`() {
        // Cut from a note rendered through the one-shot's own chain, so the rasp, the bell and the voicing's plateau are the
        // one-shot's. Measured as the bite is (the 1-4 kHz band against the fundamental, past 0.5 s): the loop is within 2.5 dB
        // of the held one-shot and well over the same note without the rasp and the voicing, which is the periodic loop
        // of the first rounds (the bell and nothing after).
        val margin = listOf(15.0, 10.0, 6.0)
        for ((i, tune) in listOf(0f, 0.5f, 1f).withIndex()) {
            val m = macros(BoreVoice.SAX, tune)
            val hz = Bore.frequencyFor(BoreVoice.SAX, tune)
            val loop = BoreMeasure.biteDb(Bore.render(BoreVoice.SAX, m + ("HOLD" to 1f)), hz, fromSec = 0.5f)
            val shot = BoreMeasure.biteDb(Snip(heldShot(BoreVoice.SAX, m), channels = 1, sampleRate = Dsp.RATE), hz, fromSec = 1.0f)
            val raw = Bore.blow(BoreVoice.SAX, Bore.tunedHz(BoreVoice.SAX, hz), m + ("HOLD" to Bore.LOOP_HOLD), rate, gateSeconds = 3.2f)
            val bare = BoreMeasure.biteDb(Snip(Bore.condition(raw, rate, Bore.biteBoostDb(BoreVoice.SAX, 0.5f), 0f, Bore.Voicing.NONE), channels = 1, sampleRate = Dsp.RATE), hz, fromSec = 1.0f)
            println("BORE loop bite at TUNE $tune: loop %.1f dB, held one-shot %.1f dB, without the rasp and the voicing %.1f dB".format(java.util.Locale.ROOT, loop, shot, bare))
            assertTrue(abs(loop - shot) <= 2.5, "the SAX loop at TUNE $tune has bite $loop dB, its one-shot's $shot")
            assertTrue(loop - bare >= margin[i], "the SAX loop at TUNE $tune is only ${loop - bare} dB over the same note without the rasp and the voicing ($loop against $bare), under ${margin[i]}")
        }
    }

    @Test
    fun `a LOOP's vibrato is a held note's at half the most, and the HOLD it is rendered at does not leak`() {
        // The held note is blown at HOLD 0.5 (Bore.LOOP_HOLD): the vibrato the one-shot the owner called good has, about
        // 17 cents peak to peak, against 30-35 at HOLD 0.98 (the step under the loop's) and none at 0. HOLD is the loop
        // step's own macro, so it must not reach anything but the blow: the filed class and the landing chain still read
        // HOLD 1, and a one-shot rendered before and after a loop is the same to the sample.
        val voice = BoreVoice.SAX
        val m = macros(voice, 0.5f)
        val hz = Bore.frequencyFor(voice, 0.5f)
        val loop = Bore.render(voice, m + ("HOLD" to 1f)).samples
        val (_, loopRange) = centsSpread(loop + loop + loop, hz, 0.4f, 1.9f)
        val (_, most) = centsSpread(heldShot(voice, m, hold = 0.98f), hz, 0.9f, 2.4f)
        val (_, none) = centsSpread(heldShot(voice, m, hold = 0f), hz, 0.9f, 2.4f)
        assertTrue(loopRange < 0.8 * most, "the loop's pitch swings $loopRange cents, HOLD 0.98's $most")
        assertTrue(loopRange > none + 6.0, "the loop's pitch swings $loopRange cents, HOLD 0's $none")
        assertEquals(DrumClass.LOOP, Bore.drumClassFor(voice, m + ("HOLD" to 1f)))
        assertNull(Bore.landingChain(m + ("HOLD" to 1f)))
        val shot = m + ("HOLD" to 0.5f)
        val before = Bore.render(voice, shot).samples
        Bore.render(voice, m + ("HOLD" to 1f))
        assertContentEquals(before, Bore.render(voice, shot).samples, "a loop render changed the one-shot that followed it")
    }

    @Test
    fun `a LOOP renders the same twice, the slowest reed included`() {
        for ((voice, m) in listOf(
            BoreVoice.SAX to macros(BoreVoice.SAX, tune = 0f, breath = 0.75f, lip = 0f, hold = 1f),
            BoreVoice.FLUTE to macros(BoreVoice.FLUTE, tune = 0.5f, hold = 1f),
        )) assertContentEquals(Bore.render(voice, m).samples, Bore.render(voice, m).samples, "$voice: two renders of the same loop differ")
    }

    @Test
    fun `the blend holds the power of two waves that correlate by any amount, where a plain fade dips`() {
        // assembleLoop on synthetic waves with known answers. The loop's last sample runs on into its first (the weights are
        // 0 and 1 at the head); a wave that is the same in both passes through at its own level; and two uncorrelated waves of
        // equal power come out at that power, where a linear fade dips 3 dB at the middle.
        val rng = Random(7)
        val n = 40_000
        val blend = 8_000
        val a = FloatArray(n) { (rng.nextFloat() - 0.5f) }
        val loopA = Bore.assembleLoop(a, 1_000, 20_000, blend, correlation = 0.0)
        assertEquals(a[1_000 + 20_000], loopA[0], 1e-6f, "the loop does not start on the material that follows the stretch")
        assertEquals(a[1_000 + 20_000 - 1], loopA[19_999], 1e-6f, "the stretch's own end moved")
        assertEquals(a[1_000 + blend + 5], loopA[blend + 5], 0f, "the stretch after the blend is not the stretch")
        fun power(x: FloatArray, from: Int, n: Int) = (from until from + n).sumOf { x[it].toDouble() * x[it] } / n
        val outside = power(loopA, blend, 8_000)
        val middle = power(loopA, blend / 2 - 400, 800)
        assertTrue(abs(10 * kotlin.math.log10(middle / outside)) < 0.7, "uncorrelated waves dip ${10 * kotlin.math.log10(middle / outside)} dB at the middle of the blend")
        val linear = FloatArray(blend) { val t = it.toFloat() / blend; t * a[1_000 + it] + (1f - t) * a[21_000 + it] }
        assertTrue(10 * kotlin.math.log10(power(linear, blend / 2 - 400, 800) / outside) < -2.0, "a linear fade of uncorrelated waves does not dip, so the check cannot fail")
        val c = FloatArray(n) { 0.3f }
        val loopC = Bore.assembleLoop(c, 1_000, 20_000, blend, correlation = 1.0)
        assertTrue(loopC.all { abs(it - 0.3f) < 1e-5f }, "the same wave in both passes did not come through at its own level")
    }

    @Test
    fun `the length search finds the stretch's own period, and sees no match in noise`() {
        val period = 200.37
        val wave = harmonicWave(120_000, period)
        val (length, r) = Bore.bestLength(wave, 1_000, 13_230, 1_000, 3_000)
        val off = (length % period).let { minOf(it, period - it) }
        assertTrue(r > 0.999, "a periodic wave matches itself only $r at its best length $length")
        assertTrue(off < 0.6, "the best length $length is $off samples off a whole number of periods")
        val rng = Random(11)
        val noise = FloatArray(120_000) { rng.nextFloat() - 0.5f }
        val (_, rNoise) = Bore.bestLength(noise, 1_000, 13_230, 1_000, 3_000)
        assertTrue(rNoise < 0.2, "white noise matches itself $rNoise")
    }

    @Test
    fun `the settle rule starts the loop after the level has come up, and refuses a note that never does`() {
        val rate = Dsp.RATE
        // A tone whose level ramps up over half a second and holds: settled at the first 50 ms window within 0.4 dB of the end.
        val up = FloatArray(4 * rate) { i -> (minOf(1.0, i.toDouble() / (0.5 * rate)) * kotlin.math.sin(2 * Math.PI * 220 * i / rate)).toFloat() }
        val at = Bore.settledFrame(up, up.size)
        assertTrue(at.toDouble() / rate in 0.35..0.5, "the ramp that holds from 0.5 s settled at ${at.toDouble() / rate} s")
        // A tone that is still swelling at the end of the note never settles: its last level is not within the tolerance of
        // the level it ends the note at (the median of its last second), and the loop is refused rather than cut from a swell.
        val swell = FloatArray(4 * rate) { i -> (i.toDouble() / (4 * rate) * kotlin.math.sin(2 * Math.PI * 220 * i / rate)).toFloat() }
        assertEquals(-1, Bore.settledFrame(swell, swell.size), "a note still swelling at its end was called settled")
    }

    @Test
    fun `a join that does not hold is refused by name, one that does is not`() {
        val good = Bore.LoopJoin(startFrames = 28_665, lengthFrames = 93_000, settledFrames = 4_000, correlation = 0.99, blendDipDb = -0.1, wrapStep = 0.01, steepestStep = 0.03)
        Bore.checkJoin("a good one", good)
        val weak = Bore.LoopJoin(good.startFrames, good.lengthFrames, good.settledFrames, correlation = 0.3, blendDipDb = -0.1, wrapStep = 0.01, steepestStep = 0.03)
        val dips = Bore.LoopJoin(good.startFrames, good.lengthFrames, good.settledFrames, correlation = 0.99, blendDipDb = -3.0, wrapStep = 0.01, steepestStep = 0.03)
        for ((join, word) in listOf(weak to "correlate", dips to "dips")) {
            val e = kotlin.test.assertFailsWith<IllegalArgumentException> { Bore.checkJoin("BORE SAX 48", join) }
            assertTrue(e.message!!.contains("BORE SAX 48") && e.message!!.contains(word), "the refusal does not name the loop and the fault: ${e.message}")
        }
    }

    // ---- the loop's pitch match -----------------------------------------------

    /** A band-limited periodic wave with a fractional period and fixed phases: eight harmonics at 1/k. */
    private fun harmonicWave(n: Int, period: Double, drift: (Double) -> Double = { it }): FloatArray =
        FloatArray(n) { i ->
            val phase = 2 * Math.PI * drift(i / period)
            var v = 0.0
            for (k in 1..8) v += kotlin.math.sin(k * phase + 0.7 * k) / k
            v.toFloat()
        }

    @Test
    fun `the loop's pitch match finds a fractional lag to a hundredth of a raw sample, whatever the window holds`() {
        // The estimator is a golden-section search over the correlation read between samples, weighted by a Hann
        // window. It replaced a parabola through three correlations, off by up to a quarter sample at a sharp
        // peak, and a plain sum whose window held no whole number of periods, which the two ends of the window tilt
        // (0.2 frames on a window of 2.15 periods). The periods below make 2048 and four periods fall at every
        // fraction between.
        for (period in listOf(337.77, 701.31, 953.37, 1347.82)) {
            val periods = 24
            val x = harmonicWave((periods * period).toInt() + 30_000, period)
            val lag = Bore.measureLoopSamples(x, 4000, periods * period, periods)
            assertEquals(periods * period, lag, 0.01, "a period of $period samples: the lag over $periods periods is off by ${lag - periods * period}")
        }
    }

    @Test
    fun `the loop's pitch match is read at the join, not averaged over a quarter second`() {
        // A note that is still settling has a period that creeps, and the click depends on the few milliseconds
        // after the join alone. The wave's phase is 2 pi (t/p)(1 + e t/D), so its period shortens slowly: the lag
        // that makes K periods match at the join differs from the one that matches the quarter second after it.
        // measureLoopSamples' last rung reads the first, and this test knows both exactly.
        val period = 1000.0
        val periods = 20
        val e = 2e-5
        val d = 50_000.0
        val from = 4000
        fun lagAt(start: Double): Double {
            // L + e (2 start L + L^2) / D = K p, by Newton
            var l = periods * period
            repeat(30) { l -= (l + e * (2 * start * l + l * l) / d - periods * period) / (1 + e * (2 * start + 2 * l) / d) }
            return l
        }
        val x = harmonicWave((periods * period).toInt() + 40_000, period) { u -> u * (1 + e * u * period / d) }
        val atJoin = lagAt(from.toDouble())
        val averaged = lagAt(from + 0.125 * Dsp.RATE * Dsp.OVERSAMPLE)
        assertTrue(abs(atJoin - averaged) > 0.2, "the test no longer separates the join's lag from the average: $atJoin and $averaged")
        val lag = Bore.measureLoopSamples(x, from, periods * period, periods)
        // The last rung's Hann window is centred half its length (about 2000 samples) after the join, where this
        // test's drift, a thousand times the reed's, has moved the lag by 0.03: so it is close to the join's, within
        // 0.06, and far from the average's, by over 0.2 (it measured 0.032 and 0.32).
        assertTrue(abs(lag - atJoin) < 0.06, "the lag at the join is $atJoin, the quarter-second average $averaged, and it measured $lag")
        assertTrue(abs(lag - averaged) > 0.2, "the lag measured $lag is the quarter-second average $averaged, not the join's $atJoin")
    }

    // ---- the pad ------------------------------------------------------------

    @Test
    fun `a one-shot lands with tape and a loop lands dry`() {
        val shot = Bore.landingChain(mapOf("HOLD" to 0.5f))
        assertTrue(shot != null && shot.tape == Bore.LANDING_TAPE, "a one-shot lands with the rack's TAPE")
        assertNull(Bore.landingChain(mapOf("HOLD" to 1f)), "a LOOP lands dry")
        assertNull(Bore.landingChain(mapOf("HOLD" to 0.995f)), "anything at or past the threshold is a LOOP")
        assertNotNull(Bore.landingChain(emptyMap()), "no HOLD means the default, a one-shot")
    }

    @Test
    fun `the engine renders dry - the tape is the rack's, not the engine's`() {
        // The CRUNCH rule: effects belong to the rack. The render must be the same with the
        // landing chain never applied, and BorePatch.render() must not know the chain exists.
        val patch = BorePatch("Dry", BoreVoice.FLUTE, Bore.defaults(BoreVoice.FLUTE))
        assertEquals(Bore.render(BoreVoice.FLUTE, patch.macros).samples.toList(), patch.render().samples.toList())
    }

    @Test
    fun `the filed class agrees with the classifier's own length line, to the frame`() {
        // The classifier calls a render a LOOP when frameCount.toFloat() / sampleRate > 1.5f. The
        // filed class must make the same comparison on the same frame count, not on a duration
        // with padding added: a 10 ms pad filed HOLD 0.580306 (1.48998 s of audio, which the
        // classifier reads as no LOOP) as a LOOP, the first review's boundary case. So this sweeps
        // HOLD across the line in steps of 0.0003, and holds every step to the frame count a real
        // render makes and to the class the comparison gives - and the two renders either side of
        // the line to the real classifier itself.
        for (voice in BoreVoice.entries) {
            assertEquals(DrumClass.PERC, Bore.drumClassFor(voice, mapOf("HOLD" to 0.3f)))
            assertEquals(DrumClass.LOOP, Bore.drumClassFor(voice, mapOf("HOLD" to 1f)))
            assertEquals(DrumClass.LOOP, Bore.drumClassFor(voice, mapOf("HOLD" to 0.95f)), "a 3.6 s one-shot is over the classifier's line")
        }
        var lastUnder: Map<String, Float>? = null
        var firstOver: Map<String, Float>? = null
        for (i in 0..40) {
            val m = Bore.defaults(BoreVoice.FLUTE) + ("HOLD" to 0.575f + i * 0.0003f)
            val snip = Bore.render(BoreVoice.FLUTE, m)
            assertEquals(snip.frameCount, Bore.renderFrames(m), "HOLD ${m["HOLD"]}: the predicted frame count is not what a render makes")
            val over = snip.frameCount.toFloat() / snip.sampleRate > 1.5f
            assertEquals(if (over) DrumClass.LOOP else DrumClass.PERC, Bore.drumClassFor(BoreVoice.FLUTE, m), "HOLD ${m["HOLD"]} (${snip.frameCount} frames)")
            if (over && firstOver == null) firstOver = m
            if (!over) lastUnder = m
        }
        assertTrue(lastUnder != null && firstOver != null, "the sweep did not cross the classifier's 1.5 s line")
        val reviewCase = Bore.defaults(BoreVoice.FLUTE) + ("HOLD" to 0.580306f)
        assertEquals(DrumClass.PERC, Bore.drumClassFor(BoreVoice.FLUTE, reviewCase), "1.48998 s is under the line")
        assertNotEquals(DrumClass.LOOP, Classifier.classify(Bore.render(BoreVoice.FLUTE, lastUnder!!)).drumClass, "the last render under the line reads as a LOOP")
        assertEquals(DrumClass.LOOP, Classifier.classify(Bore.render(BoreVoice.FLUTE, firstOver!!)).drumClass, "the first render over the line does not read as a LOOP")
        assertNotEquals(DrumClass.LOOP, Classifier.classify(Bore.render(BoreVoice.SAX, reviewCase)).drumClass, "SAX at the review's boundary case reads as a LOOP")
    }

    @Test
    fun `scramble rolls always speak and never reach the loop step`() {
        val random = Random(42)
        for (voice in BoreVoice.entries) repeat(12) {
            val macros = Bore.scramble(voice, random)
            assertTrue(macros.values.all { it in 0f..1f }, "$voice scrambled out of range: $macros")
            assertTrue(macros.getValue("HOLD") <= Bore.SCRAMBLE_HOLD_CEILING, "$voice scrambled into the loop step: $macros")
            val snip = Bore.render(voice, macros)
            assertTrue(snip.peak() > 0.05f && snip.samples.all { it in -1f..1f }, "$voice scrambled to silence or clipping: $macros")
        }
        assertEquals(Bore.scramble(BoreVoice.SAX, Random(7)), Bore.scramble(BoreVoice.SAX, Random(7)), "the same seed must roll the same sound")
    }

    @Test
    fun `velocity softens through the fallback and is darker at low velocity`() {
        // BORE names no brightness macro of Velocity's (BREATH is a breath, not a cutoff, and
        // nothing measured it to be monotonic in centroid), so it falls back to soften, the way
        // VOX does; a soft note is a dulled one, and the centroid must fall.
        for (voice in BoreVoice.entries) {
            val patch = BorePatch("Vel Canary", voice, Bore.defaults(voice))
            val viaFallback = Velocity.atVelocity(patch, 0.3f)
            val viaSoften = Velocity.soften(patch.render(), 0.7f)
            assertTrue(viaFallback.samples.contentEquals(viaSoften.samples), "$voice: atVelocity is not the soften fallback")
            val soft = FeatureExtractor.extract(Velocity.atVelocity(patch, 0.25f)).centroidHz
            val hard = FeatureExtractor.extract(Velocity.atVelocity(patch, 1f)).centroidHz
            assertTrue(soft < hard, "$voice: soft centroid $soft should be below hard centroid $hard")
        }
    }
}
