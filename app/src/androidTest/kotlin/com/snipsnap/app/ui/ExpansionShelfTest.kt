package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
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
import com.snipsnap.shell.Schemes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The shelf's EXPANSIONS group: each expansion with its live kit count, renamed and deleted from a held row. */
@RunWith(AndroidJUnit4::class)
class ExpansionShelfTest : ComposeScreenTest() {

    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "shelf-${System.nanoTime()}")
    private val store = ExpansionStore(File(root, "expansions"))
    private val toasts = mutableListOf<String>()
    private var kits by mutableStateOf<List<KitShelf.Entry>>(emptyList())
    private val knobs = ArtKnobs.default(Schemes.DEFAULT)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A kit on the shelf with an id, as joining an expansion leaves it. */
    private fun entry(name: String): KitShelf.Entry {
        val dir = File(root, "Kits/$name").apply { mkdirs() }
        WavWriter.write(File(dir, "kick.wav"), Snip(FloatArray(4_410) { (it % 40) / 40f - 0.5f }, 1, WavWriter.MPC_SAMPLE_RATE))
        KitStore.save(Kit(name, listOf(KitPad(slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK))), dir)
        KitIds.ensure(dir)
        return KitShelf.Entry(dir, KitStore.load(dir))
    }

    private fun show() {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                KitsScreen(
                    kits = kits,
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

    private fun shows(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theShelfListsExpansionsWithTheirKitCount() {
        val one = entry("ONE")
        val two = entry("TWO")
        kits = listOf(one, two)
        store.create("FULL", one.kit.id!!, knobs).also { store.add(it.id, two.kit.id!!) }
        store.create("HOLLOW", "a-kit-long-gone", knobs)
        show()
        waitFor("the expansions group") { shows(Copy.EXPANSIONS_HEADING) }
        assertReadsInFull("FULL")
        assertReadsInFull("2 KITS")
        waitFor("the empty one's note") { shows(Copy.EXPANSION_EMPTY) }
    }

    @Test
    fun renameKeepsTheIdentifierAndDeleteKeepsTheKits() {
        val one = entry("ONE")
        val two = entry("TWO")
        kits = listOf(one, two)
        val e = store.create("NIGHT SHIFT", one.kit.id!!, knobs).let { store.add(it.id, two.kit.id!!) }
        show()
        waitFor("the expansion row") { shows("NIGHT SHIFT") }

        compose.onNodeWithText("NIGHT SHIFT").performSemanticsAction(SemanticsActions.OnLongClick)
        pump()
        press("RENAME NIGHT SHIFT")
        compose.onNode(hasSetTextAction()).performTextReplacement("LATE SHIFT")
        pump()
        press("RENAME")
        waitFor("the new title") { store.get(e.id)?.title == "LATE SHIFT" }
        assertEquals(e.identifier, store.get(e.id)!!.identifier)
        waitFor("the row to read the new title") { shows("LATE SHIFT") }

        compose.onNodeWithText("LATE SHIFT").performSemanticsAction(SemanticsActions.OnLongClick)
        pump()
        press("DELETE LATE SHIFT")
        press("DELETE")
        waitFor("the expansion to go") { store.list().isEmpty() }
        waitFor("going to be said") { Copy.expansionDeleted("LATE SHIFT") in toasts }
        assertEquals(true, one.dir.isDirectory && two.dir.isDirectory)
    }

    @Test
    fun aBinnedKitLeavesTheCountAndComesBackOnRestore() {
        val one = entry("ONE")
        val two = entry("TWO")
        kits = listOf(one, two)
        store.create("PAIR", one.kit.id!!, knobs).also { store.add(it.id, two.kit.id!!) }
        show()
        waitFor("two kits") { shows("2 KITS") }
        kits = listOf(one)
        waitFor("one kit while the other is in the bin") { shows("1 KIT") }
        kits = listOf(one, two)
        waitFor("two again after restore") { shows("2 KITS") }
    }
}
