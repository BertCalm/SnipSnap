package com.snipsnap.shell

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.ErosionBank
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Velocity
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErosionStarterTest {
    @Test fun `shelf bank preserves all accepted touches through storage and both MPC exports`() {
        val root = createTempDirectory("erosion-starter").toFile()
        try {
            val starter = assertNotNull(StarterKits.byId("erosion"))
            assertEquals(1, StarterShelf.GROUPS.sumOf { it.ids.count { id -> id == starter.id } })
            assertFalse(starter.seeded)
            assertNull(starter.key, "relative TUNE must not invent a musical root")
            val dir = root.resolve("source")
            val kit = starter.render("Erosion Contrast", dir)
            assertEquals(kit, KitStore.load(dir))
            assertFalse(Preflight.check(kit, dir).blocked())
            assertEquals((1..8).toList(), kit.pads.map { it.slot })
            val entries = ErosionBank.entries()
            assertEquals(entries.map { it.name }, kit.pads.map { it.displayName })
            for ((pad, entry) in kit.pads.zip(entries)) {
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                assertEquals(entry.patch, recipe.patch)
                assertNull(recipe.fx)
                assertEquals(0, pad.muteGroup, "separate impacts may overlap")
                assertTrue(pad.oneShot)
                val hard = entry.patch.render()
                assertEquals(Classifier.classify(hard).drumClass, pad.drumClass)
                assertEquals(listOf(1 to 41, 42 to 84, 85 to 127),
                    pad.velocityLayers.map { it.velStart to it.velEnd })
                assertEquals(listOf("SOFT", "MEDIUM", "HARD"), pad.velocityLayers.map { it.displayName })
                for ((layer, touch) in pad.velocityLayers.zip(ErosionBank.velocities)) {
                    val native = entry.patch.copy(velocity = touch).render()
                    val expected = if (touch == 1f) native else Velocity.peakMatch(hard, native)
                    val bytes = ByteArrayOutputStream().also { WavWriter.write(it, expected) }.toByteArray()
                    assertContentEquals(bytes, dir.resolve(layer.sampleFile).readBytes(), "${entry.name} touch $touch")
                }
            }
            for (format in listOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.MPC3_TRACK)) {
                val result = Exporters.export(format, kit, dir, root.resolve(format.id))
                val findings = ExportReadBack.verify(kit, result)
                assertFalse(findings.blocked(), "$format: ${findings.joinToString { it.message }}")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
