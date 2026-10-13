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
 * pad at the lowest key, at the middle key and at the top key. The middle key is the one the roster audition used,
 * where FOLLOW changes nothing, so it is the reference the two ends are heard against. Each part is levelled by
 * [AuditionLevel]. The files are
 * `<VOICE>-01` to `<VOICE>-08` in roster order, and `manifest.txt` says which preset each is. Not a test - run with
 * `generateGlintRosterListen`.
 */
object GlintRosterListenGenerator {

    /**
     * The least a pad runs. In practice the first whole breath after the onset sets it ([padFor]): 3.45 s on every
     * voice but STEP, whose onset is its ladder at 0.15 s a rung, so a STEP pad is 3.15 s with one rung and longer
     * after a climb.
     */
    private const val PAD_SECONDS = 3f
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

    /** The one-shot, then the pad at the lowest, the middle and the top key, a gap before each. */
    internal fun clipFor(preset: GlintPatch): Snip {
        val oneShot = AuditionLevel.level(preset.render())
        val rate = oneShot.sampleRate
        val midis = Keys.glintPadMidis(preset.voice)
        val gap = FloatArray((GAP_SECONDS * rate).toInt())
        val parts = listOf(
            oneShot.samples,
            gap, padFor(preset, midis.first(), rate),
            gap, padFor(preset, midis[midis.size / 2], rate),
            gap, padFor(preset, midis.last(), rate),
        )
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return Snip(out, channels = 1, sampleRate = rate)
    }

    /** The pad at [midi]: [PAD_SECONDS] at least, and to the end of the first whole breath after its onset, faded out, levelled. */
    internal fun padFor(preset: GlintPatch, midi: Int, rate: Int): FloatArray {
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
