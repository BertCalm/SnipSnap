package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent fixtures copied from the published, heard recipe download on 31 October. */
class MurmurSelectedPresetsTest {
    @Test
    fun `six appended factory presets reproduce the published saved recipes and all twelve WAV hashes`() {
        val rows = Json.parse(resource("manifest.json")).obj().getValue("clips").arr().map { it.obj() }
        val selected = MurmurPresets.selected()
        assertEquals(6, selected.size)
        assertEquals(MurmurPresets.original() + selected, MurmurPresets.all())
        assertEquals(42, MurmurPresets.all().map { it.name }.toSet().size)
        assertEquals(rows.map { it.getValue("name").str() }, selected.map { it.name })
        for ((row, patch) in rows.zip(selected)) {
            val expected = MurmurPatch.fromJsonText(resource(row.getValue("recipePath").str()))
            assertEquals(expected, patch, "${patch.name} changed a heard macro, root or saved configuration")
            val recipe = PadRecipe.fromJsonText(PadRecipe(patch).toJsonText())
            assertEquals(patch, recipe.patch)
            assertNull(recipe.fx)
            assertNull(recipe.terminalFadeMs, "the preset must retain the seamless source")
            assertEquals(3, patch.config.model)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
            val probe = Murmur.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config)
            assertEquals(row.getValue("frames").int(), probe.samples.size)
            val raw = Snip(probe.samples, 1, Dsp.RATE)
            assertEquals(row.getValue("rawSha256").str(), wavHash(raw), patch.name)
            assertEquals(row.getValue("matchedSha256").str(), wavHash(AuditionLevel.level(raw)), patch.name)
            if (patch.voice == MurmurVoice.CHOIR) {
                assertEquals(.1f, patch.macros.getValue("BREATH"))
                val seam = Keys.seamError(probe.previousCycle + probe.samples, probe.previousCycle.size)
                assertEquals(probe.seamError, seam)
                assertTrue(seam < Keys.MAX_SEAM_ERROR, "${patch.name} no longer joins its preceding rendered cycle")
            }
        }
    }

    @Test
    fun `selected starter includes all six heard settings all voice roles and bounded one shot choir delivery`() {
        val pads = SynthKits.murmurSelected().map { assertNotNull(it) }
        assertEquals(16, pads.size)
        val recipes = pads.map { PadRecipe.fromJsonValue(assertNotNull(it.recipe)) }
        val patches = recipes.map { assertIs<MurmurPatch>(it.patch) }
        assertEquals(listOf(48, 51, 53, 55, 58, 60), patches.take(6).map { it.midi })
        assertTrue(patches.take(6).all { it.config.model == 3 && it.macros.getValue("MOTION") == .5f })
        assertEquals(MurmurVoice.entries.toSet(), patches.map { it.voice }.toSet())
        assertEquals(MurmurPresets.selected(), listOf(6, 7, 8, 9, 14, 15).map { patches[it] })
        for ((index, pad) in pads.withIndex()) {
            val recipe = recipes[index]
            val patch = patches[index]
            assertTrue(pad.oneShot)
            assertNull(recipe.fx)
            assertEquals(Murmur.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            if (index < 14) assertNull(recipe.terminalFadeMs)
            else {
                assertEquals(DrumClass.LOOP, pad.drumClass)
                assertEquals(8f, recipe.terminalFadeMs)
                val source = patch.render()
                val fadeFrames = (8 * Dsp.RATE / 1000f).toInt()
                assertEquals(source.frameCount, pad.snip.frameCount)
                assertContentEquals(source.samples.copyOfRange(0, source.frameCount - fadeFrames),
                    pad.snip.samples.copyOfRange(0, source.frameCount - fadeFrames))
                assertEquals(0f, pad.snip.samples.last())
                assertContentEquals(recipe.render().samples, pad.snip.samples)
            }
        }
    }

    private fun resource(path: String) = checkNotNull(javaClass.getResource("/murmur-selected/$path")).readText()
    private fun wavHash(snip: Snip): String {
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24) }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
