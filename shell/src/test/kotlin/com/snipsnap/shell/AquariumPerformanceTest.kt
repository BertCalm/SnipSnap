package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.AquariumControls
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AquariumPerformanceTest {
    private val root = Files.createTempDirectory("aquarium-performance-storage").toFile()
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun fixture(): File = File(root, "KIT").apply {
        mkdirs()
        WavWriter.write(File(this, "pad.wav"), Snip(FloatArray(256) { .2f }, 1, 44100))
        KitStore.save(Kit("KIT", listOf(KitPad(1, "pad.wav")), tempoBpm = 123.5f), this)
    }

    private fun wet() = AquariumPerformanceSettings(
        AquariumRecipe(AquariumControls.preset("full").settings.parameters, seed = 0xffff_ffffL),
        enabled = true, outputGain = .125f,
    )

    @Test fun `old kits open dry without any write and explicit settings survive unrelated saves`() {
        val dir = fixture()
        val json = File(dir, KitStore.FILE_NAME)
        val before = json.readBytes()
        assertEquals(AquariumPerformanceSettings(), AquariumPerformance.read(KitStore.load(dir)).settings)
        assertContentEquals(before, json.readBytes())
        val settings = wet()
        AquariumPerformance.save(dir, settings)
        val model = KitBuilderModel.open(dir)
        model.setTempo(97f)
        model.update(1) { it.copy(level = .7f) }
        model.save()
        val reopened = KitStore.load(dir)
        assertEquals(settings, AquariumPerformance.read(reopened).settings)
        assertEquals(97f, reopened.tempoBpm)
        assertEquals(.7f, reopened.pad(1)!!.level)
        assertEquals(52, AquariumPerformance.read(reopened).settings.settings.parameters.size)
    }

    @Test fun `save reopens current metadata leaves source bytes alone and archives the previous settings`() {
        val dir = fixture()
        val audio = File(dir, "pad.wav").readBytes()
        val first = wet()
        AquariumPerformance.save(dir, first)
        val editor = KitBuilderModel.open(dir)
        editor.update(1) { it.copy(pan = .2f, displayName = "LATEST") }
        editor.setTempo(88f)
        editor.save()
        val second = first.copy(enabled = false, outputGain = 0f)
        val kit = AquariumPerformance.save(dir, second)
        assertEquals("LATEST", kit.pad(1)!!.displayName)
        assertEquals(.2f, kit.pad(1)!!.pan)
        assertEquals(88f, kit.tempoBpm)
        assertEquals(second, AquariumPerformance.read(KitStore.load(dir)).settings)
        assertContentEquals(audio, File(dir, "pad.wav").readBytes())
        val takes = KitBuilderModel.open(dir).takes()
        assertTrue(takes.any { AquariumPerformance.read(KitStore.read(it)).settings == first })
    }

    @Test fun `damaged and future settings keep the kit readable and survive normal edits until explicitly replaced`() {
        val dir = fixture()
        val valid = wet().toJsonValue()
        for (raw in listOf(
            JsonValue.Str("damaged"), JsonValue.Null,
            JsonValue.Obj(valid.entries + ("aquariumPerformance" to JsonValue.Num(999.0))),
            JsonValue.Obj(valid.entries + ("outputGain" to JsonValue.Num(2.0))),
        )) {
            KitStore.save(KitStore.load(dir).copy(aquariumPerformance = raw), dir)
            val model = KitBuilderModel.open(dir)
            val loaded = AquariumPerformance.read(model.kit)
            assertFalse(loaded.settings.enabled)
            assertTrue(loaded.settings.settings.isBypass)
            assertNotNull(loaded.warning)
            model.setTempo(99f)
            model.save()
            assertEquals(raw, KitStore.load(dir).aquariumPerformance)
            AquariumPerformance.save(dir, wet())
            assertNull(AquariumPerformance.read(KitStore.load(dir)).warning)
        }
    }

    @Test fun `saving to a removed kit refuses without recreating its directory`() {
        val dir = fixture()
        dir.deleteRecursively()
        assertFailsWith<java.io.IOException> { AquariumPerformance.save(dir, wet()) }
        assertFalse(dir.exists())
    }
}
