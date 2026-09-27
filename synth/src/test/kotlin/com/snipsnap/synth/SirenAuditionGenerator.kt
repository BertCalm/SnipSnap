package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the SIREN audition (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md,
 * the S12 gate) under testkit/siren-audition/ (gitignored): the Siren Kit's
 * sixteen pads exactly as they land on the MPC (the one-shots carrying the
 * rack's ECHO in their recipe, the LOOPs dry), then for every voice its
 * default, RATE, DEPTH, SWEEP and GRIT at both ends of their travel with
 * the rest at their defaults, and its LOOP. The four LOOPs are also listed
 * for the page's SURFACE stand-in, which holds one under a finger through
 * Web Audio the way the phone's SURFACE screen does. Clips share one
 * loudness ([AuditionLevel]); a LOOP is levelled by the same uniform gain,
 * so its wrap stays exact. Writes `manifest.json`, which the page builds
 * itself from, so the clip list lives here and nowhere else, then copies
 * the listening page from the test resources. Run via
 * `./gradlew :synth:generateSirenAudition`, then publish the folder as the
 * listening artifact.
 */
object SirenAuditionGenerator {

    private class Knob(val name: String, val low: String, val high: String)

    /** The four knobs at both ends, in the order the SYNTH screen lists them. TUNE and HOLD are not knobs to audition: a note and a length. */
    private val KNOBS = listOf(
        Knob("RATE", "the slowest: a quarter of a hertz", "the fastest: 25 Hz, a buzz of pitch"),
        Knob("DEPTH", "no movement at all: a plain tone", "two octaves each way"),
        Knob("SWEEP", "the note falls in from two octaves above", "the note climbs in from two octaves below"),
        Knob("GRIT", "soft: the pulse rounded off", "the raw chip: edge and drive"),
    )

    private val BODIES = mapOf(
        SirenVoice.WAIL to "the slow air-raid rise and fall",
        SirenVoice.TRILL to "two alternating tones",
        SirenVoice.LASER to "a pitch that dives and snaps back up",
        SirenVoice.BIRD to "a pitch that climbs and snaps back down",
    )

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/siren-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()
        val loops = StringBuilder()

        // The kit, as it lands: SynthKits.siren() is the one list of what is
        // on which pad, and each pad's own recipe names its patch.
        val kitDir = File(root, "KIT")
        val kitClips = SynthKits.siren().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the siren kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as SirenPatch
            val tag = "A%02d".format(i + 1)
            val id = tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_')
            WavWriter.write(File(kitDir, "$id.wav"), AuditionLevel.level(arranged.snip), WavWriter.BitDepth.PCM_16)
            count++
            val loop = Siren.isLoop(patch.macros["HOLD"] ?: Siren.defaults(patch.voice).getValue("HOLD"))
            val desc = patch.voice.name + (if (loop) ", a LOOP, dry" else ", with the rack's ECHO") + " " + DOT + " " + macroLine(patch.voice, patch.macros)
            Clip(id, "$tag ${patch.name.uppercase()}", desc)
        }
        sections.append(
            sectionJson(
                id = "KIT", display = "THE SIREN KIT", body = "sixteen pads as the MPC gets them",
                readout = listOf("A01-A04 THE WAIL ROW", "A13-A16 THE LOOPS"),
                groups = listOf(
                    Group("THE WAIL ROW, ROOT TO OCTAVE", key = true, clips = kitClips.subList(0, 4)),
                    Group("ONE-SHOTS", key = false, clips = kitClips.subList(4, 12)),
                    Group("THE LOOPS", key = true, clips = kitClips.subList(12, 16)),
                ),
            ),
        )

        for (voice in SirenVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Siren.defaults(voice)
            fun write(id: String, macros: Map<String, Float>) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(Siren.render(voice, macros)), WavWriter.BitDepth.PCM_16)
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
                write(hi, mapOf(knob.name to 1f))
                groups += Group(
                    knob.name + " " + DOT + " DEFAULT " + fmt(defaults.getValue(knob.name)), key = false,
                    clips = listOf(Clip(lo, "${knob.name} 0", knob.low), Clip(hi, "${knob.name} 1", knob.high)),
                )
            }
            write("loop", mapOf("HOLD" to 1f))
            groups += Group(
                "THE LOOP", key = true,
                clips = listOf(Clip("loop", "LOOP", "HOLD at its top: whole LFO periods, the pulse closing on whole cycles, cut at a crossing. Plays once here; hold it above.")),
            )

            if (sections.isNotEmpty()) sections.append(",\n")
            sections.append(
                sectionJson(
                    id = voice.name, display = voice.name, body = BODIES.getValue(voice),
                    readout = listOf(
                        "CENTRE C5 " + DOT + " 523 HZ",
                        "RATE " + hz(Siren.rateHz(defaults.getValue("RATE"))),
                        "DEPTH " + Siren.depthSemitones(defaults.getValue("DEPTH")).roundToInt() + " ST EACH WAY",
                    ),
                    groups = groups,
                ),
            )
            if (loops.isNotEmpty()) loops.append(",")
            loops.append("{\"id\":${q(voice.name)},\"name\":${q(voice.name)},\"sub\":${q(hz(Siren.rateHz(defaults.getValue("RATE"))))},\"file\":${q(voice.name + "/loop.wav")}}")
        }

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n],\"loops\": [$loops]}\n")
        val page = SirenAuditionGenerator::class.java.getResourceAsStream("/audition/siren-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "·"

    /** The knobs that differ from the voice's defaults, the way the app's sliders read them. */
    private fun macroLine(voice: SirenVoice, macros: Map<String, Float>): String {
        val d = Siren.defaults(voice)
        val moved = Siren.macrosFor(voice).map { it.name }.filter { (macros[it] ?: d.getValue(it)) != d.getValue(it) }
        return if (moved.isEmpty()) "every knob at its default" else moved.joinToString(" ") { "$it ${fmt(macros.getValue(it))}" }
    }

    private fun hz(v: Float): String = if (v < 1f) "%.2f HZ".format(v) else if (v < 10f) "%.1f HZ".format(v) else "${v.roundToInt()} HZ"

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
