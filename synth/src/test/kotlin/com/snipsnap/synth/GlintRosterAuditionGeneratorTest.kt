package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The roster candidates' clips (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2): what is sent must be what the manifest says. */
class GlintRosterAuditionGeneratorTest {

    @Test
    fun `a clip is five seconds, ends at silence, is finite, and sits at the audition level`() {
        for (voice in GlintVoice.entries) {
            val clip = GlintRosterAuditionGenerator.clipFor(voice, GlintRosterCandidates.forVoice(voice).first())
            assertEquals(5 * clip.sampleRate, clip.frameCount, "$voice: length in frames")
            assertTrue(clip.samples.all { it.isFinite() }, "$voice: a sample is not finite")
            assertTrue(abs(clip.samples.last()) < 1e-6f, "$voice: the clip ends at ${clip.samples.last()}, not at zero")
            val peak = clip.samples.maxOf { abs(it) }
            assertTrue(peak <= 0.99f, "$voice: peak $peak is over the audition guard")
            val loudness = Loudness.of(clip)
            assertTrue(abs(loudness - 0.03f) < 5e-4f, "$voice: loudness $loudness is not the audition level 0.03")
        }
    }

    @Test
    fun `a voice's sixteen clips and its manifest lines agree on their numbers and values`() {
        val dir = Files.createTempDirectory("glint-roster").toFile()
        try {
            for (voice in listOf(GlintVoice.SWEEP, GlintVoice.VOWEL)) {
                val lines = GlintRosterAuditionGenerator.renderVoice(voice, dir)
                assertEquals(16, lines.size, "$voice: manifest lines")
                for (c in GlintRosterCandidates.forVoice(voice)) {
                    val name = "%s-%02d".format(Locale.ROOT, voice.name, c.number)
                    assertEquals("$name.wav", GlintRosterAuditionGenerator.clipName(voice, c.number), "$name: the clip's file name")
                    assertTrue(File(dir, "$name.wav").length() > 44, "$name.wav was not written")
                    val tokens = lines[c.number - 1].trim().split(Regex("\\s+"))
                    assertEquals(name, tokens.first(), "$name: the manifest line's own name")
                    val shown = tokens.drop(1).associate { it.substringBefore('=') to it.substringAfter('=').toFloat() }
                    assertEquals(GlintRosterCandidates.varied(voice).toSet(), shown.keys, "$name: the macros the line lists")
                    for ((macro, value) in shown) {
                        val real = c.macros.getValue(macro)
                        assertTrue(abs(value - real) < 5e-4f, "$name: $macro reads $value in the manifest and is $real in the candidate")
                    }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `preparing the folder removes an earlier draw's clips and manifest and nothing else`() {
        val dir = Files.createTempDirectory("glint-roster").toFile()
        try {
            val stale = listOf("SWEEP-01.wav", "VOWEL-16.wav", "manifest.txt")
            // Each kept name differs from every stale one by more than case: macOS's file system is
            // case-insensitive, so "sweep-01.wav" would be the very file "SWEEP-01.wav".
            val keep = listOf("notes.txt", "SWEEP-01.mp3", "sweep-02.wav", "SWEEP-1.wav")
            for (name in stale + keep) File(dir, name).writeText("x")
            GlintRosterAuditionGenerator.prepare(dir)
            for (name in stale) assertTrue(!File(dir, name).exists(), "$name was left behind")
            for (name in keep) assertTrue(File(dir, name).exists(), "$name was deleted, and it is not this tool's")
        } finally {
            dir.deleteRecursively()
        }
    }
}
