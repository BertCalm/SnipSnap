package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.CreaseVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The authoring door must preserve a saved excitation/model and offer only the meaningful release control. */
@RunWith(AndroidJUnit4::class)
class CreaseInstrumentScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val folders = mutableListOf<File>()

    @After
    fun cleanUp() {
        appScope.cancel()
        folders.forEach { it.deleteRecursively() }
    }

    private fun show(patch: CreasePatch): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val shelf = File(cache, "crease-keys-ui-${System.nanoTime()}").apply { mkdirs() }
        folders += shelf
        val settings = ConcurrentHashMap<String, String>().apply {
            put("engine", "CREASE")
            put("voice", patch.voiceName)
            put("preset.CREASE.${patch.voiceName}", patch.name)
            patch.macros.forEach { (name, value) -> put("macro.CREASE.${patch.voiceName}.$name", value.toString()) }
        }
        setPhoneContent {
            CompositionLocalProvider(LocalSynthPresetReader provides { _: File ->
                listOf(UserPresets.Saved(patch, 1L)) to emptyList<UserPresets.Binned>()
            }) {
                SynthScreen(entry = null, shelfRoot = shelf, onToast = {}, onKitUpdated = {},
                    onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                    appScope = appScope, settings = settings)
            }
        }
        val expectedRoot = (patch.midi - Crease.MIDI_MIN).toFloat() / (Crease.MIDI_MAX - Crease.MIDI_MIN)
        waitFor("saved CREASE root and excitation") {
            runCatching { progress("ROOT") == expectedRoot && progress("VELOCITY") == patch.velocity }.getOrDefault(false)
        }
        return shelf
    }

    private fun progress(label: String): Float = compose.onNode(
        hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
    ).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun click(label: String) {
        val action = compose.onNode(hasContentDescription(label) and hasClickAction())
            .fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("no click for $label")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    @Test
    fun current_saved_patch_opens_release_only_keys_without_creating_files() {
        val patch = CreasePatch("MY INK", CreaseVoice.RUBBER,
            Crease.defaults(CreaseVoice.RUBBER) + ("PRESSURE" to .61f), midi = 67, velocity = .35f, model = 3)
        val shelf = show(patch)
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertIsEnabled()
        click("MAKE INSTRUMENT")
        compose.onNodeWithContentDescription("RELEASE").assertIsEnabled()
        compose.onNodeWithContentDescription("ATTACK").assertDoesNotExist()
        compose.onNodeWithContentDescription("MAKE").assertIsEnabled()
        compose.onNodeWithText("KEEPS THE PRESSURE ATTACK, THEN REPEATS ITS MOVING SUSTAIN WHILE HELD. KEY TOUCH CHANGES VOLUME.")
            .fetchSemanticsNode()
        val instruments = File(shelf, KitShelf.INSTRUMENTS_DIR)
        assertTrue("opening authoring must not write a package", !instruments.exists() || instruments.listFiles().isNullOrEmpty())
    }

    @Test
    fun legacy_saved_model_keeps_its_note_and_velocity_and_cannot_silently_make_current_keys() {
        val patch = CreasePatch("MY ORIGINAL", CreaseVoice.ROUND,
            Crease.defaults(CreaseVoice.ROUND, 2), midi = 43, velocity = .4f, model = 2)
        show(patch)
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertIsNotEnabled()
        compose.onNodeWithText("MAKE INSTRUMENT NEEDS MODEL 3. THE SAVED ORIGINAL STILL PLAYS AS BEFORE.")
            .fetchSemanticsNode()
        assertEquals(.4f, progress("VELOCITY"), .0001f)
    }
}
