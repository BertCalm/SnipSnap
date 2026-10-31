package com.snipsnap.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.audio.Snip
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.AquariumPadSheet
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.synth.AquariumRecipe
import com.snipsnap.synth.AquariumRenderRecipe
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Real JVM/JNI transfers and the production core, without opening an audio stream. */
@RunWith(AndroidJUnit4::class)
class AquariumPadRendererTest {
    private val temp = File.createTempFile(
        "aquarium-pad-", "", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
    ).apply { check(delete()); check(mkdirs()) }

    @After fun cleanup() { temp.deleteRecursively() }

    private fun controls(settings: AquariumRecipe = AquariumRecipe()): FloatArray =
        AquariumRecipe.PARAMETERS.map { settings.parameters.getValue(it.id) }.toFloatArray()

    private fun raw(
        source: FloatArray,
        channels: Int = 1,
        values: FloatArray = controls(),
        seed: Long = 1337L,
        tail: Int = 0,
        gain: Float = 1f,
        ceiling: Float = 1f,
    ): FloatArray = NativeAquarium.renderOffline(source, channels, 48000, values, seed, 0.0, tail, 0, gain, ceiling, 37)

    private fun rejected(word: String, block: () -> Unit) {
        var failure: IllegalArgumentException? = null
        try { block() } catch (e: IllegalArgumentException) { failure = e }
        assertTrue("native refusal must name $word, got ${failure?.message}", failure?.message?.contains(word, ignoreCase = true) == true)
    }

    @Test fun dryMonoDuplicatesWithoutChangingTheJavaSource() {
        val source = floatArrayOf(.25f, -.5f, 0f, .75f, -.125f)
        val original = source.copyOf()
        assertArrayEquals(floatArrayOf(.25f, .25f, -.5f, -.5f, 0f, 0f, .75f, .75f, -.125f, -.125f), raw(source), 0f)
        assertArrayEquals(original, source, 0f)
    }

    @Test fun stereoKeepsIndependentChannelsAndActualRate() {
        val input = floatArrayOf(.4f, -.2f, .1f, .3f, -.5f, .125f)
        val recipe = AquariumRenderRecipe(tailSeconds = 0.0, fadeSeconds = 0.0, outputGain = .5f)
        val output = AquariumPadRenderer.render(Snip(input, 2, 96000), recipe)
        assertEquals(96000, output.sampleRate)
        assertEquals(2, output.channels)
        assertArrayEquals(input.map { it * .5f }.toFloatArray(), output.samples, 0f)
    }

    @Test fun savedWetContextRepeatsWithTempoTailAndFinishedFade() {
        val source = Snip(FloatArray(4096) { i -> (.12 * sin(2.0 * PI * (if (i % 2 == 0) 317 else 521) * (i / 2) / 48000)).toFloat() }, 2, 48000)
        val settings = AquariumRecipe(mapOf("aqua_drift_mix" to .65f, "aqua_tide_mix" to .5f, "aqua_reef_mix" to .25f), seed = 0xffff_ffffL)
        val saved = AquariumRenderRecipe(settings, bpm = 123.0, tailSeconds = .01, fadeSeconds = .002, outputGain = .25f)
        val a = AquariumPadRenderer.render(source, saved)
        val b = AquariumPadRenderer.render(source, AquariumRenderRecipe.fromJsonText(saved.toJsonText()))
        assertArrayEquals(a.samples, b.samples, 0f)
        assertEquals(source.frameCount + 480, a.frameCount)
        assertTrue(a.samples.all { it.isFinite() && abs(it) <= saved.peakCeiling })
        assertEquals(0f, a.samples[a.samples.lastIndex], 0f)
        assertEquals(0f, a.samples[a.samples.lastIndex - 1], 0f)
        assertTrue(a.samples.take(source.samples.size).zip(source.samples.toList()).any { (wet, dry) -> abs(wet - dry * saved.outputGain) > 1e-5f })
    }

