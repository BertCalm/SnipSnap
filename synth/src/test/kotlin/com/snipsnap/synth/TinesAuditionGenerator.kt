package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Renders the TINES sound-design audition under testkit/tines-audition/
 * (gitignored): for every voice, today's sound, each sound-design macro at
 * both ends of its travel with the rest at their defaults, and the voice's
 * four BOLD presets - every clip a short phrase rather than one hit, since
 * TINES is the melodic engine and a bell or a kalimba is judged by how it
 * plays a line. Clips share one loudness ([AuditionLevel]). Writes
 * `manifest.json`, which the page builds itself from, so the clip list
 * lives here and nowhere else, then copies the listening page from the
 * test resources. Run via `./gradlew :synth:generateTinesAudition`, then
 * publish the folder as the listening artifact.
 */
object TinesAuditionGenerator {

    /** Semitones over the sound's own note: up the major triad to the octave and back to the fifth. */
    private val PHRASE = intArrayOf(0, 4, 7, 12, 7)

    /** Seconds between phrase notes: slow enough that each note's decay is heard, not just its strike. */
    private const val STEP_SECONDS = 0.32f

    /** The macros the sound-design round added, per voice, with what each end is. */
    private val KNOBS: Map<TinesVoice, List<Knob>> = mapOf(
        TinesVoice.BELL to listOf(BITE, Knob("CLANG", "a fifth: rounder, organ-ish", "off every harmonic: church bell, gong")),
        TinesVoice.CHIME to listOf(BITE, RATIO),
        TinesVoice.BLOCK to listOf(BITE, Knob("RATIO", "a round, near-sine tap", "a struck metal bar")),
        TinesVoice.ZAP to listOf(
            BITE, Knob("BEND", "a long, falling laser", "a pitched thud, no audible fall"), RATIO,
        ),
        TinesVoice.TOY to listOf(BITE, RATIO, Knob("SHAPE", "the wobble slides (today)", "the wobble steps: a chip-tune trill")),
        TinesVoice.KALIMBA to listOf(BITE, Knob("TICK", "a fleshy thumb, no click", "a hard nail, six times today's click")),
    )

    private val BODIES = mapOf(
        TinesVoice.BELL to "a struck bell with a quieter partner an octave up",
        TinesVoice.CHIME to "two near-identical glass bells beating",
        TinesVoice.BLOCK to "a woodblock, gone in a blink",
        TinesVoice.ZAP to "a laser tom: the pitch falls onto its note",
        TinesVoice.TOY to "a cheap keyboard's wobbling hit",
        TinesVoice.KALIMBA to "a plucked tine, ringing long",
    )

    private class Knob(val name: String, val low: String, val high: String)

    private val BITE get() = Knob("BITE", "the FM stays bright to the end", "a sharp strike that settles pure")
    private val RATIO get() = Knob("RATIO", "a round tube", "a hard, clanging rod")

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/tines-audition")
        root.mkdirs()
        var count = 0
        val voices = StringBuilder()

        for (voice in TinesVoice.entries) {
            val dir = File(root, voice.name)
            fun write(id: String, macros: Map<String, Float>) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(phrase(voice, macros)), WavWriter.BitDepth.PCM_16)
                count++
            }

            write("today", emptyMap())
            val groups = mutableListOf(Group("TODAY'S SOUND", key = true, clips = listOf(Clip("today", "TODAY", "every macro at its default"))))

            for (knob in KNOBS.getValue(voice)) {
                write("${knob.name.lowercase()}_0", mapOf(knob.name to 0f))
                write("${knob.name.lowercase()}_1", mapOf(knob.name to 1f))
                groups += Group(
                    knob.name, key = false,
                    clips = listOf(
                        Clip("${knob.name.lowercase()}_0", "${knob.name} 0", knob.low),
                        Clip("${knob.name.lowercase()}_1", "${knob.name} 1", knob.high),
                    ),
                )
            }

            // The BOLD presets are each voice's last four (TinesPresets' own
            // KDoc): the ones that push these macros.
            val bold = TinesPresets.forVoice(voice).takeLast(4).map { preset ->
                val id = "bold_" + preset.name.lowercase().replace(' ', '_')
                write(id, preset.macros)
                Clip(id, preset.name, preset.macros.entries.joinToString(" ") { (k, v) -> "$k ${fmt(v)}" })
            }
            groups += Group("BOLD PRESETS", key = true, clips = bold)

            if (voices.isNotEmpty()) voices.append(",\n")
            voices.append(voiceJson(voice, groups))
        }

        File(root, "manifest.json").writeText("{\"voices\": [\n$voices\n]}\n")
        val page = TinesAuditionGenerator::class.java.getResourceAsStream("/audition/tines-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    /**
     * [PHRASE] played by [voice] from the note [macros] sets, each note a
     * full render at its own TUNE. Notes that would leave the voice's range
     * drop an octave, so every phrase stays where the voice can play it.
     */
    private fun phrase(voice: TinesVoice, macros: Map<String, Float>): Snip {
        val full = Tines.defaults(voice) + macros
        val tune = full.getValue("TUNE")
        val notes = if (voice == TinesVoice.KALIMBA) {
            val rootSemi = (tune * Tines.KALIMBA_TUNE_SEMITONES).roundToInt()
            PHRASE.map { step ->
                var semi = rootSemi + step
                while (semi > Tines.KALIMBA_TUNE_SEMITONES) semi -= 12
                semi / Tines.KALIMBA_TUNE_SEMITONES.toFloat()
            }
        } else {
            val rootHz = Tines.carrierFor(voice, tune)
            val hi = Tines.carrierRange(voice).second
            PHRASE.map { step ->
                var hz = rootHz * 2f.pow(step / 12f)
                while (hz > hi * 1.0001f) hz /= 2f
                Tines.tuneFor(voice, hz)
            }
        }
        val renders = notes.map { Tines.render(voice, full + ("TUNE" to it)) }
        val step = (STEP_SECONDS * Dsp.RATE).toInt()
        val out = FloatArray(step * (renders.size - 1) + renders.maxOf { it.samples.size })
        renders.forEachIndexed { i, r -> for (j in r.samples.indices) out[i * step + j] += r.samples[j] }
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }

    private fun rootLabel(voice: TinesVoice): String {
        val tune = Tines.defaults(voice).getValue("TUNE")
        val hz = if (voice == TinesVoice.KALIMBA) Tines.frequencyFor(voice, tune) else Tines.carrierFor(voice, tune)
        return "${noteName(hz)} · ${hz.roundToInt()} HZ"
    }

    private fun noteName(hz: Float): String {
        val midi = (69 + 12 * kotlin.math.log2(hz / 440f)).roundToInt()
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return names[midi.mod(12)] + (midi / 12 - 1)
    }

    /** A macro value the way the app's sliders read it: `0`, `.35`, `1`. */
    private fun fmt(v: Float) = when {
        v <= 0f -> "0"
        v >= 1f -> "1"
        else -> "." + (v * 100).roundToInt().toString().padStart(2, '0')
    }

    private fun voiceJson(voice: TinesVoice, groups: List<Group>): String {
        val g = groups.joinToString(",") { grp ->
            val c = grp.clips.joinToString(",") { "[${q(it.id)},${q(it.name)},${q(it.desc)}]" }
            "{\"label\":${q(grp.label)},\"key\":${grp.key},\"clips\":[$c]}"
        }
        return "{\"id\":${q(voice.name)},\"display\":${q(voice.name)},\"note\":${q(rootLabel(voice))}," +
            "\"body\":${q(BODIES.getValue(voice))},\"groups\":[$g]}"
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
