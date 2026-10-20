package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import com.snipsnap.synth.Velocity
import com.snipsnap.synth.Vent
import com.snipsnap.synth.VentKitPresets
import com.snipsnap.synth.VentPatch
import com.snipsnap.synth.VentVoice
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VentHostIntegrationTest {
    @Test
    fun `the twelve dry starter pads reopen with complete recipes and four free capture slots`() {
        val directory = createTempDirectory("vent-starter").toFile()
        try {
            val starter = requireNotNull(StarterKits.byId("vent"))
            assertEquals("VENT", starter.displayName)
            assertFalse(starter.seeded)
            val kit = starter.render("Vent proof", directory)
            assertFalse(Preflight.check(kit, directory).blocked())
            val model = KitBuilderModel.open(directory)
            assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
            assertEquals((1..12).toList(), model.kit.pads.map { it.slot })
            assertTrue(model.kit.pads.all { it.drumClass == DrumClass.TONAL && it.oneShot })
            val expectedLevels = listOf(.72f, .54f, .60f, .22f)
            for ((index, pad) in model.kit.pads.withIndex()) {
                val recipe = requireNotNull(Breed.recipeOf(pad.recipe))
                val patch = assertIs<VentPatch>(recipe.patch)
                assertEquals(VentKitPresets.entries[index].patch, patch, "slot ${pad.slot} lost its note, touch or trajectory")
                assertEquals(patch.name, pad.displayName)
                assertEquals(expectedLevels[index % 4], pad.level)
                assertEquals(Vent.CLEAN_MODEL, patch.model)
                assertNull(recipe.fx, "the starter's dry identity must survive export")
            }
            // Paste a different voice into a destination through the real host path,
            // then compare saved WAV bytes rather than only re-decoding the JSON.
            val source = model.kit.pads.first()
            val target = model.kit.pads.last()
            val original = File(directory, source.sampleFile).readBytes()
            val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
            assertEquals(source.recipe, replayed.pad.recipe)
            assertContentEquals(original, File(directory, replayed.pad.sampleFile).readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `factory edits user saves and promotion retain the note touch seed and both saved models`() {
        val directory = createTempDirectory("vent-presets").toFile()
        try {
            for ((index, voice) in listOf(VentVoice.ROUND, VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME).withIndex()) {
                val source = if (voice == VentVoice.ROUND)
                    VentPatch("MY ORBIT $index", voice, Vent.defaultsFor(voice))
                else assertIs<VentPatch>(Presets.forVoice("VENT", voice.name).first())
                val patch = source.copy(name = "MY ORBIT $index", midi = 57, velocity = .63f,
                    seed = Int.MIN_VALUE, macros = source.macros + ("LENGTH" to 0f))
                val edited = assertIs<VentPatch>(Patches.edited(patch, patch.name, patch.macros + ("VIEW" to .61f)))
                assertEquals(patch.midi, edited.midi)
                assertEquals(patch.velocity, edited.velocity)
                assertEquals(patch.seed, edited.seed)
                assertEquals(patch.model, edited.model)
                UserPresets.save(directory, edited, index.toLong())
                val saved = UserPresets.forVoice(UserPresets.read(directory), "VENT", voice.name).single().patch
                assertEquals(edited, saved)
                assertContentEquals(edited.render().samples, saved.render().samples)
                assertEquals(edited, fromRosterLine(UserPresets.rosterLine(saved)))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `mixed saved models promote into separate tables with complete JSON state`() {
        val pulse = VentKitPresets.forVoice(VentVoice.PULSE).first().copy(
            name = "MY \"PULSE\" \$X", midi = 57, velocity = .63f, seed = Int.MIN_VALUE,
        )
        val legacy = VentPatch("OLD \\ ORBIT", VentVoice.WIRE,
            mapOf("VIEW" to .6100001f), midi = 48, velocity = .34f, seed = -71)
        val plume = VentKitPresets.forVoice(VentVoice.PLUME).last().copy(name = "MY PLUME", seed = 91827)
        val rendered = UserPresets.renderAll(listOf(pulse, legacy, plume).mapIndexed { index, patch ->
            UserPresets.Saved(patch, index.toLong())
        })
        val lines = rendered.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(listOf("VentKitPresets.kt", "VentPresets.kt"), lines.filter { !it.startsWith("  ") })
        val decoded = lines.filter { it.startsWith("  ") }.map { fromRosterLine(it.trim()) }
        assertEquals(listOf(pulse, plume, legacy), decoded)
        assertEquals("VentKitPresets.kt", UserPresets.rosterFile(VentPatch.ENGINE))
        assertEquals("VentKitPresets.kt", UserPresets.rosterFile(pulse))
        assertEquals("VentPresets.kt", UserPresets.rosterFile(legacy))
        for ((expected, promoted) in listOf(pulse, plume, legacy).zip(decoded)) {
            assertEquals(expected.toJsonValue(), promoted.toJsonValue())
            assertEquals(expected.macros.mapValues { it.value.toRawBits() }, promoted.macros.mapValues { it.value.toRawBits() })
        }
        assertEquals(12, VentKitPresets.entries.size, "factory promotions must not extend the fixed kit layout")
    }

    private fun fromRosterLine(line: String): VentPatch {
        val call = assertIs<KotlinLiteral.Call>(KotlinLiteral.parse(line.removeSuffix(",")))
        assertEquals("fromJsonText", call.name)
        assertEquals("VentPatch", assertIs<KotlinLiteral.Call>(call.receiver).name)
        assertTrue(call.named.isEmpty())
        return VentPatch.fromJsonText(assertIs<KotlinLiteral.Str>(call.positional.single()).value)
    }

    @Test
    fun `kit velocity rerenders the gesture using the saved trajectory`() {
        for (voice in listOf(VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME)) {
            val patch = VentKitPresets.forVoice(voice).first().copy(macros = Vent.defaultsFor(voice) + ("LENGTH" to 0f))
            val expected = patch.copy(velocity = .27f).render()
            assertContentEquals(expected.samples, Velocity.atVelocity(patch, .27f).samples,
                "$voice must rerender native excitation instead of filtering the main WAV")
            assertFalse(patch.render().samples.contentEquals(expected.samples))
        }
    }
}
