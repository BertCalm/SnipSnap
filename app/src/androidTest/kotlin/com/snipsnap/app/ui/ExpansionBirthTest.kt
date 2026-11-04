package com.snipsnap.app.ui

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitIds
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.ArtKnobs
import com.snipsnap.shell.Copy
import com.snipsnap.shell.ExpansionStore
import com.snipsnap.shell.Palettes
import com.snipsnap.shell.Schemes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The shelf's EXPANSION door: NEW names an expansion, picks its art, and the held kit joins; an existing one toggles. */
@RunWith(AndroidJUnit4::class)
class ExpansionBirthTest : ComposeScreenTest() {

    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "birth-${System.nanoTime()}")
    private val store = ExpansionStore(File(root, "expansions"))
    private val toasts = mutableListOf<String>()
    private var backOwner: OnBackPressedDispatcherOwner? = null

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun entry(name: String): KitShelf.Entry {
        val dir = File(root, "Kits/$name").apply { mkdirs() }
        WavWriter.write(File(dir, "kick.wav"), Snip(FloatArray(4_410) { (it % 40) / 40f - 0.5f }, 1, WavWriter.MPC_SAMPLE_RATE))
        val kit = Kit(name, listOf(KitPad(slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK)))
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun show(kit: KitShelf.Entry) {
        setPhoneContent {
            backOwner = LocalOnBackPressedDispatcherOwner.current
            CompositionLocalProvider(LocalReduceMotion provides true) {
                KitsScreen(
                    kits = listOf(kit),
                    projects = emptyList(),
                    onOpenProject = {},
                    onNewProject = {},
                    onCatchFirst = {},
                    busy = false,
                    loading = false,
                    onOpen = {},
                    onFresh = {},
                    onBackup = {},
                    expansions = store,
                    onToast = { toasts += it },
                )
            }
        }
    }

    private fun press(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick)
        pump()
    }

    private fun openTheDoor(kit: KitShelf.Entry) {
        compose.onNodeWithText(kit.kit.name).performSemanticsAction(SemanticsActions.OnLongClick)
        pump()
        press("ADD ${kit.kit.name} TO AN EXPANSION")
        waitFor("the expansion sheet") {
            compose.onAllNodesWithText(Copy.EXPANSION_SHEET_TITLE, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun holdingAKitThenNewNamesAnExpansionPicksItsArtAndMakesTheKitAMember() {
        val kit = entry("TIDAL")
        show(kit)
        openTheDoor(kit)
        press(Copy.EXPANSION_NEW)
        compose.onNode(hasSetTextAction()).performTextReplacement("NIGHT SHIFT")
        pump()
        press(Copy.EXPANSION_NAME_CONFIRM)
        waitFor("the art picker for the new expansion") {
            compose.onAllNodesWithText("${Copy.ART_FOR} NIGHT SHIFT", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        press("PALETTE: RISO")
        press("CLOSE")
        waitFor("the expansion to be born") { store.list().isNotEmpty() }
        val born = store.list().single()
        assertEquals("NIGHT SHIFT", born.title)
        assertEquals(listOf(KitIds.read(kit.dir)), born.memberKitIds)
        assertEquals(Palettes.RISO, born.knobs.palette)
        waitFor("the birth to be said") { Copy.expansionBorn("NIGHT SHIFT") in toasts }
        // The shelf's own kit list was read before the kit had an id; the new row must still count it.
        waitFor("the new expansion's row to count its kit") {
            compose.onAllNodesWithText("1 KIT", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun backOnTheNameDialogReturnsToTheSheet() {
        val kit = entry("TIDAL")
        show(kit)
        openTheDoor(kit)
        press(Copy.EXPANSION_NEW)
        assertTrue(compose.onAllNodesWithText(Copy.EXPANSION_NAME_TITLE, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.runOnUiThread { backOwner!!.onBackPressedDispatcher.onBackPressed() }
        pump()
        assertTrue("the dialog is gone", compose.onAllNodesWithText(Copy.EXPANSION_NAME_TITLE, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue("the sheet is back", compose.onAllNodesWithText(Copy.EXPANSION_SHEET_TITLE, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(emptyList<Any>(), store.list())
    }

    @Test
    fun closingThePickerTwiceBearsOneExpansion() {
        val kit = entry("TIDAL")
        show(kit)
        openTheDoor(kit)
        press(Copy.EXPANSION_NEW)
        compose.onNode(hasSetTextAction()).performTextReplacement("TWICE")
        pump()
        press(Copy.EXPANSION_NAME_CONFIRM)
        waitFor("the picker") { compose.onAllNodesWithText("${Copy.ART_FOR} TWICE", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        button("CLOSE").performSemanticsAction(SemanticsActions.OnClick)
        runCatching { button("CLOSE").performSemanticsAction(SemanticsActions.OnClick) }
        pump()
        waitFor("the birth") { Copy.expansionBorn("TWICE") in toasts }
        pump(500)
        assertEquals(1, store.list().size)
        assertTrue("no failure after the birth: $toasts", Copy.EXPANSION_SAVE_FAILED !in toasts)
    }

    @Test
    fun theSheetCountsOnlyKitsOnTheShelf() {
        val kit = entry("TIDAL")
        val id = KitIds.ensure(kit.dir)
        store.create("HALF", id, ArtKnobs.default(Schemes.DEFAULT)).let { store.add(it.id, "a-kit-now-gone") }
        show(kit)
        openTheDoor(kit)
        waitFor("the sheet's row") {
            compose.onAllNodesWithText("HALF", useUnmergedTree = true).fetchSemanticsNodes().size >= 2
        }
        pump(500)
        // The shelf's own group behind the sheet says 1 KIT too; the sheet must not count the kit that is gone.
        assertTrue("the sheet counts a kit that is not on the shelf",
            compose.onAllNodesWithText("2 KITS", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aTakenNameIsRefusedAndNothingIsBorn() {
        val kit = entry("TIDAL")
        store.create("NIGHT SHIFT", "someone", ArtKnobs.default(Schemes.DEFAULT))
        show(kit)
        openTheDoor(kit)
        waitFor("the existing expansion listed") {
            compose.onAllNodesWithText("NIGHT SHIFT", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        press(Copy.EXPANSION_NEW)
        compose.onNode(hasSetTextAction()).performTextReplacement("night shift")
        pump()
        button(Copy.EXPANSION_NAME_CONFIRM).assertIsNotEnabled()
        assertTrue(compose.onAllNodesWithText(Copy.EXPANSION_NAME_TAKEN, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // The scrim and the button both answer to CANCEL, as in the shelf's rename dialog: press the button.
        compose.onNodeWithText("CANCEL").performSemanticsAction(SemanticsActions.OnClick)
        pump()
        assertEquals(1, store.list().size)
    }

    @Test
    fun tappingAnExpansionTheKitIsInTakesItOut() {
        val kit = entry("TIDAL")
        val id = KitIds.ensure(kit.dir)
        val e = store.create("CRATE", id, ArtKnobs.default(Schemes.DEFAULT))
        show(kit)
        openTheDoor(kit)
        waitFor("the kit's expansion marked IN") {
            compose.onAllNodesWithText(Copy.EXPANSION_IN, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        press("CRATE")
        waitFor("the kit to leave") { store.get(e.id)!!.memberKitIds.isEmpty() }
        waitFor("leaving to be said") { Copy.kitLeft("TIDAL", "CRATE") in toasts }
        press("CRATE")
        waitFor("the kit to join again") { store.get(e.id)!!.memberKitIds == listOf(id) }
        waitFor("joining to be said") { Copy.kitJoined("TIDAL", "CRATE") in toasts }
    }
}
