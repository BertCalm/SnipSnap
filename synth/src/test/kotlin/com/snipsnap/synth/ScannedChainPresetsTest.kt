package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScannedChainPresetsTest {
    private val originals = listOf(
        ScannedChainPatch("Spring Pluck", ScannedChainVoice.PLUCK),
        ScannedChainPatch("Metallic Trace", ScannedChainVoice.METAL),
        ScannedChainPatch("Evolving Bloom", ScannedChainVoice.BLOOM),
    )

    @Test
    fun `original factories retain their names settings and first position in each voice`() {
        assertEquals(originals, ScannedChainPresets.all().take(3))
        for (original in originals) {
            assertEquals(original, ScannedChainPresets.forVoice(original.voice).first())
            assertEquals(original, Presets.byName(original.engine, original.voiceName, original.name))
        }
    }

    @Test
    fun `six additional role presets are registered with finite editable settings`() {
        val all = ScannedChainPresets.all()
        assertEquals(9, all.size)
        assertEquals(all.size, all.map { it.name }.toSet().size)
        assertEquals(all, Presets.all().filter { it.engine == ScannedChainPatch.ENGINE })
        assertEquals(setOf("Short Pluck", "Low Pluck", "Bright Strike", "Long Ring", "Slow Bloom", "Round Bloom"),
            all.drop(3).map { it.name }.toSet())
        for (voice in ScannedChainVoice.entries) {
            val bank = ScannedChainPresets.forVoice(voice)
            assertEquals(3, bank.size)
            assertEquals(bank, Presets.forVoice(ScannedChainPatch.ENGINE, voice.name))
            assertEquals(3, bank.map { it.copy(name = "Same display name") }.toSet().size,
                "preset roles must differ in saved sound settings, not only their labels")
        }
        for (patch in all) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
            assertEquals(2, patch.model)
            assertEquals(setOf("MOTION", "DAMP", "BEND", "BLOOM"), patch.macros.keys)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
            assertTrue(patch.midi in ScannedChain.MIDI_MIN..ScannedChain.MIDI_MAX)
            assertTrue(patch.velocity > 0f && patch.velocity <= 1f)
            assertTrue(patch.config.seconds in .25f..4f)
            assertTrue(patch.config.attackSeconds + patch.config.releaseSeconds < patch.config.seconds)
            assertEquals(ScannedChainConfig.forVoice(patch.voice).mechanicalHz, patch.config.mechanicalHz)
            assertEquals(ScannedChainConfig.forVoice(patch.voice).driveHz, patch.config.driveHz)
            assertEquals(DrumClass.TONAL, ScannedChain.drumClassFor(patch.voice, patch.macros))
        }
    }

    @Test
    fun `all presets retain exact shape and settings through patch and pad recipe recall`() {
        for (patch in ScannedChainPresets.all()) {
            val decoded = assertIs<ScannedChainPatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, decoded)
            assertContentEquals(patch.shape.points.map { it.toRawBits() }, decoded.shape.points.map { it.toRawBits() })
            val recipe = PadRecipe(patch = patch)
            val recalled = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, recalled)
            assertEquals(recipe.toJsonText(), recalled.toJsonText())
        }
    }

    @Test
    fun `new roles render finite distinct audio and regenerate exactly from their recipes`() {
        val audioByVoice = mutableMapOf<ScannedChainVoice, MutableList<Snip>>()
        for (patch in ScannedChainPresets.all().drop(3)) {
            ScannedChainRenderCache.clear()
            val audio = patch.render()
            assertEquals(Dsp.RATE, audio.sampleRate)
            assertEquals(1, audio.channels)
            assertEquals((patch.config.seconds * Dsp.RATE).roundToInt(), audio.frameCount)
            assertTrue(audio.samples.all { it.isFinite() }, patch.name)
            assertTrue(audio.peak() in .01f..(.9f * patch.velocity + 1e-6f), patch.name)
            assertTrue(abs(audio.samples.average()) < 1e-5, patch.name)
            val recipe = PadRecipe.fromJsonText(PadRecipe(patch = patch).toJsonText())
            ScannedChainRenderCache.clear()
            assertContentEquals(audio.samples, recipe.render().samples,
                "${patch.name} must regenerate from its saved controls, envelope, note and shape")
            audioByVoice.getOrPut(patch.voice) { mutableListOf() }.add(audio)
        }
        for ((voice, pair) in audioByVoice) {
            val a = pair[0].samples
            val b = pair[1].samples
            // Compare the shared body interval after the attack, independent of overall
            // gain and file length, rather than accepting different duration fields alone.
            val from = (Dsp.RATE * .05).toInt()
            val to = minOf(a.size, b.size, (Dsp.RATE * .30).toInt())
            var aa = 0.0
            var bb = 0.0
            var ab = 0.0
            for (i in from until to) {
                aa += a[i].toDouble() * a[i]
                bb += b[i].toDouble() * b[i]
                ab += a[i].toDouble() * b[i]
            }
            assertTrue(aa > 1e-10 && bb > 1e-10)
            val residual = sqrt((bb - ab * ab / aa).coerceAtLeast(0.0) / bb)
            assertTrue(residual > .02, "$voice role presets must have different PCM beyond scalar gain")
        }
    }
}
