package com.snipsnap.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SheetRowTest {

    @Test
    fun `a row with a description is spoken as its name, then its description`() {
        assertEquals("BACKUP. EVERY KIT, ONE FILE", sheetRowSpoken("BACKUP", "EVERY KIT, ONE FILE"))
    }

    @Test
    fun `a row with none is spoken as its name alone`() {
        assertEquals("SETTINGS ▸", sheetRowSpoken("SETTINGS ▸", null))
        assertEquals("SETTINGS ▸", sheetRowSpoken("SETTINGS ▸", ""))
    }
}
