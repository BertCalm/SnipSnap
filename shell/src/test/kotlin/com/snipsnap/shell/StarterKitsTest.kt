package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StarterKitsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("starters").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `every starter renders a preflight-clean kit folder`() {
        assertEquals(StarterKits.ALL.size, StarterKits.ALL.map { it.id }.toSet().size, "ids unique")
        for (starter in StarterKits.ALL) {
            val dir = File(temp, starter.id)
            val kit = starter.render("Starter ${starter.id}", dir, seed = 1)
            if (starter.id == "blank") {
                // The empty grid is the point: no pads to assign yet, and
                // preflight correctly refuses to export nothing.
                assertTrue(kit.pads.isEmpty(), "blank is the empty grid")
                assertTrue(Preflight.check(kit, dir).blocked(), "blank should block export until captured into")
            } else {
                assertTrue(kit.pads.isNotEmpty(), starter.id)
                assertFalse(Preflight.check(kit, dir).blocked(), "${starter.id} failed preflight")
                // Regenerable where the engine records how: most starter pads
                // carry recipes (not all — some treatments are render-only).
                assertTrue(kit.pads.any { it.recipe != null }, "${starter.id}: no pad carries a recipe")
            }
            assertEquals(starter, StarterKits.byId(starter.id))
        }
        assertEquals(null, StarterKits.byId("nope"))
    }

    @Test
    fun `seeded starters reroll and are deterministic per seed`() {
        val dip = StarterKits.byId("lucky-dip")!!
        assertTrue(dip.seeded)
        val a = dip.render("Dip A", File(temp, "a"), seed = 7)
        val b = dip.render("Dip A", File(temp, "b"), seed = 7)
        val c = dip.render("Dip A", File(temp, "c"), seed = 8)

        val jsonA = File(temp, "a/kit.json").readText()
        assertEquals(jsonA, File(temp, "b/kit.json").readText(), "same seed, same kit")
        assertNotEquals(jsonA, File(temp, "c/kit.json").readText(), "different seed, different kit")
        assertEquals(a.pads.map { it.slot }, b.pads.map { it.slot })
        assertTrue(a.pads.isNotEmpty() && c.pads.isNotEmpty())

        assertFalse(StarterKits.byId("factory")!!.seeded, "the factory kit never rerolls")
    }

    @Test
    fun `eight starters, and blank leads them`() {
        assertEquals(
            listOf("blank", "factory", "lucky-dip", "lucky-dip-ab", "melodic", "chip", "cloud", "velocity"),
            StarterKits.ALL.map { it.id },
        )
    }

    @Test
    fun `the velocity starter gives every pad soft layers`() {
        val dir = kotlin.io.path.createTempDirectory("velocity").toFile()
        try {
            val kit = StarterKits.byId("velocity")!!.render("VELOCITY", dir)
            val assigned = kit.pads.filter { it.sampleFile.isNotBlank() }
            assertTrue(assigned.isNotEmpty(), "the starter rendered an empty kit")
            for (pad in assigned) {
                assertEquals(
                    3, pad.velocityLayers.size,
                    "${pad.displayName}: two soft zones plus the main sample on top",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the A-B starter fills bank B and melodic remembers its key`() {
        val ab = StarterKits.byId("lucky-dip-ab")!!.render("DipAB", File(temp, "ab"), seed = 3)
        assertTrue(ab.pads.any { it.slot > 16 }, "bank B should be populated")

        val melodic = StarterKits.byId("melodic")!!.render("Melodic", File(temp, "mel"), seed = 0)
        assertEquals(KeySpec.parse("Aminpent"), melodic.key, "melodic content declares its key")
    }

    @Test
    fun `melodic pads gate while factory pads stay one-shot`() {
        val melodic = StarterKits.byId("melodic")!!.render("Melodic Gate", File(temp, "mel-gate"), seed = 0)
        assertTrue(melodic.pads.isNotEmpty(), "melodic should have pads")
        for (pad in melodic.pads) {
            assertFalse(pad.oneShot, "${pad.displayName}: melodic pads should sustain while held")
        }

        val factory = StarterKits.byId("factory")!!.render("Factory OneShot", File(temp, "factory-oneshot"), seed = 0)
        assertTrue(factory.pads.isNotEmpty(), "factory should have pads")
        for (pad in factory.pads) {
            assertTrue(pad.oneShot, "${pad.displayName}: factory pads should remain one-shot")
        }
    }
}
