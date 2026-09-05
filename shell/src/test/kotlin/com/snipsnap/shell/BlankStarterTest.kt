package com.snipsnap.shell

import com.snipsnap.kit.KitStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlankStarterTest {
    @Test
    fun `the blank starter renders an empty, saved kit`() {
        val blank = StarterKits.byId("blank")
        assertNotNull(blank, "expected a 'blank' starter in StarterKits.ALL")
        assertTrue(!blank.seeded, "blank has nothing to reroll")

        val dir = Files.createTempDirectory("blank-kit").toFile()
        val kit = blank.render("BLANK TEST", dir, seed = 0)
        assertTrue(kit.pads.isEmpty(), "a blank kit has no pads")
        // assembleArranged persists kit.json itself
        assertTrue(File(dir, "kit.json").exists(), "kit.json written")
        assertEquals(kit.name, KitStore.load(dir).name)
        assertTrue(KitStore.load(dir).pads.isEmpty())
    }

    @Test
    fun `blank is discoverable in the FRESH menu list`() {
        assertTrue(StarterKits.ALL.any { it.id == "blank" }, "blank must be in ALL so the StarterMenu shows it")
    }
}
