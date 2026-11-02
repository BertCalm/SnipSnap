package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.OrigamiGesture
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Velocity
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Gestures survive the actual preset, pad, promotion and held-instrument recipe doors. */
class OrigamiGestureWorkflowTest {
    private val temp = kotlin.io.path.createTempDirectory("origami-gesture-workflow").toFile()
    private val gestures = listOf(OrigamiGesture.BLOOM, OrigamiGesture.UNFOLD)

    @AfterTest
    fun cleanUp() { temp.deleteRecursively() }

    private fun authored(voice: OrigamiVoice, gesture: OrigamiGesture) = OrigamiPatch(
        name = "MY ${gesture.name}", voice = voice,
        macros = linkedMapOf("TUNE" to 0f, "CREASE" to .37f, "FOLD" to .82f,
            "TURN" to .61f, "WARP" to .44f, "MOTION" to .73f),
        midi = 72, velocity = .58f, seed = -9007199254740993L, model = 4, gesture = gesture,
    )

    @Test
    fun `saved gestures retain their complete state through editing native velocity and persisted pad recipes`() {
        val shelf = File(temp, "shelf")
        val originals = OrigamiVoice.entries.flatMap { voice -> gestures.map { authored(voice, it) } }
        val saved = originals.mapIndexed { index, patch -> UserPresets.save(shelf, patch, 1000L + index) }
        assertTrue(UserPresets.file(shelf).isFile)
        assertEquals(saved, UserPresets.read(shelf))
        val restored = UserPresets.read(shelf).map { assertIs<OrigamiPatch>(it.patch) }
        for ((original, reopened) in originals.zip(restored)) {
            assertEquals(original, reopened)
            val macros = reopened.macros + ("FOLD" to .63f)
            val edited = assertIs<OrigamiPatch>(Patches.edited(reopened, "EDITED PAPER", macros))
            assertEquals(original.copy(name = "EDITED PAPER", macros = macros), edited)
            assertEquals(edited, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
        }

        // One audible pair verifies the native adapter's render as well as the saved recipe's metadata.
        val original = originals.first { it.voice == OrigamiVoice.FAN && it.gesture == OrigamiGesture.BLOOM }
        val reopened = restored.single { it.voice == original.voice && it.gesture == original.gesture }
        val expected = original.copy(velocity = .35f)
        val rendered = Velocity.atVelocity(reopened, .35f)
        assertContentEquals(expected.render().samples, rendered.samples,
            "native velocity lost the saved gesture or changed its seed, note, model or geometry")
        val recipe = PadRecipe(expected)
        val dir = File(temp, "pad-kit")
        val kit = KitBuilderModel.create("GESTURE PAPER", dir)
        kit.assign(1, rendered, DrumClass.TONAL, expected.name, knownRootMidi = 72)
        kit.update(1) { it.copy(recipe = recipe.toJsonValue()) }
        kit.save()
        val pad = assertNotNull(KitBuilderModel.open(dir).pad(1))
        assertEquals(recipe, PadRecipe.fromJsonValue(assertNotNull(pad.recipe)))
        assertTrue(pad.oneShot, "a gesture changes a finite note's timbre, not its pad gate")
        assertEquals("72", pad.source["rootMidi"])
        assertEquals("authored", pad.source["rootOrigin"])
    }

    @Test
    fun `bench promotion preserves gestures and escaped complete recipes as real Kotlin literals`() {
        for (voice in OrigamiVoice.entries) for (gesture in gestures) {
            val patch = authored(voice, gesture).copy(name = "FOLD \"\$X\"")
            val line = UserPresets.rosterLine(patch)
            val call = KotlinLiteral.parse(line.removeSuffix(",")) as KotlinLiteral.Call
            assertEquals("fromJsonText", call.name)
            val receiver = call.receiver as KotlinLiteral.Call
            assertEquals("OrigamiPatch", receiver.name)
            assertTrue(receiver.positional.isEmpty() && receiver.named.isEmpty())
            assertTrue(call.named.isEmpty())
            val recipe = (call.positional.single() as KotlinLiteral.Str).value
            assertEquals(patch, OrigamiPatch.fromJsonText(recipe),
                "$voice $gesture promotion discarded independent gesture or render state")
        }
    }

    @Test
    fun `held exports keep the gesture in their version one companion without changing roots loops or release`() {
        for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) for (gesture in gestures) {
            val patch = authored(voice, gesture).copy(macros = mapOf("FOLD" to .82f, "MOTION" to .73f))
            assertTrue(OrigamiPadMaker.supports(patch))
            val spec = OrigamiPadMaker.Spec(patch, .73f)
            assertEquals(spec, OrigamiPadMaker.Spec.fromJsonText(spec.toJsonText()))
            val roots = OrigamiPadMaker.zoneMidis(spec)
            val notes = roots.mapIndexed { index, _ ->
                KeyNote(Snip(FloatArray(512) { frame -> (.12 * sin(2 * PI * frame / 32)).toFloat() }, 1, 44100),
                    loopStartFrame = (64 + index * 8).toLong())
            }
            val dir = File(temp, "${voice.name}-${gesture.name}")
            val name = "${voice.name} ${gesture.name}"
            OrigamiPadMaker.export(name, spec, notes, dir)
            val recipeText = OrigamiPadMaker.recipeFile(dir, name).readText()
            val saved = Json.parse(recipeText).obj()
            assertEquals(1, saved.getValue("version").int())
            assertEquals(1, saved.getValue("heldModel").int())
            assertEquals(patch.toJsonValue(), saved.getValue("patch"))
            val savedGesture = saved.getValue("patch").obj().getValue("gesture").obj()
            assertEquals(gesture.name, savedGesture.getValue("name").str())
            assertEquals(1, savedGesture.getValue("model").int())
            assertEquals(spec, OrigamiPadMaker.Spec.fromJsonText(recipeText))
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(.73f, instrument.release)
            assertEquals(roots, instrument.zones.map { it.rootNote })
            assertEquals(0, instrument.zones.first().lowNote)
            assertEquals(127, instrument.zones.last().highNote)
            val center = instrument.zones.single { it.rootNote == 60 }
            assertEquals(notes[roots.indexOf(60)].loopStartFrame, center.loopStartFrame)
            assertEquals(SmplChunk(60, SmplChunk.Loop(center.loopStartFrame, center.frameCount)),
                WavReader.readSmpl(File(sidecar.parentFile, center.sample)))
        }
    }

    @Test
    fun `held recipe recall defaults old patches to steady and refuses unknown nested gesture versions`() {
        val patch = authored(OrigamiVoice.STILL, OrigamiGesture.UNFOLD)
        val spec = OrigamiPadMaker.Spec(patch, .6f)
        val fields = Json.parse(spec.toJsonText()).obj()
        val patchFields = fields.getValue("patch").obj()
        val old = JsonValue.Obj(fields + ("patch" to JsonValue.Obj(patchFields - "gesture")))
        val restored = OrigamiPadMaker.Spec.fromJsonText(Json.write(old))
        assertEquals(spec.copy(patch = patch.copy(gesture = OrigamiGesture.STEADY)), restored)
        assertFalse("gesture" in restored.patch.toJsonValue().entries,
            "recalling an old finite source recipe must not add new gesture JSON")
        val unknown = JsonValue.Obj(patchFields + ("gesture" to JsonValue.Obj(linkedMapOf(
            "name" to JsonValue.Str("UNFOLD"), "model" to JsonValue.Num(99.0),
        ))))
        assertFailsWith<JsonException> {
            OrigamiPadMaker.Spec.fromJsonText(Json.write(JsonValue.Obj(fields + ("patch" to unknown))))
        }
    }
}
