package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the BORE audition (docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md,
 * the S17 gate) under testkit/bore-audition/ (gitignored): the sixteen-pad kit as it lands on
 * the MPC, then for each voice its default, BREATH/LIP/CHIFF/HOLD at both ends with the rest at
 * their defaults, all eight of its own presets, and the landing - the same note dry, with the
 * TAPE recipe every one-shot pad lands with, and with twice the wow. The LOOP step is a clip of
 * its own per voice, played to its own length. Clips share one loudness ([AuditionLevel]).
 * Writes `manifest.json`, which the page builds itself from, so the clip list lives here and
 * nowhere else, then copies the listening page from the test resources. Run via
 * `./gradlew :synth:generateBoreAudition`, then publish the folder as the listening artifact.
 *
 * Nothing in BORE has been heard by anyone when this is first run: it is the gate that decides
 * whether the measured engine is a woodwind. The page says so.
 */
object BoreAuditionGenerator {

    private class Knob(val name: String, val low: String, val high: String)

    /** The knobs at both ends. TUNE is a note, not a knob to audition. HOLD's top end is a LOOP, so it has its own clip below and this one stops short of it. */
    private val KNOBS = listOf(
        Knob("BREATH", "the softest note that speaks: a quiet breath, dull, little noise", "the hardest breath the note takes: brighter, more air in it"),
        Knob("LIP", "FLUTE: a dark, round jet; SAX: a loose, open, buzzy reed", "FLUTE: a bright, open jet; SAX: a tight, pure, pinched reed"),
        Knob("CHIFF", "a slow swell, no tongue", "a hard tongue: fast attack, a breath burst, a key thump underneath"),
        Knob("HOLD", "the shortest breath: 0.3 s of note", "the longest one-shot: 4 s, with its vibrato"),
    )

    private val BODIES = mapOf(
        BoreVoice.FLUTE to "an air jet across a mouth hole, in a pipe: the fundamental leads at every breath, the note follows the pitch's own bore",
        BoreVoice.SAX to "a single reed on a cone: the full harmonic series, slow to speak below about C4, buzzier the looser the lip",
    )

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/bore-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()

        // The kit, as it lands: SynthKits.bore() is the one list of what is on which pad,
        // and each pad's own recipe names its patch.
        val kitDir = File(root, "KIT")
        val kitClips = SynthKits.bore().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the bore kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as BorePatch
            val tag = "A%02d".format(i + 1)
            val id = tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_')
            WavWriter.write(File(kitDir, "$id.wav"), AuditionLevel.level(arranged.snip), WavWriter.BitDepth.PCM_16)
            count++
            Clip(id, "$tag ${patch.name.uppercase()}", patch.voice.name + " " + DOT + " " + macroLine(patch.voice, patch.macros))
        }
        sections.append(
            sectionJson(
                id = "KIT", display = "THE BORE KIT", body = "sixteen pads as the MPC gets them",
                readout = listOf("A01-A04 FLUTE, A TRIAD AND ITS OCTAVE", "A05-A08 SAX, THE SAME", "A09-A16 EIGHT PRESETS, FOUR PER VOICE"),
                groups = listOf(
                    Group("FLUTE, C MAJOR TO THE OCTAVE", key = true, clips = kitClips.subList(0, 4)),
                    Group("SAX, THE SAME", key = true, clips = kitClips.subList(4, 8)),
                    Group("FLUTE PRESETS", key = true, clips = kitClips.subList(8, 12)),
                    Group("SAX PRESETS", key = true, clips = kitClips.subList(12, 16)),
                ),
            ),
        )

        for (voice in BoreVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Bore.defaults(voice)
            fun write(id: String, macros: Map<String, Float>) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(Bore.render(voice, macros)), WavWriter.BitDepth.PCM_16)
                count++
            }

            write("default", emptyMap())
            val groups = mutableListOf(
                Group("THE DEFAULT", key = true, clips = listOf(Clip("default", "DEFAULT", macroLine(voice, emptyMap())))),
            )
            for (knob in KNOBS) {
                val lo = knob.name.lowercase() + "_0"
                val hi = knob.name.lowercase() + "_1"
                write(lo, mapOf(knob.name to 0f))
                // HOLD 1 is the LOOP step; the top of the one-shot travel is just under it.
                write(hi, mapOf(knob.name to if (knob.name == "HOLD") Bore.SCRAMBLE_HOLD_CEILING else 1f))
                groups += Group(
                    knob.name + " " + DOT + " DEFAULT " + fmt(defaults.getValue(knob.name)), key = false,
                    clips = listOf(Clip(lo, "${knob.name} 0", knob.low), Clip(hi, "${knob.name} 1", knob.high)),
                )
            }

            val presets = BorePresets.forVoice(voice).map { preset ->
                val id = "preset_" + preset.name.lowercase().replace(' ', '_')
                write(id, preset.macros)
                Clip(id, preset.name, macroLine(voice, preset.macros))
            }
            groups += Group("${voice.name}'S OWN PRESETS", key = true, clips = presets)

            // The LOOP step, alone: a seamless two seconds, played once here (hold a pad to hear it wrap).
            write("loop", mapOf("HOLD" to 1f))
            groups += Group(
                "THE LOOP STEP", key = false,
                clips = listOf(Clip("loop", "HOLD 1 (LOOP)", "a seamless whole-period loop: dry, no vibrato, no breath noise; a pad held down repeats it")),
            )

            // The landing: the same note dry, landed with the recipe's TAPE, and with twice the wow
            // - the numbers Bore.LANDING_TAPE leaves to this gate.
            val landing = mutableListOf<Clip>()
            val patch = BorePatch("Landing", voice, defaults)
            val landed = mapOf(
                "dry" to Triple("DRY", "the engine alone, no tape", null),
                "landed" to Triple("LANDED, WOBBLE .10", "the recipe every one-shot pad lands with", Bore.LANDING_TAPE),
                "wow" to Triple("LANDED, WOBBLE .20", "the same tape with twice the wow", Bore.LANDING_TAPE + ("WOBBLE" to 0.2f)),
            )
            for ((id, spec) in landed) {
                val (name, desc, tape) = spec
                val fx = tape?.let { FxChain().withSection("tape", it) }
                WavWriter.write(File(dir, "landing_$id.wav"), AuditionLevel.level(PadRecipe(patch, fx).render()), WavWriter.BitDepth.PCM_16)
                count++
                landing += Clip("landing_$id", name, desc)
            }
            groups += Group("THE LANDING: DRY, TAPE, MORE TAPE", key = true, clips = landing)

            if (sections.isNotEmpty()) sections.append(",\n")
            sections.append(
                sectionJson(
                    id = voice.name, display = voice.name, body = BODIES.getValue(voice),
                    readout = listOf(
                        "ROOT " + (if (voice == BoreVoice.FLUTE) "C4" else "C3") + " " + DOT + " 24 SEMITONES OF TRAVEL",
                        "BREATH " + fmt(defaults.getValue("BREATH")) + DOT + "LIP " + fmt(defaults.getValue("LIP")),
                    ),
                    groups = groups,
                ),
            )
        }

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n]}\n")
        val page = BoreAuditionGenerator::class.java.getResourceAsStream("/audition/bore-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "\u00b7"

    /** The knobs that differ from the voice's defaults, the way the app's sliders read them. */
    private fun macroLine(voice: BoreVoice, macros: Map<String, Float>): String {
        val d = Bore.defaults(voice)
        val moved = Bore.macrosFor(voice).map { it.name }.filter { (macros[it] ?: d.getValue(it)) != d.getValue(it) }
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
