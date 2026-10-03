package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Renders the TERRA audition (the audition gate `docs/SYNTH_ROADMAP.md`
 * calls for on every new engine, and TERRA shipped in PR #371 without
 * one) under testkit/terra-audition/ (gitignored): the sixteen-pad kit as
 * it lands on the MPC, then for each of the four topologies its default,
 * DECAY/FORCE/POS at both ends, and that topology's own extra macro
 * (DROOP, CAVITY+BUZZ, CLACK, or BUZZ) at both ends. TUNE is not a knob to
 * audition here, same reasoning as FORK's own STRIKE exclusion - it's a
 * note, not a sound-design axis. Clips share one loudness ([AuditionLevel]).
 * Writes `manifest.json`, which the page builds itself from, then copies
 * the listening page from the test resources. Run via
 * `./gradlew :synth:generateTerraAudition`, then publish the folder as the
 * listening artifact. With a second argument `r1` it renders R1's page
 * instead ([renderR1]): HIT in the engine, ten clips and three questions.
 * With `r1b` it renders R1b's page ([renderR1B]): HIT's ladder and its
 * floor, nine clips and two questions.
 */
object TerraAuditionGenerator {

    /**
     * The floors R1b's page offered: none, -18 and -12 dB. The owner picked
     * -12 dB (2026-10-02), which is [Terra.HIT_FLOOR]; the list stays so the
     * page renders the same nine clips. There is no -6 dB (0.5) choice: at
     * phi 0.5 the bell's coupling bar fails (the two heads sit 2.50 dB apart,
     * under the 3 dB the test holds) and the cavity's tanh drive passes its
     * cap (0.3661 against 0.36). The controller ruled it out, 2026-10-02.
     * Internal, not private, because TerraTest's floor tests iterate it.
     */
    internal val R1B_FLOOR_CHOICES = listOf(0f, 0.125f, 0.25f)

    private class Knob(val name: String, val low: String, val high: String)

    private val DECAY_KNOB = Knob("DECAY", "the shortest ring: 0.08s at the fundamental", "the longest ring: 0.9s at the fundamental")
    private val POS_KNOB = Knob("POS", "struck at the center: fewer overtones, a warmer tone", "struck at the rim: more overtones, a brighter tone")
    private val FORCE_FLESH_PALM = Knob("FORCE", "a soft palm push: a long, broad pulse, no noise", "a hard palm strike: a short, sharp pulse, still no noise")
    private val FORCE_HARD_STICK = Knob("FORCE", "a soft mallet: a longer tick, almost no noise", "a hard mallet: a short tick with a full noise component")

    private val EXTRA_KNOBS = mapOf(
        TerraVoice.COMPOUND_MEMBRANE to listOf(
            Knob("DROOP", "no pitch sag: the fundamental holds steady from the strike", "a hard downward pitch sag right at the strike, settling back over ~20ms"),
        ),
        TerraVoice.RESONANT_CAVITY to listOf(
            Knob("DROOP", "no pitch sag: the fundamental holds steady from the strike", "a hard downward pitch sag right at the strike, settling back over ~20ms"),
            Knob("CAVITY", "dry: no Helmholtz air-cavity resonance", "full coupling: the deep cavity resonance mixed fully into the strike"),
            Knob("BUZZ", "clean: no parasitic rattle", "full parasitic contact buzz: the loose boundary rattling against the body"),
        ),
        TerraVoice.CONICAL_BELL to listOf(
            Knob("CLACK", "a plain strike: no pre-roll", "the full pre-strike squeeze: a quiet click, then the bell rings fresh"),
        ),
        TerraVoice.TUNED_BAR to listOf(
            Knob("BUZZ", "clean: no parasitic rattle", "full parasitic contact buzz: the loose boundary rattling against the body"),
        ),
    )

    private val BODIES = mapOf(
        TerraVoice.COMPOUND_MEMBRANE to "djembe, dholak, dumbek, tabla — a hand-struck membrane (FLESH_PALM)",
        TerraVoice.RESONANT_CAVITY to "udu, cajón — a hand-struck membrane coupled to a Helmholtz air cavity",
        TerraVoice.CONICAL_BELL to "agogô — a mallet-struck forged cone (HARD_STICK)",
        TerraVoice.TUNED_BAR to "balafon — a mallet-struck wooden bar (HARD_STICK)",
    )

