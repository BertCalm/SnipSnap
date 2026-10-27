package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.TapeTheme
import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Schemes
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Pluck
import com.snipsnap.synth.PluckPatch
import com.snipsnap.synth.PluckVoice
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

/** Real SYNTH controls, navigation bag and SEND use the same saved bass excitation. */
@RunWith(AndroidJUnit4::class)
class UprightSynthScreenTest {
    @get:Rule val compose = createComposeRule()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var showing by mutableStateOf(true)
    private var entry by mutableStateOf<KitShelf.Entry?>(null)
    private var kitUpdates = 0
    private lateinit var shelf: File
    private lateinit var kitDir: File
    private val settings = mutableMapOf("engine" to "PLUCK", "voice" to "UPRIGHT")

    @After fun finish() {
        compose.runOnIdle { showing = false }
        compose.waitForIdle()
        appScope.cancel()
        if (::shelf.isInitialized) shelf.deleteRecursively()
    }

    private fun show(source: PluckPatch) {
        shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "upright-synth-${System.nanoTime()}").apply { mkdirs() }
        kitDir = File(shelf, "kit").apply { mkdirs() }
        val kit = Kit(name = "UPRIGHT TEST", pads = emptyList())
        KitStore.save(kit, kitDir)
        entry = KitShelf.Entry(kitDir, kit)
        settings["recipe.PLUCK.UPRIGHT"] = source.toJsonText()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (showing) TapeTheme(Schemes.DEFAULT) {
                Box(Modifier.width(360.dp).fillMaxHeight()) {
                    SynthScreen(entry = entry, shelfRoot = shelf, onToast = {},
                        onKitUpdated = { updated -> entry = KitShelf.Entry(kitDir, updated); kitUpdates += 1 },
                        onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                        appScope = appScope, settings = settings)
                }
            }
        }
        pump()
    }

    private fun pump(millis: Long = 100) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun click(label: String) {
        val action = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun dial(label: String, value: Float) {
        val action = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
            .config[SemanticsActions.SetProgress].action!!
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    private fun reenter() {
        compose.runOnIdle { showing = false }
        compose.waitForIdle()
        compose.runOnIdle { showing = true }
        pump()
    }

    private fun send(): PluckPatch {
        val before = kitUpdates
        val old = entry!!.kit.pad(1)
        click("SEND TO PAD")
        click("A01, ${old?.displayName ?: "EMPTY"}")
        val deadline = System.currentTimeMillis() + 180_000
        while (kitUpdates == before) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("UPRIGHT SEND did not complete")
            pump(16)
        }
        val pad = KitStore.load(kitDir).pad(1)!!
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertTrue(pad.oneShot)
        assertEquals(0, pad.muteGroup)
        assertEquals(0, pad.tuneFine)
        assertEquals("33", pad.source.getValue("rootMidi"))
        return PadRecipe.fromJsonValue(pad.recipe!!).patch as PluckPatch
    }

    @Test fun savedBassKeepsPlayingStrengthThroughEditsNavigationAndPadReplacement() {
        val source = PluckPatch("MY BASS", PluckVoice.UPRIGHT,
            Pluck.defaults(PluckVoice.UPRIGHT), velocity = .35f)
        show(source)
        for (label in listOf("TUNE", "TOUCH", "DAMP", "WOOD", "VELOCITY"))
            compose.onNodeWithContentDescription(label).assertExists()
        compose.onNodeWithContentDescription("PICK").assertDoesNotExist()
        compose.onNodeWithContentDescription("HOLD").assertDoesNotExist()
        dial("WOOD", .8f)
        dial("VELOCITY", .25f)
        reenter()
        val kept = PluckPatch.fromJsonText(settings.getValue("recipe.PLUCK.UPRIGHT")) as PluckPatch
        assertEquals(.8f, kept.macros.getValue("WOOD"), 0f)
        assertEquals(.25f, kept.velocity, 0f)
        val first = send()
        assertEquals(kept.macros, first.macros)
        assertEquals(.25f, first.velocity, 0f)
        dial("DAMP", 1f)
        val replaced = send()
        assertEquals(1f, replaced.macros.getValue("DAMP"), 0f)
        assertEquals(.25f, replaced.velocity, 0f)
        assertEquals(.8f, replaced.macros.getValue("WOOD"), 0f)
    }

    @Test fun factoryRoundAndMutedRemainTheTwoNamedArticulations() {
        show(PluckPatch("ROUND", PluckVoice.UPRIGHT, Pluck.defaults(PluckVoice.UPRIGHT), velocity = .65f))
        click("PRESET MUTED")
        val muted = send()
        assertEquals(1f, muted.macros.getValue("DAMP"), 0f)
        assertEquals(.65f, muted.velocity, 0f)
        click("PRESET ROUND")
        val round = send()
        assertEquals(.18f, round.macros.getValue("DAMP"), 0f)
        assertEquals(.65f, round.velocity, 0f)
        assertFalse("HOLD" in round.macros)
    }
}
