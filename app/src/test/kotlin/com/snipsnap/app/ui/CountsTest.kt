package com.snipsnap.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CountsTest {

    @Test
    fun `one is singular`() {
        assertEquals("1 TAPE", tapes(1))
        assertEquals("1 SNIP", snips(1))
    }

    @Test
    fun `zero is plural`() {
        assertEquals("0 TAPES", tapes(0))
        assertEquals("0 SNIPS", snips(0))
    }

    @Test
    fun `many is plural`() {
        assertEquals("16 SNIPS", snips(16))
        assertEquals("42 TAPES", tapes(42))
    }
}
