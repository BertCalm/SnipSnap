package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Features
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ARCO's factory roster: eight per voice, the same identity/sanity/round-trip/names/blocklist/spread
 * contract every `<Engine>PresetsTest` holds (`BorePresetsTest`'s own shape), plus the claims the roster
 * itself makes: each preset sits on the note its comment names, the scrapes scrape and the clean presets
 * lock, the two loops close, and every reading the real classifier gives has room to its line.
 *
 * These prove the roster is *sound* - renders clean, files honestly, round-trips, and is what its
 * comments say. They cannot prove it is *good*: nothing in it was listened to, and the audition page is
 * where that is decided.
 *
 * The numbers below are what R1b's roster pass saw (the engine at the work-in-progress commit), each
 * with its bar beside it, and R1c's where its retunes moved them (CELLO's bite of 3.0 times for 60 ms with half of it in the pressure, GRIT BOW's BOW of 0.65, the box that
 * rings louder than the string above BODY 0.5, and MOON FIDDLE's BODY of 0.8): the KDoc says which.
 */
class ArcoPresetsTest {

    /** Everything a roster claim needs about one preset, worked out once for the whole file. */
    private class Reading(val preset: ArcoPatch) {
        val voice = preset.voice
        val snip: Snip = preset.render()
        val features: Features = FeatureExtractor.extract(snip)
        val heard: DrumClass = Classifier.classify(features).drumClass
        val filed: DrumClass = Arco.drumClassFor(voice, preset.macros)
        val hold: Float = preset.macros.getValue("HOLD")
        val loop: Boolean = Arco.isLoop(hold)
        val midi: Int = Arco.midiFor(voice, preset.macros.getValue("TUNE"))
        val hz: Float = Arco.frequencyFor(voice, preset.macros.getValue("TUNE"))
        val holdSeconds: Float = Arco.holdSeconds(hold)
        val seconds: Float = snip.frameCount.toFloat() / Dsp.RATE
        val label: String get() = "$voice ${preset.name}"

        /**
         * When the string locks into one slip a period on the raw core at this preset's own macros, in seconds;
         * -1 when it never does inside the bow-on time (a scrape), and -2 for a loop (which discards its start).
         */
        val lockSeconds: Double by lazy {
            if (loop) {
                -2.0
            } else {
                val m = Arco.settled(preset.macros, voice)
                val bowPoint = FloatArray((Arco.renderFrames(voice, m) + 3) * Dsp.OVERSAMPLE)
                Arco.bow(voice, hz, m, bowPointOut = bowPoint)
                ArcoMeasure.lockSeconds(bowPoint, (holdSeconds * ArcoMeasure.RATE).toInt(), hz)
            }
        }

        /** The note the finished render sounds, in cents from the note TUNE names, read on the locked stretch; null when that stretch is too short to read. */
        val cents: Double? by lazy {
            val from = lockSeconds + LOCK_SETTLE_SECONDS
            val body = minOf(READ_SECONDS, holdSeconds - from - READ_TAIL_SECONDS)
            if (lockSeconds < 0 || body < MIN_READ_SECONDS) {
                null
            } else {
                FineTuning.cents(FineTuning.measuredHz(snip, hz, from.toFloat(), body.toFloat()), hz.toDouble())
            }
        }
    }

    private companion object {
        /** The classes a real drum pad's choke group answers to: a pitched note must never read as one. */
        val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)

        /** After the lock is found the note is given this long to settle before its pitch is read, and the read stops this long before the bow lifts. */
        const val LOCK_SETTLE_SECONDS = 0.05
        const val READ_TAIL_SECONDS = 0.03

        /** The read is up to this long, and a stretch shorter than the minimum is not read at all (a few periods of a low note are too few to read to a cent). */
        const val READ_SECONDS = 0.4
        const val MIN_READ_SECONDS = 0.12

        /**
         * The bars of the room test, each between what R1b measured and the classifier's own line: a PERC reading's head under
         * 200 Hz (line 0.55) and over 2 kHz (line 0.5), a TONAL reading's ring past its peak in ms (line 500), and a one-shot's
         * length in seconds (the LOOP line is 1.5).
         */
        const val MAX_PERC_LOW = 0.50f
        const val MAX_PERC_HIGH = 0.46f
        const val MIN_TONAL_DECAY_MS = 560f
        const val MAX_ONE_SHOT_SECONDS = 1.48f

        /** The GRIP at which the G#2 swell (BOW 0.3, BODY 1, HOLD 0.44) reads KICK since R1g: 441 ms past its peak, 59 ms under the classifier's 500 ms line, but only 11 ms over what the plain reads there (430), so it is printed and no longer the control. R1c's was GRIP 0.5. */
        const val SWELL_KICK_GRIP = 0.9f

        /** The corner the G#2 swell control stands on since R1g's review: BOW 0.25, HOLD 0.42, GRIP 0.7, where the lift moves the reading by about 70 ms (BODY 1 reads KICK 453 ms, the plain's BODY 0.5 KICK 383 ms). */
        const val SWELL_AIMED_BOW = 0.25f
        const val SWELL_AIMED_HOLD = 0.42f
        const val SWELL_AIMED_GRIP = 0.7f

        val readings: List<Reading> by lazy { ArcoPresets.all().parallelStream().map { Reading(it) }.toList() }

        private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun noteName(midi: Int): String = noteNames[midi % 12] + (midi / 12 - 1)

        fun f2(x: Number): String = "%.2f".format(java.util.Locale.ROOT, x.toDouble())

        /** The frozen roster: names in order, per voice (the kit's pads and the audition page ask for these by name). */
        val celloNames = listOf("SLOW BOW", "SHORT STAB", "DEEP PEDAL", "GRIT BOW", "DRY SCRAPE", "CINEMA LOW", "HORSEHAIR", "ENDLESS DRAW")
        val erhuNames = listOf("NASAL LINE", "MOON FIDDLE", "THIN SCRAPE", "HIGH CRY", "SLOW CRY", "TEA HOUSE", "TWO STRING", "ENDLESS CRY")

        /** The note each preset's comment in [ArcoPresets] names (TUNE is k over 24 for CELLO, k over 19 for ERHU, snapped to a semitone). */
        val notes = mapOf(
            "SLOW BOW" to "D3", "SHORT STAB" to "A3", "DEEP PEDAL" to "A2", "GRIT BOW" to "F#2",
            "DRY SCRAPE" to "E2", "CINEMA LOW" to "A#2", "HORSEHAIR" to "C4", "ENDLESS DRAW" to "G2",
            "NASAL LINE" to "C#5", "MOON FIDDLE" to "G4", "THIN SCRAPE" to "A#4", "HIGH CRY" to "G5",
            "SLOW CRY" to "B4", "TEA HOUSE" to "E4", "TWO STRING" to "A4", "ENDLESS CRY" to "E5",
        )
    }

    @Test
    fun `every preset renders clean audio at full level`() {
        for (r in readings) {
            val snip = r.snip
            assertTrue(snip.frameCount > 0, "${r.label} rendered nothing")
            assertTrue(snip.samples.all { it.isFinite() }, "${r.label} produced non-finite samples")
            assertTrue(snip.samples.all { it in -1f..1f }, "${r.label} clipped")
            val loud = Loudness.of(snip)
            assertTrue(
                loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f,
                "${r.label} is too quiet: loudness $loud, peak ${snip.peak()}",
            )
            val dc = snip.samples.average().toFloat()
            assertTrue(abs(dc) < 0.05f, "${r.label} has DC offset $dc")
        }
    }

    @Test
    fun `every preset classifies as a pitched note, never a drum with a choke group`() {
        // What the classifier makes of a sustained bowed note is measured, not wished for: a note over
        // 1.5 s reads LOOP (the length rule, exact and predictable from HOLD), a shorter one PERC - or
        // TONAL, when the head window's share under 200 Hz passes 0.55 and it rings past 500 ms, which
        // every slow low preset here does (a slow bow's first 93 ms is its own swell). The readings
        // that are drums are the danger, and R1b's first roster had them: ERHU's THIN SCRAPE and HIGH
        // CRY read SNARE (over half their head above 2 kHz, from a hard bow on a thin box at the top of
        // the span), and CINEMA LOW at F2 read KICK (a slow bow on a short note is all low swell and no
        // sustain). So the filed class (what SynthScreen reads before a render exists) is exact over
        // the LOOP line, and PERC-or-TONAL below it.
        println("ARCO presets by the classifier:")
        for (r in readings) {
            println(
                "ARCO PRESET ${r.label} ${noteName(r.midi)} len=${f2(r.seconds)}s filed=${r.filed} heard=${r.heard} " +
                    "low=${f2(r.features.lowRatio)} mid=${f2(r.features.midRatio)} high=${f2(r.features.highRatio)} " +
                    "decay=${r.features.decayMs.roundToInt()}ms",
            )
            assertTrue(r.heard !in choking, "${r.label} classified as ${r.heard}, a real drum's own choke group")
            if (r.heard == DrumClass.LOOP || r.filed == DrumClass.LOOP) {
                assertEquals(r.filed, r.heard, "${r.label}: filed class disagrees with the classifier over the LOOP line")
            } else {
                assertTrue(r.heard in setOf(DrumClass.PERC, DrumClass.TONAL), "${r.label}: a one-shot read as ${r.heard}")
                assertEquals(DrumClass.PERC, r.filed, "${r.label}: a one-shot is filed PERC")
            }
        }
    }

    /**
     * R1c saw, over the fourteen one-shots: PERC readings with a head under 200 Hz share of at most 0.45 (DEEP PEDAL; the
     * classifier's line is 0.55; R1b saw 0.44, before the box above BODY 0.5 rang louder) and a head above 2 kHz of at most 0.41 (TWO STRING; the line is 0.5;
     * R1b saw 0.42), TONAL readings that ring at least 627 ms past their peak (SLOW BOW and CINEMA LOW, as R1b's; the line is 500), and a
     * longest note of 1.44 s (R1b's 1.436; the line is 1.5). Each bar sits between the measurement and the line, so a small change in the engine moves a note toward
     * its line without turning it into something else, and a large one fails here by name instead of in a kit. MOON FIDDLE reads TONAL at 813 ms at its BODY of 0.8
     * (at 0.85 the louder box brought it to 522 ms, 22 ms from the line, which is why the roster's BODY went down a notch).
     *
     * The knife-edge is real, which is why the bars exist: at CELLO's G#2 a swell of BOW 0.3 and BODY 1 at HOLD 0.44 is TONAL at
     * GRIP 0.7 (575 ms past its peak) and KICK at GRIP 0.5 (441 ms), so CINEMA LOW sits at A#2,
     * where the same swell stays clear of the line (627 ms, R1c and R1b alike). R1b's own render of that swell (its bite, its plain vibrato and its box at the macro BODY, the four-override recipe
     * [the G sharp 2 swell is on the classifier's line, and what moved it] plays) read 569 and 557 ms, TONAL at both GRIPs: the KICK at GRIP 0.5 is new with R1c, a 57 ms margin over the line turned into 59 ms under it, and it is the louder box
     * and the drifting vibrato together that did it, neither alone (that test prints all four readings). R1b's own KDoc here quoted 580 and 488 ms (KICK) for this swell: that was read on the block-retune vibrato before it became a read-back delay and was never re-measured; the 569 and 557 ms of the exact R1b engine are the figure for what merged.
     *
     * R1g saw the same bars hold with the nine presets above BODY 0.5 (DEEP PEDAL, CINEMA LOW, ENDLESS DRAW, NASAL LINE, MOON FIDDLE, HIGH CRY, SLOW CRY, TEA HOUSE, TWO STRING) now carrying the lift on the plain's gain instead of R1c's climbed box, every BODY as it was
     * and no bar moved: PERC head under 200 Hz at most 0.45 (DEEP PEDAL), over 2 kHz at most 0.40 (TWO STRING), TONAL rings at least 627 ms (SLOW BOW and CINEMA LOW, 633), the longest note 1.44 s. The G#2 swell's KICK corner is no longer GRIP 0.5 (TONAL, 604 ms now): it is GRIP 0.9, 441 ms, and the swell test's control stands at BOW 0.25, HOLD 0.42, GRIP 0.7, where the lift
     * moves the reading (BODY 1 453 ms against the plain's 383) (the swell test below prints the sweep).
     */
    @Test
    fun `every classifier reading keeps its room to its line`() {
        var worstLow = 0f
        var worstHigh = 0f
        var leastDecay = Float.MAX_VALUE
        var longest = 0f
        val problems = ArrayList<String>()
        for (r in readings.filter { !it.loop }) {
            longest = maxOf(longest, r.seconds)
            if (r.seconds > MAX_ONE_SHOT_SECONDS) problems += "${r.label} renders ${f2(r.seconds)} s, too near the classifier's 1.5 s LOOP line"
            when (r.heard) {
                DrumClass.PERC -> {
                    worstLow = maxOf(worstLow, r.features.lowRatio)
                    worstHigh = maxOf(worstHigh, r.features.highRatio)
                    if (r.features.lowRatio > MAX_PERC_LOW) problems += "${r.label}: head under 200 Hz is ${f2(r.features.lowRatio)}, near the 0.55 that reads bass"
                    if (r.features.highRatio > MAX_PERC_HIGH) problems += "${r.label}: head over 2 kHz is ${f2(r.features.highRatio)}, near the 0.5 that reads SNARE"
                }
                DrumClass.TONAL -> {
                    leastDecay = minOf(leastDecay, r.features.decayMs)
                    if (r.features.decayMs < MIN_TONAL_DECAY_MS) problems += "${r.label}: rings ${r.features.decayMs.roundToInt()} ms past its peak, near the 500 ms that separates a note from a KICK"
                }
                else -> fail("${r.label} read as ${r.heard}")
            }
        }
        println("ARCO roster room: PERC low <= ${f2(worstLow)} (line 0.55, bar $MAX_PERC_LOW), PERC high <= ${f2(worstHigh)} (line 0.5, bar $MAX_PERC_HIGH), TONAL decay >= ${leastDecay.roundToInt()} ms (line 500, bar $MIN_TONAL_DECAY_MS), longest ${f2(longest)} s (line 1.5, bar $MAX_ONE_SHOT_SECONDS)")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** The classifier's reading of [snip]: the class it files it as and how long it rings past its peak, in ms. */
    private fun heardOf(snip: Snip): Pair<DrumClass, Int> {
        val features = FeatureExtractor.extract(snip)
        return Classifier.classify(features).drumClass to features.decayMs.roundToInt()
    }

    /**
     * A CELLO note as R1b rendered it, from the engine's own probe overrides: the bite `overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f` and the vibrato `vibratoShape = Arco.VIBRATO_PLAIN` (R1b's sine), through R1b's box,
     * `Strings.bodyRing` at the macro BODY itself (not [Arco.boxAmountFor] of it) cut to the string's length, then the same finish. With [humanVibrato] true the vibrato is CELLO's own, and with [louderBox] true the box is R1c's:
     * one or the other put back, which is how the test says which of the two moved a reading. R1g retired R1c's climb from the engine ([Arco.withBody] keeps the box at the knee's size above the knee now), so [louderBox] reads R1c's own box
     * from its frozen copy in the test tree ([ArcoBodyCandidates.last], R1c's box at BODY 1: this recipe is only asked for at BODY 1), and not [Arco.withBody].
     */
    private fun r1bRender(macros: Map<String, Float>, humanVibrato: Boolean = false, louderBox: Boolean = false): Snip {
        val voice = ArcoVoice.CELLO
        val m = Arco.settled(macros, voice)
        val hz = Arco.frequencyFor(voice, m.getValue("TUNE"))
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = Arco.bow(voice, hz, m, rate, overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f, vibratoShape = if (humanVibrato) null else Arco.VIBRATO_PLAIN)
        val boxed = if (louderBox) {
            check(m.getValue("BODY") == 1f) { "R1c's box is only written out at BODY 1" }
            ArcoBodyCandidates.last(raw, voice, rate)
        } else {
            val rung = Strings.bodyRing(raw, Arco.bodyFor(voice), m.getValue("BODY"), rate, Arco.BODY_CEILING_SECONDS)
            if (rung.size == raw.size) rung else rung.copyOf(raw.size)
        }
        // BODY 0 hands an already-boxed string back untouched, so this is the engine's one finish ([Arco.finished]) of [boxed] alone.
        return Snip(Arco.finished(boxed, voice, 0f, rate), channels = 1, sampleRate = Dsp.RATE)
    }

    /**
     * The knife edge under CINEMA LOW's A#2, read four ways, and where the lift moved it. A CELLO swell at G#2 (BOW 0.3, BODY 1, HOLD 0.44) is on the classifier's KICK/TONAL line: TONAL when it rings more than 500 ms past its peak,
     * KICK when it does not, and GRIP moves it across. R1c saw, at GRIP 0.7 then 0.5, the shipped engine at 575 ms (TONAL) and 441 ms (KICK); R1b's exact render (`r1bRender`: its bite, its plain vibrato, its box at the macro BODY) at 569 ms (TONAL)
     * and 557 ms (TONAL), so R1b's swell was TONAL at both GRIPs and R1c's GRIP 0.5 swell was KICK: neither the louder box nor CELLO's drifting vibrato did it alone (R1c's box with R1b's plain vibrato read 575 ms at GRIP 0.5 and 563 at 0.7, R1b's box
     * with the drifting vibrato 557 and 569) and the two together read 441 ms. The reading is a decay time measured on a note that is nearly on the line, and a change that does nothing alone can move it together with another. That is why CINEMA LOW
     * sits at A#2, where the same swell is clear of the line (627 ms), and why the classifier reading is a bar of the roster and not a thing to leave to chance.
     *
     * R1g retired R1c's climb: above BODY 0.5 the box stays at the knee's size and BODY adds the lift on the plain's own gain ([Arco.finished]), which rings the held note on longer. R1g saw the shipped swell at GRIP 0.7 read 615 ms (TONAL) and at GRIP 0.5
     * read 604 ms (TONAL), so the corner this control stood on moved off the line by design, 104 ms to the TONAL side where it had been 59 ms to the KICK side. The same swell at GRIP 0.9 reads 441 ms, KICK, 59 ms under the line, and R1g first re-aimed the control
     * there; R1g's review (T4) saw that corner is KICK at the plain too (430 ms at BODY 0.5) and the lift moves it by only 11 ms, so it could not show the lift at work and a considerably stronger lift could still pass it. It is now printed and not asserted
     * as the control. The control stands where the lift matters: BOW 0.25, HOLD 0.42, GRIP 0.7 ([SWELL_AIMED_BOW], [SWELL_AIMED_HOLD], [SWELL_AIMED_GRIP]). R1g saw BODY 1 read KICK at 453 ms there, 47 ms under the line, and the plain at BODY 0.5 read KICK at 383 ms,
     * so the lift moves the reading by 70 ms and no more than that (the sweep below prints the cell with the other 29, where the lift moves the reading by 6 to 70 ms, this cell the largest move, and carries 6 of the 30 cells from a drum class at BODY 0.5 to TONAL at BODY 1: GRIP 0.5 to 0.9, BOW 0.25 to 0.35, HOLD 0.42 and 0.44, BODY 1 and the plain at BODY 0.5, on `ARCO G#2 swell sweep` lines). Asserted, both readings of the aimed
     * cell: BODY 1 reads a choking class between 400 and 499 ms (within 100 ms under the line; at this corner the cap, not [Arco.LIFT_TOP_DB], sets the delivered lift, so this sees a lift that is absent or that moves the reading through the shape or the cap, and not a change of the lift's two stops), the plain at BODY 0.5 reads a choking class between 350 and 420 ms (the plain is the frozen sound at and
     * under the knee), and the lift moves the reading by between 30 and 100 ms (R1g saw 70; a lift that did nothing, or one whose shape or cap moved it by under 30 or over 100 ms, fails by name); the shipped swell at GRIP 0.7 (BOW 0.3, HOLD 0.44) is TONAL; the neighbourhood holds a cell
     * where the plain reads a drum class and BODY 1 reads TONAL (the lift carries a swell across the line, which a lift that did nothing could not show: a sweep that held a drum class and TONAL anywhere would have been satisfied by cells the test already asserts); and R1b's
     * own render is TONAL at GRIP 0.7 and 0.5, as R1b saw it. The old corner (GRIP 0.9) and the R1c and R1b-box-with-drift readings are printed, not asserted. The control for [the classifier guard can fail - the corners the roster avoids do read as drums] is unchanged.
     */
    @Test
    fun `the G sharp 2 swell is on the classifier's line, and what moved it`() {
        val g2 = Arco.defaults(ArcoVoice.CELLO) + mapOf("TUNE" to 8f / 24, "BOW" to 0.3f, "BODY" to 1f, "HOLD" to 0.44f)
        val rows = listOf(0.7f, 0.5f, SWELL_KICK_GRIP).map { grip ->
            val macros = g2 + ("GRIP" to grip)
            grip to listOf(
                "shipped" to heardOf(Arco.render(ArcoVoice.CELLO, macros)),
                "R1b exact" to heardOf(r1bRender(macros)),
                "R1c box, plain vibrato" to heardOf(r1bRender(macros, louderBox = true)),
                "R1b box, human vibrato" to heardOf(r1bRender(macros, humanVibrato = true)),
            )
        }
        for ((grip, readings) in rows) println("ARCO G#2 swell GRIP $grip: " + readings.joinToString(", ") { (name, r) -> "$name ${r.first} ${r.second} ms" })
        val shipped = rows.associate { (grip, readings) -> grip to readings[0].second }
        val r1b = rows.associate { (grip, readings) -> grip to readings[1].second }

        // The neighbourhood: BOW and HOLD one step either side of the swell, GRIP from 0.5 to 0.9, at BODY 1 and at the plain's BODY 0.5 (the same corner without the lift), rendered in parallel and printed in order.
        val cells = listOf(0.25f, 0.3f, 0.35f).flatMap { bow -> listOf(0.42f, 0.44f).flatMap { hold -> listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f).map { grip -> Triple(bow, hold, grip) } } }
        val sweep = cells.parallelStream().map { (bow, hold, grip) ->
            val at = g2 + mapOf("BOW" to bow, "HOLD" to hold, "GRIP" to grip)
            Triple(Triple(bow, hold, grip), heardOf(Arco.render(ArcoVoice.CELLO, at)), heardOf(Arco.render(ArcoVoice.CELLO, at + ("BODY" to Arco.DEFAULT_BODY))))
        }.toList()
        for ((cell, lifted, plain) in sweep) {
            println("ARCO G#2 swell sweep BOW ${cell.first} HOLD ${cell.second} GRIP ${cell.third}: BODY 1 ${lifted.first} ${lifted.second} ms, BODY 0.5 ${plain.first} ${plain.second} ms")
        }
        val crossing = sweep.filter { it.third.first in choking && it.second.first == DrumClass.TONAL }
        println("ARCO G#2 swell sweep: the lift carries ${crossing.size} of ${sweep.size} cells from a drum class at BODY 0.5 to TONAL at BODY 1, and moves the reading by ${sweep.minOf { it.second.second - it.third.second }} to ${sweep.maxOf { it.second.second - it.third.second }} ms (mean ${f2(sweep.map { it.second.second - it.third.second }.average())})")

        // The aimed corner: both readings, so the lift's effect and its distance from the line are both asserted.
        val aimed = g2 + mapOf("BOW" to SWELL_AIMED_BOW, "HOLD" to SWELL_AIMED_HOLD, "GRIP" to SWELL_AIMED_GRIP)
        val aimedLifted = heardOf(Arco.render(ArcoVoice.CELLO, aimed))
        val aimedPlain = heardOf(Arco.render(ArcoVoice.CELLO, aimed + ("BODY" to Arco.DEFAULT_BODY)))
        val oldCorner = shipped.getValue(SWELL_KICK_GRIP)
        println(
            "ARCO G#2 swell control: BOW $SWELL_AIMED_BOW HOLD $SWELL_AIMED_HOLD GRIP $SWELL_AIMED_GRIP: BODY 1 ${aimedLifted.first} ${aimedLifted.second} ms, BODY 0.5 ${aimedPlain.first} ${aimedPlain.second} ms, " +
                "the lift moves it ${aimedLifted.second - aimedPlain.second} ms, ${500 - aimedLifted.second} ms under the 500 ms line; the old corner (GRIP $SWELL_KICK_GRIP, BOW 0.3, HOLD 0.44) reads ${oldCorner.first} ${oldCorner.second} ms at BODY 1",
        )

        assertEquals(DrumClass.TONAL, shipped.getValue(0.7f).first, "the shipped G#2 swell at GRIP 0.7 reads ${shipped.getValue(0.7f)}, not TONAL")
        assertTrue(aimedLifted.first in choking, "the lifted G#2 swell (BOW $SWELL_AIMED_BOW, HOLD $SWELL_AIMED_HOLD, GRIP $SWELL_AIMED_GRIP, BODY 1) reads $aimedLifted, not a drum: the lift now rings it past the classifier's 500 ms line")
        assertTrue(aimedLifted.second in 400..499, "the lifted G#2 swell rings ${aimedLifted.second} ms past its peak, not within 100 ms under the 500 ms line")
        assertTrue(aimedPlain.first in choking, "the plain G#2 swell (the same corner at BODY 0.5) reads $aimedPlain, not a drum: the plain's sound moved")
        assertTrue(aimedPlain.second in 350..420, "the plain G#2 swell rings ${aimedPlain.second} ms past its peak, not 350 to 420 ms (R1g saw 383): the plain's sound moved")
        assertTrue(aimedLifted.second - aimedPlain.second in 30..100, "the lift moves the aimed G#2 swell by ${aimedLifted.second - aimedPlain.second} ms (BODY 1 ${aimedLifted.second}, plain ${aimedPlain.second}), not 30 to 100 ms: the lift is not what it was")
        assertTrue(crossing.isNotEmpty(), "no cell of the neighbourhood goes from a drum class at BODY 0.5 to TONAL at BODY 1: the lift no longer carries a swell across the line")
        for (grip in listOf(0.7f, 0.5f)) assertEquals(DrumClass.TONAL, r1b.getValue(grip).first, "R1b's G#2 swell at GRIP $grip reads ${r1b.getValue(grip)}, not TONAL")
    }

    private class Control(val voice: ArcoVoice, val label: String, val macros: Map<String, Float>)

    /**
     * The guard above only means something if a bowed note really can land in a drum's class. Two renders
     * R1b saw do, each the mechanism the roster steers around: a slow bow on a short bass note (CELLO C2, BOW 0,
     * HOLD 0) reads KICK because its head is all low swell and it does not ring on, and a hard bow on a thin box
     * at the top of ERHU's span (G5, BODY 0.1) reads SNARE (0.53 of its head above 2 kHz). (A third, a slow-ish
     * bow on a 0.42 HOLD A2, read KICK until the stop and the vibrato changed: whether a slow low swell reads a
     * kick or a tone is a knife edge between neighbouring notes, which is why the roster is checked by name.)
     */
    @Test
    fun `the classifier guard can fail - the corners the roster avoids do read as drums`() {
        val controls = listOf(
            Control(ArcoVoice.CELLO, "C2 BOW 0 HOLD 0", mapOf("TUNE" to 0f, "BOW" to 0f, "GRIP" to 0.5f, "BODY" to 0.5f, "HOLD" to 0f)),
            Control(ArcoVoice.ERHU, "G5 BODY 0.1", mapOf("TUNE" to 17f / 19, "BOW" to 0.85f, "GRIP" to 0.5f, "BODY" to 0.1f, "HOLD" to 0.2f)),
        )
        for (c in controls) {
            val heard = Classifier.classify(Arco.render(c.voice, c.macros)).drumClass
            println("ARCO control ${c.voice} ${c.label} reads $heard")
            assertTrue(heard in choking, "${c.voice} ${c.label} should read as a drum (the corner the roster avoids), read $heard")
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in ArcoPresets.all()) {
            val restored = ArcoPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertContentEquals(preset.render().samples, restored.render().samples, "${preset.name} rendered differently after a round-trip")
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in ArcoVoice.entries) {
            val names = ArcoPresets.forVoice(voice).map { it.name }
            assertEquals(8, names.size, "$voice should ship 8 presets, has ${names.size}")
            assertEquals(names.toSet().size, names.size, "$voice has duplicate preset names: $names")
            for (name in names) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
        }
    }

    @Test
    fun `the roster carries the frozen names, in order`() {
        assertEquals(celloNames, ArcoPresets.forVoice(ArcoVoice.CELLO).map { it.name })
        assertEquals(erhuNames, ArcoPresets.forVoice(ArcoVoice.ERHU).map { it.name })
    }

    @Test
    fun `no preset name is an engine's name or a rack section's`() {
        // The house applies this by hand (HEAVY CRUNCH names CRUNCH, so SOFT SWELL would name SWELL): a name that is
        // another part of the product's name reads, on the phone and in a filename, as if it belonged to it.
        val taken = listOf(
            ThumpPatch.ENGINE, SkinPatch.ENGINE, TinesPatch.ENGINE, PluckPatch.ENGINE, TonewheelPatch.ENGINE, VelvetPatch.ENGINE,
            FathomPatch.ENGINE, ResinPatch.ENGINE, TidePatch.ENGINE, VoxPatch.ENGINE, SnapPatch.ENGINE, GlintPatch.ENGINE,
            SirenPatch.ENGINE, ForkPatch.ENGINE, TerraPatch.ENGINE, SilkPatch.ENGINE, BorePatch.ENGINE, ArcoPatch.ENGINE,
        ) + FxChain.SECTION_NAMES.map { it.uppercase() } + ArcoVoice.entries.map { it.name }
        assertTrue(taken.size > 30, "the list of taken names lost its rack sections")
        for (preset in ArcoPresets.all()) {
            val words = preset.name.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            val clash = words.filter { it in taken }
            assertTrue(clash.isEmpty(), "${preset.name} names $clash, which is an engine, a voice or a rack section")
        }
    }

    @Test
    fun `no preset name references a real instrument or its maker`() {
        val offenders = ArcoPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `the blocklist catches the string machines and their makers, and lets harp and sharp through`() {
        // The word boundary is the point: a bare "arp" would refuse HARP, SHARP and WARP (sixteen shipped names),
        // and the lookahead lets ARPEGGIO through while still catching ARPSTRING and ARP STRINGS.
        val nearMisses = listOf(
            "SOLINA CELLO 74", "Solina String", "Solina-ish", "EMINENT 310", "ARP ODYSSEY", "ARP STRINGS", "ARPSTRING", "arp-ish",
            "String Ensemble", "string ensemble", "STRINGENSEMBLE", "String   Ensemble",
        )
        for (nearMiss in nearMisses) {
            assertTrue(PresetTestSupport.trademarkBlocklist.containsMatchIn(nearMiss), "blocklist let '$nearMiss' through")
        }
        val clean = listOf("SHARP BOW", "HARP DOUBLE", "MEDIEVAL BOW", "ARPEGGIO PAD", "ARPEGGIO", "WARP", "DEEP HARP", "STRING MACHINE", "THIN STRINGS", "SOLO CELLO")
        for (name in clean) {
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(name), "blocklist wrongly flagged '$name'")
        }
    }

    /**
     * The sixteen shipped names the bare term would have caught are DEEP HARP, HARP DOUBLE, LOW HARP, MUTED HARP, SOFT
     * HARP, WARP and ten SHARP names (the design record counted them against the shipped roster). A script over every
     * shipped name, the four string machines (STRING MACHINE, THIN STRINGS, WIDE STRINGS, DARK STRINGS) among them,
     * shows the new alternatives block none of them, and a bare `arp` stands as the control that would have.
     */
    @Test
    fun `the new blocklist terms block no shipped preset name`() {
        val shipped = Presets.all().map { it.name }.distinct()
        val machines = listOf("STRING MACHINE", "THIN STRINGS", "WIDE STRINGS", "DARK STRINGS")
        assertTrue(shipped.containsAll(machines), "the string machines are not all shipped: ${machines - shipped.toSet()}")
        val blocked = shipped.filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it) }
        assertTrue(blocked.isEmpty(), "shipped names the blocklist now refuses: $blocked")
        val bare = Regex("(?i)arp")
        val bareHits = shipped.filter { bare.containsMatchIn(it) }
        println("ARCO blocklist: ${shipped.size} shipped names, 0 blocked; a bare arp would block ${bareHits.size}: $bareHits")
        assertTrue(bareHits.size >= 16, "a bare arp should flag the sixteen HARP, SHARP and WARP names, flagged ${bareHits.size}")
    }

    @Test
    fun `presets spread out rather than cluster`() {
        for (voice in ArcoVoice.entries) {
            val presets = ArcoPresets.forVoice(voice)
            for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
                val d = PresetTestSupport.rmsDistance(a.macros, b.macros)
                assertTrue(d > 0.05f, "$voice: ${a.name} and ${b.name} are nearly the same sound (${"%.3f".format(java.util.Locale.ROOT, d)})")
            }
        }
    }

    @Test
    fun `every preset names every macro, so the roster is the full knob and not a default in disguise`() {
        for (preset in ArcoPresets.all()) {
            assertEquals(Arco.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, "${preset.name} leaves a macro at its default")
        }
    }

    @Test
    fun `the roster's HOLD 1 presets are loops and the rest are not`() {
        for (voice in ArcoVoice.entries) {
            val loops = readings.filter { it.voice == voice && it.loop }.map { it.preset.name }
            assertEquals(listOf(if (voice == ArcoVoice.CELLO) "ENDLESS DRAW" else "ENDLESS CRY"), loops, "$voice's loops")
        }
        for (r in readings) {
            assertEquals(r.loop, Arco.isLoop(r.preset.macros.getValue("HOLD")))
            if (r.loop) {
                assertEquals(1f, r.hold, "${r.label}: a loop is HOLD 1")
                assertEquals(DrumClass.LOOP, r.filed, "${r.label}: a loop is filed LOOP")
                assertTrue(r.snip.frameCount >= 1.5f * Dsp.RATE, "${r.label}: a LOOP under the classifier's line")
            } else {
                assertEquals(DrumClass.PERC, r.filed, "${r.label}: a one-shot is filed PERC")
                assertEquals(Arco.renderFrames(r.voice, Arco.settled(r.preset.macros, r.voice)), r.snip.frameCount, "${r.label}: the filed length is not the rendered length")
            }
        }
    }

    /**
     * TUNE snaps to a semitone, so a value that lands within a hair of a note names the note and a value near the
     * half-way line between two would name either. R1b's TUNEs are k over 24 or k over 19 written to three places, which
     * is at most 0.01 of a semitone off; the bar is 0.05, a tenth of the way to the rounding edge.
     */
    @Test
    fun `every preset lands on the note its comment names`() {
        for (r in readings) {
            val span = Arco.tuneSemitones(r.voice)
            val exact = r.preset.macros.getValue("TUNE") * span
            assertTrue(abs(exact - exact.roundToInt()) < 0.05f, "${r.label}: TUNE ${r.preset.macros["TUNE"]} is ${f2(exact)} semitones, not on a note")
            assertEquals(notes.getValue(r.preset.name), noteName(r.midi), "${r.label} sounds ${noteName(r.midi)}")
        }
    }

    /** A note is a scrape when it never locks into one slip a period, or locks only in the last tenth of its bow-on: that is no note, the string is in its scratch for all but a moment. */
    private fun scrapes(lockSeconds: Double, holdSeconds: Float) = lockSeconds < 0 || lockSeconds >= 0.9 * holdSeconds

    /**
     * When the string locks, in seconds, on the raw core at [macros] on [voice] (-1 never): the roster's own reading ([Reading.lockSeconds]) for macros that are not a preset's, and, with [r1bBite], for the CELLO stroke
     * as R1b played it (`overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f`).
     */
    private fun lockOf(voice: ArcoVoice, macros: Map<String, Float>, r1bBite: Boolean = false): Double {
        val m = Arco.settled(macros, voice)
        val hz = Arco.frequencyFor(voice, m.getValue("TUNE"))
        val bowPoint = FloatArray((Arco.renderFrames(voice, m) + 3) * Dsp.OVERSAMPLE)
        if (r1bBite) Arco.bow(voice, hz, m, bowPointOut = bowPoint, overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f) else Arco.bow(voice, hz, m, bowPointOut = bowPoint)
        return ArcoMeasure.lockSeconds(bowPoint, (Arco.holdSeconds(m.getValue("HOLD")) * ArcoMeasure.RATE).toInt(), hz)
    }

    /**
     * What the roster says about each note's character, held to the raw bow at the preset's own macros. R1c saw: every
     * one-shot but DRY SCRAPE lock into one slip a period inside its bow-on time (the latest, relative to its bow, is GRIT BOW at 0.45 s of 0.58, 0.78 of its bow, where the bar is 0.85, that is 0.49 s:
     * about 40 ms of room, and R1b's was 0.42 s at the BOW 0.85 it was then written at; then SHORT STAB at 0.22 s of 0.30, the bar 0.255 s: about 35 ms of room, and R1b's stroke had it at 0.19 s at the same BOW 0.9;
     * HORSEHAIR is at 0.15 s of 0.62). DRY SCRAPE is a scrape: R1b read it never locking in its 0.34 s (-1; at E2, below G#2, the scratch outlasts a short bow), and R1c reads it locking at 0.33 s, in the last
     * 10 ms of the 0.34 s, so it is still a scrape in all but its last moment (R1c's bigger bite lets it lock at the very end) and the clause is "never locks, or locks no earlier than 0.9 of its bow-on" (0.31 s), which both
     * engines meet. GRIT BOW locks no earlier than 0.3 s (R1c saw 0.45 s, R1b 0.42 s), so its first stretch is a scratch (the bar is 0.3 s). The clean presets are the
     * negative control for the scrape clause and the other way round: SHORT STAB, a stab on A3 of the same short bow-on, locks at 0.22 s, and DRY SCRAPE moved up there would fail its clause (it must scrape: 0.9 of 0.30 s is 0.27 s,
     * and the test asserts that SHORT STAB is not a scrape by that clause); either claim failing means a preset stopped being what its name says.
     *
     * SHORT STAB is at its authored BOW 0.9 and locks at 0.22 s (R1b's stroke, 0.19 s); the interim bite (3.0 times for 60 ms with all of its share in the pressure) never locked it in its stab at all. GRIT BOW is
     * re-authored to BOW 0.65, because at its authored BOW 0.85 the shipped bite no longer locks it inside its bow (R1c saw it never lock in the 0.58 s; at 0.65 it locks at 0.45 s, and R1b's stroke at 0.65 never locks, so the preset's BOW and the
     * bite are one pair: both are printed below). It is the one preset whose BOW moved.
     */
    @Test
    fun `the scrapes scrape and the clean presets lock`() {
        val problems = ArrayList<String>()
        for (r in readings.filter { !it.loop }) {
            println("ARCO lock ${r.label} ${noteName(r.midi)} bow-on=${f2(r.holdSeconds)}s lock=${f2(r.lockSeconds)}s cents=${r.cents?.let { f2(it) } ?: "-"}")
            if (r.preset.name == "DRY SCRAPE") {
                if (!scrapes(r.lockSeconds, r.holdSeconds)) problems += "${r.label} locked at ${f2(r.lockSeconds)} s of ${f2(r.holdSeconds)} s, before 0.9 of its bow-on (${f2(0.9 * r.holdSeconds)} s): it is no longer a scrape"
            } else if (r.lockSeconds < 0) {
                problems += "${r.label} never locks into one slip a period in its ${f2(r.holdSeconds)} s"
            } else if (r.lockSeconds > 0.85 * r.holdSeconds) {
                problems += "${r.label} locks at ${f2(r.lockSeconds)} s of ${f2(r.holdSeconds)} s, too late to be a note"
            }
        }
        val grit = readings.first { it.preset.name == "GRIT BOW" }
        if (grit.lockSeconds < 0.3) problems += "GRIT BOW locks at ${f2(grit.lockSeconds)} s, too early to be a scratch"
        val gritAuthored = lockOf(ArcoVoice.CELLO, grit.preset.macros + ("BOW" to 0.85f))
        println("ARCO lock GRIT BOW as R1b authored it, at BOW 0.85 (bow-on ${f2(grit.holdSeconds)}s): lock=${if (gritAuthored < 0) "never" else "${f2(gritAuthored)}s"}; at the shipped BOW ${grit.preset.macros["BOW"]}: ${f2(grit.lockSeconds)}s")
        val gritR1b = lockOf(ArcoVoice.CELLO, grit.preset.macros + ("BOW" to 0.85f), r1bBite = true)
        println("ARCO lock GRIT BOW as R1b shipped it (BOW 0.85, R1b's stroke): ${if (gritR1b < 0) "never" else "${f2(gritR1b)}s"}")
        for (name in listOf("SHORT STAB", "GRIT BOW", "DRY SCRAPE")) {
            val r = readings.first { it.preset.name == name }
            val r1b = lockOf(ArcoVoice.CELLO, r.preset.macros, r1bBite = true)
            println("ARCO lock $name with R1b's stroke at the shipped macros (BOW ${r.preset.macros["BOW"]}): ${if (r1b < 0) "never" else "${f2(r1b)}s"}; shipped ${f2(r.lockSeconds)}s of ${f2(r.holdSeconds)}s")
        }
        val stab = readings.first { it.preset.name == "SHORT STAB" }
        println("ARCO lock the control: SHORT STAB scrapes by the DRY SCRAPE clause: ${scrapes(stab.lockSeconds, stab.holdSeconds)} (lock ${f2(stab.lockSeconds)}s of ${f2(stab.holdSeconds)}s, the clause is at ${f2(0.9 * stab.holdSeconds)}s)")
        if (scrapes(stab.lockSeconds, stab.holdSeconds)) problems += "SHORT STAB scrapes by the clause DRY SCRAPE is held to (lock ${f2(stab.lockSeconds)} s of ${f2(stab.holdSeconds)} s), so the clause cannot tell a note from a scrape"
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * R1b saw the locked stretch of every one-shot that has one long enough to read (eleven of the fourteen; SHORT STAB, GRIT
     * BOW and DRY SCRAPE have none) within 2.8 cents of its note (ERHU's NASAL LINE at C#5, the worst), and the engine's own bar
     * is 5. Vibrato is baked in past 0.6 s of bow and is at most 3.9 cents at the longest bow here (10 cents scaled by how far
     * 0.95 s is from 0.6 toward 1.5), inside the same bar.
     */
    @Test
    fun `every locked preset sounds the note it is filed on`() {
        var measured = 0
        var worst = 0.0
        for (r in readings.filter { !it.loop }) {
            val c = r.cents ?: continue
            measured++
            worst = maxOf(worst, abs(c))
            assertTrue(abs(c) < 5.0, "${r.label} sounds ${f2(c)} cents from ${noteName(r.midi)}")
        }
        println("ARCO in tune: $measured one-shots read, worst ${f2(worst)} cents")
        assertTrue(measured >= 10, "only $measured of the one-shots had a locked stretch long enough to read")
    }

    /**
     * R1c saw both loops close well under [Keys.MAX_SEAM_ERROR] (CELLO's at 5.1e-05, ERHU's at 1.2e-05, against 1e-3; R1b saw CELLO's at 5.9e-05, before
     * ENDLESS DRAW's BODY of 0.6 rang louder; the seam is read on the kept stretch against itself one loop later, never on the loop played twice) and sound their
     * note to within 0.06 cents (R1b's 0.07; the bar is 5, the engine's own).
     */
    @Test
    fun `the two loops close and sound their note`() {
        for (r in readings.filter { it.loop }) {
            val rendered = Arco.renderLoopMeasured(r.voice, Arco.settled(r.preset.macros, r.voice))
            val c = FineTuning.cents(FineTuning.measuredHz(r.snip, r.hz, 0.1f, 0.5f), r.hz.toDouble())
            println("ARCO loop ${r.label} ${noteName(r.midi)} seam=${"%.2e".format(java.util.Locale.ROOT, rendered.seam)} frames=${r.snip.frameCount} cents=${f2(c)}")
            assertTrue(rendered.seam < Keys.MAX_SEAM_ERROR, "${r.label}: the loop does not close (seam ${rendered.seam})")
            assertContentEquals(rendered.loop, r.snip.samples, "${r.label}: the preset's render is not the measured loop")
            assertTrue(abs(c) < 5.0, "${r.label} sounds ${f2(c)} cents from ${noteName(r.midi)}")
        }
    }

    @Test
    fun `the dispatcher knows ARCO`() {
        assertEquals(ArcoPresets.forVoice(ArcoVoice.CELLO), Presets.forVoice("ARCO", "CELLO"))
        assertEquals(ArcoPresets.forVoice(ArcoVoice.ERHU), Presets.forVoice("ARCO", "ERHU"))
        assertEquals(ArcoPresets.forVoice(ArcoVoice.ERHU).first(), Presets.byName("ARCO", "ERHU", "NASAL LINE"))
        assertTrue(Presets.all().containsAll(ArcoPresets.all()))
    }
}
