package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScannedChainIntegrationTest {
    @Test
    fun `tonal cycle extraction saves a confident stereo source and refuses unusable windows`() {
        val rate = 44_100
        val source = Snip(FloatArray(rate) { sample ->
            val frame = sample / 2
            val fundamental = sin(2 * PI * 220 * frame / rate)
            val second = sin(2 * PI * 440 * frame / rate)
            (if (sample % 2 == 0) .4 * fundamental + .1 * second
                else .35 * fundamental - .05 * second).toFloat()
        }, channels = 2, sampleRate = rate)
        val shape = ScannedChainShape.fromSample(source, startFrame = 441,
            mode = ScannedChainSampleMode.TONAL_CYCLE, label = "Stereo cycle")
        assertEquals(ScannedChainShapeSource.SAMPLE, shape.source)
        val info = assertNotNull(shape.sample)
        assertEquals(ScannedChainSampleMode.TONAL_CYCLE, info.mode)
        assertEquals(2, info.channels)
        assertEquals(rate, info.sampleRate)
        assertEquals(441, info.startFrame)
        assertTrue(info.frames in 199..202, "one 220 Hz source cycle spans about 200 frames")
        assertTrue(assertNotNull(info.detectedHz) in 215f..225f)
        assertTrue(assertNotNull(info.confidence) >= .7f)
        assertTrue(info.windowSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(shape.points.all { it.isFinite() })

        val patch = ScannedChainPatch("Saved cycle", ScannedChainVoice.PLUCK, shape = shape,
            config = ScannedChainConfig.forVoice(ScannedChainVoice.PLUCK).copy(seconds = .25f, releaseSeconds = .05f))
        val savedJson = patch.toJsonText()
        val expected = patch.render()
        source.samples.fill(Float.NaN)
        val recalled = ScannedChainPatch.fromJsonText(savedJson)
        assertEquals(shape, recalled.shape)
        assertEquals(info, recalled.shape.sample)
        assertContentEquals(expected.samples, recalled.render().samples,
            "tonal-cycle replay needs the saved points, not the original stereo recording")

        val silent = Snip(FloatArray(rate / 2), 1, rate)
        val noise = Random(9847)
        val unpitched = Snip(FloatArray(rate / 2) { noise.nextFloat() * .8f - .4f }, 1, rate)
        for (input in listOf(silent, unpitched)) {
            assertFailsWith<IllegalArgumentException> {
                ScannedChainShape.fromSample(input, mode = ScannedChainSampleMode.TONAL_CYCLE)
            }
        }
        for (start in listOf(-1, silent.frameCount, silent.frameCount - 10)) {
            assertFailsWith<IllegalArgumentException> {
                ScannedChainShape.fromSample(silent, startFrame = start, mode = ScannedChainSampleMode.TIMBRAL_WINDOW)
            }
        }
    }

    @Test
    fun `a sampled shape survives recipe disk recall and native MPC exports without its source`() {
        val temp = kotlin.io.path.createTempDirectory("scanned-chain-integration").toFile()
        try {
            val source = Snip(FloatArray(4_410) { i ->
                (.4 * sin(2 * PI * 220 * i / 44_100) + .12 * sin(2 * PI * 660 * i / 44_100)).toFloat()
            }, channels = 1, sampleRate = 44_100)
            val shape = ScannedChainShape.fromSample(source, startFrame = 300,
                mode = ScannedChainSampleMode.TIMBRAL_WINDOW, label = "Saved room window")
            assertEquals(ScannedChainShapeSource.SAMPLE, shape.source)
            val provenance = assertNotNull(shape.sample)
            assertEquals(300, provenance.startFrame)
            assertEquals(882, provenance.frames)
            assertTrue(provenance.windowSha256.matches(Regex("[0-9a-f]{64}")))
            val patch = ScannedChainPatch("Saved window", ScannedChainVoice.PLUCK,
                macros = mapOf("MOTION" to .7f, "DAMP" to .3f, "BEND" to .5f, "BLOOM" to .2f),
                shape = shape, config = ScannedChainConfig.forVoice(ScannedChainVoice.PLUCK)
                    .copy(seconds = .4f, restFraction = .12f, attackSeconds = .01f, releaseSeconds = .08f, seed = 731),
                midi = 67, velocity = .65f)
            val recipe = PadRecipe(patch)
            val restored = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, restored)
            assertFalse(restored.alias)

            // Only the saved points and provenance remain useful. Replaying the
            // recipe must not consult the original Snip or an external WAV.
            source.samples.fill(Float.NaN)
            val original = recipe.render()
            assertContentEquals(original.samples, restored.render().samples)
            val work = File(temp, "work")
            KitAssembler.assembleArranged("Scanned Recall", listOf(ArrangedPad(
                original, DrumClass.TONAL, recipe.toJsonValue(), displayName = "SAVED WINDOW",
            )), work)
            val loaded = KitStore.load(work)
            val pad = loaded.pads.single()
            assertEquals(DrumClass.TONAL, pad.drumClass)
            assertTrue(pad.oneShot, "the first product voice renders finite pads")
            val saved = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            val recalled = assertIs<ScannedChainPatch>(saved.patch)
            assertEquals(patch, recalled)
            assertEquals(provenance, recalled.shape.sample)
            assertContentEquals(shape.points, recalled.shape.points)
            assertContentEquals(original.samples, saved.render().samples)

            val mpc2 = KitExporter.exportProgramFolder(loaded, work, File(temp, "mpc2"))
            val mpc3 = Mpc3Exporter.exportTrack(loaded, work, File(temp, "mpc3"), mpc2Twin = true)
            assertTrue(mpc2.program.isFile)
            assertTrue(Mpc3Project.read(mpc3.program).isTrack)
            assertEquals(1, mpc2.samples.size)
            assertEquals(1, mpc3.samples.size)
            val storedBytes = File(work, pad.sampleFile).readBytes()
            for (wav in mpc2.samples + mpc3.samples) {
                assertContentEquals(storedBytes, wav.readBytes(), "export preserves the rendered pad's WAV")
                val info = WavInfo.read(wav)
                assertEquals(44_100, info.sampleRate)
                assertEquals(24, info.bitsPerSample)
                assertEquals(1, info.channels)
                assertEquals(original.frameCount.toLong(), info.frameCount)
                assertTrue(WavReader.read(wav).samples.all { it.isFinite() })
            }
        } finally {
            temp.deleteRecursively()
        }
    }
}
