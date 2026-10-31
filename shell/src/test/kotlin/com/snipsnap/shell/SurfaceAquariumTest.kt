package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.synth.AquariumControls
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SurfaceAquariumTest {
    private val root = Files.createTempDirectory("surface-aquarium").toFile()
    @AfterTest fun cleanup() { root.deleteRecursively() }
    private val live = AquariumPerformanceSettings(
        AquariumRecipe(AquariumControls.preset("full").settings.parameters, 0xffff_ffffL), true, .125f)

    @Test fun `SURFACE opt in uses complete kit sound gain seed and tempo without changing global bypass`() {
        val kit = Kit("KIT", emptyList(), tempoBpm = 123.5f, aquariumPerformance = live.toJsonValue())
        val on = SurfaceAquarium.route(kit, true)
        assertEquals(live, on.settings)
        assertEquals(123.5, on.bpm)
        assertTrue(on.wet)
        val bypass = live.copy(enabled = false, outputGain = 0f)
        val bypassed = SurfaceAquarium.route(kit.copy(aquariumPerformance = bypass.toJsonValue()), true)
        assertEquals(bypass, bypassed.settings)
        assertFalse(bypassed.wet)
        val off = SurfaceAquarium.route(kit, false)
        assertFalse(off.settings.enabled)
        assertTrue(off.settings.settings.isBypass, "an unpatched route must not clock a hidden wet core")
        assertEquals(123.5, off.bpm)
        assertEquals(live.toJsonValue(), kit.aquariumPerformance)
    }

    @Test fun `no kit old kit and damaged settings all resolve dry with damaged metadata preserved`() {
        assertFalse(SurfaceAquarium.route(null, true).wet)
        assertEquals(0.0, SurfaceAquarium.route(null, true).bpm)
        val old = Kit("OLD", emptyList())
        assertFalse(SurfaceAquarium.route(old, true).wet)
        val bad = old.copy(aquariumPerformance = JsonValue.Str("future or damaged"))
        val route = SurfaceAquarium.route(bad, true)
        assertFalse(route.wet)
        assertNotNull(route.warning)
        assertEquals(JsonValue.Str("future or damaged"), bad.aquariumPerformance)
    }

    @Test fun `old surface files default unpatched and the new choice survives other surface edits`() {
        val dir = File(root, "KIT").apply { mkdirs() }
        val file = SurfaceStore.save(dir, SurfaceStore.Settings.DEFAULT)
        val old = Json.parse(file.readText()).obj() - "aquariumFromKit"
        file.writeText(Json.write(JsonValue.Obj(old)))
        assertFalse(SurfaceStore.load(dir).aquariumFromKit)
        val on = SurfaceStore.load(dir).copy(aquariumFromKit = true)
        SurfaceStore.save(dir, on.copy(secondPadSlot = 9, keySnap = true))
        val reopened = SurfaceStore.load(dir)
        assertTrue(reopened.aquariumFromKit)
        assertEquals(9, reopened.secondPadSlot)
        assertTrue(reopened.keySnap)
        val invalid = Json.parse(file.readText()).obj() + ("aquariumFromKit" to JsonValue.Num(1.0))
        file.writeText(Json.write(JsonValue.Obj(invalid)))
        assertFailsWith<JsonException> { SurfaceStore.load(dir) }
    }

    @Test fun `pending surface writes keep each kits latest snapshot across a switch and reversed completion`() {
        val a = File(root, "A").apply { mkdirs() }
        val b = File(root, "B").apply { mkdirs() }
        val writes = SurfaceSettingsWrites()
        val oldA = SurfaceStore.Settings(1, aquariumFromKit = false)
        val newA = oldA.copy(aquariumFromKit = true, secondPadSlot = 8)
        val kitB = SurfaceStore.Settings(12, aquariumFromKit = false)
        writes.stage(a, oldA)
        writes.stage(b, kitB)
        writes.stage(a, newA)
        writes.save(b); writes.save(a); writes.save(a)
        assertEquals(newA, SurfaceStore.load(a))
        assertEquals(kitB, SurfaceStore.load(b))
    }

    @Test fun `pending surface write refuses a removed directory without resurrecting it`() {
        val dir = File(root, "REMOVED").apply { mkdirs() }
        val writes = SurfaceSettingsWrites()
        writes.stage(dir, SurfaceStore.Settings.DEFAULT.copy(aquariumFromKit = true))
        dir.deleteRecursively()
        val error = assertFailsWith<IllegalArgumentException> { writes.save(dir) }
        assertTrue(error.message!!.startsWith("kit folder no longer exists:"))
        assertFalse(dir.exists())
    }
}
