package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PadRecipeBoardTest {
    @Test
    fun `saved synth board preserves the source patch and its finishing rack`() {
        val patch = PluckPatch("Nylon with room", PluckVoice.NYLON, mapOf("DAMP" to .4f))
        val fx = FxChain(eq = mapOf("BASS" to .55f))
        val original = PadRecipe(patch = patch, fx = fx)
        val edited = original.copy(board = BoardRecipe(amount = .7f))
        val restored = PadRecipe.fromJsonText(edited.toJsonText())
        assertEquals(edited, restored)
        assertEquals(original, restored.copy(board = null))
        val expected = Wide.process(original.render(), .7f)
        val actual = restored.render()
        assertEquals(expected.channels, actual.channels)
        assertTrue(expected.samples.contentEquals(actual.samples))
    }

    @Test
    fun `captured board recipe can reprocess its source and old recipes still roundtrip`() {
        val dry = Thump.render(ThumpVoice.SNARE)
        val boardOnly = PadRecipe(board = BoardRecipe(amount = .35f))
        val restored = PadRecipe.fromJsonText(boardOnly.toJsonText())
        assertTrue(Wide.process(dry, .35f).samples.contentEquals(restored.process(dry).samples))
        val legacy = PadRecipe(fx = FxChain(tape = mapOf("DRIVE" to .2f)))
        val oldJson = legacy.toJsonText()
        val read = PadRecipe.fromJsonText(oldJson)
        assertNull(read.board)
        assertEquals(oldJson, read.toJsonText())
        assertEquals(2, PadRecipe.VERSION)
    }
}
