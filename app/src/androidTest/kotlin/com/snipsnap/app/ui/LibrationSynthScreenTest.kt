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
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Libration
import com.snipsnap.synth.LibrationPatch
import com.snipsnap.synth.LibrationPresets
import com.snipsnap.synth.LibrationVoice
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class LibrationSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
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

    private fun show(factory: LibrationPatch? = null, entry: KitShelf.Entry? = null): File {
        val shelf = folder("libration-ui")
        settings["engine"] = "LIBRATION"
        settings["voice"] = factory?.voiceName ?: "GATHER"
        if (factory != null) {
            settings["preset.LIBRATION.${factory.voiceName}"] = factory.name
            for ((name, value) in factory.macros) settings["macro.LIBRATION.${factory.voiceName}.$name"] = value.toString()
        }
        setPhoneContent {
            key(visit) {
                SynthScreen(entry, shelf, onToast = {}, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        return shelf
    }

    // The navigation bag keeps performance fields in a draft and live sliders
    // in macro.* entries. Reconstruct their combined working sound as SYNTH does.
    private fun stored(voice: LibrationVoice) = SynthDrafts.read(settings).filterIsInstance<LibrationPatch>()
        .firstOrNull { it.voice == voice }?.let { patch ->
            patch.copy(macros = Libration.defaults(voice).mapValues { (name, fallback) ->
                settings["macro.LIBRATION.${voice.name}.$name"]?.toFloatOrNull()
                    ?: patch.macros[name] ?: fallback
            })
        }

    private fun invokeClick(label: String) {
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

    private fun progress(label: String) = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.current

    @Test fun all_three_voices_expose_the_twelve_factory_recipes_and_finite_controls() {
        show()
        compose.onNodeWithContentDescription("ENGINE LIBRATION. CHOOSE ENGINE").assertExists()
        for (voice in LibrationVoice.entries) {
            invokeClick(voice.name)
            for (macro in Libration.macrosFor(voice).filterNot { it.name == "HOLD" }) slider(macro.name).assertExists()
            slider("HOLD").assertDoesNotExist()
            for (label in listOf("ROOT", "TOUCH", "LENGTH")) slider(label).assertExists()
            for (patch in LibrationPresets.forVoice(voice)) {
                invokeClick("PRESET ${patch.name}")
                waitFor("${patch.name} saved performance") { stored(voice) == patch }
                assertEquals(patch.velocity, progress("TOUCH"), 1e-6f)
                assertEquals((patch.midi - 36) / 48f, progress("ROOT"), 1e-6f)
            }
        }
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertDoesNotExist()
    }

    @Test fun root_touch_length_and_macros_survive_return_save_and_send_to_pad() {
        val kitDir = folder("libration-pad")
        val kit = Kit("LIBRATION UI", emptyList())
        KitStore.save(kit, kitDir)
        val factory = LibrationPresets.all().single { it.name == "ROUND KEYS" }
        val shelf = show(factory, KitShelf.Entry(kitDir, kit))
        waitFor("factory performance") { stored(factory.voice) == factory }
        dial("ROOT", .25f)
        dial("TOUCH", .37f)
        dial("LENGTH", .2f)
        dial("NUDGE", .61f)
        waitFor("edited performance") {
            stored(factory.voice)?.let { it.midi == 48 && it.velocity == .37f &&
                it.sourceSeconds == .512 && it.macros["NUDGE"] == .61f } == true
        }
        val edited = requireNotNull(stored(factory.voice))
        assertFalse(settings.containsKey("preset.LIBRATION.GATHER"))
        invokeClick("SCRAMBLE")
        waitFor("scrambled timbre") { stored(factory.voice)?.macros != edited.macros }
        val scrambled = requireNotNull(stored(factory.voice))
        assertEquals(edited.copy(macros = scrambled.macros), scrambled)
        assertEquals(0f, scrambled.macros.getValue("HOLD"), 0f)
        compose.runOnIdle { visit += 1 }
        pump()
        waitFor("return to the same recipe") { stored(factory.voice) == scrambled }
        assertEquals(.25f, progress("ROOT"), 1e-6f)
        assertEquals(.37f, progress("TOUCH"), 1e-6f)
        invokeClick("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY ORBIT")
        pump()
        invokeClick("SAVE")
        waitFor("saved preset") { UserPresets.read(shelf).any { it.name == "MY ORBIT" } }
        assertEquals(scrambled.copy(name = "MY ORBIT"), UserPresets.read(shelf).single().patch)
        invokeClick("SEND TO PAD")
        invokeClick("A01, EMPTY")
        waitFor("recipe on A01", 60_000) { KitStore.load(kitDir).pad(1)?.recipe != null }
        val pad = requireNotNull(KitStore.load(kitDir).pad(1))
        val landed = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as LibrationPatch
        assertEquals(scrambled.copy(name = landed.name), landed)
        assertEquals("48", pad.source["rootMidi"])
        assertEquals(0, pad.tuneFine)
        assertTrue(pad.oneShot)
    }
}
