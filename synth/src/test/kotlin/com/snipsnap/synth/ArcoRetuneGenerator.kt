package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders the ARCO R1c re-listen page under testkit/arco-retune/ (gitignored): `manifest.json`, which the page builds itself from,
 * the clips, and a copy of the page from the test resources. Run via `./gradlew :synth:generateArcoRetune`, then publish the folder.
 *
 * The owner listened to the R1b engine ([ArcoAuditionGenerator]'s page) and marked three things NEARLY: BODY 1 in both voices ("not
 * enough": the box should be louder at the top of the knob), CELLO's BOW 1 ("not enough bite") and CELLO's vibrato ("too mechanical").
 * ERHU's BOW 1 and ERHU's vibrato were a YES and do not change. R1c changed those three in [Arco]; this page lets the owner hear each
 * one beside what it was. Twenty-three clips on four cards, each a before-and-now comparison, labelled (not blind), dry and mono, and
 * every one through [AuditionLevel.level], so one loudness for all of them as on the big page.
 *
 *  - **BODY**, for CELLO then ERHU at the voice's default note and default knobs but BODY: the default (BODY .5, which R1c leaves exactly
 *    as it was), BODY 1 BEFORE (the box rung as loud as the string, 1.00 times) and BODY 1 NOW (the box [ArcoBodyCandidates.R1C_BODY_TOP] times the string).
 *  - **BOW**, CELLO only, BOW 1 and every other knob at its default, at the default note and at the root (TUNE 0): BEFORE (the old bite),
 *    NOW (the engine's own) and STRONGER (a calibration, not in the engine: a bigger and longer bite of the same kind, with the same
 *    share of it pressing the string; if NOW is still not enough this is the next step). STRONGER is held to a lock inside the clip's
 *    own bow-on ([checkLocks]), at both notes, and printed beside BEFORE and NOW.
 *  - **STAB**, CELLO only: the figure the owner marked YES (ArcoAuditionGenerator's `STAB_FIGURE`, SHORT STAB's knobs at BOW 1) as single
 *    hits, one BEFORE and one NOW at each of the figure's three pitches. The bigger bite is not free there: a hit that locked into a note
 *    under R1b's bite can be a scrape under it, so the card prints the engine's own lock reading for every clip (also on `ARCO` lines)
 *    and the verdict asks whether the bite is worth what it costs.
 *  - **VIBRATO**, CELLO only, at the default note and the root, HOLD for three seconds of bow: BEFORE (the old sine) and NOW (the engine's
 *    own). Beside them one ERHU clip of the default note, as a control: its vibrato was a yes and is unchanged on purpose.
 *
 * **The BEFORE clips are the present engine told to play as R1b did**, through its own probe overrides and nothing copied: CELLO's
 * stroke is `overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f, vibratoShape = VIBRATO_PLAIN` (R1b's bite size for both voices,
 * relaxing in the stroke's own time, all of its share pressing the string and measured against that size, the plain sine), and the box is
 * [Strings.bodyRing] at the macro BODY itself ([macroBox]), where [Arco.withBody] now rings it at [Arco.boxAmountFor] of it. For ERHU those
 * four overrides are its own values, so its BEFORE is the engine's ERHU ([checkReRender]); [checkBefore] holds CELLO's BOW 1 BEFORE to the
 * engine called with the four overrides and to not being NOW. The default-note BEFORE clips (BODY in both voices, BOW at C3, VIBRATO at C3)
 * and the ERHU controls are byte-identical PCM to the clips R1b's page wrote, and so is VIBRATO's root (C2) BEFORE to R1b's held-note
 * round's bowed clip at C2 (heard there unlabelled); that comparison is made outside this generator (the R1b clips are not in the
 * repository). The BOW card's C2 BEFORE clips are the same recipe at a note R1b's page never rendered, so the page calls them "the old stroke"
 * and not "what you heard"; the stab's hits are the figure's own hits, which R1b's page played inside its two-bar pattern, here one at a time.
 *
 * Nothing here has been heard by anyone yet. The page says so.
 */
