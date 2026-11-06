package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.TerraPatch
import com.snipsnap.synth.TerraVoice
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Robin on a modal-drum synth pad: each take is hit again (Restrike), not resampled. */
class RobinRestrikeTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("robin-restrike").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun terraModel(name: String): KitBuilderModel {
        val patch = TerraPatch("Djembe", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.7185f, "FORCE" to 0.5f, "POS" to 0.5f))
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, patch.render(), DrumClass.PERC)
        m.update(1) { it.copy(recipe = PadRecipe(patch = patch).toJsonValue()) }
        return m
    }

    /**
     * A resampled take changes length with its pitch (a few cents is a few
     * frames on a short hit); a re-struck take is the drum's own length,
     * less only Robin's start jitter (at most 2 ms).
     */
    @Test
    fun `a TERRA pad's takes are new hits at the drum's own length`() {
        val m = terraModel("Restrike")
        val padFile = File(m.kitDir, m.pad(1)!!.sampleFile)
        val original = WavReader.read(padFile)
        val pad = Robin.apply(m, 1, takes = 4, seed = 3)
        val chain = pad.chain!!
        val chained = WavReader.read(padFile)
        val lengths = (0 until 4).map { i -> chain.window(i, chained.frameCount.toLong()).let { (it.last - it.first + 1).toInt() } }
        assertEquals(original.frameCount, lengths[0], "take one is the original")
        val jitter = (Robin.START_JITTER_SEC * original.sampleRate).toInt() + 1
        for (t in 1 until 4) {
            assertTrue(lengths[t] in original.frameCount - jitter..original.frameCount, "take ${t + 1}: ${lengths[t]} frames against ${original.frameCount}")
        }
        val takes = (0 until 4).map { i -> chain.window(i, chained.frameCount.toLong()).let { chained.samples.copyOfRange(it.first.toInt(), (it.last + 1).toInt()) } }
        for (i in 1 until 4) for (j in i + 1 until 4) assertFalse(takes[i].contentEquals(takes[j]), "takes ${i + 1} and ${j + 1} differ")
    }

    @Test
    fun `the same seed re-strikes the same chain`() {
        val a = terraModel("A").let { m -> Robin.apply(m, 1, takes = 3, seed = 5); WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile)).samples }
        val b = terraModel("B").let { m -> Robin.apply(m, 1, takes = 3, seed = 5); WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile)).samples }
        assertTrue(a.contentEquals(b))
    }
}
