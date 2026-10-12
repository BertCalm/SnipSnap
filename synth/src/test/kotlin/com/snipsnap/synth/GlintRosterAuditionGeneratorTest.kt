package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The roster candidates' clips (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2): what is sent must be what the manifest says. */
class GlintRosterAuditionGeneratorTest {

    @Test
    fun `a clip is at least five seconds, ends at silence, is finite, and sits at the audition level`() {
        for (voice in GlintVoice.entries) {
            val clip = GlintRosterAuditionGenerator.clipFor(voice, GlintRosterCandidates.forVoice(voice).first())
            assertTrue(clip.frameCount >= 5 * clip.sampleRate, "$voice: ${clip.frameCount} frames is under five seconds")
            assertTrue(clip.samples.all { it.isFinite() }, "$voice: a sample is not finite")
            assertTrue(abs(clip.samples.last()) < 1e-6f, "$voice: the clip ends at ${clip.samples.last()}, not at zero")
            val peak = clip.samples.maxOf { abs(it) }
            assertTrue(peak <= 0.99f, "$voice: peak $peak is over the audition guard")
            val loudness = Loudness.of(clip)
            assertTrue(abs(loudness - 0.03f) < 5e-4f, "$voice: loudness $loudness is not the audition level 0.03")
        }
    }

    @Test
    fun `a clip is five seconds, or exactly as long as its onset and one whole breath when that is longer`() {
        fun candidate(voice: GlintVoice, overrides: Map<String, Float>) =
            GlintRosterCandidates.Candidate(0, Glint.defaults(voice) + overrides)

        val sweep = GlintRosterAuditionGenerator.clipFor(GlintVoice.SWEEP, candidate(GlintVoice.SWEEP, emptyMap()))
        assertEquals(5 * sweep.sampleRate, sweep.frameCount, "SWEEP: a short onset leaves the clip at five seconds")

        // STEP climbs a ladder of whole harmonics before it holds, 0.15 s a rung: PEAK high and BLOOM falling
        // from far above is a long climb, so the owner would hear little else in five seconds.
        val climb = candidate(GlintVoice.STEP, mapOf("PEAK" to 0.933f, "BLOOM" to 0.133f))
        val held = Keys.glintPad(GlintVoice.STEP, climb.macros, GlintRosterAuditionGenerator.zoneMidi(GlintVoice.STEP)).loopStartFrame
        val clip = GlintRosterAuditionGenerator.clipFor(GlintVoice.STEP, climb)
        assertTrue(held > 5L * clip.sampleRate, "this STEP setting's onset and first breath must run past five seconds ($held frames), or the test checks nothing")
        assertEquals(held.toInt(), clip.frameCount, "STEP with a long climb: the clip runs to the end of the first whole breath after it")
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
            val keep = listOf("notes.txt", "SWEEP-01.mp3", "sweep-02.wav", "SWEEP-1.wav", "NOTES-01.wav")
            for (name in stale + keep) File(dir, name).writeText("x")
            GlintRosterAuditionGenerator.prepare(dir)
            for (name in stale) assertTrue(!File(dir, name).exists(), "$name was left behind")
            for (name in keep) assertTrue(File(dir, name).exists(), "$name was deleted, and it is not this tool's")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `preparing the folder stops when a stale file cannot be removed, and names it`() {
        val dir = Files.createTempDirectory("glint-roster").toFile()
        try {
            File(dir, "SWEEP-01.wav").writeText("x")
            val failure = assertFailsWith<IllegalStateException> { GlintRosterAuditionGenerator.prepare(dir) { false } }
            assertTrue("SWEEP-01.wav" in (failure.message ?: ""), "the refusal does not name the file: ${failure.message}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `every varied macro is audible at the clips' zone, and FOLLOW, which is not varied, is not`() {
        for (voice in GlintVoice.entries) {
            val midi = GlintRosterAuditionGenerator.zoneMidi(voice)
            val defaults = Glint.defaults(voice)
            fun pad(name: String, value: Float): FloatArray = Keys.glintPad(voice, defaults + (name to value), midi).snip.samples
            for (name in GlintRosterCandidates.varied(voice)) {
                val top = if (name == "DEPTH") GlintRosterCandidates.DEPTH_MAX else 1f
                val change = relativeChange(pad(name, 0f), pad(name, top))
                assertTrue(change > 0.01f, "$voice $name: 0 and $top sound alike at the clips' zone (relative change $change), so the owner cannot pick on it")
            }
            if ("FOLLOW" in defaults) {
                assertTrue(
                    pad("FOLLOW", 0f).contentEquals(pad("FOLLOW", 1f)),
                    "$voice: FOLLOW now changes the sound at the clips' zone; reconsider varying it (spec 6.2.2)",
                )
            }
        }
    }

    /** The RMS of the difference over the RMS of [a]; 1 when the pads differ in length, since a different timing is a different sound. */
    private fun relativeChange(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 1f
        var diff = 0.0
        var reference = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i]).toDouble()
            diff += d * d
            reference += a[i].toDouble() * a[i]
        }
        return sqrt(diff / reference).toFloat()
    }
}
