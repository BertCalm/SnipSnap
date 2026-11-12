package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpPatch
import com.snipsnap.synth.ThumpVoice
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A THUMP SUB pad on its own, outside the CYPHER kits: the robin re-strikes it (each take a fresh strike at
 * the drum's own length) and the kit saves and reloads with its chain.
 */
class ThumpSubRobinTest {
    private val temp: File = java.nio.file.Files.createTempDirectory("sub-robin").toFile()

    @AfterTest fun cleanUp() { temp.deleteRecursively() }

    private fun subModel(name: String): KitBuilderModel {
        val patch = ThumpPatch("Slide", ThumpVoice.SUB, Thump.defaults(ThumpVoice.SUB) + mapOf("SLIDE" to .29f, "DECAY" to .7f))
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, patch.render(), DrumClass.KICK)
        m.update(1) { it.copy(recipe = PadRecipe(patch = patch).toJsonValue()) }
        return m
    }

    @Test fun `a SUB pad's takes are fresh strikes at the drum's own length and the kit reloads with them`() {
        val m = subModel("Sub")
        val padFile = File(m.kitDir, m.pad(1)!!.sampleFile)
        val original = WavReader.read(padFile)
        val pad = Robin.apply(m, 1, takes = 4, seed = 3)
        val chain = assertNotNull(pad.chain)
        val chained = WavReader.read(padFile)
        val windows = (0 until 4).map { chain.window(it, chained.frameCount.toLong()) }
        assertEquals(original.frameCount, (windows[0].last - windows[0].first + 1).toInt(), "take one is the original")
        val jitter = (Robin.START_JITTER_SEC * original.sampleRate).toInt() + 1
        for (t in 1 until 4) {
            val length = (windows[t].last - windows[t].first + 1).toInt()
            assertTrue(length in original.frameCount - jitter..original.frameCount, "take ${t + 1}: $length frames against ${original.frameCount}")
        }
        val takes = windows.map { chained.samples.copyOfRange(it.first.toInt(), (it.last + 1).toInt()) }
        for (i in 1 until 4) for (j in i + 1 until 4) assertFalse(takes[i].contentEquals(takes[j]), "takes ${i + 1} and ${j + 1} differ")
        // The saved kit carries the chain (the zoned grid's own bookkeeping replaces the recipe, as for every
        // engine's robin), with its four-take cycle and its zones.
        m.save()
        val saved = KitStore.load(m.kitDir)
        val loaded = saved.pads.single { it.slot == 1 }
        val reloaded = assertNotNull(loaded.chain)
        assertEquals(4, reloaded.cycle)
        assertEquals(chain.boundaries, reloaded.boundaries)
    }
}
