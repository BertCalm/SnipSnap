package com.snipsnap.shell

import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticPresets
import com.snipsnap.synth.CausticVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CausticEditingTest {
    @Test fun `opening every original preset exposes zero extensions without changing its recipe`() {
        for (patch in CausticPresets.all().filter { it.model == 1 }) {
            val before = patch.toJsonText()
            val controls = CausticEditing.controls(patch, mapOf("HOLD" to 1f, "MOTION" to .8f))
            assertEquals(0f, controls.getValue("HOLD"))
            assertEquals(0f, controls.getValue("MOTION"))
            assertEquals(patch, CausticEditing.edited(patch, macros = controls))
            assertEquals(before, patch.toJsonText())
        }
    }

    @Test fun `only a deliberate extension request changes an original model`() {
        for (voice in CausticVoice.entries) {
            val original = CausticPatch("Saved direction", voice, mapOf("BODY" to .6f),
                midi = 55, velocity = .37f, model = 1)
            val before = original.toJsonText()
            val controls = CausticEditing.controls(original)
            val moved = CausticEditing.edited(original, "Moved pickup", controls + ("FOCUS" to .31f))
            assertEquals(1, moved.model)
            assertFalse("MOTION" in moved.macros)
            for (extension in listOf("HOLD" to .4f, "MOTION" to .7f)) {
                val expanded = CausticEditing.edited(original, macros = controls + extension,
                    requestExtension = true)
                assertEquals(2, expanded.model)
                assertEquals(voice, expanded.voice)
                assertEquals(55, expanded.midi)
                assertEquals(.37f, expanded.velocity)
                assertEquals(.6f, expanded.macros.getValue("BODY"))
                assertEquals(extension.second, expanded.macros.getValue(extension.first))
            }
            val error = assertFailsWith<IllegalArgumentException> {
                CausticEditing.edited(original, macros = controls + ("MOTION" to .2f))
            }
            assertTrue(error.message.orEmpty().contains("MOTION"))
            assertEquals(before, original.toJsonText())
        }
    }

    @Test fun `restoring expanded drafts retains held controls over stale macro bags`() {
        val patch = CausticPatch("Held draft", CausticVoice.RUBBED_RING,
            mapOf("MOTION" to .65f, "HOLD" to 1f), midi = 67, velocity = .42f, model = 2)
        val controls = CausticEditing.controls(patch, mapOf("HOLD" to 0f, "MOTION" to 0f, "SOURCE" to .33f))
        assertEquals(1f, controls.getValue("HOLD"))
        assertEquals(.65f, controls.getValue("MOTION"))
        assertEquals(.33f, controls.getValue("SOURCE"))
        val restored = CausticEditing.edited(patch, macros = controls)
        assertEquals(2, restored.model)
        assertEquals(patch.midi, restored.midi)
        assertEquals(patch.velocity, restored.velocity)
    }
}
