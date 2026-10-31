package com.snipsnap.shell

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismGroupProfile
import com.snipsnap.synth.PrismMotionEditor
import com.snipsnap.synth.PrismMotionSources
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismVoice
import com.snipsnap.synth.Velocity
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Motion edits cross the real preset, navigation, stored-pad and factory promotion doors. */
class PrismMotionStudioWorkflowTest {
    @Test
    fun `finite and repeating source motions remain editable after saving landing and reopening`() {
        val shelf = createTempDirectory("prism-motion-studio").toFile()
        try {
            // Analyze one inexpensive native donor; both delivery cases reuse its saved endpoints.
            val source = assertNotNull(PrismMotionSources.byId("fork-soft-keys"))
            assertEquals(source, PrismMotionSources.all().single { it.id == source.id })
            val carrier = PrismPatch(
                name = "MY MOTION", voice = PrismVoice.KEY,
                macros = Prism.defaults(PrismVoice.KEY) + mapOf(
                    "TUNE" to .375f, "GRAIN" to .18f, "DRIFT" to .23f,
                    "SHAPE" to .44f, "STRIKE" to .37f, "BODY" to .58f, "HOLD" to .61f,
                ),
                velocity = .43f,
                template = Prism.templateFor(PrismVoice.KEY).copy(
                    ratioOverrides = List(Prism.PARTIALS) { it + 1f },
                    amplitudeOverrides = List(Prism.PARTIALS) { .8f / (it + 1f) },
                    groupProfiles = List(Prism.GROUPS) { group ->
                        PrismGroupProfile(1f + group * .11f, .9f + group * .03f,
                            group * .004f, 1 + group, .6f + group * .07f)
                    },
                    attackSeconds = .017f, decaySeconds = 1.1f, loopSeconds = 1.3f,
                ),
            )
            val selected = PrismMotionSources.applySource(carrier, source)
            assertEquals(carrier, selected.copy(motion = null), "a donor changes the spectrum, preserving the carrier recipe")
            val selectedMotion = assertNotNull(selected.motion)
            assertEquals(source.id, selectedMotion.sourceId)
            assertNotNull(selectedMotion.amplitudesA)
            assertEquals(source.patch, PrismMotionSources.originalSource(selected))

            for (repeat in listOf(false, true)) {
                val label = if (repeat) "REPEAT" else "FINITE"
                val presetName = if (repeat) "MY REPEAT MOVE" else "MY OPEN MOVE"
                val seeded = selected.copy(name = presetName, motion = selectedMotion.copy(
                    morph = .13f, evolve = .69f, seconds = .47f, groupLag = .31f,
                ))
                val amountEdited = PrismMotionEditor.withAmount(seeded, .71f)
                val timed = PrismMotionEditor.withTime(amountEdited, .83f)
                val modeEdited = PrismMotionEditor.withRepeat(timed, repeat)
                assertEquals(seeded.copy(motion = seeded.motion!!.copy(evolve = .71f)), amountEdited)
                assertEquals(amountEdited.copy(motion = amountEdited.motion!!.copy(seconds = .83f)), timed)
                assertEquals(timed.copy(motion = timed.motion!!.copy(repeat = repeat)), modeEdited)
                val patch = if (repeat) modeEdited.copy(macros = modeEdited.macros + ("HOLD" to 1f)) else modeEdited
                assertEquals(patch, PrismMotionEditor.ensureMotion(patch), "opening the editor must preserve authored motion")

                val saved = UserPresets.save(shelf, patch, nowMillis = 12L)
                assertEquals(saved, UserPresets.read(shelf).single { it.name == patch.name })
                val binned = assertNotNull(UserPresets.forget(shelf, patch.engine, patch.voiceName, patch.name, nowMillis = 13L))
                val newer = PrismMotionEditor.withTime(patch, 1.17f)
                UserPresets.save(shelf, newer, nowMillis = 14L)
                val restored = assertNotNull(UserPresets.unforget(shelf, binned))
                val restoredName = if (repeat) "MY REPEAT MO 2" else "MY OPEN MOVE 2"
                val expected = patch.copy(name = restoredName)
                assertEquals(expected, restored.patch, "collision restore preserves both endpoint banks, provenance and repeat mode")
                assertEquals(expected, UserPresets.read(shelf).single { it.name == expected.name }.patch)
                assertEquals(newer, UserPresets.read(shelf).single { it.name == patch.name }.patch)

                val recipe = PadRecipe(restored.patch, treatment = "KEPT MOTION", amount = .4f, alias = false)
                assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()))
                val kitDir = File(shelf, label)
                val model = KitBuilderModel.create(label, kitDir)
                model.assign(1, recipe.render(), Prism.drumClassFor(patch.voice, patch.macros), expected.name)
                model.update(1) { it.copy(recipe = recipe.toJsonValue()) }
                model.save(accrueWear = false)
                val reopened = KitBuilderModel.open(kitDir)
                val pad = assertNotNull(reopened.pad(1))
                val landed = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                assertEquals(recipe, landed, "$label: kit.json must retain the complete editable motion")
                assertTrue(pad.oneShot, "source repeat does not change a landed pad's gate")

                // A fresh composition has persisted strings and a shelf-relative update target.
                val settings = mutableMapOf("engine" to "PRISM", "voice" to patch.voiceName)
                val target = assertNotNull(SynthWork.source(shelf, kitDir, 1))
                SynthWork.replace(settings, listOf(SynthWork.Working(landed, target)))
                val work = SynthWork.read(settings.toMap()).single()
                val recalled = assertIs<PrismPatch>(work.recipe.patch)
                assertEquals(expected, recalled)
                assertEquals(source.patch, PrismMotionSources.originalSource(recalled), "saved provenance resolves without source audio in the recipe")
                val changedAmount = PrismMotionEditor.withAmount(recalled, .38f)
                val changedTime = PrismMotionEditor.withTime(changedAmount, .93f)
                val macros = changedTime.macros + mapOf("TUNE" to .25f, "BODY" to .67f)
                val edited = assertIs<PrismPatch>(Patches.edited(changedTime, "EDITED $label", macros))
                assertEquals(recalled.copy(name = "EDITED $label", macros = macros,
                    motion = recalled.motion!!.copy(evolve = .38f, seconds = .93f)), edited)
                val changed = work.recipe.copy(patch = edited)
                SynthWork.replace(settings, listOf(work.copy(recipe = changed)))
                SynthDrafts.write(settings, listOf(edited))
                val returning = SynthWork.read(settings.toMap()).single()
                val draft = assertIs<PrismPatch>(SynthDrafts.read(settings.toMap()).single())
                assertEquals(edited, draft)
                assertEquals(changed, returning.recipe)
                assertTrue(assertNotNull(returning.source).matches(shelf, kitDir))
                assertTrue(settings.values.none { shelf.absolutePath in it })

                val beforeVelocity = draft.toJsonText()
                assertContentEquals(edited.copy(velocity = .31f).render().samples,
                    Velocity.atVelocity(draft, .31f).samples, "$label: native velocity retains saved source motion")
                assertEquals(beforeVelocity, draft.toJsonText())

                val expectedWav = wav(changed)
                assertEquals(changed, assertIs<RecipeReplay.Plan.Patch>(RecipeReplay.plan(returning.recipe.toJsonValue())).recipe)
                RecipeReplay.apply(reopened, 1, returning.recipe.toJsonValue(), edited.name)
                reopened.save(accrueWear = false)
                val finalPad = assertNotNull(KitBuilderModel.open(kitDir).pad(1))
                assertEquals(changed, PadRecipe.fromJsonValue(assertNotNull(finalPad.recipe)))
                assertContentEquals(expectedWav, File(kitDir, finalPad.sampleFile).readBytes(),
                    "$label: the updated pad must contain the complete edited recipe's render")

                val promotedPatch = edited.copy(name = "MOTION \"BODY\" \$\\\n\t")
                val promoted = fromMotionRosterLine(UserPresets.rosterLine(promotedPatch))
                assertEquals(promotedPatch, promoted, "$label: factory promotion retains every source and travel field")
                assertEquals(promotedPatch.toJsonText(), promoted.toJsonText())
                assertContentEquals(promotedPatch.render().samples, promoted.render().samples)
            }
        } finally {
            shelf.deleteRecursively()
        }
    }

    private fun wav(recipe: PadRecipe): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, recipe.render())
    }.toByteArray()

    /** Parse actual Kotlin literals, then call the production constructor emitted by promotion. */
    private fun fromMotionRosterLine(line: String): PrismPatch {
        val separator = ".let { "
        assertTrue(line.endsWith(" },"), line)
        assertEquals(2, line.split(separator).size)
        val base = assertIs<KotlinLiteral.Call>(KotlinLiteral.parse(line.substringBefore(separator)))
        assertEquals("p", base.name)
        assertNull(base.receiver)
        assertTrue(base.named.isEmpty())
        val voice = assertIs<KotlinLiteral.Ref>(base.positional[0])
        assertEquals("PrismVoice", voice.owner)
        val name = assertIs<KotlinLiteral.Str>(base.positional[1]).value
        val macros = base.positional.drop(2).associate { value ->
            val pair = assertIs<KotlinLiteral.Pair>(value)
            val number = assertIs<KotlinLiteral.Num>(pair.second)
            assertEquals(KotlinLiteral.Num.Type.FLOAT, number.type)
            assertIs<KotlinLiteral.Str>(pair.first).value to number.text.dropLast(1).toFloat()
        }
        // Kotlin constructs p(...) before invoking its let; validate that helper's arguments too.
        val helper = PrismPatch(name, PrismVoice.valueOf(voice.name), Prism.defaults(PrismVoice.valueOf(voice.name)) + macros)
        val call = assertIs<KotlinLiteral.Call>(KotlinLiteral.parse(line.substringAfter(separator).removeSuffix(" },")))
        assertEquals("fromJsonText", call.name)
        val receiver = assertIs<KotlinLiteral.Call>(call.receiver)
        assertEquals("PrismPatch", receiver.name)
        assertNull(receiver.receiver)
        assertTrue(receiver.positional.isEmpty() && receiver.named.isEmpty())
        assertTrue(call.named.isEmpty())
        val patch = PrismPatch.fromJsonText(assertIs<KotlinLiteral.Str>(call.positional.single()).value)
        assertEquals(helper.name, patch.name)
        assertEquals(helper.voice, patch.voice)
        assertEquals(macros.mapValues { it.value.toRawBits() }, patch.macros.mapValues { it.value.toRawBits() })
        return patch
    }
}
