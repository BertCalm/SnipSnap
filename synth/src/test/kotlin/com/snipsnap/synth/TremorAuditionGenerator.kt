package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the TREMOR audition (docs/superpowers/specs/2026-10-04-tremor-engine-design.md)
 * under testkit/tremor-audition/ (gitignored): the kit, each voice across its range and knobs,
 * the interaction grids, and the presets.
 *
 * Clips share one loudness ([AuditionLevel]). Run via `./gradlew :synth:generateTremorAudition`.
 * Nothing in TREMOR has been heard by anyone when this is first run.
 */
object TremorAuditionGenerator {

    private class Knob(val name: String, val low: String, val high: String)

    private val KNOBS = listOf(
        Knob("STRIKE", "a soft blow: slower, duller, the head kept broad", "a hard blow: a short, bright strike"),
        Knob("ENSEMBLE", "one drummer", "four drummers sharing one blow"),
        Knob("BEADS", "a few beads, quiet contact", "a full tray, scatter and return"),
        Knob("CAGE", "the floor of the cage, still audible", "the cage open"),
        Knob("CURRENT", "passive strings, no actuator and no fuzz", "the circuit blooming"),
        Knob("FAULT", "no load fault", "dense load faults"),
        Knob("HOLD", "a one-shot under the loop line", "the held region, a loop"),
    )

    private val BODIES = mapOf(
        TremorVoice.HIDE to "one drummer on a tuned hide",
        TremorVoice.UNISON to "up to four strikers sharing one blow",
        TremorVoice.ROLL to "beads on the internal tray",
        TremorVoice.WIRE to "the six-string cage, passive unless CURRENT is up",
        TremorVoice.CHARGE to "the cage with the circuit blooming",
        TremorVoice.FRACTURE to "load faults interrupting the bloom",
    )

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/tremor-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()

