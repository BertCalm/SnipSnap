package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BallastAuditionTest {
    private fun temporary(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ballast-audition-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun `a clean candidate run refuses to invent baseline calibration`() = temporary { dir ->
        val candidate = File(dir, "candidate")
        val error = assertFailsWith<IllegalArgumentException> {
            BallastComparisonGenerator.main(arrayOf(candidate.absolutePath))
        }
        assertTrue(error.message.orEmpty().contains("-PballastComparisonVersion=before"))
        assertTrue(error.message.orEmpty().contains("reference-gain.txt"))
        assertFalse(File(dir, "reference-gain.txt").exists())
        assertTrue(candidate.listFiles().orEmpty().isEmpty(), "Refusal must happen before emitting candidate clips")
    }

    @Test
    fun `only baseline mode can create calibration and candidates reuse it unchanged`() = temporary { dir ->
        var measured = 0
        val captured = BallastComparisonGenerator.referenceGain(File(dir, "before")) {
            measured++
            .24024269f
        }
        val gainFile = File(dir, "reference-gain.txt")
        val originalBytes = gainFile.readBytes()
        val reused = BallastComparisonGenerator.referenceGain(File(dir, "candidate")) {
            error("A candidate must not measure its own replacement calibration")
        }
        assertEquals(1, measured)
        assertEquals(.24024269f, captured)
        assertEquals(captured, reused)
        assertContentEquals(originalBytes, gainFile.readBytes())
    }

    @Test
    fun `invalid saved calibration is refused without replacing the original`() = temporary { dir ->
        val gainFile = File(dir, "reference-gain.txt")
        for (text in listOf("NaN", "Infinity", "0", "-1", "not-a-number", "")) {
            gainFile.writeText(text)
            val error = assertFailsWith<IllegalArgumentException> {
                BallastComparisonGenerator.referenceGain(File(dir, "candidate")) {
                    error("An invalid baseline file must not be replaced with a candidate measurement")
                }
            }
            assertTrue(error.message.orEmpty().contains("Invalid baseline calibration"))
            assertEquals(text, gainFile.readText())
        }
    }

    @Test
    fun `soft and hard touches retain relative output instead of independent loudness targets`() {
        val macros = Ballast.defaults(BallastVoice.ROOT) + mapOf("TUNE" to 12 / 36f, "HOLD" to 0f)
        val clips = BallastAuditionGenerator.velocityClips(BallastVoice.ROOT, macros)
        val soft = Loudness.of(clips.getValue(.3f))
        val hard = Loudness.of(clips.getValue(1f))
        println("BALLAST shared-touch loudness soft=$soft hard=$hard")
        assertTrue(hard > soft * 1.05f, "Touches were individually levelled: soft=$soft hard=$hard")
        for (clip in clips.values) {
            assertEquals(1, clip.channels)
            assertEquals(Dsp.RATE, clip.sampleRate)
            assertTrue(clip.samples.all { it.isFinite() && abs(it) < .99f })
            assertTrue(abs(clip.samples.last()) < 1e-8f, "The production tail fade is missing")
        }
    }

    @Test
    fun `component audition preserves sums and source off relative level at one full mix gain`() {
        val macros = Ballast.defaults(BallastVoice.WIRE) + mapOf("TUNE" to 12 / 36f, "HOLD" to .6f)
        val clips = BallastAuditionGenerator.structureClips(BallastVoice.WIRE, macros)
        val bass = clips.getValue("bass").samples
        val structure = clips.getValue("structure").samples
        val both = clips.getValue("bass+structure").samples
        var worst = 0f
        for (i in both.indices) worst = maxOf(worst, abs(both[i] - bass[i] - structure[i]))
        assertTrue(worst < 1e-6f, "Independent component levels broke the physical sum: $worst")
        val full = Loudness.of(clips.getValue("all"))
        val off = Loudness.of(clips.getValue("off"))
        println("BALLAST source-off full=$full off=$off sumError=$worst")
        assertTrue(abs(full - off) > full * .01f, "The stopped source was independently levelled to the full mix")
        assertTrue(clips.values.all { clip -> clip.samples.all { it.isFinite() && abs(it) < .99f } })
    }
}
