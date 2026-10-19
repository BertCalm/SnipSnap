package com.snipsnap.shell

import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import com.snipsnap.kit.blocked
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import com.snipsnap.synth.Spool
import com.snipsnap.synth.SpoolMachine
import com.snipsnap.synth.SpoolPatch
import com.snipsnap.synth.SpoolPresets
import com.snipsnap.synth.SpoolRoute
import com.snipsnap.synth.SpoolSplice
import com.snipsnap.synth.SpoolVoice
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpoolIntegrationTest {
    @Test
    fun `the complete factory bank can be loaded edited and saved without losing recipes`() {
        val shelf = kotlin.io.path.createTempDirectory("spool-factory-shelf").toFile()
        try {
            val factory = SpoolPresets.all()
            assertEquals(36, factory.size)
            assertEquals(factory, Presets.all().filterIsInstance<SpoolPatch>(), "all factory recipes reach the host registry")
            for (voice in SpoolVoice.entries) {
                assertEquals(factory.filter { it.voice == voice }, Presets.forVoice("SPOOL", voice.name),
                    "$voice exposes its complete factory row")
            }

            val personal = factory.mapIndexed { index, patch ->
                val nameCheck = UserPresets.check(patch.name, patch.engine, patch.voiceName, emptyList())
                // Some approved factory labels exceed the personal shelf's fourteen-character limit.
                assertTrue(nameCheck is UserPresets.Check.Factory || nameCheck is UserPresets.Check.TooLong,
                    "${patch.name}: a personal save must not reuse a factory label")
                val loaded = assertIs<SpoolPatch>(Patches.fromJsonText(patch.toJsonText()))
                val head = if (loaded.macros.getValue("HEAD") < .5f) .83f else .17f
                val macros = loaded.macros + ("HEAD" to head)
                val edited = assertIs<SpoolPatch>(Patches.edited(loaded, "MY TAPE ${index + 1}", macros))
                assertEquals(loaded.copy(name = edited.name, macros = macros), edited,
                    "${patch.name}: host edits retain machine geometry, velocity and model")
                UserPresets.save(shelf, edited, nowMillis = index.toLong())
                edited
            }

            val restarted = UserPresets.read(shelf)
            assertEquals(personal, restarted.map { it.patch }, "a new shelf read retains all thirty-six complete recipes")
            for (voice in SpoolVoice.entries) {
                assertEquals(factory.count { it.voice == voice }, UserPresets.forVoice(restarted, "SPOOL", voice.name).size,
                    "$voice keeps every personal recipe after restart")
            }
        } finally {
            shelf.deleteRecursively()
        }
    }

    @Test
    fun `edited preview survives preset restart pad replay and a sixteen pad export`() {
        val shelf = kotlin.io.path.createTempDirectory("spool-workflow").toFile()
        try {
            // A loaded recipe can carry geometry and velocity the seven macro sliders do not own.
            val loaded = SpoolPatch(
                "MY TAPE", SpoolVoice.CHOIR, Spool.defaults(SpoolVoice.CHOIR),
                machine = SpoolMachine.forVoice(SpoolVoice.CHOIR).copy(
                    sourceSeconds = 2.2f, gateSeconds = .85f, returnSeconds = .65f,
                    motorTorque = 1.15f, headOffsets = listOf(.05f, -.2f, .28f),
                    spliceSchedule = listOf(SpoolSplice(.32f, -.12f), SpoolSplice(.71f, .18f)),
                    routes = listOf(SpoolRoute(0, 1, .3f), SpoolRoute(1, 2, .25f)),
                    seed = 314, writeDelaySamples = 75, reengage = true,
                ),
                velocity = .43f,
            )
            val macros = linkedMapOf(
                "TUNE" to .5f, "HEAD" to .77f, "STRAIN" to .35f, "RETURN" to .68f,
                "IMPRINT" to .63f, "SPLICE" to .22f, "HOLD" to 0f,
            )
            // SYNTH's buildCurrentPatch uses this same edit door after moving the sliders.
            val edited = assertIs<SpoolPatch>(Patches.edited(loaded, "MY ANSWER", macros))
            assertEquals(loaded.machine, edited.machine)
            assertEquals(loaded.velocity, edited.velocity)
            assertEquals(macros, edited.macros)
            val preview = edited.render()
            assertFalse(preview.samples.contentEquals(loaded.render().samples), "the edited preview must change")

            UserPresets.save(shelf, edited, nowMillis = 12L)
            val restarted = assertIs<SpoolPatch>(UserPresets.read(shelf).single().patch)
            assertEquals(edited, restarted, "a fresh read retains every macro, machine field and velocity")
            assertTrue(preview.samples.contentEquals(restarted.render().samples), "restart regenerates the preview exactly")

            val dir = File(shelf, "kit")
            val starter = StarterKits.byId("spool")!!.render("Spool Acceptance", dir)
            assertEquals(16, starter.pads.size)
            val starterPatches = starter.pads.map { assertIs<SpoolPatch>(Breed.recipeOf(it.recipe)!!.patch) }
            assertEquals(SpoolVoice.entries.toSet(), starterPatches.map { it.voice }.toSet())
            assertEquals((1..8).map { "Frame $it" } + listOf(
                "SOFT FRAME", "RETURNING CHOIR", "FLUTE MEMORY", "WORN BRASS", "GHOST COPY", "SHORT SPLICE",
                "RETURNING CHOIR LOOP", "GHOST COPY LOOP",
            ), starterPatches.map { it.name }, "expanding the shelf must preserve the accepted sixteen-pad map")
            assertTrue(starterPatches.take(8).all { it.voice == SpoolVoice.STRING })
            assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65),
                starterPatches.take(8).map { Spool.midiFor(it.voice, it.macros.getValue("TUNE")) })
            assertEquals(SpoolVoice.entries + listOf(SpoolVoice.CHOIR, SpoolVoice.PALIMPSEST),
                starterPatches.drop(8).map { it.voice })
            assertEquals(List(14) { false } + listOf(true, true),
                starterPatches.map { Spool.isLoop(it.macros.getValue("HOLD")) }, "only the final two pads are frozen HOLD loops")

            val recipe = PadRecipe(patch = restarted)
            val model = KitBuilderModel.open(dir)
            model.replaceAudio(1, recipe.toJsonValue()) { preview }
            model.save()
            val reopened = KitBuilderModel.open(dir)
            assertEquals(restarted, Breed.recipeOf(reopened.pad(1)!!.recipe)!!.patch)
            val expected = File(shelf, "preview.wav")
            WavWriter.write(expected, preview)
            val landed = File(dir, reopened.pad(1)!!.sampleFile).readBytes()
            assertTrue(expected.readBytes().contentEquals(landed), "the pad contains the preview in MPC PCM format")
            RecipeReplay.apply(reopened, 2, reopened.pad(1)!!.recipe!!, "ANSWER")
            reopened.save()
            val finalModel = KitBuilderModel.open(dir)
            assertEquals(finalModel.pad(1)!!.recipe, finalModel.pad(2)!!.recipe)
            assertTrue(landed.contentEquals(File(dir, finalModel.pad(2)!!.sampleFile).readBytes()), "recipe replay regenerates the exact saved pad")
            assertFalse(Preflight.check(finalModel.kit, dir).blocked())

            val outcome = Exporters.export(ExportFormat.PROGRAM_FOLDER, finalModel.kit, dir, File(shelf, "card"))
            val findings = ExportReadBack.verify(finalModel.kit, outcome)
            assertTrue(findings.none { it.severity == Severity.FAIL }, findings.joinToString { it.message })
            assertEquals(17, findings.count { it.severity == Severity.OK }, "one program and sixteen readable pad references")
            for (pad in finalModel.kit.pads) {
                val exported = File(outcome.primary.parentFile, pad.sampleFile)
                assertTrue(File(dir, pad.sampleFile).readBytes().contentEquals(exported.readBytes()), "${pad.sampleFile}: export preserves audio")
                val info = WavInfo.read(exported)
                assertEquals(WavWriter.MPC_SAMPLE_RATE, info.sampleRate)
                assertEquals(24, info.bitsPerSample)
                assertEquals(1, info.channels)
            }
        } finally {
            shelf.deleteRecursively()
        }
    }
}