        val kitDir = File(root, "KIT")
        val kitClips = SynthKits.tremor().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the tremor kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as TremorPatch
            val tag = "A%02d".format(i + 1)
            val id = tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_')
            WavWriter.write(File(kitDir, "$id.wav"), AuditionLevel.level(arranged.snip), WavWriter.BitDepth.PCM_16)
            count++
            Clip(id, "$tag ${patch.name.uppercase()}", patch.voice.name + " " + DOT + " " + macroLine(patch.voice, patch.macros))
        }
        sections.append(
            sectionJson(
                id = "KIT", display = "THE TREMOR KIT", body = "sixteen pads as the MPC gets them",
                readout = listOf("A01-A08 HIDE UP THE MINOR PENTATONIC FROM C2", "A09-A14 ONE-SHOT PRESETS", "A15-A16 HELD PRESETS"),
                groups = listOf(
                    Group("HIDE, THE PENTATONIC", key = true, clips = kitClips.subList(0, 8)),
                    Group("ONE-SHOT PRESETS", key = true, clips = kitClips.subList(8, 14)),
                    Group("HELD PRESETS", key = true, clips = kitClips.subList(14, 16)),
                ),
            ),
        )

        for (voice in TremorVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Tremor.defaults(voice)
            fun writeSnip(id: String, snip: Snip) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
                count++
            }
            fun write(id: String, macros: Map<String, Float>) = writeSnip(id, Tremor.render(voice, defaults + macros))

            write("default", emptyMap())
            val groups = mutableListOf(Group("THE DEFAULT", key = true, clips = listOf(Clip("default", "DEFAULT", macroLine(voice, defaults)))))

            val range = listOf(0f, 0.5f, 1f).map { t ->
                val id = "tune_" + fmt(t).trimStart('.')
                write(id, mapOf("TUNE" to t))
                Clip(id, noteName(Tremor.midiFor(voice, t)), if (t == 0f) "C2, the bottom of TUNE" else if (t == 1f) "C4, the top" else "C3, the middle")
            }
            groups += Group("THE RANGE", key = true, clips = range)

            val steps = listOf(0, 3, 5, 7, 10, 7, 5, 0)
            val notes = steps.map { s -> Tremor.render(voice, defaults + mapOf("TUNE" to s / Tremor.TUNE_SEMITONES.toFloat())).samples }
            val spacing = (0.45 * Dsp.RATE).toInt()
            val mix = FloatArray(spacing * (notes.size - 1) + notes.last().size)
            for ((k, n) in notes.withIndex()) for (i in n.indices) mix[k * spacing + i] += n[i]
            writeSnip("phrase", Snip(mix, channels = 1, sampleRate = Dsp.RATE))
            groups += Group("A PHRASE", key = true, clips = listOf(Clip("phrase", "PENTATONIC", "eight notes 0.45 s apart, each ringing into the next")))

            val patch = TremorPatch("Velocity", voice, defaults)
            val vel = listOf(0.3f to "SOFT", 0.65f to "MEDIUM", 1f to "HARD").map { (v, label) ->
                val id = "velocity_" + fmt(v).trimStart('.')
                writeSnip(id, Velocity.atVelocity(patch, v))
                Clip(id, label, "velocity ${fmt(v)}: the blow, not a low-pass over a hard hit")
            }
            groups += Group("VELOCITY", key = false, clips = vel)

            for (knob in KNOBS) {
                val lo = knob.name.lowercase() + "_0"
                val hi = knob.name.lowercase() + "_1"
                write(lo, mapOf(knob.name to 0f))
                write(hi, mapOf(knob.name to 1f))
                groups += Group(
                    knob.name + " " + DOT + " DEFAULT " + fmt(defaults.getValue(knob.name)), key = false,
                    clips = listOf(Clip(lo, "${knob.name} 0", knob.low), Clip(hi, "${knob.name} 1", knob.high)),
                )
            }

            val presets = TremorPresets.forVoice(voice).map { preset ->
                val id = "preset_" + preset.name.lowercase().replace(' ', '_')
                writeSnip(id, preset.render())
                Clip(id, preset.name, noteName(Tremor.midiFor(voice, preset.macros.getValue("TUNE"))) + " " + DOT + " " + macroLine(voice, preset.macros))
            }
            groups += Group("${voice.name}'S OWN PRESETS", key = true, clips = presets)

            sections.append(",\n")
            sections.append(
                sectionJson(
                    id = voice.name, display = voice.name, body = BODIES.getValue(voice),
                    readout = listOf(
                        "C2 TO C4 " + DOT + " 24 SEMITONES",
                        "STRIKE " + fmt(defaults.getValue("STRIKE")) + " " + DOT + " BEADS " + fmt(defaults.getValue("BEADS")) + " " + DOT + " CURRENT " + fmt(defaults.getValue("CURRENT")),
                    ),
                    groups = groups,
                ),
            )
        }

        val gridDir = File(root, "GRIDS")
        val grids = listOf(
            Triple(TremorVoice.UNISON, "ENSEMBLE" to "BEADS", "more hands into a heavier tray"),
            Triple(TremorVoice.ROLL, "STRIKE" to "BEADS", "how hard the blow is, against how many beads it throws"),
            Triple(TremorVoice.WIRE, "CAGE" to "CURRENT", "a passive cage against a powered one"),
            Triple(TremorVoice.FRACTURE, "CURRENT" to "FAULT", "faults with the circuit off, and with it on"),
            Triple(TremorVoice.ROLL, "BEADS" to "CAGE", "the tray against the strings it drives"),
        )
        val gridGroups = grids.map { (voice, pair, what) ->
            val (a, b) = pair
            val clips = listOf(0f, 0.5f, 1f).flatMap { va ->
                listOf(0f, 0.5f, 1f).map { vb ->
                    val id = "${voice.name.lowercase()}_${a.lowercase()}${fmt(va).trimStart('.')}_${b.lowercase()}${fmt(vb).trimStart('.')}"
                    val snip = Tremor.render(voice, Tremor.defaults(voice) + mapOf(a to va, b to vb))
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
                id = "GRIDS", display = "THE INTERACTIONS", body = "five grids: each axis should change the instrument, not only its level",
                readout = listOf("EACH 3 × 3 " + DOT + " 0, .5, 1", "THE REST OF THE KNOBS AT THE VOICE'S DEFAULTS"),
                groups = gridGroups,
            ),
        )

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n]}\n")
        val page = TremorAuditionGenerator::class.java.getResourceAsStream("/audition/tremor-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "·"

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private fun noteName(midi: Int) = NOTE_NAMES[midi % 12] + (midi / 12 - 1)

    private fun macroLine(voice: TremorVoice, macros: Map<String, Float>): String {
        val d = Tremor.defaults(voice)
        val moved = Tremor.macrosFor(voice).map { it.name }.filter { (macros[it] ?: d.getValue(it)) != d.getValue(it) }
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