    // TUNE's own expMap range per voice (Terra.kt's compoundMembrane/
    // resonantCavity/conicalBell/tunedBar) - shown in the readout, not swept.
    private val TUNE_RANGE_HZ = mapOf(
        TerraVoice.COMPOUND_MEMBRANE to (55f to 440f),
        TerraVoice.RESONANT_CAVITY to (45f to 300f),
        TerraVoice.CONICAL_BELL to (500f to 950f),
        TerraVoice.TUNED_BAR to (180f to 400f),
    )

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/terra-audition")
        root.mkdirs()
        if (args.getOrNull(1) == "r1") {
            renderR1(root)
            return
        }
        if (args.getOrNull(1) == "r1b") {
            renderR1B(root)
            return
        }
        var count = 0
        val sections = StringBuilder()

        // The kit, as it lands: TerraKits.classic() is the one list of what
        // is on which pad, and each pad's own recipe names its patch.
        val kitDir = File(root, "KIT")
        val kitClips = TerraKits.classic().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the terra kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as TerraPatch
            val tag = "A%02d".format(i + 1)
            val id = tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_')
            WavWriter.write(File(kitDir, "$id.wav"), AuditionLevel.level(arranged.snip), WavWriter.BitDepth.PCM_16)
            count++
            Clip(id, "$tag ${patch.name.uppercase()}", arranged.drumClass.name + " " + DOT + " " + macroLine(patch.voice, patch.macros))
        }
        sections.append(
            sectionJson(
                id = "KIT", display = "THE TERRA KIT", body = "sixteen pads as the MPC gets them",
                readout = listOf("A01-A12 MEMBRANE + CAVITY (A07 IS BAR)", "A13-A16 BELL + BAR"),
                groups = listOf(Group("ALL SIXTEEN PADS", key = true, clips = kitClips)),
            ),
        )

        for (voice in TerraVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Terra.defaults(voice)
            fun write(id: String, macros: Map<String, Float>) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(Terra.render(voice, macros)), WavWriter.BitDepth.PCM_16)
                count++
            }

            write("default", emptyMap())
            val groups = mutableListOf(
                Group("THE DEFAULT", key = true, clips = listOf(Clip("default", "DEFAULT", macroLine(voice, emptyMap())))),
            )

            val forceKnob = if (voice == TerraVoice.CONICAL_BELL || voice == TerraVoice.TUNED_BAR) FORCE_HARD_STICK else FORCE_FLESH_PALM
            val knobs = listOf(DECAY_KNOB, forceKnob, POS_KNOB) + EXTRA_KNOBS.getValue(voice)
            for (knob in knobs) {
                val lo = knob.name.lowercase() + "_0"
                val hi = knob.name.lowercase() + "_1"
                write(lo, mapOf(knob.name to 0f))
                write(hi, mapOf(knob.name to 1f))
                groups += Group(
                    knob.name + " " + DOT + " DEFAULT " + fmt(defaults.getValue(knob.name)), key = false,
                    clips = listOf(Clip(lo, "${knob.name} 0", knob.low), Clip(hi, "${knob.name} 1", knob.high)),
                )
            }

            val (loHz, hiHz) = TUNE_RANGE_HZ.getValue(voice)
            if (sections.isNotEmpty()) sections.append(",\n")
            sections.append(
                sectionJson(
                    id = voice.name, display = voice.name, body = BODIES.getValue(voice),
                    readout = listOf(
                        "TUNE " + loHz.roundToInt() + "-" + hiHz.roundToInt() + "HZ",
                        knobs.joinToString(" " + DOT + " ") { it.name + " " + fmt(defaults.getValue(it.name)) },
                    ),
                    groups = groups,
                ),
            )
        }

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n]}\n")
        val page = TerraAuditionGenerator::class.java.getResourceAsStream("/audition/terra-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "·"

    /** The knobs that differ from the voice's defaults, the way the app's sliders read them. */
    private fun macroLine(voice: TerraVoice, macros: Map<String, Float>): String {
        val d = Terra.defaults(voice)
        val moved = Terra.macrosFor(voice).map { it.name }.filter { (macros[it] ?: d.getValue(it)) != d.getValue(it) }
        return if (moved.isEmpty()) "every knob at its default" else moved.joinToString(" ") { "$it ${fmt(macros.getValue(it))}" }
    }

    /** A macro value the way the app's sliders read it: `0`, `.35`, `1`. */
    private fun fmt(v: Float) = when {
        v <= 0f -> "0"
        v >= 1f -> "1"
        else -> "." + (v * 100).roundToInt().toString().padStart(2, '0')
    }

    private fun sectionJson(id: String, display: String, body: String, readout: List<String>, groups: List<Group>): String {
        val g = groups.joinToString(",") { grp ->
            val c = grp.clips.joinToString(",") { "[${q(it.id)},${q(it.name)},${q(it.desc)}]" }
            "{\"label\":${q(grp.label)},\"key\":${grp.key},\"clips\":[$c]}"
        }
        val r = readout.joinToString(",") { q(it) }
        return "{\"id\":${q(id)},\"display\":${q(display)},\"body\":${q(body)},\"readout\":[$r],\"groups\":[$g]}"
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * R1's page (docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md,
     * "Phasing and gates", "R1's page: HIT in the engine"): ten clips and
     * three questions under [root]/R1, with their own manifest. Every clip
     * is the render a struck pad's recipe gives, `TerraPatch.render`. Each
     * striker is captured by `Terra.captureStriker`, as the phone will
     * capture it. The BUZZ pad is A07 CAJON SLAP, the kit's most BUZZ: 0.75,
     * against A16's 0.60 and A04's 0.15.
     */
    private fun renderR1(root: File) {
        val dir = File(root, "R1").apply { mkdirs() }
        val sources = TerraStrikers.ten(File(root.parentFile, "Expansions/SnipSnap Factory/Samples")).associateBy { it.id }
        fun struck(id: String, hit: Float): TerraPatch.Striker {
            val s = sources.getValue(id)
            val head = Terra.captureStriker(s.snip) ?: error("${s.name} captured as silence")
            return TerraPatch.Striker(head, hit, s.name)
        }
        val a07 = requireNotNull(TerraKits.classic()[6]) { "the terra kit has no A07" }
        val buzzPad = PadRecipe.fromJsonValue(requireNotNull(a07.recipe) { "A07 carries no recipe" }).patch as TerraPatch
        check(buzzPad.name == "Cajon Slap" && buzzPad.macros["BUZZ"] == 0.75f) { "A07 is ${buzzPad.name} ${buzzPad.macros}, not the BUZZ pad this page names" }
        val membrane = TerraPatch("Membrane", TerraVoice.COMPOUND_MEMBRANE, emptyMap())
        val cavity = TerraPatch("Cavity", TerraVoice.RESONANT_CAVITY, emptyMap())
        val bell = TerraPatch("Bell", TerraVoice.CONICAL_BELL, emptyMap())
        val bar = TerraPatch("Bar", TerraVoice.TUNED_BAR, emptyMap())
        class R1Clip(val id: String, val label: String, val why: String, val patch: TerraPatch)
        val clips = listOf(
            R1Clip("01_membrane_today", "MEMBRANE $DOT TODAY", "the anchor", membrane),
            R1Clip("02_membrane_hit05_thump_snare", "MEMBRANE $DOT HIT .5 $DOT THUMP SNARE", "subtle, bright head", membrane.copy(striker = struck("tsnare", 0.5f))),
            R1Clip("03_membrane_hit1_thump_snare", "MEMBRANE $DOT HIT 1 $DOT THUMP SNARE", "strong, bright head", membrane.copy(striker = struck("tsnare", 1f))),
            R1Clip("04_membrane_hit1_factory_kick", "MEMBRANE $DOT HIT 1 $DOT FACTORY KICK", "strong, bass head (the soft attack)", membrane.copy(striker = struck("kick01", 1f))),
            R1Clip("05_cavity_today", "CAVITY $DOT TODAY", "the anchor", cavity),
            R1Clip("06_cavity_hit05_wraith_word", "CAVITY $DOT HIT .5 $DOT WRAITH WORD", "the cavity under a bright head at subtle (+8 dB; THUMP SNARE moves it +12)", cavity.copy(striker = struck("wraith", 0.5f))),
            R1Clip("07_bell_hit05_factory_clap", "BELL $DOT HIT .5 $DOT FACTORY CLAP", "a comb-shaped head on a bright voice", bell.copy(striker = struck("clap01", 0.5f))),
            R1Clip("08_bar_hit05_beatbox_rim", "BAR $DOT HIT .5 $DOT BEATBOX RIM", "the bar's brightest striker", bar.copy(striker = struck("bbrim", 0.5f))),
            R1Clip("09_kit_a07_today", "TERRA KIT A07 CAJON SLAP $DOT TODAY", "BUZZ as tuned (0.75, the kit's most)", buzzPad),
            R1Clip("10_kit_a07_hit1_factory_hat", "THE SAME PAD $DOT HIT 1 $DOT FACTORY HAT", "BUZZ following a bright, light head (decision 2's default)", buzzPad.copy(striker = struck("hat01", 1f))),
        )
        val questions = listOf(
            "Does HIT move from today through subtle to strong in steps you can hear, on every voice?",
            "Is any voice wrong at HIT .5 (the bell or bar going thin under a dark head), so that HIT needs a floor?",
            "Should the rattle follow the striker (clip 10, the default) or stay as today (decision 2)?",
        )
        check(clips.size <= 10 && questions.size <= 3) { "a gate page leads with at most ten clips and three questions" }
        val entries = clips.map { c ->
            WavWriter.write(File(dir, "${c.id}.wav"), AuditionLevel.level(c.patch.render()), WavWriter.BitDepth.PCM_16)
            "{\"id\":${q(c.id)},\"file\":${q("${c.id}.wav")},\"label\":${q(c.label)},\"why\":${q(c.why)}}"
        }
        File(dir, "manifest.json").writeText(
            "{\"page\":\"R1\",\"title\":\"HIT IN THE ENGINE\",\"clips\":[\n" + entries.joinToString(",\n") +
                "\n],\"questions\":[\n" + questions.joinToString(",\n") { q(it) } + "\n]}\n",
        )
        println("terra R1: ${clips.size} clips and ${questions.size} questions under ${dir.absolutePath}")
    }

    /**
     * R1b's page (docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md,
     * "Phasing and gates", "R1b's page: HIT's ladder and its floor"): nine clips
     * and two questions under [root]/R1B, with a manifest in R1's shape.
     * - The ladder: COMPOUND_MEMBRANE struck by THUMP SNARE at HIT 0, .25, .5,
     *   .75 and 1. Each clip is the render its recipe gives
     *   (`TerraPatch.render`), so at the floor default. HIT 0 is a striker at
     *   strength 0, today's drum.
     * - The floor: TUNED_BAR today, then at HIT 1 struck by the factory kick
     *   (A01_Kick_01.wav, a dark head) at each of [R1B_FLOOR_CHOICES].
     *   These render through `Terra.renderStruck`'s floor overload, because a
     *   recipe carries no floor.
     * Each floor clip's per-mode levels against today's are measured on the
     * bank, before the output chain (which renormalises). They are printed
     * and written into the clip's line. Each floor's held tone must differ
     * from the next one's by at least 0.5 dB on some mode, or the second
     * question has one answer. (Their first moments always differ, because
     * `|P_k|` rises through the head under every floor, so a sample check
     * would prove nothing.)
     */
    private fun renderR1B(root: File) {
        val dir = File(root, "R1B").apply { mkdirs() }
        val sources = TerraStrikers.ten(File(root.parentFile, "Expansions/SnipSnap Factory/Samples")).associateBy { it.id }
        fun head(id: String): FloatArray {
            val s = sources.getValue(id)
            return Terra.captureStriker(s.snip) ?: error("${s.name} captured as silence")
        }
        class R1BClip(val id: String, val label: String, val why: String, val snip: Snip)

        class Step(val hit: Float, val shown: String, val tag: String, val why: String)
        val snare = head("tsnare")
        val snareName = sources.getValue("tsnare").name
        val membrane = TerraPatch("Membrane", TerraVoice.COMPOUND_MEMBRANE, emptyMap())
        val ladder = listOf(
            Step(0f, "0", "hit0", "the ladder's foot: today's drum"),
            Step(0.25f, ".25", "hit025", "a quarter"),
            Step(0.5f, ".5", "hit05", "subtle"),
            Step(0.75f, ".75", "hit075", "three quarters"),
            Step(1f, "1", "hit1", "strong; HIT 1 now keeps today's attack (the soft attack R1's page had is gone at the default floor)"),
        ).mapIndexed { i, st ->
            R1BClip(
                "%02d_membrane_%s_thump_snare".format(i + 1, st.tag),
                "MEMBRANE $DOT HIT ${st.shown} $DOT THUMP SNARE",
                st.why,
                membrane.copy(striker = TerraPatch.Striker(snare, st.hit, snareName)).render(),
            )
        }

        class Floor(val phi: Float, val label: String, val tag: String, val why: String)
        val floors = listOf(
            Floor(0f, "NO FLOOR", "no_floor", "R1's HIT under a dark head"),
            Floor(0.125f, "FLOOR -18 DB", "floor_18db", "a floor at -18 dB"),
            Floor(0.25f, "FLOOR -12 DB", "floor_12db", "a floor at -12 dB, the default"),
        )
        check(floors.map { it.phi } == R1B_FLOOR_CHOICES) { "the page's floors are not R1B_FLOOR_CHOICES" }
        val bar = TerraVoice.TUNED_BAR
        val kick = head("kick01")
        val body = TerraMeasure.bodyOf(bar)
        val x = Terra.upsample(kick)
        val today = TerraMeasure.modeLevels(Terra.bankWith(bar, emptyMap(), null), body, TerraMeasure.MODE_FROM_FRAMES, TerraMeasure.MODE_WINDOW)
        val held = floors.map { f -> TerraMeasure.modeLevels(Terra.bankStruckAt(bar, emptyMap(), x, 1f, f.phi), body, TerraMeasure.MODE_FROM_FRAMES, TerraMeasure.MODE_WINDOW) }
        val floorClips = floors.mapIndexed { i, f ->
            val struck = held[i]
            val levels = today.indices.joinToString(" / ") { k -> "%+.1f".format(20.0 * log10(maxOf(struck[k] / today[k], 1e-9))) }
            println("terra R1B floor ${f.phi} (${f.label}): bar modes 1-${today.size} at $levels dB against today's, on the bank before the output chain")
            R1BClip(
                "%02d_bar_hit1_factory_kick_%s".format(7 + i, f.tag),
                "BAR $DOT HIT 1 $DOT FACTORY KICK $DOT ${f.label}",
                "${f.why}; modes 1-${today.size} at $levels dB against today's (the bank, before the output chain)",
                Terra.renderStruck(bar, emptyMap(), kick, 1f, f.phi),
            )
        }
        // Two floors always differ in the first moments, where |P_k| is still rising
        // under every floor, so the samples prove nothing. The held tone must differ:
        // at least one mode 0.5 dB apart between each floor and the next.
        for (i in 0 until floors.size - 1) {
            val apart = today.indices.maxOf { k -> abs(20.0 * log10(maxOf(held[i + 1][k], 1e-12) / maxOf(held[i][k], 1e-12))) }
            check(apart >= 0.5) {
                "${floors[i].label} and ${floors[i + 1].label} hold every bar mode within ${"%.2f".format(apart)} dB of each other: the floor never bites on the held tone under the factory kick"
            }
        }

        val clips = ladder + R1BClip("06_bar_today", "BAR $DOT TODAY", "the floor's anchor", Terra.render(bar)) + floorClips
        val questions = listOf(
            "Can you hear the steps from 0 to 1 on the ladder?",
            "Which floor keeps the bar sounding right with a dark hit: none, -18 or -12 dB?",
        )
        check(clips.size <= 10 && questions.size <= 3) { "a gate page leads with at most ten clips and three questions" }
        val entries = clips.map { c ->
            WavWriter.write(File(dir, "${c.id}.wav"), AuditionLevel.level(c.snip), WavWriter.BitDepth.PCM_16)
            "{\"id\":${q(c.id)},\"file\":${q("${c.id}.wav")},\"label\":${q(c.label)},\"why\":${q(c.why)}}"
        }
        File(dir, "manifest.json").writeText(
            "{\"page\":\"R1B\",\"title\":\"HIT'S STEPS AND ITS FLOOR\",\"clips\":[\n" + entries.joinToString(",\n") +
                "\n],\"questions\":[\n" + questions.joinToString(",\n") { q(it) } + "\n]}\n",
        )
        println("terra R1B: ${clips.size} clips and ${questions.size} questions under ${dir.absolutePath}")
    }
}
