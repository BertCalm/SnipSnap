package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.ArtSidecar
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitArt
import com.snipsnap.shell.Palettes
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

/** EXPORT's ART row and the picker it opens: eight live previews, three knobs, the sidecar written on each pick. */
@RunWith(AndroidJUnit4::class)
class ArtPickerTest : ComposeScreenTest() {

    private val toasts = mutableListOf<String>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dirs = mutableListOf<File>()
    private var session by mutableStateOf<ExportSession?>(null)

    @After
    fun cleanUp() {
        appScope.cancel()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun entry(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "art-picker-kit-${System.nanoTime()}").apply { mkdirs() }
        dirs += dir
        fun tone(n: Int, hz: Double) = Snip(FloatArray(n) { i -> (0.5 * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat() }, 1, WavWriter.MPC_SAMPLE_RATE)
        WavWriter.write(File(dir, "kick.wav"), tone(8_000, 90.0))
        WavWriter.write(File(dir, "snare.wav"), tone(5_000, 400.0))
        val kit = Kit(
            name = "ART PICKER",
            pads = listOf(
                KitPad(slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK),
                KitPad(slot = 2, sampleFile = "snare.wav", drumClass = DrumClass.SNARE),
            ),
            tempoBpm = 92f,
        )
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    /**
     * A tap inside the sheet: the node's own click action, so a cell below
     * the sheet's fold is reachable without scrolling a scroll that lives in
     * the dialog's window (performScrollTo there spins on the semantics tree).
     */
    private fun press(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick)
        pump()
    }

    private fun show(e: KitShelf.Entry) {
        session = null
        setPhoneContent {
            ExportScreen(
                entry = e,
                session = session,
                onSessionChange = { session = it },
                appScope = appScope,
                onToast = { toasts += it },
                onNote = {},
                onNavigateKits = {},
                onAddFromShelf = {},
            )
        }
        waitFor("the kit's name in the header") {
            compose.onAllNodesWithText(e.kit.name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theArtRowOpensThePickerAndAPickLandsInTheSidecar() {
        val e = entry()
        show(e)
        tap("ART: BLOOM · VENT · AUTO · STACKED")
        waitFor("the picker's title") {
            compose.onAllNodesWithText(Copy.ART_SHEET_TITLE, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        press("STYLE: FIELD")
        press("PALETTE: RISO")
        press("SEED PAD: A02")
        waitFor("the sidecar to hold the picks") {
            val k = ArtSidecar.read(e.dir)
            k != null && k.style == KitArt.Style.FIELD && k.palette == Palettes.RISO && k.seedSlot == 2
        }
        press("CLOSE")
        waitFor("the row to read the new knobs") {
            compose.onAllNodesWithContentDescription("ART: FIELD · RISO · A02 · STACKED").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("the picker says the art is set", Copy.ART_SET in toasts)
        assertEquals(KitArt.Style.FIELD, session!!.model.artKnobs!!.style)
    }

    @Test
    fun aPickThatCannotBeSavedSaysSo() {
        val e = entry()
        show(e)
        tap("ART: BLOOM · VENT · AUTO · STACKED")
        waitFor("the picker's title") {
            compose.onAllNodesWithText(Copy.ART_SHEET_TITLE, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // A kit folder the app can no longer write into: the sidecar cannot land.
        assertTrue(e.dir.setWritable(false, false))
        try {
            press("PALETTE: RISO")
            waitFor("the failure to be said") { Copy.ART_SAVE_FAILED in toasts }
            press("CLOSE")
            pump()
            assertTrue("a pick that did not save is not called set", Copy.ART_SET !in toasts)
            assertEquals(null, ArtSidecar.read(e.dir))
        } finally {
            e.dir.setWritable(true, false)
        }
    }

    @Test
    fun eightPreviewsArrive() {
        show(entry())
        tap("ART: BLOOM · VENT · AUTO · STACKED")
        waitFor("eight style cells") {
            compose.onAllNodesWithContentDescription("STYLE: ", substring = true).fetchSemanticsNodes().size == 8
        }
        waitFor("every preview to be drawn", timeoutMillis = 30_000) {
            compose.onAllNodesWithText(Copy.ART_BUSY, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }


}
