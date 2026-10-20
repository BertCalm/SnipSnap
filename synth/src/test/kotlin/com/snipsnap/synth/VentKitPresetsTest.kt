package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VentKitPresetsTest {
    private val voices = listOf(VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME)
    private val roles = listOf("Bass", "Short note", "Percussive hit", "Long texture")

    @Test
    fun `kit supplies four usable dry roles per clean voice in stable pad order`() {
        val entries = VentKitPresets.entries
        assertEquals(12, entries.size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertEquals(entries.size, entries.map { it.patch.name }.toSet().size)
        assertTrue(entries.all { it.id.matches(Regex("[a-z]+-(bass|note|hit|texture)")) })
        assertEquals(voices.flatMap { voice -> List(4) { voice } }, entries.map { it.patch.voice })
        assertEquals(VentKitPresets.all(), entries.map { it.patch })
        for (voice in voices) {
            val row = entries.filter { it.patch.voice == voice }
            assertEquals(roles, row.map { it.role })
            assertEquals(row.map { it.patch }, VentKitPresets.forVoice(voice))
            assertTrue(row[0].patch.midi < row[1].patch.midi, "$voice root needs a lower register")
        }
        for (voice in listOf(VentVoice.ROUND, VentVoice.WIRE, VentVoice.TEAR)) {
            assertTrue(VentKitPresets.forVoice(voice).isEmpty())
        }
        for (entry in entries) {
            val patch = entry.patch
            assertEquals(Vent.CLEAN_MODEL, patch.model)
            assertTrue(patch.midi % 12 in setOf(0, 3, 7), "${entry.id} leaves the kit's C-minor family")
            assertTrue(patch.velocity in .1f..1f)
            assertEquals(setOf("CHAOS", "LEASH", "VIEW", "DAMP", "PUSH", "LENGTH"), patch.macros.keys)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
            val recipe = PadRecipe(patch)
            assertEquals(null, recipe.fx)
            assertFalse(recipe.alias)
        }
    }

    @Test
    fun `starter recipes replay their complete finite audio without emergency resets`() {
        for (entry in VentKitPresets.entries) {
            val patch = entry.patch
            val recipe = PadRecipe(patch)
            val restored = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, restored, entry.id)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), entry.id)
            val rendered = Vent.renderDetailed(patch.voice, patch.macros, patch.midi,
                patch.velocity, patch.seed, patch.model)
            assertContentEquals(rendered.snip.samples, restored.render().samples, entry.id)
            assertEquals(0, rendered.diagnostics.integrationFailures, entry.id)
            assertEquals("clean-linear", rendered.diagnostics.signalPath, entry.id)
            assertTrue(rendered.diagnostics.rawRms > 1e-4, entry.id)
            assertTrue(rendered.snip.samples.all { it.isFinite() && abs(it) <= .991f }, entry.id)
            assertTrue(abs(rendered.snip.samples.last()) < 1e-4, "${entry.id} needs a finite faded end")
            val seconds = rendered.snip.frameCount.toDouble()/rendered.snip.sampleRate
            val range = when (entry.role) {
                "Bass" -> .3.. .7
                "Short note" -> .18.. .35
                "Percussive hit" -> .18.. .28
                "Long texture" -> 3.0..5.0
                else -> error("unknown musical role ${entry.role}")
            }
            assertTrue(seconds in range, "${entry.id} lasts $seconds seconds")
        }
    }

    @Test
    fun `leashed bass and short notes retain their chosen roots in the note body`() {
        for (entry in VentKitPresets.entries.filter { it.role == "Bass" || it.role == "Short note" }) {
            val patch = entry.patch
            val note = patch.render()
            val bodySeconds = minOf(.25f, note.frameCount.toFloat()/note.sampleRate-.05f)
            val pitch = assertNotNull(Pitch.detect(note, .03f, bodySeconds), entry.id)
            val cents = 1200.0*ln(pitch.hz/Keys.midiHz(patch.midi).toDouble())/ln(2.0)
            assertTrue(abs(cents) < 15.0, "${entry.id} root departed by $cents cents")
            assertTrue(pitch.confidence > .75f, "${entry.id} body has no stable root")
        }
    }
}
