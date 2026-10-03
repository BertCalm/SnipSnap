package com.snipsnap.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two places this module restates `:synth` code it cannot reach: the
 * audition loudness rule ([AuditionLevel]) and the 4 ms tail fade that
 * `BecomeAuditionGenerator` renders its clips with. Both are `internal` to
 * `:synth`, so a copy is the plan's answer; a copy with nothing watching it
 * drifts, so this reads the originals as text, the way `ConventionTest`
 * reads `:app`, and fails the day either one changes. Gradle's test working
 * directory is `:shell`'s own project dir, hence the `../synth/...` paths;
 * `shell/build.gradle.kts` declares both originals as inputs of `tasks.test`
 * so an edit to one re-runs this instead of leaving the task UP-TO-DATE.
 */
class AuditionCopiesTest {

    private val synthAuditionLevel = File("../synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt")
    private val shellAuditionLevel = File("src/test/kotlin/com/snipsnap/shell/AuditionLevel.kt")
    private val synthDsp = File("../synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt")
    private val generator = File("src/test/kotlin/com/snipsnap/shell/BecomeAuditionGenerator.kt")

    /** [file]'s code with the package line, block comments and `//` notes removed and every run of whitespace made one space. */
    private fun code(file: File): String {
        assertTrue(file.isFile, "${file.path} is missing: this law reads it as text from :shell's project dir")
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString(" ") { it.substringBefore("//") }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    @Test
    fun `law - the shell AuditionLevel is the synth AuditionLevel in code`() {
        assertEquals(
            code(synthAuditionLevel),
            code(shellAuditionLevel),
            "shell's AuditionLevel.kt no longer matches ${synthAuditionLevel.path} in code: a BECOME page and a synth page would be " +
                "levelled by different rules. Copy the change across (comments may differ).",
        )
    }

    @Test
    fun `law - the generator's tail fade restates the synth's Dsp fadeTail`() {
        val dsp = code(synthDsp)
        val mine = code(generator)
        // Each pair is one fact about the fade: Dsp's text first, the restatement's text second.
        // A change on either side breaks a pair and prints which fact moved.
        val facts = listOf(
            "the default length is 4 ms" to ("ms: Float = 4f" to "(4f / 1000f * rate).toInt()"),
            "the length in frames is ms / 1000 * rate, truncated" to ("(ms / 1000f * rate).toInt()" to "(4f / 1000f * rate).toInt()"),
            "the ramp is linear from 0 at the last frame" to ("val g = i.toFloat() / n" to "i.toFloat() / fade"),
            "the ramp is applied from the last frame backwards" to ("val frame = frames - 1 - i" to "out[out.size - 1 - i]"),
        )
        for ((fact, texts) in facts) {
            assertTrue(texts.first in dsp, "Dsp.fadeTail no longer says `${texts.first}` ($fact): update BecomeAuditionGenerator's restatement and this law.")
            assertTrue(texts.second in mine, "BecomeAuditionGenerator no longer says `${texts.second}` ($fact): keep it equal to Dsp.fadeTail.")
        }
    }
}
