package com.snipsnap.shell

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.*
import java.io.File
import kotlin.test.*

class FiredBronzeStarterTest {
    private val dir = java.nio.file.Files.createTempDirectory("fired-bronze").toFile()
    @AfterTest fun clean() { dir.deleteRecursively() }

    @Test fun `starter saves editable four layer kit and edits regenerate every zone`() {
        val starter = StarterKits.byId("fired-bronze")!!
        assertFalse(starter.seeded)
        val kitDir = File(dir, "kit")
        starter.render("Fired Bronze", kitDir)
        val model = KitBuilderModel.open(kitDir)
        val frozen = com.snipsnap.json.Json.parse(javaClass.getResourceAsStream("/fired-bronze/approved-wavs.json")!!
            .bufferedReader().use { it.readText() }).obj().getValue("files").obj()
        assertEquals(64, frozen.size)
        for ((file, hash) in frozen) {
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(File(kitDir, file).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(hash.str(), actual, file)
        }
        assertEquals(96f, model.kit.tempoBpm)
        assertEquals(16, model.kit.pads.size)
        assertTrue(model.kit.pads.all(FiredBronzePads::canEdit))
        model.update(5) { it.copy(source = it.source + mapOf("rawSha256" to "old", "rawVelocitySha256" to "old")) }
        val old = model.pad(5)!!
        val patch = PadRecipe.fromJsonValue(old.recipe!!).patch as FiredBronzePatch
        val edited = patch.withMacros(patch.macros + ("AMP" to .9f))
        val recipe = PadRecipe(edited)
        val bytesBefore = old.velocityLayers.map { File(kitDir, it.sampleFile).readBytes() }
        val new = model.replaceFiredBronze(5, recipe)
        assertEquals(old.velocityLayers, new.velocityLayers)
        assertFalse("rawSha256" in new.source)
        assertFalse("rawVelocitySha256" in new.source)
        assertTrue(new.source.getValue("attribution").contains("ajaysm"))
        assertEquals(old.level, new.level)
        val hard = recipe.render()
        new.velocityLayers.forEachIndexed { i, layer ->
            val expected = if (i == 3) hard else Velocity.peakMatch(hard,
                Velocity.atVelocity(edited, FiredBronzePerformance.layerVelocities[i]))
            val wav = File(dir, "expected.wav")
            WavWriter.write(wav, expected)
            val actual = File(kitDir, layer.sampleFile).readBytes()
            assertContentEquals(wav.readBytes(), actual)
            assertFalse(bytesBefore[i].contentEquals(actual))
        }
        val editedBytes = new.velocityLayers.map { File(kitDir, it.sampleFile).readBytes() }
        RecipeReplay.apply(model, 5, recipe.toJsonValue(), "Edge Ring")
        new.velocityLayers.forEachIndexed { i, layer ->
            assertContentEquals(editedBytes[i], File(kitDir, layer.sampleFile).readBytes())
        }
        model.save()
        assertTrue(FiredBronzePads.canEdit(KitBuilderModel.open(kitDir).pad(5)))
        assertFalse(FiredBronzePads.canEdit(new.copy(velocityLayers = new.velocityLayers.drop(1))))
        assertFalse(FiredBronzePads.canEdit(new.copy(recipe = null)))
        assertFailsWith<IllegalArgumentException> { model.replaceAudio(5, null) { it } }
    }
}