object ArcoRetuneGenerator {

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val clips: List<Clip>)
    private class Section(val id: String, val display: String, val body: String, val readout: List<String>, val groups: List<Group>)
    private class Block(val hd: String, val paragraphs: List<String>)

    /**
     * What a clip is rendered as: the engine's own, or with the probe overrides [overshootMax], [biteSeconds], [pressureBite] and
     * [vibratoShape] (and R1b's box, if [macroBox]). [biteOf] is the bite those overrides (or the voice's own numbers where there are none) make.
     */
    private class Variant(
        val overshootMax: Float? = null,
        val biteSeconds: Float? = null,
        val pressureBite: Float? = null,
        val vibratoShape: Arco.VibratoShape? = null,
        val macroBox: Boolean = false,
    ) {
        fun biteOf(voice: ArcoVoice) = Bite(
            overshootMax ?: Arco.overshootMax(voice), biteSeconds ?: Arco.biteSeconds(voice), pressureBite ?: Arco.bitePressure(voice),
        )
    }

    /** A BOW 1 bite as the page describes it: its size, the least time constant it relaxes with, and the part of its share that presses the string. */
    private class Bite(val max: Float, val biteSeconds: Float, val pressure: Float) {
        /** The time constant the excess relaxes with: the longer of the stroke's attack and [biteSeconds], as [Arco] has it. */
        val tauSeconds: Float get() = max(Arco.attackSeconds(1f), biteSeconds)
    }

    /** R1b's BOW 1 bite, for both voices; [Arco] no longer carries it as one number (ERHU's is [Arco.OVERSHOOT_MAX_ERHU], the same). */
    private const val OLD_OVERSHOOT_MAX = 1.75f

    /** R1b's share of the bite that pressed the string, for both voices: all of it ([Arco.BITE_PRESSURE_ERHU] is the same). */
    private const val OLD_PRESSURE_BITE = 1f

    /**
     * The calibration the BOW card offers beside NOW: a bigger and longer bite of the same kind (the pressure's share of it is the
     * voice's own, [Arco.BITE_PRESSURE_CELLO]; [checkLocks] holds it to a lock inside the clip's bow-on). R1c saw 3.5 times for 80 ms lock at
     * C3 at 0.51 s and at C2 at 0.31 s of a 0.85 s bow-on (the bar is 0.73 s), so the step-down (3.25 and 0.07 s, then 3.0 and 0.07 s) was
     * not needed; NOW locks at 0.48 s and 0.31 s, BEFORE at 0.41 s and 0.50 s. The readings are on the `ARCO retune lock` lines.
     */
    private const val STRONGER_OVERSHOOT = 3.5f
    private const val STRONGER_BITE_SECONDS = 0.08f

    private val NOW = Variant()
    private val BEFORE = Variant(
        overshootMax = OLD_OVERSHOOT_MAX, biteSeconds = 0f, pressureBite = OLD_PRESSURE_BITE, vibratoShape = Arco.VIBRATO_PLAIN, macroBox = true,
    )
    private val STRONGER = Variant(overshootMax = STRONGER_OVERSHOOT, biteSeconds = STRONGER_BITE_SECONDS)

    /** The three seconds of bow the vibrato card holds: HOLD 0.88. */
    private const val HELD_SECONDS = 3f
    private val HELD_HOLD = Arco.holdFor(HELD_SECONDS)

    private val CELLO = ArcoVoice.CELLO
    private val ERHU = ArcoVoice.ERHU

    // ---- the render, with the engine's own overrides --------------------------

    /**
     * A one-shot ARCO note: [Arco.render]'s own path (the bow, the box, the finish) with the engine's probe overrides applied
     * as [variant] says. With none it is [Arco.render] to the sample ([checkReRender]); BEFORE is the four overrides that make R1b's
     * stroke, and the box as R1b rang it ([macroBox]).
     */
    private fun renderWith(voice: ArcoVoice, macros: Map<String, Float>, variant: Variant): Snip {
        val m = Arco.settled(macros, voice)
        require(!Arco.isLoop(m.getValue("HOLD"))) { "renderWith is for one-shots" }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = rawBow(voice, m, variant, rate)
        val finished = if (variant.macroBox) {
            // BODY 0 hands an already-boxed string back untouched, so this is the engine's one finish ([Arco.finished]) of R1b's box alone.
            Arco.finished(macroBox(raw, voice, m.getValue("BODY"), rate), voice, 0f, rate)
        } else {
            Arco.finished(raw, voice, m.getValue("BODY"), rate)
        }
        return Snip(finished, channels = 1, sampleRate = Dsp.RATE)
    }

    /**
     * R1c's BODY 1 NOW clip. R1g retired R1c's climb: the engine's BODY 1 is the lift now and no longer the NOW this page describes, so the clip is rendered from R1c's own box ([ArcoBodyCandidates.last]),
     * which is what [Arco.render] made at BODY 1 on R1c's engine (the retired checks of [ArcoBodyGenerator] held it to the sample).
     */
    private fun r1cBodyNow(voice: ArcoVoice): Snip = ArcoBodyCandidates.renderThrough(voice, mapOf("BODY" to 1f), ArcoBodyCandidates::last)

    /** The bow's own wave ([Arco.bow]) with [variant]'s overrides, [bowPointOut] receiving the string's velocity under the bow. */
    private fun rawBow(voice: ArcoVoice, m: Map<String, Float>, variant: Variant, rate: Int, bowPointOut: FloatArray? = null): FloatArray =
        Arco.bow(
            voice, Arco.frequencyFor(voice, m.getValue("TUNE")), m, rate, bowPointOut = bowPointOut,
            biteSeconds = variant.biteSeconds, vibratoShape = variant.vibratoShape, overshootMax = variant.overshootMax,
            pressureBite = variant.pressureBite,
        )

    /** R1b's box: [Strings.bodyRing] at the macro BODY itself (the engine now rings it at [Arco.boxAmountFor] of it), cut to the string's length as the engine's is. */
    private fun macroBox(raw: FloatArray, voice: ArcoVoice, body: Float, rate: Int): FloatArray {
        val rung = Strings.bodyRing(raw, Arco.bodyFor(voice), body, rate, Arco.BODY_CEILING_SECONDS)
        return if (rung.size == raw.size) rung else rung.copyOf(raw.size)
    }

    // ---- the checks ---------------------------------------------------------

    private fun same(a: Snip, b: Snip) = a.samples.contentEquals(b.samples)

    /**
     * This page's own render path is the engine's, to the sample, for every macro set a NOW clip uses (and ERHU's default and held notes).
     * ERHU is unchanged: R1b's four overrides are ERHU's own values ([Arco.OVERSHOOT_MAX_ERHU], [Arco.BITE_SECONDS_ERHU],
     * [Arco.BITE_PRESSURE_ERHU], the plain vibrato), so with them ERHU is the engine's own ERHU at every macro set tried, BOW 1 and the held note among them; only the box
     * ([macroBox]) differs, and it is the engine's wherever BODY is at or under [Arco.BODY_KNEE].
     */
    private fun checkReRender() {
        val nowSets = listOf(
            CELLO to emptyMap<String, Float>(), ERHU to emptyMap(),
            CELLO to mapOf("BODY" to 1f), ERHU to mapOf("BODY" to 1f),
            CELLO to mapOf("BOW" to 1f), CELLO to mapOf("BOW" to 1f, "TUNE" to 0f),
            CELLO to mapOf("HOLD" to HELD_HOLD), CELLO to mapOf("HOLD" to HELD_HOLD, "TUNE" to 0f), ERHU to mapOf("HOLD" to HELD_HOLD),
        ) + STAB_PITCHES.map { CELLO to stabMacros(it) }
        for ((voice, macros) in nowSets) {
            check(same(renderWith(voice, macros, NOW), Arco.render(voice, macros))) { "this page's re-render of $voice $macros is not what Arco.render makes" }
        }
        check(
            OLD_OVERSHOOT_MAX == Arco.OVERSHOOT_MAX_ERHU && Arco.BITE_SECONDS_ERHU == 0f && OLD_PRESSURE_BITE == Arco.BITE_PRESSURE_ERHU &&
                Arco.vibratoShapeFor(ERHU) === Arco.VIBRATO_PLAIN,
        ) {
            "ERHU's stroke is no longer R1b's"
        }
        val untouched = listOf(
            emptyMap<String, Float>(), mapOf("BOW" to 1f), mapOf("BOW" to 0.8f, "HOLD" to HELD_HOLD, "TUNE" to 0.9f),
            mapOf("HOLD" to HELD_HOLD), mapOf("BODY" to Arco.BODY_KNEE, "GRIP" to 1f),
        )
        for (macros in untouched) {
            check(same(renderWith(ERHU, macros, BEFORE), Arco.render(ERHU, macros))) { "ERHU $macros with R1b's overrides and box is not the engine's ERHU: ERHU changed" }
        }
    }

    /**
     * Every BEFORE differs from its NOW where R1c changed something (BODY 1 in both voices; CELLO BOW 1; CELLO's held note), or the BEFORE
     * clips would be the NOW clips under another name. The BOW clip is held to the point of the exercise: CELLO BOW 1 BEFORE is not NOW
     * and is exactly the engine called with R1b's four overrides (`overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f,
     * vibratoShape = VIBRATO_PLAIN`, written out here and not through [renderWith]) through R1b's box; STRONGER is neither of them.
     */
    private fun checkBefore() {
        for (voice in listOf(CELLO, ERHU)) {
            check(!same(renderWith(voice, mapOf("BODY" to 1f), BEFORE), r1cBodyNow(voice))) { "$voice BODY 1 BEFORE is the same as NOW" }
        }
        val bow1 = mapOf("BOW" to 1f)
        val before = renderWith(CELLO, bow1, BEFORE)
        val now = Arco.render(CELLO, bow1)
        check(!same(before, now)) { "CELLO BOW 1 BEFORE is the same as NOW" }
        val m = Arco.settled(bow1, CELLO)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = Arco.bow(
            CELLO, Arco.frequencyFor(CELLO, m.getValue("TUNE")), m, rate,
            overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f, vibratoShape = Arco.VIBRATO_PLAIN,
        )
        val direct = Snip(Arco.finished(macroBox(raw, CELLO, m.getValue("BODY"), rate), CELLO, 0f, rate), channels = 1, sampleRate = Dsp.RATE)
        check(same(before, direct)) { "CELLO BOW 1 BEFORE is not the engine called with R1b's four overrides" }
        val stronger = renderWith(CELLO, bow1, STRONGER)
        check(!same(stronger, now) && !same(stronger, before)) { "CELLO BOW 1 STRONGER is the same as NOW or BEFORE" }
        check(!same(renderWith(CELLO, mapOf("HOLD" to HELD_HOLD), BEFORE), Arco.render(CELLO, mapOf("HOLD" to HELD_HOLD)))) { "CELLO's held note BEFORE is the same as NOW" }
        for (midi in STAB_PITCHES) {
            val macros = stabMacros(midi)
            val sm = Arco.settled(macros, CELLO)
            val stabRaw = Arco.bow(
                CELLO, Arco.frequencyFor(CELLO, sm.getValue("TUNE")), sm, rate,
                overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f, vibratoShape = Arco.VIBRATO_PLAIN,
            )
            val stabDirect = Snip(Arco.finished(macroBox(stabRaw, CELLO, sm.getValue("BODY"), rate), CELLO, 0f, rate), channels = 1, sampleRate = Dsp.RATE)
            val stabBefore = renderWith(CELLO, macros, BEFORE)
            check(same(stabBefore, stabDirect)) { "the stab's ${spell(midi)} BEFORE is not the engine called with R1b's four overrides" }
            check(!same(stabBefore, Arco.render(CELLO, macros))) { "the stab's ${spell(midi)} BEFORE is the same as NOW" }
        }
        println("ARCO retune checks: the page's render path is Arco.render to the sample, ERHU is as it was, every BEFORE differs from its NOW, CELLO BOW 1 and the stab's three pitches BEFORE are the engine with R1b's four overrides")
    }

    /** A note has spoken when it locks by this share of its bow-on (the presets test's own bar: later than that it is not a note). */
    private const val LOCK_LATEST_SHARE = 0.85

    /** When the string locks into one slip a period on the raw core as [variant] bows [macros], in seconds (-1 never inside the bow-on), and the bow-on's seconds. */
    private fun lockOf(voice: ArcoVoice, macros: Map<String, Float>, variant: Variant): Pair<Double, Double> {
        val m = Arco.settled(macros, voice)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val holdSeconds = Arco.holdSeconds(m.getValue("HOLD")).toDouble()
        val bowPoint = FloatArray((Arco.renderFrames(voice, m) + 3) * Dsp.OVERSAMPLE)
        rawBow(voice, m, variant, rate, bowPoint)
        val hz = Arco.frequencyFor(voice, m.getValue("TUNE"))
        return ArcoMeasure.lockSeconds(bowPoint, (holdSeconds * rate).toInt(), hz) to holdSeconds
    }

    /**
     * STRONGER is a bite nobody has heard in the engine, so it is held to the one thing a bite must not cost: at both notes of the BOW card
     * (the default note and the root) NOW and STRONGER lock into one slip a period inside the clip's own bow-on, by [LOCK_LATEST_SHARE] of
     * it, at BOW 1 and every other knob at its default (the clip's own macros, read on the raw core as the clip is rendered). BEFORE is printed
     * beside them and not held: it is the old stroke, whatever it did there.
     */
    private fun checkLocks() {
        val problems = ArrayList<String>()
        for (tune in listOf(defaultTune(CELLO), 0f)) {
            val macros = mapOf("BOW" to 1f, "TUNE" to tune)
            for ((name, variant) in listOf("BEFORE" to BEFORE, "NOW" to NOW, "STRONGER" to STRONGER)) {
                val (lock, holdSeconds) = lockOf(CELLO, macros, variant)
                println(
                    "ARCO retune lock CELLO ${note(CELLO, tune)} BOW 1 $name: lock ${f2(lock.toFloat())} s of a ${f2(holdSeconds.toFloat())} s bow-on " +
                        "(the bar is ${f2((LOCK_LATEST_SHARE * holdSeconds).toFloat())} s)",
                )
                if (variant !== BEFORE && (lock < 0 || lock > LOCK_LATEST_SHARE * holdSeconds)) {
                    problems += "CELLO ${note(CELLO, tune)} BOW 1 $name locks at ${f2(lock.toFloat())} s of a ${f2(holdSeconds.toFloat())} s bow-on"
                }
            }
        }
        check(problems.isEmpty()) { "a clip the owner is asked to judge as a bowed note does not lock inside its bow-on: $problems" }
    }

    // ---- the stab ------------------------------------------------------------

    /** SHORT STAB, the preset whose knobs the owner's stab figure was played with. */
    private val STAB_PRESET = ArcoPresets.forVoice(CELLO).first { it.name == "SHORT STAB" }

    /** The pitches of the stab figure the owner marked YES, low to high (C3, E-flat3, G3): [ArcoAuditionGenerator.STAB_FIGURE]'s own. */
    private val STAB_PITCHES: List<Int> = ArcoAuditionGenerator.STAB_FIGURE.map { it.second }.distinct().sorted()

    /** The last search's own figures (Arco's KDoc on the bite): bite settings run against the stab figure, and how many of them kept every other bar the engine is held to. */
    private const val STAB_SETTINGS_SEARCHED = 80
    private const val STAB_SETTINGS_KEPT_THE_OTHER_BARS = 19

    /** SHORT STAB's own knobs at [midi], BOW forced to 1 as in the figure the owner marked (HOLD 0: a 0.3 s hit). */
    private fun stabMacros(midi: Int): Map<String, Float> {
        val tune = (midi - Arco.CELLO_ROOT_MIDI) / Arco.CELLO_TUNE_SEMITONES.toFloat()
        check(Arco.midiFor(CELLO, tune) == midi) { "the stab's TUNE for midi $midi lands on ${Arco.midiFor(CELLO, tune)}" }
        return STAB_PRESET.macros + mapOf("TUNE" to tune, "BOW" to 1f)
    }

    /** What the raw core did in one hit: when it locked into one slip a period (-1 never inside the bow-on), and the bow-on's seconds. */
    private class Reading(val lock: Double, val bowOn: Double) {
        val locked get() = lock >= 0.0

        /** The reading as a clip description says it. */
        val words: String
            get() = if (locked) "it locks into a note at ${f2(lock.toFloat())} s of its ${f2(bowOn.toFloat())} s of bow"
            else "it never settles into one slip a period inside its ${f2(bowOn.toFloat())} s of bow (a scrape)"
    }

    /** The lock reading of the stab's [midi] as [variant] plays it: the same raw-core measurement [checkLocks] makes. */
    private fun stabReading(midi: Int, variant: Variant): Reading = lockOf(CELLO, stabMacros(midi), variant).let { (lock, bowOn) -> Reading(lock, bowOn) }

    /** Every stab hit's reading, BEFORE then NOW, so the descriptions, the page text and the `ARCO` lines are one measurement. */
    private val STAB_READINGS: Map<Int, Pair<Reading, Reading>> by lazy {
        STAB_PITCHES.associateWith { midi -> stabReading(midi, BEFORE) to stabReading(midi, NOW) }
    }

    /** The pitches whose hit locked into a note under R1b's bite and does not under the engine's: what the bigger bite costs. */
    private fun stabLost(): List<Int> = STAB_PITCHES.filter { STAB_READINGS.getValue(it).first.locked && !STAB_READINGS.getValue(it).second.locked }

    /**
     * Prints the stab's readings and holds the card to what its question asks: exactly one pitch goes from a note to a scrape (the question
     * names it), so a change in the engine that moves the reading makes this generator fail instead of the page misdescribing the figure.
     */
    private fun checkStab() {
        for (midi in STAB_PITCHES) {
            val (before, now) = STAB_READINGS.getValue(midi)
            println(
                "ARCO retune stab CELLO ${spell(midi)}: BEFORE lock ${f3(before.lock.toFloat())} s, NOW lock ${f3(now.lock.toFloat())} s " +
                    "of a ${f2(now.bowOn.toFloat())} s bow-on (-1 is never)",
            )
        }
        val lost = stabLost()
        check(lost.size == 1) { "the stab question names one pitch that turns from a note into a scrape; the engine's readings say ${lost.map { spell(it) }}" }
        println("ARCO retune stab lost: ${spell(lost.single())} locked under R1b's bite and is a scrape under the engine's")
    }

    // ---- the cards ----------------------------------------------------------

    private fun note(voice: ArcoVoice, tune: Float) = noteName(Arco.midiFor(voice, tune))

    private fun tag(voice: ArcoVoice, tune: Float) = note(voice, tune).lowercase().replace('#', 's')

    private fun defaultTune(voice: ArcoVoice) = Arco.defaults(voice).getValue("TUNE")

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/arco-retune")
        root.mkdirs()
        var count = 0
        fun write(dir: String, id: String, snip: Snip) {
            val levelled = AuditionLevel.level(snip)
            WavWriter.write(File(File(root, dir), "$id.wav"), levelled, WavWriter.BitDepth.PCM_16)
            var at = 0
            for (i in snip.samples.indices) if (abs(snip.samples[i]) > abs(snip.samples[at])) at = i
            val gain = levelled.samples[at] / snip.samples[at]
            val peak = abs(levelled.samples[at])
            check(peak in 0.001f..0.999f) { "$dir/$id has a peak of $peak" }
            println(
                "ARCO retune clip $dir/$id: ${f2(snip.samples.size.toFloat() / Dsp.RATE)} s, loudness ${f3(Loudness.of(snip))} before levelling " +
                    "(gain ${f2(gain)}), ${f3(Loudness.of(levelled))} after, peak ${f3(peak)}",
            )
            count++
        }

        checkReRender()
        checkBefore()
        checkLocks()
        checkStab()

        val sections = listOf(bodySection(::write), bowSection(::write), stabSection(::write), vibratoSection(::write))

        // The manifest is the only place the clip list lives; every clip in it must be a file on disk.
        for (section in sections) for (group in section.groups) for (clip in group.clips) {
            check(File(File(root, section.id), clip.id + ".wav").isFile) { "the manifest lists ${section.id}/${clip.id}.wav and it was not written" }
        }
        check(count == sections.sumOf { s -> s.groups.sumOf { it.clips.size } }) { "the clips written are not the clips listed" }

        val pageText = (ArcoRetuneGenerator::class.java.getResourceAsStream("/audition/arco-retune.html")
            ?: error("the re-listen page is missing from synth/src/test/resources/audition/")).use { it.readBytes() }.toString(Charsets.UTF_8)
        val facts = facts()
        for ((key, value) in facts) {
            check(pageText.contains("data-fact=\"$key\">$value<")) { "the page's own text for $key does not say $value: the page and the engine have drifted" }
        }

        File(root, "manifest.json").writeText(
            "{\"surfaceAfter\":null,\"voices\": [\n" + sections.joinToString(",\n") { sectionJson(it) } + "\n],\"loops\": []," +
                "\"intro\":[" + introBlocks().joinToString(",") { blockJson(it) } + "]," +
                "\"facts\":{" + facts.entries.joinToString(",") { "${q(it.key)}:${q(it.value)}" } + "}}\n",
        )
        File(root, "index.html").writeText(pageText)
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    /** The numbers and names the page's own question text carries, which the page fills from the manifest and which are checked against the page's fallback above. */
    private fun facts(): Map<String, String> = mapOf("bodyTop" to f2(ArcoBodyCandidates.R1C_BODY_TOP), "stabLost" to spell(stabLost().single()))

    private fun bodySection(write: (String, String, Snip) -> Unit): Section {
        val groups = listOf(CELLO, ERHU).map { voice ->
            val tune = defaultTune(voice)
            val where = note(voice, tune)
            val t = voice.name.lowercase()
            val default = Arco.render(voice)
            val before = renderWith(voice, mapOf("BODY" to 1f), BEFORE)
            val now = r1cBodyNow(voice)
            write("BODY", t + "_default", default)
            write("BODY", t + "_before", before)
            write("BODY", t + "_now", now)
            Group(
                "${voice.name}, $where (THE DEFAULT NOTE)",
                listOf(
                    Clip(t + "_default", "BODY .5 $DOT THE DEFAULT", "the default knobs: the box rings ${f2(Arco.boxAmountFor(Arco.DEFAULT_BODY))} times as loud as the string, as it always did"),
                    Clip(t + "_before", "BODY 1 $DOT BEFORE", "what you heard: the box as loud as the string, 1.00 times"),
                    Clip(t + "_now", "BODY 1 $DOT NOW", "the box ${f2(ArcoBodyCandidates.R1C_BODY_TOP)} times the string, ${f1(20f * log10(ArcoBodyCandidates.R1C_BODY_TOP))} dB more box than BEFORE"),
                ),
            )
        }
        return Section(
            id = "BODY", display = "BODY", body = "both voices: the box, at the top of the knob",
            readout = listOf(
                "CELLO ${note(CELLO, defaultTune(CELLO))} $DOT ERHU ${note(ERHU, defaultTune(ERHU))} $DOT EVERY OTHER KNOB AT ITS DEFAULT",
                "BODY .5 $DOT BODY 1 BEFORE $DOT BODY 1 NOW",
            ),
            groups = groups,
        )
    }

    private fun bowSection(write: (String, String, Snip) -> Unit): Section {
        val groups = listOf(defaultTune(CELLO), 0f).map { tune ->
            val where = note(CELLO, tune)
            val macros = mapOf("BOW" to 1f, "TUNE" to tune)
            val t = "${tag(CELLO, tune)}"
            write("BOW", t + "_before", renderWith(CELLO, macros, BEFORE))
            write("BOW", t + "_now", Arco.render(CELLO, macros))
            write("BOW", t + "_stronger", renderWith(CELLO, macros, STRONGER))
            val label = if (tune == 0f) "CELLO, $where (THE ROOT)" else "CELLO, $where (THE DEFAULT NOTE)"
            // R1b's knob page rendered BOW 1 at the default note only: the root's BEFORE is the old stroke there, not a clip the owner heard.
            val beforeIs = if (tune == 0f) "the old stroke at the root (not on the last page)" else "what you heard"
            Group(
                label,
                listOf(
                    Clip(t + "_before", "BOW 1 $DOT BEFORE", "$beforeIs: ${biteWords(BEFORE.biteOf(CELLO))}"),
                    Clip(t + "_now", "BOW 1 $DOT NOW", "the engine now: ${biteWords(NOW.biteOf(CELLO))}"),
                    Clip(
                        t + "_stronger", "BOW 1 $DOT STRONGER",
                        "not in the engine, to calibrate: ${biteWords(STRONGER.biteOf(CELLO))}. If NOW is still not enough, this is the next step",
                    ),
                ),
            )
        }
        return Section(
            id = "BOW", display = "BOW", body = "CELLO only: BOW 1, the stab with a bite",
            readout = listOf(
                "BOW 1 $DOT EVERY OTHER KNOB AT ITS DEFAULT",
                "${note(CELLO, defaultTune(CELLO))} THE DEFAULT NOTE $DOT ${note(CELLO, 0f)} THE ROOT",
            ),
            groups = groups,
        )
    }

    /** The stab card: the figure's three pitches as single hits, BEFORE and NOW, each described by the engine's own lock reading of it. */
    private fun stabSection(write: (String, String, Snip) -> Unit): Section {
        val bowOn = STAB_READINGS.getValue(STAB_PITCHES.first()).second.bowOn
        val groups = STAB_PITCHES.map { midi ->
            val macros = stabMacros(midi)
            val t = spell(midi).lowercase().replace("-", "")
            write("STAB", t + "_before", renderWith(CELLO, macros, BEFORE))
            write("STAB", t + "_now", Arco.render(CELLO, macros))
            val (before, now) = STAB_READINGS.getValue(midi)
            val change = when {
                before.locked && !now.locked -> "BEFORE locked at ${f2(before.lock.toFloat())} s: this is the note the extra bite costs"
                !before.locked && now.locked -> "BEFORE was a scrape: the extra bite gives this note its lock"
                before.locked -> "BEFORE locked at ${f2(before.lock.toFloat())} s too: the same reading"
                else -> "BEFORE was a scrape too: the same reading"
            }
            Group(
                "CELLO, ${spell(midi).uppercase()} (A PITCH OF THE FIGURE)",
                listOf(
                    Clip(
                        t + "_before", "${spell(midi).uppercase()} $DOT BEFORE",
                        "the hit of the figure you marked YES, alone, with R1b's bite (${f2(BEFORE.biteOf(CELLO).max)} times, ${ms(BEFORE.biteOf(CELLO).tauSeconds)} ms time constant): ${before.words}",
                    ),
                    Clip(
                        t + "_now", "${spell(midi).uppercase()} $DOT NOW",
                        "the engine now (${f2(NOW.biteOf(CELLO).max)} times, ${ms(NOW.biteOf(CELLO).tauSeconds)} ms time constant): ${now.words}. $change",
                    ),
                ),
            )
        }
        return Section(
            id = "STAB", display = "THE STAB", body = "CELLO only: the figure you marked YES, one hit at a time",
            readout = listOf(
                "SHORT STAB'S KNOBS $DOT BOW 1 $DOT HOLD ${fmt(STAB_PRESET.macros.getValue("HOLD"))} $DOT ${f2(bowOn.toFloat())} S OF BOW",
                STAB_PITCHES.joinToString(" $DOT ") { spell(it).uppercase() } + " $DOT THE FIGURE'S THREE PITCHES",
            ),
            groups = groups,
        )
    }

    private fun vibratoSection(write: (String, String, Snip) -> Unit): Section {
        val held = HELD_HOLD
        val groups = listOf(defaultTune(CELLO), 0f).map { tune ->
            val where = note(CELLO, tune)
            val macros = mapOf("HOLD" to held, "TUNE" to tune)
            val t = tag(CELLO, tune)
            write("VIBRATO", t + "_before", renderWith(CELLO, macros, BEFORE))
            write("VIBRATO", t + "_now", Arco.render(CELLO, macros))
            val h = Arco.VIBRATO_HUMAN
            // R1b's held-note round played the bowed note at the root with these very macros, unlabelled among a plucked and a synth clip (the same PCM, checked outside this generator).
            val beforeIs = if (tune == 0f) "the old vibrato at the root, the bowed clip of the held-note round, heard there without its label" else "what you heard"
            Group(
                if (tune == 0f) "CELLO, $where (THE ROOT)" else "CELLO, $where (THE DEFAULT NOTE)",
                listOf(
                    Clip(
                        t + "_before", "VIBRATO $DOT BEFORE",
                        "$beforeIs: a constant ${Arco.VIBRATO_MAX_CENTS.roundToInt()} cents at ${f1(Arco.VIBRATO_HZ.toFloat())} Hz, every swing the same, fully in after ${f1(Arco.VIBRATO_RISE_SECONDS.toFloat())} s",
                    ),
                    Clip(
                        t + "_now", "VIBRATO $DOT NOW",
                        "the rate drifts by up to ${pct(h.rateWander)}, the depth by up to ${pct(h.depthWander)}, a slight lean, a swell-in of ${f1(h.riseSeconds.toFloat())} s; ${Arco.VIBRATO_MAX_CENTS.roundToInt()} cents at ${f1(Arco.VIBRATO_HZ.toFloat())} Hz on average",
                    ),
                ),
            )
        }
        val erhuTune = defaultTune(ERHU)
        write("VIBRATO", "erhu_unchanged", Arco.render(ERHU, mapOf("HOLD" to held)))
        val control = Group(
            "ERHU, ${note(ERHU, erhuTune)} (A CONTROL)",
            listOf(
                Clip(
                    "erhu_unchanged", "ERHU $DOT UNCHANGED",
                    "the same held note on ERHU, as it was and on purpose: you said its vibrato was a yes, and it is the plain ${Arco.VIBRATO_MAX_CENTS.roundToInt()} cents at ${f1(Arco.VIBRATO_HZ.toFloat())} Hz still",
                ),
            ),
        )
        return Section(
            id = "VIBRATO", display = "VIBRATO", body = "CELLO only: a player, not a machine",
            readout = listOf(
                "HOLD ${fmt(held)} $DOT ${f1(HELD_SECONDS)} S OF BOW $DOT EVERY OTHER KNOB AT ITS DEFAULT",
                "${note(CELLO, defaultTune(CELLO))} $DOT ${note(CELLO, 0f)} $DOT ERHU: THE CONTROL",
            ),
            groups = groups + control,
        )
    }

    /** The page's two guide cards that carry numbers: what changed and what did not, from the constants so the text cannot drift. */
    private fun introBlocks(): List<Block> {
        val h = Arco.VIBRATO_HUMAN
        val defaultHold = Arco.defaults(CELLO).getValue("HOLD")
        val defaultCents = Arco.vibratoCentsFor(Arco.holdSeconds(defaultHold))
        val lo = Arco.VIBRATO_MAX_CENTS * (1f - h.depthWander.toFloat())
        val hi = Arco.VIBRATO_MAX_CENTS * (1f + h.depthWander.toFloat())
        val before = BEFORE.biteOf(CELLO)
        val now = NOW.biteOf(CELLO)
        val strokeMs = ms(Arco.attackSeconds(1f))
        val bowOn = STAB_READINGS.getValue(STAB_PITCHES.first()).second.bowOn
        val stabChanges = STAB_PITCHES.joinToString("; ") { midi ->
            val (b, n) = STAB_READINGS.getValue(midi)
            when {
                b.locked && !n.locked -> "${spell(midi)} locked into a note at ${f2(b.lock.toFloat())} s and is now a scrape"
                !b.locked && n.locked -> "${spell(midi)} was a scrape and now locks at ${f2(n.lock.toFloat())} s"
                b.locked && f2(b.lock.toFloat()) == f2(n.lock.toFloat()) -> "${spell(midi)} locks at ${f2(b.lock.toFloat())} s in both"
                b.locked -> "${spell(midi)} locks at ${f2(b.lock.toFloat())} s and then at ${f2(n.lock.toFloat())} s"
                else -> "${spell(midi)} is a scrape in both"
            }
        }
        return listOf(
            Block(
                "WHAT CHANGED",
                listOf(
                    "BODY is as it was up to ${fmt(Arco.BODY_KNEE)}, where the box rings ${f2(Arco.BODY_KNEE)} times as loud as the string. Above that it climbs to ${f2(ArcoBodyCandidates.R1C_BODY_TOP)} times the string at BODY 1, in both voices. It was 1.00 times, so ${f1(20f * log10(ArcoBodyCandidates.R1C_BODY_TOP))} dB more box.",
                    "CELLO's BOW 1: the bite is ${f2(now.max)} times the sustain speed (it was ${f2(before.max)}), and the speed peaks at ${f2(peakSpeed(now))} times (it was ${f2(peakSpeed(before))}), because the stroke is still rising for its first $strokeMs ms. " +
                        "The excess relaxes with a ${ms(now.tauSeconds)} ms time constant (it was the stroke's own ${ms(before.tauSeconds)} ms), ${shareOf(now.pressure)} of it presses the string and the rest is speed alone (before, ${shareOf(before.pressure)} of it pressed the string). " +
                        "A bass string needs many milliseconds to build a period, and a bite with a ${ms(before.tauSeconds)} ms time constant had mostly gone before it did. At BOW ${fmt(Arco.OVERSHOOT_FROM)} and under there is still no bite.",
                    "CELLO's vibrato was a plain sine, ${Arco.VIBRATO_MAX_CENTS.roundToInt()} cents at ${f1(Arco.VIBRATO_HZ.toFloat())} Hz, every swing the same, in over ${f1(Arco.VIBRATO_RISE_SECONDS.toFloat())} s. Now the rate drifts by up to ${pct(h.rateWander)} and the depth by up to ${pct(h.depthWander)} (swings of about ${lo.roundToInt()} to ${hi.roundToInt()} cents), the swing leans a little (a second harmonic of ${f2(h.skew.toFloat())}), and it swells in over ${f1(h.riseSeconds.toFloat())} s. The drift depends on time alone, so a note renders the same every time.",
                    "The stab figure you marked YES (SHORT STAB's knobs at BOW 1, ${f2(bowOn.toFloat())} s a hit, on ${STAB_PITCHES.joinToString(", ") { spell(it) }}) is changed by the bigger bite, and not for free. Lock readings, BEFORE to NOW: $stabChanges. " +
                        "A scrape is the string failing to settle into one slip a period inside the hit. R1c ran $STAB_SETTINGS_SEARCHED bite settings against the figure; $STAB_SETTINGS_KEPT_THE_OTHER_BARS of them kept every other bar the engine is held to, and none of those $STAB_SETTINGS_KEPT_THE_OTHER_BARS kept ${spell(stabLost().single())} a note. The STAB card plays each pitch alone, so you can judge whether the extra bite is worth it.",
                ),
            ),
            Block(
                "WHAT DID NOT",
                listOf(
                    "ERHU's bite (${f2(Arco.OVERSHOOT_MAX_ERHU)} times, ${shareOf(Arco.BITE_PRESSURE_ERHU)} of it pressing the string, in the stroke's own time), ERHU's vibrato (the plain sine) and ERHU's box shape. Every knob's default. The box at or under BODY ${fmt(Arco.BODY_KNEE)}, so every preset at or under it keeps its box to the bit.",
                    "Two things to know. CELLO's default note is ${f2(Arco.holdSeconds(defaultHold))} s of bow and carries ${f1(defaultCents)} cents of vibrato, so the CELLO default clip has the new drift in it too, far too faint to matter. CELLO presets with a BOW above ${fmt(Arco.OVERSHOOT_FROM)} get the new bite, and those held over ${f1(Arco.VIBRATO_HOLD_FROM_SECONDS)} s the new vibrato; this page plays only the stab among them.",
                    "The BEFORE clips are the engine made to play as it did last time: the old bite, the old vibrato and the old box. At the default note they are, to the bit, the clips of the last page (and so is the vibrato at the root, which its held-note round played); the BOW clips at the root are the same recipe at a note that page did not render, and the stab's hits are the figure's own hits (you heard them inside the two-bar pattern), here one at a time.",
                ),
            ),
        )
    }

    // ---- words --------------------------------------------------------------

    private const val DOT = "·"

    private fun f1(v: Float) = "%.1f".format(Locale.ROOT, v)
    private fun f2(v: Float) = "%.2f".format(Locale.ROOT, v)
    private fun f3(v: Float) = "%.3f".format(Locale.ROOT, v)
    private fun ms(seconds: Float) = (seconds * 1000f).roundToInt()
    private fun pct(share: Double) = "${(share * 100).roundToInt()} percent"

    /** A share of the bite as the page says it: "all", "half", "a quarter", otherwise a percentage. */
    private fun shareOf(share: Float) = when {
        share >= 1f -> "all"
        abs(share - 0.5f) < 1e-4f -> "half"
        abs(share - 0.25f) < 1e-4f -> "a quarter"
        else -> pct(share.toDouble())
    }

    /**
     * How a clip describes a bite: its size, the true peak of the bow's speed (the stroke is still rising while the excess starts to
     * relax, so the peak is under the size), the time constant the excess relaxes with (a time constant, not a time to be gone: 37 percent of
     * the excess is left after one) and the share of it that presses the string.
     */
    private fun biteWords(b: Bite): String =
        "a bite of ${f2(b.max)} times the sustain speed (the speed itself peaks at ${f2(peakSpeed(b))} times it, since the stroke is still rising); the excess relaxes with a time constant of ${ms(b.tauSeconds)} ms " +
            "(${pct(exp(-1.0))} of it is left after that long); ${shareOf(b.pressure)} of that accent presses the string" + (if (b.pressure < 1f) ", the rest is speed alone" else "")

    /**
     * The most the bow's speed reaches in a stroke with bite [b], as a multiple of the sustain speed: the maximum of
     * `ramp * (1 + (max - 1) * exp(-t / tau))` that [Arco]'s own stroke computes, on its own sample grid (the stroke's attack is
     * [Arco.attackSeconds] of BOW 1, which no note on this page is short enough to cut).
     */
    private fun peakSpeed(b: Bite): Float {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val attackN = (Arco.attackSeconds(1f) * rate).toInt().coerceAtLeast(1)
        val biteN = max(attackN, (b.biteSeconds * rate).toInt())
        val tau = biteN.toDouble() / rate
        var peak = 0.0
        for (i in 0 until 14 * biteN) {
            val ramp = if (i < attackN) i.toDouble() / attackN else 1.0
            peak = max(peak, ramp * (1.0 + (b.max - 1f).toDouble() * exp(-(i.toDouble() / rate) / tau)))
        }
        return peak.toFloat()
    }

    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** MIDI note to its name, middle C (60) being C4. */
    private fun noteName(midi: Int) = NOTE_NAMES[midi % 12] + (midi / 12 - 1)

    /** MIDI note to its name as the stab figure is written: flats for the black keys (E-flat3, not D#3). */
    private fun spell(midi: Int): String {
        val name = NOTE_NAMES[midi % 12]
        val letters = "CDEFGAB"
        val flat = if (name.endsWith("#")) letters[(letters.indexOf(name[0]) + 1) % letters.length].toString() + "-flat" else name
        return flat + (midi / 12 - 1)
    }

    /** A macro value the way the app's sliders read it: `0`, `.35`, `1`. */
    private fun fmt(v: Float) = when {
        v <= 0f -> "0"
        v >= 1f -> "1"
        else -> "." + (v * 100).roundToInt().toString().padStart(2, '0')
    }

    private fun sectionJson(s: Section): String {
        val g = s.groups.joinToString(",") { grp ->
            val c = grp.clips.joinToString(",") { clip -> "[${q(clip.id)},${q(clip.name)},${q(clip.desc)}]" }
            "{\"label\":${q(grp.label)},\"key\":true,\"clips\":[$c]}"
        }
        val r = s.readout.joinToString(",") { q(it) }
        return "{\"id\":${q(s.id)},\"display\":${q(s.display)},\"body\":${q(s.body)},\"readout\":[$r],\"groups\":[$g]}"
    }

    private fun blockJson(b: Block) = "{\"hd\":${q(b.hd)},\"p\":[" + b.paragraphs.joinToString(",") { q(it) } + "]}"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
