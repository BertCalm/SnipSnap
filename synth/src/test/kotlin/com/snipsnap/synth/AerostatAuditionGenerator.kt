package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the AEROSTAT audition (docs/superpowers/specs/2026-10-04-aerostat-engine-design.md,
 * the S24 gate) under testkit/aerostat-audition/ (gitignored): the kit, the diagnostic taps,
 * low/mid/high notes, each macro at 0/.25/.5/.75/1, the five presets, the held loop, a phrase
 * on one reservoir, raw against the engine's own levelling, and the two interaction grids.
 *
 * Clips share one loudness ([AuditionLevel]), including the raw clip, so a control that only
 * got quieter cannot hide. It writes `manifest.json`, which the page builds itself from, then
 * copies the listening page. Run via `./gradlew :synth:generateAerostatAudition`. Nothing in
 * AEROSTAT has been heard by anyone when this is first run.
 */
object AerostatAuditionGenerator {

    private val voice = AerostatVoice.FLOAT
    private val defaults = Aerostat.defaults(voice)

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/aerostat-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()

        fun write(dir: File, id: String, snip: Snip) {
            WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            count++
        }

        val kitDir = File(root, "KIT")
        val kitClips = SynthKits.aerostat().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the aerostat kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as AerostatPatch
            val tag = "A%02d".format(i + 1)
            val id = tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_')
            write(kitDir, id, arranged.snip)
            Clip(id, "$tag ${patch.name.uppercase()}", macroLine(patch.macros))
        }
        sections.append(
            sectionJson(
                id = "KIT",
                display = "THE KIT",
                body = "sixteen dry pads, the way they land",
                readout = listOf(
                    "A01-A08 PENTATONIC FROM C3, EVERY OTHER KNOB AT ITS DEFAULT",
                    "A09-A13 THE FIVE PRESETS",
                    "A14-A15 TWO MORE NOTES",
                    "A16 THE HELD LOOP, STRIKE NOT IN THE BUFFER",
                ),
                groups = listOf(
                    Group("PENTATONIC", key = true, clips = kitClips.subList(0, 8)),
                    Group("PRESETS", key = true, clips = kitClips.subList(8, 13)),
                    Group("CLIMB AND THE LOOP", key = true, clips = kitClips.subList(13, 16)),
                ),
            ),
        )

        val tapDir = File(root, "TAPS")
        val taps = listOf(
            AerostatTap.TUBE to "the knock alone, no whistle",
            AerostatTap.FLOW to "the whistle alone, after the catch",
            AerostatTap.QUICK to "the brighter bank, on the note",
            AerostatTap.HEAVY to "the darker bank, about five cents sharp",
            AerostatTap.FULL to "both banks, the instrument",
        ).map { (tap, desc) ->
            val id = tap.name.lowercase()
            write(tapDir, id, Aerostat.render(voice, defaults, tap = tap))
            Clip(id, tap.name, desc)
        }
        val velocities = listOf(0.3f to "SOFT", 0.65f to "MEDIUM", 1f to "HARD").map { (v, label) ->
            val id = "velocity_" + fmt(v).trimStart('.')
            val patch = AerostatPatch("Velocity", voice, defaults)
            write(tapDir, id, Velocity.atVelocity(patch, v))
            Clip(id, label, "velocity ${fmt(v)}: event energy, not a second STRIKE")
        }
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "TAPS",
                display = "THE TAPS",
                body = "engineering branches of one default strike, then velocity",
                readout = listOf("DEFAULT KNOBS", "VELOCITY IS THE HIT, STRIKE STAYS PUT"),
                groups = listOf(
                    Group("TUBE, FLOW, QUICK, HEAVY, FULL", key = true, clips = taps),
                    Group("VELOCITY", key = false, clips = velocities),
                ),
            ),
        )

        val noteDir = File(root, "NOTES")
        val notes = listOf(0f to "C3", 0.5f to "C4", 1f to "C5").map { (tune, name) ->
            val id = "tune_" + fmt(tune).trimStart('.').ifEmpty { "0" }
            write(noteDir, id, Aerostat.render(voice, defaults + ("TUNE" to tune) + ("LIFT" to 0f)))
            Clip(id, name, if (tune == 0.5f) "the middle, the default, LIFT held at 0" else "the end of TUNE, LIFT held at 0")
        }
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "NOTES",
                display = "THE NOTES",
                body = "C3, C4 and C5 with lift held off, so the pitch is the tube and the whistle",
                readout = listOf("ROOT C3", "24 SEMITONES", "LIFT 0"),
                groups = listOf(Group("LOW, MIDDLE, HIGH", key = true, clips = notes)),
            ),
        )

        val sweepDir = File(root, "SWEEPS")
        val knobs = listOf("STRIKE", "PRESSURE", "INERTIA", "RELEASE", "LIFT", "HOLD")
        val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val sweepGroups = knobs.map { knob ->
            val clips = steps.map { step ->
                val id = knob.lowercase() + "_" + fmt(step).trimStart('.').ifEmpty { "0" }
                write(sweepDir, id, Aerostat.render(voice, defaults + (knob to step)))
                val extra = if (knob == "HOLD" && step >= 1f) " The strike is not in this buffer." else ""
                Clip(id, "$knob ${fmt(step)}", "the rest at the defaults.$extra")
            }
            Group(knob + " " + SEP + " DEFAULT " + fmt(defaults.getValue(knob)), key = knob == "HOLD", clips = clips)
        }
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "SWEEPS",
                display = "THE KNOBS",
                body = "each control at 0, .25, .5, .75 and 1, the others at the default",
                readout = listOf("ONE LOUDNESS", "A CHANGE HAS TO BE A DIFFERENT TONE"),
                groups = sweepGroups,
            ),
        )

        val presetDir = File(root, "PRESETS")
        val presetClips = AerostatPresets.forVoice(voice).map { preset ->
            val id = "preset_" + preset.name.lowercase().replace(' ', '_')
            write(presetDir, id, preset.render())
            Clip(id, preset.name, noteName(Aerostat.midiFor(preset.macros.getValue("TUNE"))) + " " + SEP + " " + macroLine(preset.macros))
        }
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "PRESETS",
                display = "THE PRESETS",
                body = "five names, provisional until this page",
                readout = listOf("SOFT CATCH", "TWIN PIPES", "HEAVY ROTOR", "DRIFTING STEAM", "HIGH ENVELOPE"),
                groups = listOf(Group("THE ROSTER", key = true, clips = presetClips)),
            ),
        )

        val loopDir = File(root, "LOOP")
        write(loopDir, "held", Aerostat.render(voice, defaults + ("HOLD" to 1f)))
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "LOOP",
                display = "THE HELD LOOP",
                body = "the settled whistle. The opening strike is not in this buffer, because a pad sample has no loop-start mark. Press REPEAT and listen for a click or a pump.",
                readout = listOf("HOLD 1", "STRIKE ABSENT", "HEAVY SITS ON THE SAME PERIOD SO THE SEAM CAN CLOSE"),
                groups = listOf(
                    Group("SETTLED SUSTAIN", key = true, clips = listOf(Clip("held", "HOLD 1", "no second knock, no strike at the start"))),
                ),
            ),
        )

        val phraseDir = File(root, "PHRASE")
        write(phraseDir, "phrase", Aerostat.phrase(voice, listOf(60, 64, 67), defaults + ("STRIKE" to 0.9f)))
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "PHRASE",
                display = "THE PHRASE",
                body = "three strikes on one reservoir. The second and third should not be copies of the first.",
                readout = listOf("C4, E4, G4", "SHARED PRESSURE AND ALTITUDE", "A HARNESS, NOT THE PAD API"),
                groups = listOf(Group("ONE RESERVOIR", key = true, clips = listOf(Clip("phrase", "C4 E4 G4", "STRIKE .90, carry kept between notes")))),
            ),
        )

        val levelDir = File(root, "LEVEL")
        val hard = defaults + ("STRIKE" to 1f) + ("PRESSURE" to 0.35f)
        write(levelDir, "raw", Aerostat.render(voice, hard, normalize = false))
        write(levelDir, "levelled", Aerostat.render(voice, hard, normalize = true))
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "LEVEL",
                display = "RAW AND LEVELLED",
                body = "the same hard strike at a low pressure, before and after the engine's own loudness. Both are then brought to this page's one level, so a collapse has to be heard as tone.",
                readout = listOf("STRIKE 1", "PRESSURE .35", "PAGE LOUDNESS AFTER"),
                groups = listOf(
                    Group("THE SAME NOTE", key = true, clips = listOf(
                        Clip("raw", "RAW", "before the engine's own level"),
                        Clip("levelled", "LEVELLED", "after it"),
                    )),
                ),
            ),
        )

        val gridDir = File(root, "GRIDS")
        fun grid(a: String, b: String): List<Clip> = listOf(0f, 0.5f, 1f).flatMap { va ->
            listOf(0f, 0.5f, 1f).map { vb ->
                val id = "${a.lowercase()}_${fmt(va).trimStart('.').ifEmpty { "0" }}_${b.lowercase()}_${fmt(vb).trimStart('.').ifEmpty { "0" }}"
                write(gridDir, id, Aerostat.render(voice, defaults + (a to va) + (b to vb)))
                Clip(id, "$a ${fmt(va)} $b ${fmt(vb)}", "the rest at the defaults")
            }
        }
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "GRIDS",
                display = "THE GRIDS",
                body = "pressure against inertia, and strike against release. A corner that only gets quieter has already been levelled; listen for whether the catch still happens.",
                readout = listOf("EACH 3 x 3", "0, .5, 1"),
                groups = listOf(
                    Group("PRESSURE x INERTIA", key = true, clips = grid("PRESSURE", "INERTIA")),
                    Group("STRIKE x RELEASE", key = true, clips = grid("STRIKE", "RELEASE")),
                ),
            ),
        )

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n]}\n")
        val page = AerostatAuditionGenerator::class.java.getResourceAsStream("/audition/aerostat-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val SEP = "-"

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private fun noteName(midi: Int) = NOTE_NAMES[midi % 12] + (midi / 12 - 1)

    private fun macroLine(macros: Map<String, Float>): String {
        val moved = Aerostat.macrosFor(voice).map { it.name }.filter { (macros[it] ?: defaults.getValue(it)) != defaults.getValue(it) }
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
