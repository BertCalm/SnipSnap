package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.Obbligato
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.ObbligatoPlayback
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

/** Mode and root choices must survive navigation before the draft effect has another frame. */
@RunWith(AndroidJUnit4::class)
class ObbligatoSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val directories = mutableListOf<File>()
    private var generation by mutableStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        directories.forEach { it.deleteRecursively() }
    }

    private fun press(label: String) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action)
        compose.runOnIdle { assertTrue("Click refused for $label", action.invoke()) }
        pump()
    }

    private fun dial(label: String, value: Float) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = requireNotNull(node.config.getOrNull(SemanticsActions.SetProgress)?.action)
        compose.runOnIdle { assertTrue("Slider refused for $label", action.invoke(value)) }
        pump()
    }

    private fun draft(settings: Map<String, String>): ObbligatoPatch =
        SynthDrafts.read(settings).filterIsInstance<ObbligatoPatch>().single()

    private fun recreate() {
        compose.runOnIdle { generation++ }
        pump()
    }

    @Test
    fun held_choice_preserves_recipe_and_a_wide_phrase_keeps_its_selected_note_after_recreation() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "obbligato-synth-test-${System.nanoTime()}")
            .apply { mkdirs(); directories += this }
        val old = ObbligatoPatch("MY DUET", Obbligato.defaults() +
            mapOf("BOND" to (1f / 7f), "HOLD" to .2f), midi = 67,
            velocity = .53f, seed = -23, model = 10)
        val settings = mutableMapOf("engine" to "OBBLIGATO", "voice" to "AIR_REED")
        SynthDrafts.write(settings, listOf(old))
        setPhoneContent {
            key(generation) {
                SynthScreen(entry = null, shelfRoot = root, onToast = {}, onKitUpdated = {},
                    onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> },
                    onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        assertRoot(67)
        press("OBBLIGATO HELD")
        val held = draft(settings)
        assertEquals(Obbligato.PERFORMANCE_MODEL, held.model)
        assertEquals(ObbligatoPlayback.HELD, held.playback)
        assertEquals(1f, held.macros.getValue("HOLD"), .00001f)
        assertEquals(-23, held.seed)
        assertEquals(.53f, held.velocity, .00001f)
        assertTrue(compose.onAllNodesWithContentDescription("HOLD", useUnmergedTree = true)
            .fetchSemanticsNodes().isEmpty())
        dial("MISCHIEF", .73f)
        recreate()
        val heldNode = compose.onNodeWithContentDescription("OBBLIGATO HELD", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(heldNode.config.getOrNull(SemanticsProperties.Selected) == true)
        assertEquals(.73f, draft(settings).macros.getValue("MISCHIEF"), .00001f)
        press("OBBLIGATO PHRASE")
        assertEquals(10, draft(settings).model)
        assertEquals(.2f, draft(settings).macros.getValue("HOLD"), .00001f)
        press("OBBLIGATO HELD")
        rootTo(48)
        press("OBBLIGATO PHRASE")
        val wide = draft(settings)
        assertEquals(ObbligatoPlayback.FINITE, wide.playback)
        assertEquals(Obbligato.PERFORMANCE_MODEL, wide.model)
        assertEquals(48, wide.midi)
        assertEquals(-23, wide.seed)
        recreate()
        assertRoot(48)
        assertEquals(wide, draft(settings))
    }
}
