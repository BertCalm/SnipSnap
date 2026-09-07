package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.Names
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

/**
 * `KitBuilderModel.nextStem`'s `while (true)` relied entirely on the
 * formatted counter varying from attempt to attempt — `Locale.ROOT`
 * restores that invariant today, but the loop itself carried no bound of
 * its own. If the invariant breaks again (or, as forced here, every
 * candidate stem the loop could produce is already taken), the loop must
 * fail loudly instead of hanging the caller — `assign()` runs from an
 * onClick in production, so a hang there wedges the app, not just a test.
 *
 * [Timeout] is the safety net for a regression of the bound itself:
 * without it, a re-introduced `while (true)` would spin this test forever
 * instead of failing fast.
 */
class KitBuilderStemBoundTest {

    @Test
    @Timeout(5)
    fun `nextStem throws instead of hanging once every candidate stem is taken`() {
        val dir = Files.createTempDirectory("kitbuilder-stem-bound").toFile()
        val model = KitBuilderModel.create("STEM BOUND", dir)

        // Reproduce nextStem's own naming exactly (`KitBuilder.kt:134`), so
        // a mismatch here fails this test for the wrong reason rather than
        // silently proving nothing.
        val base = "%s_%s".format(PadNoteMap.labelForPad(1), AutoPlace.nameFor(DrumClass.KICK))
        val firstStem = Names.sanitizeStem(String.format(Locale.ROOT, "%s_%02d", base, 1))
        assertTrue(
            firstStem.startsWith("A01_Kick"),
            "reconstructed stem drifted from production naming: $firstStem",
        )

        // Occupy every candidate nextStem could produce, forcing the loop
        // to exhaust its full bound before it can throw.
        for (n in 1..999) {
            val stem = Names.sanitizeStem(String.format(Locale.ROOT, "%s_%02d", base, n))
            File(dir, "$stem.wav").createNewFile()
        }

        val error = assertFailsWith<IllegalStateException> {
            model.assign(1, DrumSynth.kick(), DrumClass.KICK)
        }
        assertTrue(
            "A01_Kick" in error.message.orEmpty(),
            "exception doesn't name the stem it kept producing: ${error.message}",
        )
    }
}
