package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.shell.Copy
import com.snipsnap.synth.Accord
import com.snipsnap.synth.AccordAcousticProfile
import com.snipsnap.synth.AccordInitializer
import com.snipsnap.synth.AccordPatch
import com.snipsnap.synth.AccordPresets
import com.snipsnap.synth.AccordVoice
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Real phone bindings must retain the complete finite ensemble, especially TINE's selected profile. */
@RunWith(AndroidJUnit4::class)
class AccordSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val toasts = CopyOnWriteArrayList<String>()
    private val folders = mutableListOf<File>()
    private var visit by mutableIntStateOf(0)

    @After fun cleanUp() {
        appScope.cancel()
        folders.forEach { it.deleteRecursively() }
    }

    private fun folder(name: String): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "$name-${System.nanoTime()}").apply { mkdirs(); folders += this }
    }

    private fun show(source: AccordPatch? = null, entry: KitShelf.Entry? = null): File {
        val shelf = folder("accord-ui")
        settings["engine"] = "ACCORD"
        settings["voice"] = source?.voiceName ?: "BRUSH"
        if (source != null) SynthWork.replace(settings,
            listOf(SynthWork.Working(PadRecipe(source), null)))
        setPhoneContent {
            key(visit) {
                SynthScreen(entry, shelf, onToast = { toasts += it }, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        return shelf
    }

    // Navigation snapshots keep typed performance fields while macro.* contains
    // the current sliders. Reconstruct the same working recipe the bench renders.
    private fun stored(voice: AccordVoice): AccordPatch? = SynthDrafts.read(settings)
        .filterIsInstance<AccordPatch>().firstOrNull { it.voice == voice }?.let { patch ->
            patch.copy(macros = Accord.defaults(voice).mapValues { (name, fallback) ->
                settings["macro.ACCORD.${voice.name}.$name"]?.toFloatOrNull()
                    ?: patch.macros[name] ?: fallback
            })
        }

    private fun activate(label: String) {
        val action = compose.onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun slider(label: String) = compose.onNode(hasContentDescription(label) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    private fun dial(label: String, value: Float) {
        val action = slider(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("no slider for $label")
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    private fun progress(label: String): Float = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.current

    private fun profileLabel(profile: AccordAcousticProfile) =
        "ACCORD PROFILE ${profile.name.replace('_', ' ')}"

    @Test fun brush_is_the_default_and_all_six_presets_restore_their_finite_profile() {
        show()
        compose.onNodeWithContentDescription("ENGINE ACCORD. CHOOSE ENGINE").assertExists()
        assertEquals("BRUSH", settings["voice"])
        assertEquals((48 - 36) / 36f, progress("ROOT"), 1e-6f)
        assertEquals(.75f, progress("TOUCH"), 1e-6f)
        assertEquals(1f, progress("LENGTH"), 1e-6f)
        for (voice in AccordVoice.entries) {
            activate(voice.name)
            for (macro in Accord.macrosFor(voice).filterNot { it.name == "HOLD" }) slider(macro.name).assertExists()
            slider("HOLD").assertDoesNotExist()
            for (label in listOf("ROOT", "TOUCH", "LENGTH")) slider(label).assertExists()
            for (preset in AccordPresets.forVoice(voice)) {
                activate("PRESET ${preset.name}")
                waitFor("${preset.name} complete recipe") { stored(voice) == preset }
                assertEquals(preset.acousticProfile, requireNotNull(stored(voice)).acousticProfile)
            }
        }
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertDoesNotExist()
    }

    @Test fun changing_tine_profile_preserves_the_saved_history_and_return_keeps_the_choice() {
        val source = AccordPatch("SAVED TINE", AccordVoice.TINE, Accord.defaults(AccordVoice.TINE),
            midi = 57, velocity = .42f, seed = 9_007_199_254_740_993L,
            acousticProfile = AccordAcousticProfile.LONG_RING,
            initializer = AccordInitializer.PROJECT_SINE_V1,
            sourceStartCycles = 1.25, sourceSeconds = Accord.MIN_SOURCE_SECONDS)
        show(source)
        waitFor("saved TINE source") { stored(source.voice) == source }
        for (profile in AccordAcousticProfile.entries.filter { it != AccordAcousticProfile.BASE }) {
            activate(profileLabel(profile))
            waitFor("TINE $profile") { stored(source.voice)?.acousticProfile == profile }
            val actual = requireNotNull(stored(source.voice))
            assertEquals(source.copy(name = actual.name, acousticProfile = profile), actual)
        }
        // Both callbacks originate in one composition. A slider that copies
        // its captured patch would undo the profile click in the same frame.
        val chooseBright = compose.onNode(hasContentDescription(profileLabel(AccordAcousticProfile.BRIGHT_EDGE)) and
            hasClickAction()).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)!!.action!!
        val moveRoot = slider("ROOT").fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)!!.action!!
        compose.runOnIdle {
            assertTrue(chooseBright.invoke())
            assertTrue(moveRoot.invoke(.25f))
        }
        pump()
        waitFor("same-frame root edit retains Bright Edge") {
            stored(source.voice)?.let { it.acousticProfile == AccordAcousticProfile.BRIGHT_EDGE && it.midi == 45 } == true
        }
        val selected = requireNotNull(stored(source.voice))
        assertEquals(source.copy(name = selected.name, midi = 45,
            acousticProfile = AccordAcousticProfile.BRIGHT_EDGE), selected)
        compose.runOnIdle { visit += 1 }
        pump()
        waitFor("return keeps Bright Edge") { stored(source.voice) == selected }
        assertEquals(9_007_199_254_740_993L, requireNotNull(stored(source.voice)).seed)
    }

    @Test fun note_touch_length_and_macros_keep_profile_seed_and_initializer_through_save_and_pad() {
        val kitDir = folder("accord-pad")
        val kit = Kit("ACCORD UI", emptyList())
        KitStore.save(kit, kitDir)
        val source = AccordPatch("SAVED TINE", AccordVoice.TINE, Accord.defaults(AccordVoice.TINE),
            midi = 57, velocity = .42f, seed = Long.MIN_VALUE,
            acousticProfile = AccordAcousticProfile.BRIGHT_EDGE,
            initializer = AccordInitializer.PROJECT_SINE_V1,
            sourceStartCycles = .75, sourceSeconds = Accord.MIN_SOURCE_SECONDS)
        val shelf = show(source, KitShelf.Entry(kitDir, kit))
        waitFor("saved performance") { stored(source.voice) == source }
        dial("ROOT", .75f)
        dial("TOUCH", .37f)
        dial("LENGTH", .2f)
        dial("CONTACT", .61f)
        waitFor("edited performance") {
            stored(source.voice)?.let { it.midi == 63 && it.velocity == .37f &&
                it.sourceSeconds == 1.04 && it.macros["CONTACT"] == .61f } == true
        }
        val edited = requireNotNull(stored(source.voice))
        assertEquals(source.copy(name = edited.name, macros = edited.macros, midi = 63,
            velocity = .37f, sourceSeconds = 1.04), edited)
        assertFalse(settings.containsKey("preset.ACCORD.TINE"))
        compose.runOnIdle { visit += 1 }
        pump()
        waitFor("edited performance after returning") { stored(source.voice) == edited }
        assertEquals(.75f, progress("ROOT"), 1e-6f)
        assertEquals(.37f, progress("TOUCH"), 1e-6f)
        activate("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY BRIGHT TINE")
        pump()
        activate("SAVE")
        waitFor("saved typed preset") { UserPresets.read(shelf).any { it.name == "MY BRIGHT TINE" } }
        assertEquals(edited.copy(name = "MY BRIGHT TINE"),
            UserPresets.read(shelf).single { it.name == "MY BRIGHT TINE" }.patch)
        toasts.clear()
        activate("SEND TO PAD")
        activate("A01, EMPTY")
        // This bounds a real asynchronous render/write on a software emulator;
        // it is not a phone latency assertion. Refusals fail immediately.
        waitFor("typed recipe on A01", 180_000) {
            assertFalse("SEND TO PAD failed; messages: ${toasts.joinToString()}",
                toasts.any { it == Copy.SYNTH_PAD_REFUSED || it == Copy.SEND_FAILED })
            KitStore.load(kitDir).pad(1)?.recipe != null
        }
        val pad = requireNotNull(KitStore.load(kitDir).pad(1))
        val landed = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as AccordPatch
        assertEquals(edited.copy(name = landed.name), landed)
        assertEquals(AccordAcousticProfile.BRIGHT_EDGE, landed.acousticProfile)
        assertEquals(Long.MIN_VALUE, landed.seed)
        assertEquals("63", pad.source["rootMidi"])
        assertEquals(0, pad.tuneFine)
        assertTrue(pad.oneShot)
    }
}
