package com.snipsnap.shell

import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Presets
import com.snipsnap.synth.Splay
import com.snipsnap.synth.SplayAcceptedBank
import com.snipsnap.synth.SplayPatch
import com.snipsnap.synth.SplayPresets
import com.snipsnap.synth.SplayVoice
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplayDeliveryTest {
    @Test fun `delivery and factory retain five complete population settings under one voice`() {
        val patches = SplayDelivery.starterPatches()
        assertEquals(SplayAcceptedBank.entries.map { it.patch }, patches)
        assertEquals(Presets.forVoice("SPLAY", "TONGUE"), patches)
        assertEquals(listOf("1 mechanism", "2 mechanisms", "4 mechanisms", "8 mechanisms", "16 mechanisms"), patches.map { it.name })
        assertEquals(setOf(SplayVoice.TONGUE), patches.map { it.voice }.toSet())
        assertEquals(listOf(0f, 1f / 99f, 3f / 99f, 7f / 99f, 15f / 99f), patches.map { it.macros.getValue("POPULATION") })
        assertTrue(patches.all { it.midi == 60 && it.velocity == .8f && it.seed == 41001 && it.model == 2 && it.macros.getValue("HOLD") == 0f })
        assertEquals(listOf("First Seams", "Soft Brush", "Early Scatter", "Slow Thread", "Small Mechanism"), SplayPresets.all().map { it.name })
    }

    @Test fun `legacy defaults and complete performance survive editing root changes and navigation`() {
        for (model in listOf(1, 2)) {
            val sparse = SplayPatch("MY TONGUE", SplayVoice.TONGUE, mapOf("CONTACT" to .29f),
                midi = 49, velocity = .37f, seed = Int.MIN_VALUE, model = model)
            val controls = SplayDelivery.controls(sparse)
            assertEquals(if (model == 1) .55f else .62f, controls.getValue("TRACE"))
            assertEquals(.29f, controls.getValue("CONTACT"))
            val edited = SplayDelivery.edited(sparse, "EDITED TONGUE", controls + ("ENERGY" to .43f))
            assertEquals(sparse.copy(name = "EDITED TONGUE", macros = controls + ("ENERGY" to .43f)), edited)
            val moved = assertNotNull(SynthRoot.of(edited)).at(72) as SplayPatch
            val bag = mutableMapOf("engine" to "SPLAY", "voice" to "TONGUE")
            SynthDrafts.write(bag, listOf(moved))
            val restored = SynthDrafts.read(bag).single() as SplayPatch
            assertEquals(edited.copy(midi = 72), restored)
            assertEquals(SplayDelivery.controls(moved), SplayDelivery.controls(restored))
            assertEquals("SPLAY", bag["engine"])
        }
    }

    @Test fun `finite delivery refuses positive hold instead of inventing a repeating articulation`() {
        for (patch in SplayDelivery.starterPatches()) {
            val failure = assertFailsWith<IllegalArgumentException> {
                SplayDelivery.edited(patch, patch.name, patch.macros + ("HOLD" to .01f))
            }
            assertTrue(failure.message.orEmpty().contains("requires HOLD 0"))
            assertFalse(Splay.isLoop(1f))
        }
    }

    @Test fun `fixed population starter is filed exactly once without implying extra voices or rerolls`() {
        val starter = assertNotNull(StarterKits.byId(SplayDelivery.STARTER_ID))
        assertEquals("SPLAY POPULATIONS", starter.displayName)
        assertFalse(starter.seeded)
        assertNull(starter.key)
        assertEquals(1, StarterKits.ALL.count { it.id == starter.id })
        assertEquals(1, StarterShelf.GROUPS.sumOf { group -> group.ids.count { it == starter.id } })
        assertTrue(starter.blurb.startsWith("One TONGUE voice."))
        assertEquals(listOf(EngineCategory.BELLS, EngineCategory.PADS), assertNotNull(EngineCatalog.entry("SPLAY")).categories)
    }

    /** Five actual starter renders plus one saved-recipe replay; separate from the four pure methods. */
    @Test fun `actual starter writes five finite native pads and replays its saved recipe without a hidden gain policy`() {
        val directory = kotlin.io.path.createTempDirectory("splay-finite-starter-").toFile()
        try {
            val kit = assertNotNull(StarterKits.byId(SplayDelivery.STARTER_ID)).render("Splay Populations", directory)
            assertEquals((1..5).toList(), kit.pads.map { it.slot })
            assertEquals(SplayDelivery.starterPatches(), kit.pads.map { PadRecipe.fromJsonValue(it.recipe!!).patch })
            assertFalse(Preflight.check(kit, directory).blocked())
            assertEquals(kit, KitStore.load(directory))
            kit.pads.zip(SplayAcceptedBank.entries).forEach { (pad, accepted) ->
                assertTrue(pad.oneShot && !pad.loop)
                assertTrue(pad.velocityLayers.isEmpty())
                assertNull(pad.chain); assertNull(pad.robin)
                assertEquals(0, pad.tuneCoarse); assertEquals(0, pad.tuneFine); assertEquals(0, pad.muteGroup)
                assertEquals(accepted.id, pad.source["settingId"])
                assertEquals(accepted.mechanisms.toString(), pad.source["populationCount"])
                assertEquals("60", pad.source["rootMidi"])
                assertEquals("authored", pad.source["rootOrigin"])
                assertEquals("native-patch-level", pad.source["renderPolicy"])
                val info = WavInfo.read(File(directory, pad.sampleFile))
                assertEquals(352_800L, info.frameCount)
                assertEquals(44_100, info.sampleRate)
                assertEquals(1, info.channels)
                assertEquals(24, info.bitsPerSample)
            }
            val pad = kit.pads[2]
            val replay = File(directory, "saved-recipe-replay.wav")
            WavWriter.write(replay, PadRecipe.fromJsonValue(pad.recipe!!).render())
            assertContentEquals(File(directory, pad.sampleFile).readBytes(), replay.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }
}
