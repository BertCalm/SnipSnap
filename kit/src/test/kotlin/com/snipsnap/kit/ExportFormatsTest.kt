package com.snipsnap.kit

import kotlin.test.Test
import kotlin.test.assertEquals

class ExportFormatsTest {

    @Test
    fun `the XPJ line says what actually rides along`() {
        assertEquals("MPC SESSION (.XPJ) — KITS + GROOVES", ExportFormat.MPC3_PROJECT.cyclerLabel)
        assertEquals("xpj", ExportFormat.MPC3_PROJECT.id, "the CLI word does not move")
    }

    @Test
    fun `every format's id is unique and lowercase`() {
        val ids = ExportFormat.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "two formats share an id")
        assertEquals(ids.map { it.lowercase() }, ids, "ids are the CLI's words, lowercase")
    }
}
