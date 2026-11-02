package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.ScannedChainEditor
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.ScannedChain
import com.snipsnap.synth.ScannedChainConfig
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.ScannedChainShape
import com.snipsnap.synth.ScannedChainShapeSource
import com.snipsnap.synth.ScannedChainVoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The SCANNED source panel's UI glue: drafts commit only on USE SHAPE,
 * independent fields retain the rest of the patch, and recall returns the
 * whole pad recipe. Solver and shape conditioning belong to :synth's tests.
 */
@RunWith(AndroidJUnit4::class)
class ScannedShapeControlsTest : ComposeScreenTest() {
    private var currentPatch by mutableStateOf(fixture(ScannedChainVoice.PLUCK))
    private var changeCount = 0
    private var recalled: PadRecipe? = null
    private val kitDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        kitDirs.forEach { it.deleteRecursively() }
    }

    private fun fixture(voice: ScannedChainVoice): ScannedChainPatch = ScannedChainPatch(
        name = "MY SCANNED ${voice.name}",
        voice = voice,
        macros = ScannedChain.defaults(voice) + mapOf("MOTION" to .43f, "BEND" to .61f),
        config = ScannedChainConfig.forVoice(voice).copy(
            restFraction = .19f, seconds = 1.75f, attackSeconds = .04f,
            releaseSeconds = .31f, seed = -831,
        ),
        midi = 57,
        velocity = .38f,
    )

    private fun show(patch: ScannedChainPatch, entry: KitShelf.Entry? = null) {
        currentPatch = patch
        setPhoneContent {
            ScannedShapeControls(
                patch = currentPatch,
                entry = entry,
                onPatchChanged = { currentPatch = it; changeCount++ },
                onRecallPatch = { recalled = it },
            )
        }
    }

    /** Invoke the accessible adjustment without depending on the control's screen position. */
    private fun adjust(label: String, target: Float) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: throw AssertionError("$label has no accessible adjustment")
        assertTrue("$label refused $target", action.invoke(target))
        pump()
        val progress = compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertNotNull("$label lost its current value", progress)
        assertEquals(target, progress!!.current, .0001f)
    }

    @Test
    fun opening_saved_envelopes_keeps_their_exact_values_in_both_models() {
        val original = fixture(ScannedChainVoice.BLOOM).copy(
            model = 1,
            config = fixture(ScannedChainVoice.BLOOM).config.copy(
                seconds = .25f, attackSeconds = .8234567f, releaseSeconds = 1.7654321f,
            ),
        )
        show(original)
        assertEquals(original.toJsonText(), currentPatch.toJsonText())
        assertEquals(0, changeCount)

        val updated = original.copy(model = 2)
        compose.runOnUiThread { currentPatch = updated }
        pump()
        assertEquals(updated.toJsonText(), currentPatch.toJsonText())
        assertEquals("opening a recipe never quantizes or shortens its envelope", 0, changeCount)
    }

    @Test
    fun envelope_edits_preserve_the_complete_source_and_other_fields_in_both_models() {
        val original = fixture(ScannedChainVoice.PLUCK).copy(
            model = 1,
            shape = ScannedChainShape.drawn(FloatArray(128) { i -> if (i % 11 < 5) .21f else -.13f }, "KEPT SOURCE"),
        )
        show(original)
        for (model in listOf(1, 2)) {
            val saved = original.copy(model = model)
            compose.runOnUiThread { currentPatch = saved }
            pump()

            adjust("ATTACK", .2f)
            val attacked = saved.copy(config = saved.config.copy(attackSeconds = ScannedChainEditor.attackSeconds(.2f)))
            assertEquals(attacked, currentPatch)

            adjust("RELEASE", .9f)
            val released = attacked.copy(config = attacked.config.copy(releaseSeconds = ScannedChainEditor.releaseSeconds(.9f)))
            assertEquals(released, currentPatch)
            assertEquals(released, ScannedChainPatch.fromJsonText(currentPatch.toJsonText()))
        }
        assertEquals(4, changeCount)
    }

    @Test
    fun consecutive_envelope_and_length_edits_keep_each_other_before_recomposition() {
        val original = fixture(ScannedChainVoice.METAL).copy(model = 1)
        show(original)
        fun action(label: String) = compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        val attack = action("ATTACK")
        val release = action("RELEASE")
        val length = action("LENGTH")
        compose.runOnUiThread {
            assertTrue(attack(.8f))
            assertTrue(release(.95f))
            assertTrue(length(.25f))
        }
        pump()

        assertEquals(original.copy(config = original.config.copy(
            seconds = .25f,
            attackSeconds = ScannedChainEditor.attackSeconds(.8f),
            releaseSeconds = ScannedChainEditor.releaseSeconds(.95f),
        )), currentPatch)
        assertEquals("long envelopes remain deliberate choices when length is reduced", 3, changeCount)
    }

    @Test
    fun updated_controls_keep_the_complete_legacy_source_and_settings() {
        val original = fixture(ScannedChainVoice.BLOOM).copy(model = ScannedChain.LEGACY_MODEL)
        show(original)
        assertReadsInFull("USE UPDATED CONTROLS")

        tap("USE UPDATED CONTROLS")

        assertEquals(original.copy(model = ScannedChain.MODEL_VERSION), currentPatch)
        assertEquals(1, changeCount)
        compose.onNodeWithText("USE UPDATED CONTROLS", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun a_drawn_starting_shape_commits_without_resetting_the_current_recipe() {
        val original = fixture(ScannedChainVoice.PLUCK)
        show(original)
        for (label in listOf("FACTORY", "DRAW", "FROM PAD ▸", "VELOCITY", "LENGTH")) {
            assertReadsInFull(label)
        }

        tap("DRAW")
        compose.onNodeWithContentDescription("DRAW INITIAL SHAPE").assertExists()
        tap("SINE")
        assertEquals("editing a draft must not change the patch", original, currentPatch)
        assertEquals(0, changeCount)
        tap("USE SHAPE")

        val committed = currentPatch
        assertEquals(1, changeCount)
        assertEquals(ScannedChainShapeSource.DRAWN, committed.shape.source)
        assertEquals(ScannedChain.SHAPE_SIZE, committed.shape.points.size)
        assertFalse(original.shape.points.contentEquals(committed.shape.points))
        assertEquals(original.copy(shape = committed.shape), committed)
        compose.onNodeWithText("INITIAL SHAPE · DRAWN", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("DRAW INITIAL SHAPE").assertDoesNotExist()
    }

    @Test
    fun cancel_discards_a_changed_draft_and_a_flat_draft_cannot_commit() {
        val original = fixture(ScannedChainVoice.METAL)
        show(original)

        tap("DRAW")
        tap("SINE")
        tap("CANCEL")
        assertEquals(original, currentPatch)
        assertEquals(0, changeCount)

        tap("DRAW")
        tap("CLEAR")
        button("USE SHAPE").assertIsNotEnabled()
        tap("CANCEL")
        assertEquals(original, currentPatch)
        assertEquals(0, changeCount)
        compose.onNodeWithContentDescription("DRAW INITIAL SHAPE").assertDoesNotExist()
    }

    @Test
    fun velocity_and_length_each_preserve_the_other_saved_fields() {
        val voice = ScannedChainVoice.BLOOM
        val custom = ScannedChainShape.drawn(
            FloatArray(ScannedChain.SHAPE_SIZE) { i -> if (i < 29) .27f else -.08f },
            label = "MY UNEVEN CHAIN",
        )
        val original = fixture(voice).copy(shape = custom)
        show(original)

        // ROOT is the shared stepper row drawn by the screen, not by these controls; the screen
        // tests drive it. What is checked here is that each control keeps the others' saved fields.
        adjust("VELOCITY", .64f)
        assertEquals(original.copy(velocity = .64f), currentPatch)

        adjust("LENGTH", 2.5f)
        assertEquals(original.copy(velocity = .64f, config = original.config.copy(seconds = 2.5f)), currentPatch)
        assertEquals(2, changeCount)
    }

    @Test
    fun recall_returns_the_full_saved_pad_recipe_including_its_effects_and_metadata() {
        val source = fixture(ScannedChainVoice.METAL).copy(
            shape = ScannedChainShape.drawn(
                FloatArray(ScannedChain.SHAPE_SIZE) { i -> if (i % 7 < 3) .2f else -.15f },
                label = "KEPT CHAIN",
            ),
        )
        val recipe = PadRecipe(
            patch = source, fx = FxChain(reverse = true),
            treatment = "TAPE", amount = .37f, alias = true,
        )
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "scanned-controls-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        WavWriter.write(File(dir, "kept.wav"), Snip(FloatArray(4410), channels = 1, sampleRate = 44_100))
        KitStore.save(
            Kit(name = "SCANNED RECALL TEST", pads = listOf(
                KitPad(slot = 3, sampleFile = "kept.wav", displayName = "KEPT CHAIN", drumClass = DrumClass.TONAL, recipe = recipe.toJsonValue()),
            )),
            dir,
        )
        val original = fixture(ScannedChainVoice.PLUCK)
        show(original, KitShelf.Entry(dir, KitStore.load(dir)))
        assertReadsInFull("RECALL SCANNED PAD ▸")

        tap("RECALL SCANNED PAD ▸")
        tap("${PadBanks.tag(3)} · KEPT CHAIN")

        assertEquals(recipe, recalled)
        assertEquals("recall is a separate callback from editing the active patch", original, currentPatch)
        assertEquals(0, changeCount)
        compose.onNodeWithText("RECALL SCANNED PAD", useUnmergedTree = true).assertDoesNotExist()
    }
}
