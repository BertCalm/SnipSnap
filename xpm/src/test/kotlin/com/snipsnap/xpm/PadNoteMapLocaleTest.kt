package com.snipsnap.xpm

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pad labels become filenames, and filenames go on a FAT card an MPC has to
 * browse. `String.format`'s `%d` follows the default locale, so on an
 * Arabic- or Persian-locale phone an unpinned `%02d` emits Eastern-Arabic
 * digits — a bug no desktop-JVM test can see.
 */
class PadNoteMapLocaleTest {

    private val original: Locale = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
    }

    @Test
    fun `pad labels stay ASCII under an eastern-digit locale`() {
        for (tag in listOf("ar-EG", "fa-IR", "my-MM")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals("A01", PadNoteMap.labelForPad(1), "locale $tag")
            assertEquals("A03", PadNoteMap.labelForPad(3), "locale $tag")
            assertEquals("A16", PadNoteMap.labelForPad(16), "locale $tag")
            assertEquals("B01", PadNoteMap.labelForPad(17), "locale $tag")
        }
    }
}
