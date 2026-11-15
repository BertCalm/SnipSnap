package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Nacre
import com.snipsnap.synth.NacrePatch
import com.snipsnap.synth.NacrePresets
import com.snipsnap.synth.NacreVoice
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

/**
 * The finite harp keeps its note, strike, seed and saved rendition through bench edits; GROTTO, the one held voice,
 * shows HOLD and saves and recalls a held loop.
 */
@RunWith(AndroidJUnit4::class)
class NacreSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "nacre-synth-${System.nanoTime()}").apply { mkdirs() }
    private var generation by mutableStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(settings: MutableMap<String, String>) {
        setPhoneContent {
            key(generation) {
                SynthScreen(
                    entry = null, shelfRoot = shelf, onToast = {}, onKitUpdated = {},
                    onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                    appScope = appScope, settings = settings,
                )
            }
        }
    }

    private fun press(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        pump(16)
    }

    private fun dial(label: String, value: Float) {
        button(label).performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
        pump(16)
    }

    private fun progress(label: String): Float = button(label).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun draft(settings: Map<String, String>): NacrePatch? =
        SynthDrafts.read(settings).filterIsInstance<NacrePatch>().singleOrNull()

    private fun assertFiniteControls() {
        for (label in listOf("HOLD", "MAKE INSTRUMENT", "MAKE DRONE", "DRONE TO LOOP")) {
            compose.onNodeWithContentDescription(label, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun picker_reaches_the_harp_and_its_starting_recipes() {
        val settings = mutableMapOf("engine" to "TRIO", "voice" to "TRES")
        show(settings)
        // The header steps through the engines in their order with its arrows (#730's picker): TRIO, then NACRE.
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE NACRE. CHOOSE ENGINE").assertExists()
        compose.onNodeWithContentDescription("PEARL").assertExists()
        for (preset in NacrePresets.forVoice(NacreVoice.PEARL)) {
            press("PRESET ${preset.name}")
            for ((name, value) in preset.macros) {
                if (name != "HOLD") assertEquals(value, progress(name), .00001f)
            }
            assertRoot(preset.midi)
            assertEquals(preset.velocity, progress("VELOCITY"), .00001f)
        }
        assertFiniteControls()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE ORIGAMI. CHOOSE ENGINE").assertExists()
    }

    @Test
    fun harmonics_is_a_voice_with_its_own_presets() = voiceReachesItsPresets(NacreVoice.HARMONICS)

    @Test
    fun shell_is_a_voice_with_its_own_presets() = voiceReachesItsPresets(NacreVoice.SHELL)

    @Test
    fun bubble_is_a_voice_with_its_own_presets() = voiceReachesItsPresets(NacreVoice.BUBBLE)

    /** HARMONICS, SHELL and BUBBLE: the voice shows, its four presets load their controls, and HOLD stays off. */
    private fun voiceReachesItsPresets(voice: NacreVoice) {
        val settings = mutableMapOf("engine" to "NACRE", "voice" to voice.name)
        show(settings)
        compose.onNodeWithContentDescription(voice.name).assertExists()
        val presets = NacrePresets.forVoice(voice)
        assertEquals(4, presets.size)
        for (preset in presets) {
            press("PRESET ${preset.name}")
            for ((name, value) in preset.macros) {
                if (name != "HOLD") assertEquals(value, progress(name), .00001f)
            }
            assertRoot(preset.midi)
            assertEquals(preset.velocity, progress("VELOCITY"), .00001f)
        }
        compose.onNodeWithContentDescription("CONTACT", useUnmergedTree = true).assertDoesNotExist()
        assertFiniteControls()
    }

    private fun grotto(name: String): NacrePatch = NacrePresets.forVoice(NacreVoice.GROTTO).single { it.name == name }

    private fun stored(voice: NacreVoice): NacrePatch? =
        SynthDrafts.read(currentSettings).filterIsInstance<NacrePatch>().firstOrNull { it.voice == voice }

    private var currentSettings: Map<String, String> = emptyMap()

    private fun holdSlider() = compose.onNode(hasContentDescription("HOLD") and
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress), useUnmergedTree = true)

    /** Recall a saved preset by its chip's click label, once savePreset has published it to Compose (WICK's wait). */
    private fun activateUserPreset(name: String) {
        val target = SemanticsMatcher("YOUR PRESET $name") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "YOUR PRESET $name"
        }
        waitFor("YOUR PRESET $name recall control", timeoutMillis = 30_000) {
            compose.onAllNodes(target, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        val action = compose.onNode(target, useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)!!.action!!
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    @Test
    fun grotto_follows_bubble_shows_hold_and_loads_its_four_presets() {
        val settings = mutableMapOf("engine" to "NACRE", "voice" to "BUBBLE")
        currentSettings = settings
        show(settings)
        holdSlider().assertDoesNotExist()
        press("GROTTO")
        waitFor("GROTTO selected") { settings["voice"] == "GROTTO" }
        holdSlider().assertExists()
        val presets = NacrePresets.forVoice(NacreVoice.GROTTO)
        assertEquals(listOf("Grotto Bow", "Soft Grotto", "Grotto Glints", "Grotto Kelp"), presets.map { it.name })
        for (preset in presets) {
            press("PRESET ${preset.name}")
            waitFor("${preset.name} complete recipe") { stored(NacreVoice.GROTTO) == preset }
            // HOLD included: GROTTO's finite bows and its held loops are both saved controls.
            for ((name, value) in preset.macros) assertEquals(value, progress(name), .00001f)
            assertRoot(preset.midi)
            assertEquals(preset.velocity, progress("VELOCITY"), .00001f)
        }
        compose.onNodeWithContentDescription("CONTACT", useUnmergedTree = true).assertDoesNotExist()
        press("PEARL")
        holdSlider().assertDoesNotExist()
    }

    @Test
    fun a_held_grotto_preset_saves_recalls_and_survives_recreation() {
        val settings = mutableMapOf("engine" to "NACRE", "voice" to "GROTTO")
        currentSettings = settings
        show(settings)
        val glints = grotto("Grotto Glints")
        press("PRESET ${glints.name}")
        waitFor("held Grotto Glints") { stored(NacreVoice.GROTTO) == glints }
        assertEquals(1f, progress("HOLD"), .00001f)
        dial("BLOOM", .52f)
        val expected = glints.copy(macros = glints.macros + ("BLOOM" to .52f))
        waitFor("held edit keeps the loop") {
            stored(NacreVoice.GROTTO)?.let { it.macros == expected.macros && it.seed == glints.seed } == true
        }

        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY HELD GROTTO")
        pump()
        press("SAVE")
        waitFor("saved held GROTTO preset", timeoutMillis = 30_000) {
            UserPresets.read(shelf).any { it.name == "MY HELD GROTTO" }
        }
        assertEquals(expected.copy(name = "MY HELD GROTTO"),
            UserPresets.read(shelf).single { it.name == "MY HELD GROTTO" }.patch)

        val bow = grotto("Grotto Bow")
        press("PRESET ${bow.name}")
        waitFor("finite Grotto Bow") { stored(NacreVoice.GROTTO) == bow }
        assertEquals(bow.macros.getValue("HOLD"), progress("HOLD"), .00001f)

        activateUserPreset("MY HELD GROTTO")
        waitFor("held GROTTO recalled") { stored(NacreVoice.GROTTO)?.macros == expected.macros }
        assertEquals(1f, progress("HOLD"), .00001f)
        assertEquals(.52f, progress("BLOOM"), .00001f)
        val recalled = requireNotNull(stored(NacreVoice.GROTTO))
        assertEquals(glints.seed, recalled.seed)
        assertEquals(glints.midi, recalled.midi)
        assertTrue(Nacre.isLoop(recalled.voice, recalled.macros))

        compose.runOnIdle { generation++ }
        pump()
        assertEquals(1f, progress("HOLD"), .00001f)
        compose.runOnIdle { assertEquals(recalled, stored(NacreVoice.GROTTO)) }
    }

    @Test
    fun saved_contact_control_survives_macro_edit_scramble_and_recreation() {
        val saved = NacrePresets.forVoice(NacreVoice.PEARL).last().copy(midi = 57, velocity = .37f,
            seed = Long.MIN_VALUE + 19)
        val settings = mutableMapOf("engine" to "NACRE", "voice" to "PEARL")
        SynthDrafts.write(settings, listOf(saved))
        show(settings)
        assertEquals(.8f, progress("CONTACT"), .00001f)
        dial("CONTACT", .62f)
        dial("BLOOM", .52f)
        waitFor("saved contact edit") {
            draft(settings)?.let { it.model == 4 && it.macros["CONTACT"] == .62f &&
                it.macros["BLOOM"] == .52f } == true
        }
        press("RANDOMISE")
        waitFor("complete contact draft after scramble") {
            draft(settings)?.let { it.model == 4 && it.macros["CONTACT"] == .62f &&
                it.macros.keys == Nacre.defaults(it.voice, 4).keys } == true
        }
        assertEquals(.62f, progress("CONTACT"), .00001f)
        val scrambled = requireNotNull(draft(settings))
        assertEquals(saved.seed, scrambled.seed)
        assertEquals(saved.midi, scrambled.midi)
        assertEquals(saved.velocity, scrambled.velocity, .00001f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(.62f, progress("CONTACT"), .00001f)
        compose.runOnIdle { assertEquals(scrambled, draft(settings)) }
        assertFiniteControls()
    }

    @Test
    fun switching_contact_and_legacy_presets_refreshes_same_voice_controls() {
        val settings = mutableMapOf("engine" to "NACRE", "voice" to "PEARL")
        show(settings)
        compose.onNodeWithContentDescription("CONTACT", useUnmergedTree = true).assertDoesNotExist()
        val close = NacrePresets.forVoice(NacreVoice.PEARL).first()
        for (contact in NacrePresets.forVoice(NacreVoice.PEARL).filter { it.model == 4 }) {
            press("PRESET ${contact.name}")
            assertEquals(contact.macros.getValue("CONTACT"), progress("CONTACT"), .00001f)
            press("PRESET ${close.name}")
            compose.onNodeWithContentDescription("CONTACT", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(close.macros.getValue("DUET"), progress("DUET"), .00001f)
        }
        assertFiniteControls()
    }

    private fun retainLegacy(model: Int) {
        val saved = NacrePatch("MY SAVED HARP", NacreVoice.PEARL,
            Nacre.defaults() + mapOf("TOUCH" to .19f, "DUET" to .72f, "CURRENT" to .47f,
                "VENUE" to 1f, "BLOOM" to .61f), midi = 57, velocity = .41f,
            seed = Long.MIN_VALUE + 91, model = model)
        val settings = mutableMapOf("engine" to "NACRE", "voice" to "PEARL")
        SynthDrafts.write(settings, listOf(saved))
        show(settings)
        assertEquals(.72f, progress("DUET"), .00001f)
        assertEquals(.61f, progress("BLOOM"), .00001f)
        assertEquals(saved.velocity, progress("VELOCITY"), .00001f)
        assertFiniteControls()

        dial("BLOOM", .54f)
        val expectedMidi = Nacre.MIN_MIDI + (Nacre.MAX_MIDI - Nacre.MIN_MIDI) / 4
        rootTo(expectedMidi)
        dial("VELOCITY", .27f)
        waitFor("complete saved harp after edits") {
            draft(settings)?.let { it.model == model && it.midi == expectedMidi &&
                it.velocity == .27f && it.macros["BLOOM"] == .54f } == true
        }
        val edited = requireNotNull(draft(settings))
        assertEquals(saved.seed, edited.seed)
        assertEquals(saved.name, edited.name)
        assertEquals(saved.voice, edited.voice)
        assertEquals(.19f, edited.macros.getValue("TOUCH"), .00001f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(.54f, progress("BLOOM"), .00001f)
        assertRoot(expectedMidi)
        assertEquals(.27f, progress("VELOCITY"), .00001f)
        compose.runOnIdle { assertEquals(edited, draft(settings)) }
    }

    @Test
    fun first_saved_rendition_keeps_its_identity_after_macro_note_and_strike_edits() = retainLegacy(1)

    @Test
    fun second_saved_rendition_keeps_its_identity_after_macro_note_and_strike_edits() = retainLegacy(2)

    @Test
    fun a_name_only_user_preset_restore_adopts_the_complete_harp_recipe() {
        val saved = NacrePatch("MY NAMED HARP", NacreVoice.PEARL,
            Nacre.defaults() + mapOf("DUET" to .68f, "CURRENT" to .13f, "BLOOM" to .24f),
            midi = 36, velocity = .32f, seed = Long.MAX_VALUE - 7, model = 1)
        UserPresets.save(shelf, saved, System.currentTimeMillis())
        val settings = mutableMapOf("engine" to "NACRE", "voice" to "PEARL",
            "preset.NACRE.PEARL" to saved.name)
        show(settings)
        waitFor("named Nacre recipe restore") { draft(settings)?.seed == saved.seed }
        assertEquals(.68f, progress("DUET"), .00001f)
        assertEquals(.24f, progress("BLOOM"), .00001f)
        assertEquals(saved.velocity, progress("VELOCITY"), .00001f)
        compose.runOnIdle { assertEquals(saved, draft(settings)) }
    }
}
