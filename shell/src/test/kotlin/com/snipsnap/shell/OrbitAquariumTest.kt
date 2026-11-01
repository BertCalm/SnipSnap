package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.json.JsonValue
import com.snipsnap.loop.OrbitSet
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import kotlin.test.*

class OrbitAquariumTest {
    private val settings = AquariumPerformanceSettings(AquariumRecipe(mapOf("aqua_reef_mix" to 1f), seed = 0xffff_ffffL), true, .125f)
    private val kit = Kit("K", emptyList(), tempoBpm = 123.5f, aquariumPerformance = settings.toJsonValue())
    private val set = OrbitSet(emptyList(), 90f, 48000, aquariumFromKit = true, aquariumTailSeconds = 3f)

    @Test fun `route uses saved kit sound while the set keeps its own tempo and tail`() {
        val route = OrbitAquarium.route(kit, set)
        assertEquals(settings, route.settings)
        assertEquals(144000, route.tailFrames)
        assertEquals(90f, set.bpm)
        assertEquals(settings.toJsonValue(), kit.aquariumPerformance)
    }
    @Test fun `unpatched bypassed and unreadable routes stay dry without a hidden tail`() {
        val off = OrbitAquarium.route(kit, set.copy(aquariumFromKit = false))
        assertTrue(off.settings.settings.isBypass); assertFalse(off.settings.enabled); assertEquals(0, off.tailFrames)
        val bypass = settings.copy(enabled = false)
        assertEquals(0, OrbitAquarium.route(kit.copy(aquariumPerformance = bypass.toJsonValue()), set).tailFrames)
        val damaged = kit.copy(aquariumPerformance = JsonValue.Str("future"))
        val route = OrbitAquarium.route(damaged, set)
        assertNotNull(route.warning); assertFalse(route.settings.enabled); assertEquals(0, route.tailFrames)
        assertEquals(JsonValue.Str("future"), damaged.aquariumPerformance)
    }

    @Test fun `shelf bound counts the tail and refuses before a bounce can be truncated`() {
        assertEquals(177 * 48000, OrbitAquarium.bounceFrames(177L * 48000, 3 * 48000, 48000))
        val failure = assertFailsWith<IllegalArgumentException> { OrbitAquarium.bounceFrames(178L * 48000, 3 * 48000, 48000) }
        assertTrue(failure.message!!.contains("INCLUDING FX TAIL"))
        assertFailsWith<IllegalArgumentException> { OrbitAquarium.bounceFrames(Int.MAX_VALUE.toLong(), 1, 48000) }
    }
}
