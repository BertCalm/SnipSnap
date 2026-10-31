package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import com.snipsnap.kit.blocked
import com.snipsnap.synth.LibrationKits
import com.snipsnap.synth.LibrationPatch
import com.snipsnap.synth.LibrationPresets
import com.snipsnap.synth.LibrationVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import java.io.File
import kotlin.test.*

class LibrationIntegrationTest {
    @Test fun `factory edits return through navigation presets and pad recipes with all performance fields`() {
        val shelf = kotlin.io.path.createTempDirectory("libration-recipes").toFile()
        try {
            val settings = mutableMapOf("engine" to "LIBRATION", "voice" to "GATHER")
            val edited = LibrationVoice.entries.mapIndexed { i, voice ->
                val factory = Presets.forVoice(LibrationPatch.ENGINE, voice.name).first() as LibrationPatch
                assertIs<UserPresets.Check.Factory>(UserPresets.check(factory.name, factory.engine, voice.name, emptyList()))
                val patch = factory.copy(name = "MY ${voice.name}", midi = 43 + i * 7,
                    sourceSeconds = .37 + i * .1, velocity = .35f,
                    macros = factory.macros + ("WANDER" to .61f))
                UserPresets.save(shelf, patch, nowMillis = 123L)
                patch
            }
            SynthDrafts.write(settings, edited)
            assertEquals(edited.toSet(), SynthDrafts.read(settings).toSet())
            assertEquals(edited.toSet(), UserPresets.read(shelf).map { it.patch }.toSet())
            assertFalse(settings.keys.any { it.startsWith("preset.") })
            for (patch in SynthDrafts.read(settings)) {
                val updated = Patches.edited(patch, patch.name, patch.macros + ("NUDGE" to .29f)) as LibrationPatch
                assertEquals((patch as LibrationPatch).copy(macros = updated.macros), updated)
                val recipe = PadRecipe.fromJsonText(PadRecipe(patch = updated).toJsonText())
                assertEquals(updated, recipe.patch)
                assertContentEquals(updated.render().samples, recipe.render().samples)
                assertEquals(updated.midi.toFloat(), Spread.detect(recipe.render(), updated))
                assertTrue(UserPresets.rosterLine(updated).contains("sourceSeconds = ${updated.sourceSeconds}"))
            }
        } finally { shelf.deleteRecursively() }
    }

    @Test fun `phone starter persists native soft and hard layers and both MPC export paths preserve them`() {
        val root = kotlin.io.path.createTempDirectory("libration-starter").toFile()
        try {
            val dir = File(root, "kit")
            val starter = assertNotNull(StarterKits.byId("libration"))
            assertFalse(starter.seeded)
            val kit = starter.render("Libration Test", dir)
            assertEquals(kit, KitStore.load(dir))
            assertFalse(Preflight.check(kit, dir).blocked())
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            for ((pad, expected) in kit.pads.zip(LibrationKits.patches())) {
                assertEquals(expected, PadRecipe.fromJsonValue(assertNotNull(pad.recipe)).patch)
                val soft = PadRecipe.fromJsonText(pad.source.getValue("softRecipe"))
                assertEquals(expected.copy(velocity = LibrationKits.SOFT_VELOCITY), soft.patch)
                assertEquals(listOf(1 to 63, 64 to 127), pad.velocityLayers.map { it.velStart to it.velEnd })
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
                assertEquals(0, pad.muteGroup)
                assertEquals(0, pad.tuneCoarse)
                assertEquals(0, pad.tuneFine)
                assertEquals(LibrationKits.PAD_LEVEL, pad.level)
                val quiet = WavReader.read(File(dir, pad.velocityLayers.first().sampleFile))
                val hard = WavReader.read(File(dir, pad.sampleFile))
                assertTrue(Loudness.of(quiet) < Loudness.of(hard) * .7f, pad.displayName)
            }
            // A fresh public recipe render, including the soft strike, must make
            // the same PCM24 files the production starter wrote through its cache.
            val first = kit.pads.first()
            val hardRecipe = PadRecipe.fromJsonValue(requireNotNull(first.recipe))
            val softRecipe = PadRecipe.fromJsonText(first.source.getValue("softRecipe"))
            for ((recipe, file) in listOf(hardRecipe to first.sampleFile,
                softRecipe to first.velocityLayers.first().sampleFile)) {
                val replay = WavWriter.write(File(root, "replay.wav"), recipe.render())
                assertContentEquals(File(dir, file).readBytes(), replay.readBytes())
            }
            for (format in listOf(ExportFormat.MPC3_TRACK, ExportFormat.PROGRAM_FOLDER)) {
                val outcome = Exporters.export(format, kit, dir, File(root, "card-${format.name}"))
                val findings = ExportReadBack.verify(kit, outcome)
                assertTrue(findings.none { it.severity == Severity.FAIL }, findings.joinToString { it.message })
            }
        } finally { root.deleteRecursively() }
    }
}
