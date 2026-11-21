package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Fill
import com.snipsnap.synth.FillKits
import com.snipsnap.synth.FillPatch
import com.snipsnap.synth.FillPresets
import com.snipsnap.synth.FillVoice
import com.snipsnap.synth.HeldPromotions
import com.snipsnap.synth.Hydroform
import com.snipsnap.synth.HydroformKits
import com.snipsnap.synth.HydroformPatch
import com.snipsnap.synth.HydroformPresets
import com.snipsnap.synth.HydroformVoice
import com.snipsnap.synth.LorentzKits
import com.snipsnap.synth.LorentzPatch
import com.snipsnap.synth.LorentzVoice
import com.snipsnap.synth.Patch
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FILL, HYDROFORM and LORENTZ starters while [HeldPromotions] holds them: off the menu and the shelf,
 * yet rendered and checked as StarterKitsTest checks a one-engine starter, so shipping one is only
 * widening the gate. Once an engine ships, StarterKitsTest's own loop covers its starter.
 */
class HeldStartersTest {
    private val temp: File = java.nio.file.Files.createTempDirectory("held-starters").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `a held starter is on no menu and no shelf, a shipped one is at the end of both`() {
        val ids = StarterKits.ALL.map { it.id }
        val shelved = StarterShelf.GROUPS.flatMap { it.ids }
        assertEquals(listOf(FillKits.ID, HydroformKits.ID, LorentzKits.ID), StarterKits.HELD.map { it.second.id })
        for ((engine, starter) in StarterKits.HELD) {
            val shipped = HeldPromotions.shipped(engine)
            assertEquals(shipped, starter.id in ids, "${starter.id}: on the menu exactly when $engine ships")
            assertEquals(shipped, starter.id in shelved, "${starter.id}: on the shelf exactly when $engine ships")
            assertEquals(shipped, StarterKits.offered(starter.id))
        }
        val shippedIds = StarterKits.HELD.filter { HeldPromotions.shipped(it.first) }.map { it.second.id }
        assertEquals(shippedIds, ids.takeLast(shippedIds.size), "a shipped starter joins the end of the stored order")
    }

    private class Expect(val patch: Class<out Patch>, val voices: Set<String>, val key: KeySpec?, val drumClassFor: (Patch) -> com.snipsnap.audio.DrumClass)

    private val expected = mapOf(
        FillKits.ID to Expect(FillPatch::class.java, FillVoice.entries.map { it.name }.toSet(), null) {
            (it as FillPatch).let { p -> Fill.drumClassFor(p.voice, p.macros) }
        },
        HydroformKits.ID to Expect(HydroformPatch::class.java, HydroformVoice.entries.map { it.name }.toSet(), KeySpec.parse("Gminpent")) {
            (it as HydroformPatch).let { p -> Hydroform.drumClassFor(p.voice, p.macros) }
        },
        LorentzKits.ID to Expect(LorentzPatch::class.java, LorentzVoice.entries.map { it.name }.toSet(), KeySpec.parse("Cminpent")) {
            LorentzKits.drumClassFor(it as LorentzPatch)
        },
    )

    @Test
    fun `every held starter renders a one-engine kit with three touch zones on every pad`() {
        for ((_, starter) in StarterKits.HELD) {
            val engine = expected.getValue(starter.id)
            val dir = File(temp, starter.id)
            val kit = starter.render("Starter ${starter.id}", dir, seed = 1)
            assertFalse(starter.seeded)
            assertFalse(Preflight.check(kit, dir).blocked(), "${starter.id} failed preflight")
            assertEquals((1..16).toList(), kit.pads.map { it.slot }, "${starter.id}: a full bank, no gaps")
            val model = KitBuilderModel.open(dir)
            assertEquals(engine.key, model.kit.key)
            val patches = model.kit.pads.map { pad ->
                val recipe = assertNotNull(Breed.recipeOf(pad.recipe), "${starter.id} ${pad.displayName}: no recipe")
                val patch = assertNotNull(recipe.patch, "${starter.id} ${pad.displayName}: the recipe has no patch")
                assertTrue(engine.patch.isInstance(patch), "${starter.id} ${pad.displayName}: a ${patch.javaClass.simpleName}")
                assertNull(recipe.fx, "${starter.id} ${pad.displayName}: starter pads land dry")
                assertEquals(engine.drumClassFor(patch), pad.drumClass, "${starter.id} ${pad.displayName}: filed by its engine")
                assertTrue(pad.oneShot, "${starter.id} ${pad.displayName}: plays its complete rendered buffer")
                assertEquals(3, pad.velocityLayers.size, "${starter.id} ${pad.displayName}: soft, medium and hard zones")
                patch
            }
            assertEquals(engine.voices, patches.map { it.voiceName }.toSet(), "${starter.id}: every voice is reachable")
            // The first pad's main sample regenerates exactly from its recipe, once its zones are cleared.
            val first = model.kit.pads.first()
            val bytes = File(dir, first.sampleFile).readBytes()
            model.clearGhostLayers(first.slot)
            val again = RecipeReplay.apply(model, first.slot, first.recipe!!, first.displayName)
            assertTrue(bytes.contentEquals(File(dir, again.pad.sampleFile).readBytes()), "${starter.id}: the first pad regenerates exactly")
        }
    }

    @Test
    fun `the held kits are built from the presets the round plays`() {
        val fill = FillKits.patches()
        assertEquals(FillPresets.all(), fill.take(8), "FILL's top row is the eight kept fills, unchanged")
        assertEquals(FillPresets.all().map { it.copy(name = "${it.name} LOOSE", macros = it.macros + ("HUMANIZE" to 1f)) }, fill.drop(8))
        val hydro = HydroformKits.patches()
        assertEquals(HydroformPresets.forVoice(HydroformVoice.BULKHEAD), hydro.subList(8, 14))
        assertTrue(hydro.all { p -> HydroformPresets.all().any { it.voice == p.voice && it.macros - "TUNE" == p.macros - "TUNE" } })
        assertIs<LorentzPatch>(LorentzKits.patches().first())
        assertEquals(16, LorentzKits.patches().size)
    }
}
