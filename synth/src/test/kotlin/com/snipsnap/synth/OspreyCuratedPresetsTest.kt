package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OspreyCuratedPresetsTest {
    @Test
    fun `musical bank follows the unchanged fifteen coast weather references`() {
        val shores = listOf("ATLANTIC" to 0f, "NORDIC" to .25f, "MEDITERRANEAN" to .5f,
            "PACIFIC" to .75f, "SOUTHERN" to 1f)
        val seas = listOf("CALM" to .1f, "ROUGH" to .5f, "STORM" to .9f)
        val expectedReferences = shores.flatMap { (shore, position) ->
            seas.map { (sea, energy) ->
                OspreyPatch("$shore $sea", OspreyVoice.SWELL,
                    mapOf("SHORE" to position, "SEA" to energy, "MOTION" to .45f,
                        "DEPTH" to .55f, "SPACE" to 0f, "HOLD" to .45f), seed = 731)
            }
        }
        val all = OspreyPresets.all()
        assertEquals(expectedReferences, OspreyPresets.coastWeather())
        assertEquals(expectedReferences, all.take(15))
        assertEquals(OspreyCuratedPresets.all(), all.drop(15))
        assertEquals(31, all.size)
        assertEquals(31, all.map { it.name }.toSet().size)
        assertEquals(all, OspreyPresets.forVoice(OspreyVoice.SWELL))
        assertEquals(all, Presets.forVoice("OSPREY", "SWELL"))
        assertTrue(Presets.all().containsAll(all))
        for (patch in all) assertEquals(patch, Presets.byName("OSPREY", "SWELL", patch.name))
    }

    @Test
    fun `curated metadata covers four musical roles with complete model three recipes`() {
        val entries = OspreyCuratedPresets.entries
        assertEquals(16, entries.size)
        assertEquals(16, entries.map { it.id }.toSet().size)
        assertEquals(listOf("foundations", "short", "textures", "swells"), entries.map { it.role }.distinct())
        assertEquals(mapOf("foundations" to 4, "short" to 4, "textures" to 4, "swells" to 4),
            entries.groupingBy { it.role }.eachCount())
        assertEquals(entries.map { it.patch }, OspreyCuratedPresets.all())
        for (entry in entries) {
            val patch = entry.patch
            assertTrue(entry.id.matches(Regex("[a-z]+(?:_[a-z]+)*")), entry.id)
            assertEquals(patch.name.lowercase().replace(' ', '_'), entry.id)
            assertTrue(entry.description.isNotBlank(), entry.id)
            assertEquals(OspreyVoice.SWELL, patch.voice)
            assertEquals(3, patch.modelVersion, "${entry.id} must keep the approved model even after a new default")
            assertEquals(731, patch.seed)
            assertEquals(setOf("SHORE", "SEA", "MOTION", "DEPTH", "SPACE", "HOLD"), patch.macros.keys)
            assertEquals(patch, OspreyPatch.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch.toJsonText(), Patches.fromJsonText(patch.toJsonText()).toJsonText())
        }
    }

    @Test
    fun `every curated recipe repeats finite bounded stereo audio through the unchanged model three route`() {
        for (entry in OspreyCuratedPresets.entries) {
            val patch = entry.patch
            val rendered = patch.render()
            val replayed = Patches.fromJsonText(patch.toJsonText()).render()
            val direct = OspreyV3.render(patch.voice, patch.macros, patch.midi, patch.velocity,
                patch.seed, patch.durationSeconds)
            assertEquals(2, rendered.channels, entry.id)
            assertEquals(44_100, rendered.sampleRate, entry.id)
            assertEquals((patch.durationSeconds * 44_100).roundToInt(), rendered.frameCount, entry.id)
            assertTrue(rendered.samples.any { it != 0f }, "${entry.id} is silent")
            assertTrue(rendered.samples.all { it.isFinite() && abs(it) < 1f }, "${entry.id} exceeds full scale")
            assertContentEquals(rendered.samples, replayed.samples, "${entry.id} recipe replay changed its audio")
            // Exact float equality also rejects normalization, clipping or an extra gain on factory renders.
            assertContentEquals(direct.samples, rendered.samples, "${entry.id} changed the model-3 output route")
        }
    }
}
