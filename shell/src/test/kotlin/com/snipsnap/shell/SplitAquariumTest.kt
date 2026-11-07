package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SplitAquariumTest {
    private val sound = AquariumPerformanceSettings(AquariumRecipe(mapOf("aqua_reef_mix" to 1f), 0xffff_ffffL), true, .125f)
    private val kit = Kit("KIT", emptyList(), tempoBpm = 123.5f, aquariumPerformance = sound.toJsonValue())
    private val on = SplitAquarium.Settings(true, 3f)

    @Test fun `route owns no copy of kit sound and an unused route clocks no hidden wet core`() {
        val loaded = SplitAquarium.route(kit, on)
        assertEquals(sound, loaded.sound); assertEquals(123.5, loaded.bpm); assertTrue(loaded.wet)
        val off = SplitAquarium.route(kit, on.copy(fromKit = false))
        assertFalse(off.patched); assertFalse(off.sound.enabled); assertTrue(off.sound.settings.isBypass)
        assertEquals(sound.toJsonValue(), kit.aquariumPerformance)
        assertFalse(SplitAquarium.route(null, on).patched)
        val bad = kit.copy(aquariumPerformance = JsonValue.Str("future"))
        assertNotNull(SplitAquarium.route(bad, on).warning)
        assertFalse(SplitAquarium.route(bad, on).wet)
        assertEquals(JsonValue.Str("future"), bad.aquariumPerformance)
    }

    @Test fun `print plan counts zero input tail and refuses shelf truncation including that tail`() {
        val route = SplitAquarium.route(kit, on)
        assertEquals(SplitAquarium.PrintPlan(48000, 144000), SplitAquarium.plan(48000, 48000, on, route))
        val bypass = SplitAquarium.route(kit.copy(aquariumPerformance = sound.copy(enabled = false).toJsonValue()), on)
        assertEquals(0, SplitAquarium.plan(48000, 48000, on, bypass).tailFrames)
        val muted = SplitAquarium.route(kit.copy(aquariumPerformance = sound.copy(outputGain = 0f).toJsonValue()), on)
        assertEquals(144000, SplitAquarium.plan(48000, 48000, on, muted).tailFrames)
        assertEquals(180 * 48000, SplitAquarium.plan(177 * 48000, 48000, on, route).frames)
        val failure = assertFailsWith<IllegalArgumentException> { SplitAquarium.plan(178 * 48000, 48000, on, route) }
        assertTrue(failure.message!!.contains("INCLUDING FX TAIL"))
        assertFailsWith<IllegalArgumentException> { SplitAquarium.plan(Int.MAX_VALUE, 48000, on, route) }
    }

    @Test fun `bus input preserves stereo clamps after the sum and starts zeros at the exact source endpoint`() {
        val source = Snip(floatArrayOf(1.5f, -.25f, .2f, -2f, .7f, .3f), 2, 48000)
        val block = FloatArray(8) { 9f }
        SplitAquarium.fillBusInput(source, 1, block)
        assertContentEquals(floatArrayOf(.2f, -1f, .7f, .3f, 0f, 0f, 0f, 0f), block)
        SplitAquarium.fillBusInput(Snip(floatArrayOf(2f, -.5f), 1, 48000), 0, block)
        assertContentEquals(floatArrayOf(1f, 1f, -.5f, -.5f, 0f, 0f, 0f, 0f), block)
        assertEquals(1.5f, source.samples[0], "input must not be rewritten")
    }

    @Test fun `missing files stay dry and strict settings survive reopen without touching kit metadata or audio`() {
        val root = Files.createTempDirectory("split-fx").toFile()
        try {
            val metadata = File(root, "kit.json").apply { writeText("opaque kit metadata") }
            val wave = File(root, "source.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertEquals(SplitAquarium.Settings(), SplitStore.load(root))
            assertFalse(File(root, SplitStore.FILE_NAME).exists())
            val file = SplitStore.save(root, on.copy(tailSeconds = 5f))
            assertEquals(on.copy(tailSeconds = 5f), SplitStore.load(root))
            assertEquals("opaque kit metadata", metadata.readText()); assertContentEquals(byteArrayOf(1, 2, 3), wave.readBytes())
            val saved = Json.parse(file.readText()).obj()
            for ((key, value) in listOf("version" to JsonValue.Num(2.0), "aquariumFromKit" to JsonValue.Num(1.0), "tailSeconds" to JsonValue.Num(31.0))) {
                file.writeText(Json.write(JsonValue.Obj(saved + (key to value))))
                val bytes = file.readBytes()
                assertFails { SplitStore.load(root) }
                assertContentEquals(bytes, file.readBytes(), "a future or damaged file is preserved")
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `staged choices stay with their kit and reversed completion keeps the newest snapshot`() {
        val root = Files.createTempDirectory("split-fx-writes").toFile()
        try {
            val a = File(root, "A").apply { mkdirs() }; val b = File(root, "B").apply { mkdirs() }
            val first = SplitSettingsWrites(a); val second = SplitSettingsWrites(b)
            first.stage(SplitAquarium.Settings()); first.stage(on); second.stage(on.copy(fromKit = false, tailSeconds = 10f))
            second.persist(); first.persist(); first.persist()
            assertEquals(on, SplitStore.load(a)); assertEquals(10f, SplitStore.load(b).tailSeconds)
            assertFalse(SplitStore.load(b).fromKit)
            a.deleteRecursively()
            val failure = assertFailsWith<IllegalArgumentException> { first.persist() }
            assertTrue(failure.message!!.startsWith("kit folder no longer exists:")); assertFalse(a.exists())
        } finally { root.deleteRecursively() }
    }
}
