package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.Names
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `KitBuilderModel.assign` is the interactive kit builder's only path to a
 * WAV filename — every pad drop on the KIT screen runs through it. Both
 * `nextStem` (`:137`, the sample filename) and the `displayName` fallback
 * (`:72`) format a `%02d` counter, and `String.format` follows the default
 * locale, so on an Arabic-, Persian- or Burmese-locale phone the counter
 * arrives as Eastern-Arabic digits — the same defect `PadNoteMapLocaleTest`
 * and `KitAssemblerLocaleTest` cover for the auto-chop path, uncovered here
 * until now.
 *
 * Assertions here compare exact strings, not just "stays ASCII":
 * `Names.sanitizeStem` (used by `nextStem`) already scrubs any non-ASCII
 * character to `_` and trims it, so a corrupted stem is *still* ASCII —
 * the tell is that the "01" suffix goes missing entirely, not that
 * something non-ASCII slips through.
 */
class KitBuilderLocaleTest {

    private val original: Locale = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
    }

    @Test
    fun `assigned pads keep their numeric suffixes under an eastern-digit locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val dir = Files.createTempDirectory("kitbuilder-locale").toFile()
        val m = KitBuilderModel.create("LOCALE KIT", dir)

        // Two pads of the same class, different slots: exercises the
        // displayName fallback's classCount counter (:72), which is shared
        // across every pad of a class regardless of slot, so the second
        // assign's counter actually reaches 02 - not just 01.
        val first = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        val second = m.assign(2, DrumSynth.kick(), DrumClass.KICK)

        // nextStem's own counter (:137) is scoped per slot+class, so it
        // only advances past 01 on a same-slot collision; exact-matching
        // the very first call already proves the fix, since a broken
        // build formats identical eastern digits at every n and
        // Names.sanitizeStem trims them all away regardless of value -
        // the loop would then never find an untaken name and spin
        // forever. Asserting the literal name here catches the bug
        // without ever exercising that collision path.
        assertEquals("A01_Kick_01.wav", first.sampleFile)
        assertEquals("A02_Kick_01.wav", second.sampleFile)
        assertEquals("Kick 01", first.displayName)
        assertEquals("Kick 02", second.displayName)

        for (pad in listOf(first, second)) {
            assertTrue(
                pad.sampleFile.all { it.code in 32..126 },
                "non-ASCII in sample filename: ${pad.sampleFile}",
            )
            assertTrue(
                pad.sampleStem.all { it.code in 32..126 },
                "non-ASCII in sample stem: ${pad.sampleStem}",
            )
            assertTrue(
                pad.displayName.all { it.code in 32..126 },
                "non-ASCII in display name: ${pad.displayName}",
            )
            assertTrue(Names.isMpcSafe(pad.sampleStem), "not MPC-safe: ${pad.sampleStem}")
        }
    }
}