    @Test fun malformedJavaArgumentsThrowAndLeaveTheBridgeUsable() {
        val input = floatArrayOf(.25f, -.25f)
        rejected("52") { raw(input, values = controls().copyOf(51)) }
        rejected("seed") { raw(input, seed = -1L) }
        rejected("finite") { raw(floatArrayOf(Float.NaN)) }
        rejected("frame") { raw(input, tail = NativeAquarium.maximumOutputFrames()) }
        assertArrayEquals(floatArrayOf(.25f, .25f, -.25f, -.25f), raw(input), 0f)
    }

    @Test fun peakRefusalDoesNotClipOrAlterTheInput() {
        val input = floatArrayOf(.75f, -.75f)
        rejected("peak") { raw(input, ceiling = .5f) }
        assertArrayEquals(floatArrayOf(.75f, -.75f), input, 0f)
        assertArrayEquals(floatArrayOf(.1875f, .1875f, -.1875f, -.1875f), raw(input, gain = .25f, ceiling = .5f), 0f)
    }

    @Test fun realPadEditsRenderAllLayersFromSavedSourcesAndRestoreMetadata() {
        val dir = File(temp, "kit").apply { check(mkdirs()) }
        val hard = Snip(FloatArray(1024) { i -> ((if (i % 2 == 0) .4 else -.3) * sin(2.0 * PI * 220 * (i / 2) / 44100)).toFloat() }, 2, 44100)
        val soft = Snip(FloatArray(512) { i -> (.2 * sin(2.0 * PI * 220 * i / 44100)).toFloat() }, 1, 44100)
        WavWriter.write(File(dir, "hard.wav"), hard, WavWriter.BitDepth.PCM_24, smpl = SmplChunk(60))
        WavWriter.write(File(dir, "soft.wav"), soft, WavWriter.BitDepth.PCM_24)
        val originalBytes = File(dir, "hard.wav").readBytes()
        val prior = Json.parse("""{"capture":1,"unknown":{"preserve":true}}""") as JsonValue.Obj
        val original = KitPad(1, "hard.wav", recipe = prior, velocityLayers = listOf(KitLayer("soft.wav", 0, 63), KitLayer("hard.wav", 64, 127)))
        KitStore.save(Kit("Aquarium native", listOf(original)), dir)
        val model = KitBuilderModel.open(dir)
        val recipe = AquariumRenderRecipe(AquariumRecipe(mapOf("aqua_surface_mix" to 1f)), tailSeconds = 0.0, fadeSeconds = 0.0)
        AquariumPadSheet.apply(model, 1, recipe, AquariumPadRenderer)
        model.save()
        val reopened = KitBuilderModel.open(dir)
        assertEquals(recipe, AquariumPadSheet.read(reopened.pad(1)!!.recipe))
        val edited = AquariumPadSheet.apply(reopened, 1, recipe.copy(outputGain = .25f), AquariumPadRenderer)
        for (layer in edited.velocityLayers) {
            val expectedFile = if (layer.velStart == 0) "soft.wav" else "hard.wav"
            val expected = WavReader.read(File(dir, expectedFile))
            val rendered = WavReader.read(File(dir, layer.sampleFile))
            assertEquals(2, rendered.channels)
            assertEquals(expected.frameCount, rendered.frameCount)
            for (frame in 0 until expected.frameCount) for (channel in 0..1) {
                val sample = expected.samples[frame * expected.channels + if (expected.channels == 1) 0 else channel]
                assertEquals(sample * .25f, rendered.samples[frame * 2 + channel], 5e-7f)
            }
        }
        assertEquals(60, WavReader.readSmpl(File(dir, edited.sampleFile))!!.rootNote)
        reopened.update(1) { it.copy(pan = .75f) }
        val restored = AquariumPadSheet.remove(reopened, 1)
        assertEquals(prior, restored.recipe)
        assertEquals(original.sampleFile, restored.sampleFile)
        assertEquals(original.velocityLayers, restored.velocityLayers)
        assertEquals(.75f, restored.pan, 0f)
        assertArrayEquals(originalBytes, File(dir, restored.sampleFile).readBytes())
        assertTrue(AquariumPadSheet.canUndo(reopened, 1))
        val undone = AquariumPadSheet.undo(reopened, 1)
        assertEquals(edited.recipe, undone.recipe)
        assertEquals(.75f, undone.pan, 0f)
    }
}
