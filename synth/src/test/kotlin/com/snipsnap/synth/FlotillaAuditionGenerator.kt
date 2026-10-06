package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the FLOTILLA audition under testkit/flotilla-audition/ (gitignored):
 * the sixteen-pad kit, and for each voice its default, MIDI 36/60/84, each knob
 * at both ends, two velocities, and that voice's presets, then four interaction
 * grids. Clips share one loudness. Nothing here has been heard when this is first run.
 *
 * Run via `./gradlew :synth:generateFlotillaAudition`.
 */
object FlotillaAuditionGenerator {

    private class Knob(val name: String, val low: String, val high: String)

    private val KNOBS = listOf(
        Knob("PULSE", "a slow swell, darker partials", "a steep onset and a brighter tilt"),
        Knob("CROSSING", "one route across the pool", "all four routes, vessels meeting on the crossings"),
        Knob("FLOTILLA", "six vessels", "eighteen vessels, the pool crowded"),
        Knob("VESSEL", "small hulls, a higher cavity", "large hulls, slower, a lower cavity"),
        Knob("SURFACE", "a quiet pool that still moves", "a strong surface, more contacts"),
        Knob("SKIN", "a thin membrane over the note", "a warm dome"),
        Knob("HOLD", "a one-shot that rings out", "a stationary held loop, cut on its period"),
    )

    private val BODIES = mapOf(
        FlotillaVoice.RIPPLE to "a clear wake: a few vessels, a light surface, the note easy to hear",
        FlotillaVoice.KNOCK to "wood against wood: brighter contacts, a harder pulse",
        FlotillaVoice.HOLLOW to "a large cavity: the hull's air tone under the note",
        FlotillaVoice.CROSSWAVE to "routes that cross: vessels meeting where the paths overlap",
        FlotillaVoice.DRIFT to "a gentle current under a warm dome",
        FlotillaVoice.GATHER to "many vessels drawn toward the middle",
    )

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/flotilla-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()

