package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/**
 * The GLINT roster's final listen (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2.5): each
 * of the 32 factory presets as the owner will meet it, in one file: what a kit pad plays (the one-shot), then the held
 * pad at the lowest key, then at the top key. The middle key is the one the roster audition used, and FOLLOW, the one
 * value nobody could hear there, only shows at the ends. Each part is levelled by [AuditionLevel]. The files are
 * `<VOICE>-01` to `<VOICE>-08` in roster order, and `manifest.txt` says which preset each is. Not a test - run with
 * `generateGlintRosterListen`.
 */
object GlintRosterListenGenerator {

    /** The least a pad runs; a long STEP climb runs it to the end of the first whole breath after the climb. */
    private const val PAD_SECONDS = 4f
    private const val GAP_SECONDS = 0.6f
    private const val FADE_SECONDS = 0.05f
    private const val MANIFEST = "manifest.txt"

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "../testkit/glint-roster-final")
        GlintRosterAuditionGenerator.prepare(dir)
        val lines = GlintVoice.entries.flatMap { voice ->
            GlintPresets.forVoice(voice).mapIndexed { i, preset ->
                WavWriter.write(File(dir, fileName(voice, i + 1)), clipFor(preset), WavWriter.BitDepth.PCM_16)
                String.format(Locale.ROOT, "%s-%02d  %s", voice.name, i + 1, preset.name)
            }
        }
        File(dir, MANIFEST).writeText(lines.joinToString("\n", postfix = "\n"))
        println("wrote ${lines.size} presets and $MANIFEST to ${dir.absolutePath}")
    }

    internal fun fileName(voice: GlintVoice, number: Int): String = String.format(Locale.ROOT, "%s-%02d.wav", voice.name, number)

    /** The one-shot, a gap, the pad at the lowest key, a gap, the pad at the top key. */
    internal fun clipFor(preset: GlintPatch): Snip {
        val oneShot = AuditionLevel.level(preset.render())
        val rate = oneShot.sampleRate
        val midis = Keys.glintPadMidis(preset.voice)
        val gap = FloatArray((GAP_SECONDS * rate).toInt())
        val parts = listOf(oneShot.samples, gap, pad(preset, midis.first(), rate), gap, pad(preset, midis.last(), rate))
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return Snip(out, channels = 1, sampleRate = rate)
    }

    /** The pad at [midi]: [PAD_SECONDS], or to the end of the first whole breath after a long climb, faded out, levelled. */
    private fun pad(preset: GlintPatch, midi: Int, rate: Int): FloatArray {
        val note = Keys.glintPad(preset.voice, preset.macros, midi)
        val played = GlintPathsAuditionGenerator.heldFor(note, PAD_SECONDS)
        require(played.sampleRate == rate) { "the pad is at ${played.sampleRate} Hz and the one-shot at $rate" }
        val samples = played.samples.copyOf(maxOf((PAD_SECONDS * rate).toInt(), note.loopStartFrame.toInt()))
        val fade = (FADE_SECONDS * rate).toInt()
        for (i in 0 until fade) {
            val at = samples.size - fade + i
            samples[at] = (samples[at] * 0.5 * (1.0 + cos(PI * (i + 1) / fade))).toFloat()
        }
        return AuditionLevel.level(Snip(samples, channels = 1, sampleRate = rate)).samples
    }
}
