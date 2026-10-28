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
import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Oven
import com.snipsnap.synth.OvenPatch
import com.snipsnap.synth.OvenPresets
import com.snipsnap.synth.OvenVoice
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

/**
 * OVEN's Android glue: factory adoption, independent root/strike editing,
 * returning to the lab and the exact recipes saved or sent to a pad.
 * Controls below the fold use their accessibility actions directly, as the
 * pad-sheet tests do; no scrolling animation depends on the frozen test clock.
 */
@RunWith(AndroidJUnit4::class)
class OvenSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val folders = mutableListOf<File>()
    private var visit by mutableIntStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        folders.forEach { it.deleteRecursively() }
    }

    private fun show(factory: OvenPatch? = null, entry: KitShelf.Entry? = null): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val shelf = File(cache, "oven-synth-test-${System.nanoTime()}").apply { mkdirs() }
        folders += shelf
        settings["engine"] = "OVEN"
        settings.putIfAbsent("voice", factory?.voiceName ?: "GRAND")
        if (factory != null) {
            settings["preset.OVEN.${factory.voiceName}"] = factory.name
            for ((macro, value) in factory.macros) settings["macro.OVEN.${factory.voiceName}.$macro"] = value.toString()
        }
        setPhoneContent {
            key(visit) {
                SynthScreen(entry, shelf, onToast = {}, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        waitFor("OVEN recipe bag") { stored(factory?.voice ?: OvenVoice.GRAND) != null }
        return shelf
    }

    private fun stored(voice: OvenVoice): OvenPatch? = runCatching {
        settings["patch.OVEN.${voice.name}"]?.let(OvenPatch::fromJsonText)
    }.getOrNull()

    private fun invokeClick(label: String) {
        val node = compose.onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")
        assertTrue("$label refused its click", action.invoke())
        pump()
    }

    private fun slider(label: String) = compose.onNode(
        hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
    )

    private fun dial(label: String, value: Float) {
        val action = slider(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("no slider for $label")
        assertTrue(action.invoke(value))
        pump()
    }

    private fun progress(label: String): Float = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current ?: error("no progress for $label")

    @Test
    fun four_articulations_expose_all_ten_complete_factory_recipes_and_six_macros() {
        show()
        compose.onNodeWithContentDescription("ENGINE OVEN. CHOOSE ENGINE").assertExists()
        for (voice in OvenVoice.entries) {
            invokeClick(voice.name)
            waitFor("${voice.name} selection") { settings["voice"] == voice.name }
            for (macro in Oven.macrosFor(voice)) slider(macro.name).assertExists()
            slider("ROOT").assertExists()
            slider("VELOCITY").assertExists()
            for (preset in OvenPresets.forVoice(voice)) {
                invokeClick("PRESET ${preset.name}")
                waitFor("${preset.name} full recipe") { stored(voice) == preset }
                assertEquals(preset.velocity, progress("VELOCITY"), 1e-6f)
            }
        }
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertDoesNotExist()
    }

    @Test
    fun edited_root_strike_and_macros_survive_scramble_return_save_and_send_to_pad() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val kitDir = File(cache, "oven-pad-test-${System.nanoTime()}").apply { mkdirs() }
        folders += kitDir
        val kit = Kit("OVEN UI TEST", emptyList())
        KitStore.save(kit, kitDir)
        val factory = OvenPresets.all().first { it.name == "Hanging Frame" }
        val shelf = show(factory, KitShelf.Entry(kitDir, kit))
        waitFor("factory strike") { stored(factory.voice) == factory }

        dial("ROOT", .25f) // MIDI 42, independent of relative TUNE.
        dial("VELOCITY", .37f)
        dial("TUNE", .75f)
        dial("BODY", .22f)
        waitFor("edited strike") {
            stored(factory.voice)?.let { it.midi == 42 && it.velocity == .37f &&
                it.macros["TUNE"] == .75f && it.macros["BODY"] == .22f } == true
        }
        val edited = requireNotNull(stored(factory.voice))
        assertFalse(settings.containsKey("preset.OVEN.BLOOM"))
        invokeClick("SCRAMBLE")
        waitFor("scrambled controls") { stored(factory.voice)?.macros != edited.macros }
        val scrambled = requireNotNull(stored(factory.voice))
        assertEquals(42, scrambled.midi)
        assertEquals(.37f, scrambled.velocity, 1e-6f)
        assertEquals(factory.model, scrambled.model)

        compose.runOnIdle { visit += 1 }
        pump()
        waitFor("restored strike") { stored(factory.voice) == scrambled }
        assertEquals(.25f, progress("ROOT"), 1e-6f)
        assertEquals(.37f, progress("VELOCITY"), 1e-6f)
        for ((name, value) in scrambled.macros) assertEquals(value, progress(name), 1e-6f)

        invokeClick("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY OVEN")
        pump()
        invokeClick("SAVE")
        waitFor("saved OVEN preset") { runCatching { UserPresets.read(shelf).any { it.name == "MY OVEN" } }.getOrDefault(false) }
        assertEquals(scrambled.copy(name = "MY OVEN"), UserPresets.read(shelf).single().patch)

        invokeClick("SEND TO PAD")
        invokeClick("A01, EMPTY")
        waitFor("OVEN on A01", timeoutMillis = 60_000) {
            runCatching { KitStore.load(kitDir).pad(1)?.recipe != null }.getOrDefault(false)
        }
        val pad = requireNotNull(KitStore.load(kitDir).pad(1))
        val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val landed = recipe.patch as OvenPatch
        assertEquals(scrambled.copy(name = landed.name), landed)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertEquals(null, recipe.fx)
        assertTrue(File(kitDir, pad.sampleFile).length() > 44)
    }

    @Test
    fun complete_recipes_take_precedence_over_older_macro_settings_on_every_voice() {
        val recipes = OvenVoice.entries.associateWith { voice ->
            OvenPresets.forVoice(voice).first().copy(
                macros = Oven.defaults(voice) + mapOf("TUNE" to .61f, "BODY" to .83f),
                midi = 43 + voice.ordinal * 7, velocity = .43f,
            )
        }
        for ((voice, patch) in recipes) {
            settings["patch.OVEN.${voice.name}"] = patch.toJsonText()
            for (macro in patch.macros.keys) settings["macro.OVEN.${voice.name}.$macro"] = "0.1"
        }
        show()
        for ((voice, patch) in recipes) {
            invokeClick(voice.name)
            waitFor("${voice.name} selection") { settings["voice"] == voice.name }
            assertEquals(patch, stored(voice))
            for ((name, value) in patch.macros) assertEquals(value, progress(name), 1e-6f)
            assertEquals(patch.velocity, progress("VELOCITY"), 1e-6f)
        }
    }

    @Test
    fun invalid_and_mismatched_bag_recipes_fall_back_to_a_valid_oven_strike() {
        settings["voice"] = "FELT"
        settings["patch.OVEN.FELT"] = "not JSON"
        show()
        waitFor("invalid recipe fallback") { stored(OvenVoice.FELT)?.voice == OvenVoice.FELT }
        assertEquals(Oven.DEFAULT_MIDI, requireNotNull(stored(OvenVoice.FELT)).midi)

        val mismatched = OvenPresets.forVoice(OvenVoice.GRAND).first().copy(midi = 24, velocity = .1f)
        compose.runOnIdle {
            settings["patch.OVEN.FELT"] = mismatched.toJsonText()
            visit += 1
        }
        pump()
        waitFor("mismatched voice fallback") { stored(OvenVoice.FELT)?.voice == OvenVoice.FELT }
        val restored = requireNotNull(stored(OvenVoice.FELT))
        assertEquals(Oven.DEFAULT_MIDI, restored.midi)
        assertEquals(1f, restored.velocity, 1e-6f)
        assertEquals(Oven.MODEL_VERSION, restored.model)
        assertEquals(Oven.defaults(OvenVoice.FELT), restored.macros)
    }
}
