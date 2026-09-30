package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the FORK audition
 * (docs/superpowers/specs/2026-09-27-fork-electric-piano-engine-design.md,
 * the S14 gate) under testkit/fork-audition/ (gitignored): the sixteen-pad
 * kit as it lands on the MPC (TINE and BAR only — the later voices did not
 * change what ships on the kit), then for each voice its default,
 * STRIKE/BARK/STIFF/DECAY at both ends of their travel with the rest at
 * their defaults, and all eight of its own presets, then one STRIKER
 * section comparing the noise hammer against a captured-snip hammer at both
 * a dull and a bright source (this environment has no real captured audio
 * to draw on, so the two sources are synthesised noise bursts, dull and
 * bright, the same stand-in [ForkTest]'s own striker tests use). Clips
 * share one loudness ([AuditionLevel]). Writes `manifest.json`, which the
 * page builds itself from, so the clip list lives here and nowhere else,
 * then copies the listening page from the test resources. Run via
 * `./gradlew :synth:generateForkAudition`, then publish the folder as the
 * listening artifact.
 *
 * Round one's own gate (TINE, BAR): closer on TINE, but neither read as
 * "piano" outright — see [ForkVoice.NODE]'s own KDoc for round two's answer
 * and [ForkVoice.REED]'s for round four's, a second electric-piano family
 * entirely rather than another attempt at "closer to a Rhodes."
 */
object ForkAuditionGenerator {

    private class Knob(val name: String, val low: String, val high: String)

    /** The four sound-design knobs at both ends. TUNE is not a knob to audition: a note, like SIREN's own TUNE. */
    private val KNOBS = listOf(
        Knob("STRIKE", "a soft mallet: dull, quiet, long swing", "a hard hammer: bright, loud, short swing"),
        Knob("BARK", "the pickup held back: a cleaner, rounder tone", "the pickup close: more edge at its strongest (REED: the contact rattle joins in too)"),
        Knob("STIFF", "a harmonic string: modes at 1-2-3-4x", "stretched half again past the voice's own bar"),
        Knob("DECAY", "the shortest ring: 0.4s at the fundamental", "the longest ring: 5s at the fundamental"),
    )

    private val BODIES = mapOf(
        ForkVoice.TINE to "a cantilever tine, like a real electric piano's — overtones far from the fundamental, gone in tens of milliseconds",
        ForkVoice.BAR to "a free-free bar, the vibraphone's own shape — overtones closer in, ringing longer",
        ForkVoice.NODE to "the same cantilever tine as TINE, read at its own second mode's node — that overtone silenced at the source, a purer and warmer tone",
        ForkVoice.REED to "the same cantilever tine again, but a second electric-piano family entirely — an electrostatic comb pickup, odd-harmonic-dominant, with a discrete mechanical rattle at hard enough strikes and a close enough plate",
    )

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    /** A short noise burst, one-pole filtered dull or bright — the same stand-in [ForkTest]'s own striker tests use for a captured hit with no single frequency to lock a mode onto. */
    private fun tiltedNoise(bright: Boolean, seconds: Float = 0.03f, rate: Int = Dsp.RATE): Snip {
        val noise = Dsp.Noise(if (bright) 41 else 43)
        val pole = Dsp.OnePole(rate)
        val cutoff = if (bright) 8000f else 400f
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { pole.lp(noise.next(), cutoff) * 0.8f }, channels = 1, sampleRate = rate)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/fork-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()

        // The kit, as it lands: SynthKits.fork() is the one list of what is
        // on which pad, and each pad's own recipe names its patch.
        val kitDir = File(root, "KIT")
        val kitClips = SynthKits.fork().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the fork kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as ForkPatch
            val tag = "A%02d".format(i + 1)
            val id = tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_')
            WavWriter.write(File(kitDir, "$id.wav"), AuditionLevel.level(arranged.snip), WavWriter.BitDepth.PCM_16)
            count++
            Clip(id, "$tag ${patch.name.uppercase()}", patch.voice.name + " " + DOT + " " + macroLine(patch.voice, patch.macros))
        }
        sections.append(
            sectionJson(
                id = "KIT", display = "THE FORK KIT", body = "sixteen pads as the MPC gets them",
                readout = listOf("A01-A08 THE TINE ROW", "A09-A16 THE BAR PRESETS"),
                groups = listOf(
                    Group("TINE, ROOT TO A SIXTH", key = true, clips = kitClips.subList(0, 8)),
                    Group("BAR'S OWN EIGHT PRESETS", key = true, clips = kitClips.subList(8, 16)),
                ),
            ),
        )

        for (voice in ForkVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Fork.defaults(voice)
            fun write(id: String, macros: Map<String, Float>) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(Fork.render(voice, macros)), WavWriter.BitDepth.PCM_16)
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

            val presets = ForkPresets.forVoice(voice).map { preset ->
                val id = "preset_" + preset.name.lowercase().replace(' ', '_')
                write(id, preset.macros)
                Clip(id, preset.name, macroLine(voice, preset.macros))
            }
            groups += Group("${voice.name}'S OWN PRESETS", key = true, clips = presets)

            // The striker: a captured snip's head standing in for the noise
            // hammer, dull and bright, against the plain noise hammer at the
            // same STRIKE — the excite-from-pad feature, heard rather than
            // just tested.
            val strikerGroup = mutableListOf<Clip>()
            for (strikeM in listOf(0f, 1f)) {
                val strikeId = if (strikeM == 0f) "0" else "1"
                val hammerId = "hammer_$strikeId"
                WavWriter.write(File(dir, "$hammerId.wav"), AuditionLevel.level(Fork.render(voice, mapOf("STRIKE" to strikeM))), WavWriter.BitDepth.PCM_16)
                count++
                strikerGroup += Clip(hammerId, "NOISE HAMMER, STRIKE $strikeId", "the built-in hammer, no captured source")
                for ((bright, tag) in listOf(false to "dull", true to "bright")) {
                    val striker = Fork.striker(tiltedNoise(bright))
                    val id = "striker_${tag}_$strikeId"
                    WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(Fork.render(voice, mapOf("STRIKE" to strikeM), striker = striker)), WavWriter.BitDepth.PCM_16)
                    count++
                    strikerGroup += Clip(id, "${tag.uppercase()} STRIKER, STRIKE $strikeId", "a captured snip's own head ($tag, synthesised here) in place of the noise hammer")
                }
            }
            groups += Group("EXCITE FROM A PAD (THE STRIKER)", key = false, clips = strikerGroup)

            if (sections.isNotEmpty()) sections.append(",\n")
            sections.append(
                sectionJson(
                    id = voice.name, display = voice.name, body = BODIES.getValue(voice),
                    readout = listOf(
                        "ROOT C3 " + DOT + " 24 SEMITONES OF TRAVEL",
                        "DECAY " + fmt(defaults.getValue("DECAY")) + DOT + "STRIKE " + fmt(defaults.getValue("STRIKE")),
                    ),
                    groups = groups,
                ),
            )
        }

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n]}\n")
        val page = ForkAuditionGenerator::class.java.getResourceAsStream("/audition/fork-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "·"

    /** The knobs that differ from the voice's defaults, the way the app's sliders read them. */
    private fun macroLine(voice: ForkVoice, macros: Map<String, Float>): String {
        val d = Fork.defaults(voice)
        val moved = Fork.macrosFor(voice).map { it.name }.filter { (macros[it] ?: d.getValue(it)) != d.getValue(it) }
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
