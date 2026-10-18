package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.TapeTheme
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiVoice
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The phone glue: absolute pitch must survive recall, edits, SCRAMBLE and SEND TO PAD. */
@RunWith(AndroidJUnit4::class)
class OrigamiSynthScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fixtures = mutableListOf<File>()
    private var entry by mutableStateOf<KitShelf.Entry?>(null)
    private var kitUpdates = 0
    private val settings = mutableMapOf<String, String>()
    private var showing by mutableStateOf(true)

    @After
    fun cleanUp() {
        appScope.cancel()
        fixtures.forEach { it.deleteRecursively() }
    }

    private fun show(saved: OrigamiPatch? = null, key: KeySpec? = null, withReplacement: Boolean = false) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val shelf = File(cache, "origami-synth-${System.nanoTime()}").apply { mkdirs() }
        fixtures += shelf
        val kitDir = File(shelf, "kit").apply { mkdirs() }
        val pads = if (withReplacement) {
            WavWriter.write(File(kitDir, "old.wav"), Snip(FloatArray(4410), channels = 1, sampleRate = 44_100))
            listOf(KitPad(2, "old.wav", "OLD CAPTURE", DrumClass.TONAL,
                tuneCoarse = 7, tuneFine = 31, oneShot = false,
                source = mapOf("app" to "old.recorder", "title" to "OLD", "rootMidi" to "33")))
        } else emptyList()
        val kit = Kit(name = "ORIGAMI TEST", pads = pads, key = key)
        KitStore.save(kit, kitDir)
        entry = KitShelf.Entry(kitDir, kit)
        saved?.let { UserPresets.save(shelf, it, System.currentTimeMillis()) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (showing) TapeTheme(Schemes.DEFAULT) {
                Box(Modifier.width(360.dp).fillMaxHeight()) {
                    SynthScreen(
                        entry = entry,
                        shelfRoot = shelf,
                        onToast = {},
                        onKitUpdated = { updated -> entry = KitShelf.Entry(kitDir, updated); kitUpdates += 1 },
                        onShelfAssetWritten = {},
                        onDroneToLoop = { _, _, _ -> },
                        onOpenSurface = {},
                        appScope = appScope,
                        settings = settings,
                    )
                }
            }
        }
        pump()
        // Exercise the real picker rather than adding a test-only initial engine.
        repeat(64) {
            val cycler = compose.onNode(hasContentDescription("ENGINE ", substring = true))
            val node = cycler.fetchSemanticsNode()
            if (node.config[SemanticsProperties.ContentDescription].single() == "ENGINE ORIGAMI · TAP FOR NEXT") return
            compose.runOnIdle { assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()) }
            pump(16)
        }
        throw AssertionError("ORIGAMI never appeared in the SYNTH picker")
    }

    private fun pump(millis: Long = 100) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    // Invoke semantics directly: these controls can be below the fold. Animated
    // performScrollTo cannot complete while this suite's frame clock is frozen.
    private fun click(label: String) {
        val action = compose.onNodeWithContentDescription(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun dial(label: String, value: Float) {
        val action = compose.onNodeWithContentDescription(label).fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    private fun macroValue(label: String): Float = compose.onNodeWithContentDescription(label)
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun send(slot: Int): OrigamiPatch {
        val before = kitUpdates
        val old = entry!!.kit.pad(slot)
        click("SEND TO PAD")
        click("A%02d, %s".format(slot, old?.displayName ?: "EMPTY"))
        val deadline = System.currentTimeMillis() + 30_000
        while (kitUpdates == before) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("ORIGAMI did not land on pad $slot")
            pump(16)
        }
        val pad = KitStore.load(entry!!.dir).pad(slot)!!
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertTrue(pad.oneShot)
        assertEquals(0, pad.tuneFine)
        val patch = PadRecipe.fromJsonValue(pad.recipe!!).patch as OrigamiPatch
        val root = patch.midi ?: Origami.midiFor(patch.voice, patch.resolvedMacros().getValue("TUNE"))
        assertEquals(mapOf("from" to "SYNTH", "rootMidi" to root.toString(), "rootNote" to Scales.nameOf(root), "rootOrigin" to "authored"), pad.source)
        return patch
    }

    @Test
    fun threeVoicesExposeSixFiniteControls() {
        show()
        for (voice in listOf("CRANE", "FAN", "STILL")) {
            click(voice)
            for (macro in listOf("TUNE", "CREASE", "FOLD", "TURN", "WARP", "MOTION")) {
                compose.onNodeWithContentDescription(macro).assertExists()
            }
            compose.onNodeWithContentDescription("HOLD").assertDoesNotExist()
            compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertDoesNotExist()
            compose.onNodeWithContentDescription("DRONE TO LOOP").assertDoesNotExist()
        }
    }

    @Test
    fun recalledR4MetadataSurvivesGeometryEditsAndDeliberatePitchChange() = editedPresetSurvives(Origami.MODEL_VERSION)

    @Test
    fun recalledFrozenModelSurvivesGeometryEditsAndDeliberatePitchChange() = editedPresetSurvives(2)

    @Test
    fun editedRecipeSurvivesLeavingSynthWithoutAPresetHighlight() {
        val saved = OrigamiPatch(
            "TEST PAPER", OrigamiVoice.FAN, mapOf("TUNE" to 0f),
            midi = 67, velocity = .42f, seed = 9_007_199_254_740_993L, model = 2,
        )
        show(saved, key = KeySpec.parse("Cmin"))
        loadSavedPreset(saved)
        dial("FOLD", .91f)
        dial("TUNE", .5f)
        assertFalse(settings.containsKey("preset.ORIGAMI.FAN"))

        // Destroy the composable and all its remembered maps, as navigation does.
        // Only the caller's settings bag and the on-disk preset shelf survive.
        compose.runOnIdle { showing = false }
        pump()
        compose.runOnIdle { showing = true }
        pump()
        compose.onNodeWithContentDescription("ENGINE ORIGAMI · TAP FOR NEXT").assertExists()
        assertEquals(.91f, macroValue("FOLD"), .0001f)
        assertEquals(.5f, macroValue("TUNE"), .0001f)
        val restored = send(1)
        assertEquals(60, restored.midi)
        assertEquals(saved.seed, restored.seed)
        assertEquals(saved.velocity, restored.velocity)
        assertEquals(saved.model, restored.model)
        assertEquals(.91f, restored.macros.getValue("FOLD"))
    }

    @Test
    fun keyedKitUsesCraneAuthoredRootWithoutDetectorFineTune() {
        show(key = KeySpec.parse("Cmin"))
        dial("TUNE", 0f)
        val patch = send(1)
        assertEquals(48, Origami.midiFor(patch.voice, patch.resolvedMacros().getValue("TUNE")))
        val pad = KitStore.load(entry!!.dir).pad(1)!!
        assertEquals(0, pad.tuneCoarse)
        assertEquals(0, pad.tuneFine)
    }

    private fun editedPresetSurvives(model: Int) {
        val saved = OrigamiPatch(
            "TEST PAPER", OrigamiVoice.FAN, mapOf("TUNE" to 0f),
            midi = 67, velocity = .42f, seed = 9_007_199_254_740_993L, model = model,
        )
        show(saved, key = KeySpec.parse("Cmin"), withReplacement = true)
        loadSavedPreset(saved)
        assertEquals(19f / 24f, macroValue("TUNE"), .0001f)
        // Sparse older recipes must use their frozen defaults, not R4's
        // recalibrated FAN crease, before the first edit or render.
        assertEquals(if (model <= 3) .63f else .5f, macroValue("CREASE"), .0001f)
        click("SCRAMBLE")
        assertEquals(19f / 24f, macroValue("TUNE"), .0001f)
        dial("FOLD", .9f)
        val first = send(1)
        assertEquals(saved.midi, first.midi)
        assertEquals(saved.velocity, first.velocity)
        assertEquals(saved.seed, first.seed)
        assertEquals(saved.model, first.model)
        assertEquals(.9f, first.macros.getValue("FOLD"))

        dial("TUNE", .5f)
        val transposed = send(2)
        assertEquals(60, transposed.midi)
        assertEquals(saved.velocity, transposed.velocity)
        assertEquals(saved.seed, transposed.seed)
        assertEquals(saved.model, transposed.model)
        assertEquals(.9f, transposed.macros.getValue("FOLD"))
        val replaced = KitStore.load(entry!!.dir).pad(2)!!
        assertEquals(0, replaced.tuneCoarse)
        assertEquals(0, replaced.tuneFine)
    }

    private fun loadSavedPreset(saved: OrigamiPatch) {
        click(saved.voice.name)
        val deadline = System.currentTimeMillis() + 10_000
        while (compose.onNodeWithText(saved.name).runCatching { fetchSemanticsNode() }.isFailure) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("the saved ORIGAMI preset never appeared")
            pump(16)
        }
        val preset = compose.onNodeWithText(saved.name).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { assertTrue(preset.invoke()) }
        pump()
    }
}
