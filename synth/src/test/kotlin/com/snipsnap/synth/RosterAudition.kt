package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ArrangedPad
import java.io.File
import kotlin.math.roundToInt

/**
 * One audition generator for any registered engine, so an engine without a
 * hand-made listening page gets the same page the newer engines have, from a
 * spec of words rather than a copy of a 190-line generator and an 800-line page.
 *
 * What it renders, per voice the factory presets name: the voice's default,
 * every macro at both ends with the rest at their defaults (TUNE at its bottom,
 * middle and top, which is the "voice at three notes" row of the house
 * audition), and every factory preset. Then, when [Spec.kit] is given, the
 * sixteen pads as the MPC gets them. Clips share one loudness ([AuditionLevel]).
 *
 * The page is `audition/roster-audition.html`, built from the manifest this
 * writes: the clip list, and the title, guide, questions and save key from the
 * [Spec]. The engine is found by its `ENGINE` tag through [Presets] and
 * [Velocity.macroSpecsFor], so nothing here names an engine's constants.
 *
 * Every default is taken from the voice's first factory preset with its macros
 * reset to the macro specs' defaults, so a preset's saved note or velocity
 * (where the patch carries one) stays as the preset authored it.
 */
internal object RosterAudition {

    /** What one engine's page says, in the engine's own words. */
    class Spec(
        val engine: String,
        val title: String,
        val tag: String,
        val guide: List<String>,
        val questions: List<String>,
        /** A line per voice under its heading: what the voice is. */
        val voiceNotes: Map<String, String> = emptyMap(),
        /** Per macro, what its low and high ends do, written for the listener. */
        val knobNotes: Map<String, Pair<String, String>> = emptyMap(),
        val kit: (() -> List<ArrangedPad?>)? = null,
        val kitNote: String = "sixteen pads as the MPC gets them",
        val phase: String = "r1",
    )

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    /** Renders [spec]'s clips, manifest and page under [root]; returns the clip count. */
    fun write(spec: Spec, root: File): Int {
        root.mkdirs()
        var count = 0
        val sections = mutableListOf<String>()
        val presets = Presets.all().filter { it.engine == spec.engine }
        require(presets.isNotEmpty()) { "${spec.engine} has no factory presets to audition" }

        spec.kit?.let { kitOf ->
            val dir = File(root, "KIT")
            val clips = kitOf().mapIndexedNotNull { i, pad ->
                pad ?: return@mapIndexedNotNull null
                val recipe = pad.recipe?.let { PadRecipe.fromJsonValue(it) }
                val patch = recipe?.patch
                val tag = "A%02d".format(i + 1)
                val name = patch?.name ?: "pad"
                val id = tag.lowercase() + "_" + slug(name)
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(pad.snip), WavWriter.BitDepth.PCM_16)
                count++
                Clip(id, "$tag ${name.uppercase()}", listOfNotNull(patch?.voiceName, pad.drumClass.name).joinToString(" $DOT "))
            }
            sections += sectionJson("KIT", "THE ${spec.engine} KIT", spec.kitNote, listOf("${clips.size} PADS"),
                listOf(Group("THE KIT, A01 ONWARD", key = true, clips = clips)))
        }

        for ((voiceName, voicePresets) in presets.groupBy { it.voiceName }) {
            val dir = File(root, voiceName)
            val base = voicePresets.first()
            val specs = Velocity.macroSpecsFor(base)
            val defaults = specs.associate { it.name to it.default }
            fun write(id: String, patch: Patch) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(patch.render()), WavWriter.BitDepth.PCM_16)
                count++
            }
            val groups = mutableListOf<Group>()
            write("default", base.withMacros(defaults))
            groups += Group("THE DEFAULT", key = true, clips = listOf(Clip("default", "DEFAULT", "every knob at its default")))
            for (spec2 in specs) {
                val knob = spec2.name
                val ends = if (knob == "TUNE") listOf(0f, .5f, 1f) else listOf(0f, 1f)
                val notes = spec.knobNotes[knob]
                val clips = ends.map { v ->
                    val id = slug(knob) + "_" + fmt(v).replace(".", "p")
                    write(id, base.withMacros(defaults + (knob to v)))
                    val desc = when {
                        knob == "TUNE" -> when (v) { 0f -> "the lowest note TUNE reaches"; 1f -> "the highest note TUNE reaches"; else -> "the middle of TUNE's travel" }
                        notes == null -> "$knob at ${fmt(v)}, the rest at their defaults"
                        v == 0f -> notes.first
                        else -> notes.second
                    }
                    Clip(id, "$knob ${fmt(v)}", desc)
                }
                groups += Group("$knob $DOT DEFAULT ${fmt(spec2.default)}", key = knob == "TUNE", clips = clips)
            }
            groups += Group("$voiceName'S OWN PRESETS", key = true, clips = voicePresets.map { preset ->
                val id = "preset_" + slug(preset.name)
                write(id, preset)
                Clip(id, preset.name.uppercase(), macroLine(preset.macros, defaults))
            })
            sections += sectionJson(voiceName, voiceName, spec.voiceNotes[voiceName] ?: "",
                listOf("${voicePresets.size} PRESETS", "${specs.size} KNOBS"), groups)
        }

        val page = "{\"title\":${q(spec.title)},\"tag\":${q(spec.tag)}," +
            "\"guide\":[${spec.guide.joinToString(",") { q(it) }}]," +
            "\"questions\":[${spec.questions.joinToString(",") { q(it) }}]," +
            "\"foot\":${q("RENDERED BY generate${spec.engine.lowercase().replaceFirstChar { it.uppercase() }}Audition $DOT ONE LOUDNESS FOR EVERY CLIP $DOT 16-BIT 44.1 KHZ $DOT CHIP TAPS AND NOTES SAVE ON THEIR OWN")}," +
            "\"phase\":${q(spec.phase)},\"store\":${q(spec.engine.lowercase() + "-audition")}}"
        File(root, "manifest.json").writeText("{\"page\": $page,\n\"voices\": [\n${sections.joinToString(",\n")}\n]}\n")
        val html = RosterAudition::class.java.getResourceAsStream("/audition/roster-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> html.use { it.copyTo(out) } }
        return count
    }

    /** Renders [spec] under the first argument or `../testkit/<engine>-audition` and says what it wrote. */
    fun main(spec: Spec, args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/${spec.engine.lowercase()}-audition")
        val count = write(spec, root)
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "·"

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun macroLine(macros: Map<String, Float>, defaults: Map<String, Float>): String {
        val moved = defaults.keys.filter { (macros[it] ?: defaults.getValue(it)) != defaults.getValue(it) }
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
        return "{\"id\":${q(id)},\"display\":${q(display)},\"body\":${q(body)},\"readout\":[${readout.joinToString(",") { q(it) }}],\"groups\":[$g]}"
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
