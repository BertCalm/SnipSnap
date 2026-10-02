package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * The GLINT DEPTH audition (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §4.11):
 * the real engine's held pads, not the listening probe, at DEPTH 0, 0.25, 0.5, 0.75 and 0.9 on a SWEEP
 * and a VOWEL pad, for the owner to hear on his phone before anything merges. The zone is MIDI 57
 * (220 Hz, the probe's pitch) and BLOOM 0.85, so the pad breathes as the probe's did. Levelled to the
 * repo's audition level. Not a test - run with `generateGlintDepthAudition`.
 */
object GlintDepthAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "../testkit/glint-depth-audition").apply { mkdirs() }
        var written = 0
        var n = 0
        for (voice in listOf(GlintVoice.SWEEP, GlintVoice.VOWEL)) {
            val midi = Keys.glintPadMidis(voice)[4]
            for (depth in listOf(0f, 0.25f, 0.5f, 0.75f, 0.9f)) {
                n++
                val note = Keys.glintPad(voice, mapOf("BLOOM" to 0.85f, "DEPTH" to depth), midi)
                val name = "%02d-%s-depth%03d".format(n, voice.name.lowercase(), Math.round(depth * 100))
                val clip = AuditionLevel.level(GlintPathsAuditionGenerator.heldFor(note, 6f))
                WavWriter.write(File(dir, "$name.wav"), clip, WavWriter.BitDepth.PCM_16)
                written++
            }
        }
        println("wrote $written clips to ${dir.absolutePath}")
    }
}
