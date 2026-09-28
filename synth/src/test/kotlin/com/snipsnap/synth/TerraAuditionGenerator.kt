package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
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
 * listening artifact.
 */
object TerraAuditionGenerator {

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
}
