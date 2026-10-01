package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * The GLINT paths audition (docs/superpowers/specs/2026-09-29-glint-paths-design.md §5):
 * one WAV per gesture and one held pad per voice, for Josh to hear on his phone
 * before the branch merges, and VOWEL's pad once more at the top of its range,
 * where its two formants thin out. Not a test - run with `generateGlintPathsAudition`.
 */
object GlintPathsAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "../testkit/glint-paths-audition").apply { mkdirs() }
        var written = 0
        fun write(name: String, snip: Snip) {
            WavWriter.write(File(dir, "$name.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            written++
        }
        val clips = linkedMapOf(
            "01-sweep-down" to Glint.render(GlintVoice.SWEEP, mapOf("BLOOM" to 1f, "DECAY" to 0.8f)),
            "02-sweep-up" to Glint.render(GlintVoice.SWEEP, mapOf("BLOOM" to 0f, "DECAY" to 0.8f)),
            "03-step-down" to Glint.render(GlintVoice.STEP, mapOf("BLOOM" to 1f, "DECAY" to 1f)),
            "04-step-up" to Glint.render(GlintVoice.STEP, mapOf("BLOOM" to 0f, "DECAY" to 1f)),
            "05-brass" to Glint.render(GlintVoice.BRASS, mapOf("BLOOM" to 1f, "DECAY" to 0.8f)),
            "06-vowel-ee-to-ah" to Glint.render(GlintVoice.VOWEL, mapOf("PEAK" to 0.5f, "BLOOM" to 1f, "DECAY" to 0.9f)),
            "07-vowel-oo-to-ah" to Glint.render(GlintVoice.VOWEL, mapOf("PEAK" to 0.5f, "BLOOM" to 0f, "DECAY" to 0.9f)),
        )
        for ((name, snip) in clips) write(name, snip)
        for ((i, voice) in GlintVoice.entries.withIndex()) {
            val midi = Keys.glintPadMidis(voice)[3]
            val note = Keys.glintPad(voice, mapOf("BLOOM" to 0.85f), midi)
            write("%02d-held-%s".format(8 + i, voice.name.lowercase()), heldFor(note, 10f))
        }
        // The twelfth clip: VOWEL's pad at its top zone, to hear where the vowel thins out.
        val top = Keys.glintPadMidis(GlintVoice.VOWEL).last()
        val topNote = Keys.glintPad(GlintVoice.VOWEL, mapOf("BLOOM" to 0.85f), top)
        write("12-held-vowel-high", heldFor(topNote, 10f))
        println("wrote $written clips to ${dir.absolutePath}")
    }

    /**
     * Plays the note as a held key would: to the end, then the loop again, for at
     * least [seconds] and always two wraps.
     */
    private fun heldFor(note: KeyNote, seconds: Float): Snip {
        val s = note.snip.samples
        val start = note.loopStartFrame.toInt()
        val loop = s.size - start
        val want = maxOf((seconds * note.snip.sampleRate).toInt(), s.size + loop + note.snip.sampleRate / 2)
        val out = FloatArray(want) { i -> if (i < s.size) s[i] else s[start + (i - s.size) % loop] }
        return Snip(out, channels = 1, sampleRate = note.snip.sampleRate)
    }
}
