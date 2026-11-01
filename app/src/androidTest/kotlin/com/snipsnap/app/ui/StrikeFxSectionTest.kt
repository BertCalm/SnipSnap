package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PedalSlate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The STRIKE FX section of the pad sheet (`StrikeFxSection`), on a 360 dp phone.
 *
 * The model's three doors (`strikeFxAudition`, `strikeFxLayers`,
 * `clearStrikeFxLayers`) are proven in `:shell`; this suite proves the glue:
 * that AUDITION reaches nothing on disk, that KEEP and CLEAR land and undo
 * each other through the sheet's own write door, that replacing soft hits asks
 * first, and that the knobs read back from a kept pad.
 *
 * Controls are pressed through their `OnClick` semantics action, not a touch:
 * the section sits below the fold of a scrolling sheet and a touch injected at
 * a node's centre would miss it (`ComposeScreenTest` warns off `performScrollTo`).
 */
@RunWith(AndroidJUnit4::class)
class StrikeFxSectionTest : ComposeScreenTest() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kitDirs = mutableListOf<File>()
    private var sheetShown by mutableStateOf(true)

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
    }

    /** A one-pad kit, optionally with the pad's soft hits already on. */
    private fun strikeKit(softHits: Boolean = false): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "strikefx-test-kit-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        val samples = FloatArray(4410) { i -> if (i < 400) 0.5f * (1f - i / 400f) * (if (i % 2 == 0) 1f else -1f) else 0f }
        WavWriter.write(File(dir, "kick1.wav"), Snip(samples, channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE))
        val kit = Kit(name = "STRIKE FX TEST", pads = listOf(KitPad(slot = 1, sampleFile = "kick1.wav", drumClass = DrumClass.KICK)), tempoBpm = 92f)
        KitStore.save(kit, dir)
        if (softHits) {
            val m = KitBuilderModel.open(dir)
            m.addGhostLayers(1)
            m.save()
        }
        return KitShelf.Entry(dir, KitStore.load(dir))
    }

    private fun show(entry: KitShelf.Entry) {
        sheetShown = true
        setPhoneContent {
            if (sheetShown) {
                PadSheetScreen(
                    entry = entry,
                    slot = 1,
                    backLabel = "◄ PADS",
                    onSlotChange = {},
                    onBack = {},
                    onToast = {},
                    onNavigateTape = {},
                    onGrainField = {},
                    onSplice = {},
                    onStack = {},
                    onKitUpdated = {},
                    onShelfAssetWritten = {},
                    appScope = appScope,
                    workshopOpen = false,
                    openBox = null,
                    onOpenBox = {},
                    pedalRow = PedalSlate.DEFAULT,
                    onPedalRowChange = {},
                )
            }
        }
        waitFor("the pad header") {
            compose.onAllNodesWithText("PAD ${PadBanks.tag(1)}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun node(label: String) = compose.onNodeWithContentDescription(label, useUnmergedTree = true)

    /** The control's own `OnClick`, which needs no part of it on screen. */
    private fun press(label: String) {
        node(label).performSemanticsAction(SemanticsActions.OnClick)
        pump()
    }

    private fun sliderText(label: String): String =
        node(label).fetchSemanticsNode("no stepper named \"$label\"").config.getOrNull(SemanticsProperties.StateDescription)
            ?: error("\"$label\" has no stateDescription")

    private fun dial(label: String, target: Float) {
        val action = node(label).fetchSemanticsNode("no stepper named \"$label\"").config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("\"$label\" has no SetProgress action")
        assertTrue(action.invoke(target))
        pump()
    }

    private fun pad(entry: KitShelf.Entry) = KitStore.load(entry.dir).pad(1)!!

    @Test
    fun strike_fx_section_fits_a_narrow_phone_beside_soft_hits() {
        show(strikeKit())
        for (text in listOf("STRIKE FX", "AUDITION", "KEEP")) assertReadsInFull(text)
        for (label in listOf("ROOM", "SLAP", "STRIKE FX AUDITION", "STRIKE FX KEEP")) {
            val bounds = node(label).fetchSemanticsNode("nothing on screen is named \"$label\"").boundsInRoot
            assertTrue("\"$label\" starts inside the phone width", bounds.left >= 0f)
            assertTrue("\"$label\" ends inside the phone width", bounds.right <= PHONE_WIDTH_DP * compose.density.density + 1f)
        }
        compose.onNodeWithText("CLEAR", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun audition_changes_nothing_in_the_kit() {
        val entry = strikeKit()
        val before = File(entry.dir, KitStore.FILE_NAME).readBytes()
        val files = entry.dir.list()!!.sorted()
        show(entry)
        press("STRIKE FX AUDITION")
        // The audition renders off the main thread; give it real time to finish and to fail, if it will.
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            Thread.sleep(50)
        }
        assertArrayEquals("kit.json is the same bytes", before, File(entry.dir, KitStore.FILE_NAME).readBytes())
        assertEquals("no file came or went", files, entry.dir.list()!!.sorted())
        assertTrue(pad(entry).velocityLayers.isEmpty())
    }

    @Test
    fun keep_then_clear_leaves_the_pad_as_found() {
        val entry = strikeKit()
        val sampleBefore = File(entry.dir, "kick1.wav").readBytes()
        show(entry)
        press("STRIKE FX KEEP")
        waitFor("the bank to land") { pad(entry).velocityLayers.size == 3 }
        assertEquals(listOf("DRY", "ROOM", "ECHO"), pad(entry).velocityLayers.map { it.displayName })
        waitFor("the layer names on the section") {
            compose.onAllNodesWithText("DRY · ROOM · ECHO", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        press("STRIKE FX CLEAR")
        waitFor("the bank to go") { pad(entry).velocityLayers.isEmpty() }
        assertArrayEquals("the pad's sample is its first bytes again", sampleBefore, File(entry.dir, "kick1.wav").readBytes())
        assertEquals("one sample on disk again", listOf("kick1.wav", KitStore.FILE_NAME).sorted(), entry.dir.list()!!.filter { !it.startsWith(".") }.sorted())
    }

    @Test
    fun keep_over_soft_hits_asks_first() {
        val entry = strikeKit(softHits = true)
        assertTrue("the fixture starts with soft hits", pad(entry).velocityLayers.isNotEmpty())
        val softLayers = pad(entry).velocityLayers
        show(entry)
        press("STRIKE FX KEEP")
        compose.onNodeWithText(Copy.STRIKE_FX_REPLACES_LAYERS, useUnmergedTree = true).assertExists()
        assertEquals("nothing is written before the answer", softLayers, pad(entry).velocityLayers)
        press("KEEP")
        waitFor("the bank to land") { pad(entry).velocityLayers.map { it.displayName } == listOf("DRY", "ROOM", "ECHO") }
        assertEquals(3, pad(entry).velocityLayers.size)
    }

    @Test
    fun knobs_read_back_from_a_kept_pad() {
        val entry = strikeKit()
        show(entry)
        assertEquals("0.42", sliderText("ROOM"))
        assertEquals("0.90", sliderText("SLAP"))
        dial("ROOM", 0.2f)
        dial("SLAP", 0.5f)
        assertEquals("0.20", sliderText("ROOM"))
        press("STRIKE FX KEEP")
        waitFor("the bank to land") { pad(entry).velocityLayers.size == 3 }
        // Close the sheet and open it on the kept kit: the knobs come from the pad's recipe.
        compose.runOnIdle { sheetShown = false }
        pump()
        compose.runOnIdle { sheetShown = true }
        waitFor("the reopened sheet's knobs") {
            compose.onAllNodesWithContentDescription("ROOM").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("0.20", sliderText("ROOM"))
        assertEquals("0.50", sliderText("SLAP"))
    }
}
