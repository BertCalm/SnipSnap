package com.snipsnap.xpm

import kotlin.test.*

class KeygroupVelocityCycleTest {
    private val layers = (0..1).flatMap { zone -> (1..3).map { take ->
        VelocityLayer("z${zone}t$take", 100L, if (zone == 0) 0 else 80, if (zone == 0) 79 else 127)
    } }

    @Test fun `legacy combined grids preserve their previous first-take export`() {
        val legacy = Keygroup(48, 72, 60, layers, cycleLayers = true)
        assertEquals(listOf("z0t1", "z1t1"), legacy.mpcLayers.map { it.sampleName })
        assertFalse(legacy.mpcCycle)
        assertFalse("<ZonePlay>0</ZonePlay>" in KeygroupWriter().write(KeygroupProgram("Legacy", listOf(legacy))))
        val single = Keygroup(48, 72, 60, layers.take(3).map { it.copy(velEnd = 127) }, cycleLayers = true)
        assertEquals(3, single.mpcLayers.size)
        assertTrue(single.mpcCycle)
    }

    @Test fun `opted-in matching windows export every strike in Cycle mode`() {
        val verified = Keygroup(48, 72, 60, layers, cycleLayers = true, mpcVelocityCycle = true)
        assertEquals(layers, verified.mpcLayers)
        assertTrue(verified.mpcCycle)
        val xml = KeygroupWriter().write(KeygroupProgram("Verified", listOf(verified)))
        assertEquals(6, Regex("<Active>True</Active>").findAll(xml).count())
        assertTrue("<ZonePlay>0</ZonePlay>" in xml)
    }

    @Test fun `MPC velocity cycle requires a valid multi-window cycle bank`() {
        for (invalid in listOf(
            { Keygroup(48, 72, 60, layers, mpcVelocityCycle = true) },
            { Keygroup(48, 72, 60, layers.take(3), cycleLayers = true, mpcVelocityCycle = true) },
        )) {
            val error = assertFailsWith<IllegalArgumentException> { invalid() }
            assertEquals("MPC velocity cycle requires cycling across multiple velocity windows", error.message)
        }
    }
}
