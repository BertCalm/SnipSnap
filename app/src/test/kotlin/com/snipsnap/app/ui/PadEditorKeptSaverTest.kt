package com.snipsnap.app.ui

import androidx.compose.runtime.saveable.SaverScope
import com.snipsnap.synth.AquariumEditor
import com.snipsnap.synth.AquariumRenderRecipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The pad editor's Aquarium draft, as it is parked while a tool is open over the editor. */
class PadEditorKeptSaverTest {
    private val scope = SaverScope { true }

    private fun staged(): AquariumEditor =
        AquariumEditor(AquariumRenderRecipe()).set("aqua_tide_mix", 0.5f)

    @Test
    fun `a staged draft comes back equal for the pad it was made on`() {
        val saver = aquariumEditorSaver(7)
        val saved = with(saver) { scope.save(staged()) }
        assertNotNull(saved)
        assertEquals(staged().render, saver.restore(saved)?.render)
    }

    @Test
    fun `a draft made against another pad or another applied sound is dropped`() {
        val saved = with(aquariumEditorSaver(7)) { scope.save(staged()) }!!
        assertNull(aquariumEditorSaver(8).restore(saved))
    }

    @Test
    fun `a damaged draft is dropped, not thrown`() {
        assertNull(aquariumEditorSaver(7).restore("7\n{not json"))
        assertNull(aquariumEditorSaver(7).restore("no fingerprint"))
    }
}