        val kitDir = File(root, "KIT")
        val kitClips = SynthKits.flotilla().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the flotilla kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as FlotillaPatch
            val tag = "A%02d".format(i + 1)
            val id = tag.lowercase() + "_" + slug(patch.name)
            WavWriter.write(File(kitDir, "$id.wav"), AuditionLevel.level(arranged.snip), WavWriter.BitDepth.PCM_16)
            count++
            Clip(id, "$tag ${patch.name.uppercase()}", patch.voice.name + " " + DOT + " MIDI ${patch.midi} " + DOT + " " + macroLine(patch.voice, patch.macros))
        }
        sections.append(
            sectionJson(
                id = "KIT",
                display = "THE FLOTILLA KIT",
                body = "sixteen pads as the MPC gets them",
                readout = listOf(
                    "A01-A08 RIPPLE UP THE MINOR PENTATONIC FROM C4",
                    "A09-A16 WOOD, HOLLOW, CROSSING, DOME, DRIFT, GATHER, TWO HELD NOTES",
                ),
                groups = listOf(
                    Group("RIPPLE, THE PENTATONIC", key = true, clips = kitClips.subList(0, 8)),
                    Group("THE OTHER EIGHT", key = true, clips = kitClips.subList(8, 16)),
                ),
            ),
        )

        for (voice in FlotillaVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Flotilla.defaults(voice)
            fun writeSnip(id: String, snip: Snip) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
                count++
            }
            fun write(id: String, macros: Map<String, Float>, midi: Int = Flotilla.DEFAULT_MIDI) =
                writeSnip(id, Flotilla.render(voice, defaults + macros, midi))

            write("default", emptyMap())
            val groups = mutableListOf(Group("THE DEFAULT", key = true, clips = listOf(Clip("default", "DEFAULT", macroLine(voice, defaults)))))

            val range = listOf(36 to "C2", 60 to "C4", 84 to "C6").map { (midi, label) ->
                val id = "midi_$midi"
                write(id, emptyMap(), midi)
                Clip(id, label, "MIDI $midi")
            }
            groups += Group("THE RANGE", key = true, clips = range)

            val patch = FlotillaPatch("Velocity", voice, defaults)
            val vel = listOf(0.25f to "SOFT", 1f to "HARD").map { (v, label) ->
                val id = "velocity_" + fmt(v).trimStart('.')
                writeSnip(id, Velocity.atVelocity(patch, v))
                Clip(id, label, "velocity ${fmt(v)}: energy, steepness and motion, not a louder copy")
            }
            groups += Group("VELOCITY", key = false, clips = vel)

            for (knob in KNOBS) {
                val lo = knob.name.lowercase() + "_0"
                val hi = knob.name.lowercase() + "_1"
                write(lo, mapOf(knob.name to 0f))
                write(hi, mapOf(knob.name to 1f))
                groups += Group(
                    knob.name + " " + DOT + " DEFAULT " + fmt(defaults.getValue(knob.name)),
                    key = false,
                    clips = listOf(Clip(lo, "${knob.name} 0", knob.low), Clip(hi, "${knob.name} 1", knob.high)),
                )
            }

            val presets = FlotillaPresets.forVoice(voice).map { preset ->
                val id = "preset_" + slug(preset.name)
                writeSnip(id, preset.render())
                Clip(id, preset.name, macroLine(voice, preset.macros))
            }
            groups += Group("${voice.name}'S OWN PRESETS", key = true, clips = presets)

            sections.append(",\n")
            sections.append(
                sectionJson(
                    id = voice.name,
                    display = voice.name,
                    body = BODIES.getValue(voice),
                    readout = listOf("C2, C4, C6", "SEVEN KNOBS, BOTH ENDS"),
                    groups = groups,
                ),
            )
        }

        val gridDir = File(root, "GRIDS")
        val grids = listOf(
            Triple(FlotillaVoice.CROSSWAVE, "CROSSING" to "SURFACE", "more routes on a stronger surface"),
            Triple(FlotillaVoice.GATHER, "FLOTILLA" to "VESSEL", "density against hull size"),
            Triple(FlotillaVoice.RIPPLE, "PULSE" to "SKIN", "the source's tilt under the dome"),
            Triple(FlotillaVoice.HOLLOW, "VESSEL" to "SURFACE", "a large hull on a moving pool"),
        )
        val gridGroups = grids.map { (voice, pair, what) ->
            val (a, b) = pair
            val clips = listOf(0f, 0.5f, 1f).flatMap { va ->
                listOf(0f, 0.5f, 1f).map { vb ->
                    val id = "${voice.name.lowercase()}_${a.lowercase()}${fmt(va).trimStart('.')}_${b.lowercase()}${fmt(vb).trimStart('.')}"
                    val snip = Flotilla.render(voice, Flotilla.defaults(voice) + mapOf(a to va, b to vb))
                    WavWriter.write(File(gridDir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
                    count++
                    Clip(id, "$a ${fmt(va)} $b ${fmt(vb)}", voice.name)
                }
            }
            Group("${voice.name}: $a × $b, $what", key = true, clips = clips)
        }
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "GRIDS",
                display = "THE INTERACTIONS",
                body = "four corners the design claims should change the scene, not only the level",
                readout = listOf("EACH 3 × 3 " + DOT + " 0, .5, 1"),
                groups = gridGroups,
            ),
        )

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n]}\n")
        val page = FlotillaAuditionGenerator::class.java.getResourceAsStream("/audition/flotilla-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "·"

    private fun slug(name: String) = name.lowercase().replace(' ', '_')

    private fun macroLine(voice: FlotillaVoice, macros: Map<String, Float>): String {
        val d = Flotilla.defaults(voice)
        val moved = Flotilla.macrosFor(voice).map { it.name }.filter { (macros[it] ?: d.getValue(it)) != d.getValue(it) }
        return if (moved.isEmpty()) "every knob at its default" else moved.joinToString(" ") { "$it ${fmt(macros.getValue(it))}" }
    }

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
