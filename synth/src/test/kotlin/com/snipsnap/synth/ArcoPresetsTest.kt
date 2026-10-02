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
 * with its bar beside it.
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
     * R1b saw, over the fourteen one-shots: PERC readings with a head under 200 Hz share of at most 0.44 (DEEP PEDAL; the
     * classifier's line is 0.55) and a head above 2 kHz of at most 0.42 (TWO STRING; the line is 0.5), TONAL readings that
     * ring at least 627 ms past their peak (SLOW BOW and CINEMA LOW; the line is 500), and a longest note of 1.436 s (the
     * line is 1.5). Each bar sits between the measurement and the line, so a small change in the engine moves a note toward
     * its line without turning it into something else, and a large one fails here by name instead of in a kit.
     *
     * The knife-edge is real, which is why the bars exist: at CELLO's G#2 a swell of BOW 0.3 and BODY 1 at HOLD 0.44 is TONAL at
     * GRIP 0.7 (580 ms past its peak) and KICK at GRIP 0.5 (488 ms), so CINEMA LOW sits at A#2, where the same swell stays clear
     * of the line (627 ms).
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

    /**
     * What the roster says about each note's character, held to the raw bow at the preset's own macros. R1b saw: every
     * one-shot but DRY SCRAPE lock into one slip a period inside its bow-on time (the latest is GRIT BOW at 0.42 s of 0.58, 0.72 of
     * its bow; the bar is 0.85), DRY SCRAPE never does in its 0.34 s (at E2, below G#2, the scratch outlasts a short bow),
     * and GRIT BOW locks no earlier than 0.4 s, so its first stretch is a scratch (the bar is 0.3 s). The locked ones are the
     * negative control for the scrape and the other way round (a stab on A3 locks at 0.19 s, so DRY SCRAPE moved up there would
     * fail its clause): either claim failing means a preset stopped being what its name says.
     */
    @Test
    fun `the scrapes scrape and the clean presets lock`() {
        val problems = ArrayList<String>()
        for (r in readings.filter { !it.loop }) {
            println("ARCO lock ${r.label} ${noteName(r.midi)} bow-on=${f2(r.holdSeconds)}s lock=${f2(r.lockSeconds)}s cents=${r.cents?.let { f2(it) } ?: "-"}")
            if (r.preset.name == "DRY SCRAPE") {
                if (r.lockSeconds >= 0) problems += "${r.label} locked at ${f2(r.lockSeconds)} s: it is no longer a scrape"
            } else if (r.lockSeconds < 0) {
                problems += "${r.label} never locks into one slip a period in its ${f2(r.holdSeconds)} s"
            } else if (r.lockSeconds > 0.85 * r.holdSeconds) {
                problems += "${r.label} locks at ${f2(r.lockSeconds)} s of ${f2(r.holdSeconds)} s, too late to be a note"
            }
        }
        val grit = readings.first { it.preset.name == "GRIT BOW" }
        if (grit.lockSeconds < 0.3) problems += "GRIT BOW locks at ${f2(grit.lockSeconds)} s, too early to be a scratch"
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
     * R1b saw both loops close well under [Keys.MAX_SEAM_ERROR] (CELLO's at 5.9e-05, ERHU's at 1.2e-05, against 1e-3; the seam is
     * read on the kept stretch against itself one loop later, never on the loop played twice) and sound their note to within
     * 0.07 cents (the bar is 5, the engine's own).
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
