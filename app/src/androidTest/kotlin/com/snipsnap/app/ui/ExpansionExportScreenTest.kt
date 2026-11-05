package com.snipsnap.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.Exports
import com.snipsnap.app.PREFS
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitIds
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.ArtKnobs
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Expansion
import com.snipsnap.shell.ExpansionStore
import com.snipsnap.shell.KitArt
import com.snipsnap.shell.Schemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** EXPORT's EXPANSION subject: the members, the last look at the tile, CHANGE ART, and the write. */
@RunWith(AndroidJUnit4::class)
class ExpansionExportScreenTest : ComposeScreenTest() {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "expexport-${System.nanoTime()}")
    private val shelf = File(root, "Kits").apply { mkdirs() }
    private val store = ExpansionStore(File(root, "expansions"))
    private val toasts = mutableListOf<String>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var subject by mutableStateOf<String?>(null)
    private val titles = mutableListOf<String>()

    @Before
    fun noCard() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_CARD_TREE).apply()
    }

    @After
    fun cleanUp() {
        appScope.cancel()
        root.deleteRecursively()
        Exports.dir(context)?.let { dir -> titles.forEach { File(dir, "Expansions/$it").deleteRecursively(); File(dir, "$it.xpn").delete() } }
    }

    private fun kit(name: String, playable: Boolean = true): String {
        val dir = File(shelf, name).apply { mkdirs() }
        if (playable) WavWriter.write(File(dir, "kick.wav"), Snip(FloatArray(4_410) { (it % 40) / 40f - 0.5f }, 1, WavWriter.MPC_SAMPLE_RATE))
        val sample = if (playable) "kick.wav" else "missing.wav"
        KitStore.save(Kit(name, listOf(KitPad(slot = 1, sampleFile = sample, drumClass = DrumClass.KICK)), tempoBpm = 92f), dir)
        return KitIds.ensure(dir)
    }

    private fun expansion(title: String, vararg ids: String): Expansion {
        titles += title
        val e = store.create(title, ids.first(), ArtKnobs.default(Schemes.DEFAULT))
        ids.drop(1).forEach { store.add(e.id, it) }
        return store.get(e.id)!!
    }

    private fun show(id: String) {
        subject = id
        setPhoneContent {
            ExportScreen(
                entry = null,
                session = null,
                onSessionChange = {},
                appScope = appScope,
                onToast = { toasts += it },
                onNote = {},
                onNavigateKits = {},
                onAddFromShelf = {},
                shelfRoot = shelf,
                expansions = store,
                expansionSubject = subject,
                onExpansionSubject = { subject = it },
            )
        }
    }

    private fun press(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick)
        pump()
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theSwitchShowsTheExpansionSubjectWithItsTileAndMembers() {
        val e = expansion("NIGHT SHIFT", kit("ONE"), kit("TWO"))
        show(e.id)
        waitFor("the title") { shows("NIGHT SHIFT") }
        waitFor("both members") { shows("ONE") && shows("TWO") }
        waitFor("the tile drawn", timeoutMillis = 20_000) { !shows(Copy.ART_BUSY) && shows(Copy.CHANGE_ART) }
    }

    @Test
    fun changeArtOpensThePickerAndThePickIsSaved() {
        val e = expansion("LATE SHIFT", kit("ONE"), kit("TWO"))
        show(e.id)
        waitFor("the door") { shows(Copy.CHANGE_ART) }
        press(Copy.CHANGE_ART)
        waitFor("the picker") { shows("${Copy.ART_FOR} LATE SHIFT") }
        press("STYLE: RIDGES")
        waitFor("the pick saved") { store.get(e.id)?.knobs?.style == KitArt.Style.RIDGES }
        press("CLOSE")
    }

    @Test
    fun writingLandsOneFolderAndNamesASkippedMember() {
        val e = expansion("DAWN SHIFT", kit("ONE"), kit("TWO"), kit("BROKEN", playable = false))
        show(e.id)
        waitFor("the write button") { shows(Copy.EXPANSION_WRITE) }
        press(Copy.EXPANSION_WRITE)
        waitFor("the write to land", timeoutMillis = 30_000) { Copy.expansionWritten("DAWN SHIFT", 2) in toasts }
        assertTrue("the skipped kit is named: $toasts", toasts.any { it.startsWith("BROKEN SKIPPED") })
        assertTrue(File(Exports.dir(context), "Expansions/DAWN SHIFT/Expansion.xml").isFile)
    }

    @Test
    fun anEmptyExpansionRefusesToWrite() {
        val id = kit("GONE")
        val e = expansion("EMPTY SHIFT", id)
        File(shelf, "GONE").deleteRecursively()
        show(e.id)
        waitFor("the empty note") { shows(Copy.EXPANSION_EMPTY) }
        button(Copy.EXPANSION_WRITE).assertIsNotEnabled()
        assertEquals(false, File(Exports.dir(context), "Expansions/EMPTY SHIFT").exists())
    }
}
