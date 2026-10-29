package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** The finite harp keeps its note, strike, seed and saved rendition through bench edits. */
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
        for (preset in NacrePresets.all()) {
            press("PRESET ${preset.name}")
            for ((name, value) in preset.macros) {
                if (name != "HOLD") assertEquals(value, progress(name), .00001f)
            }
            assertEquals((preset.midi - Nacre.MIN_MIDI).toFloat() / (Nacre.MAX_MIDI - Nacre.MIN_MIDI),
                progress("ROOT"), .00001f)
            assertEquals(preset.velocity, progress("VELOCITY"), .00001f)
        }
        assertFiniteControls()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE ORIGAMI. CHOOSE ENGINE").assertExists()
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
        dial("ROOT", .25f)
        dial("VELOCITY", .27f)
        val expectedMidi = Nacre.MIN_MIDI + (Nacre.MAX_MIDI - Nacre.MIN_MIDI) / 4
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
        assertEquals(.25f, progress("ROOT"), .00001f)
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
