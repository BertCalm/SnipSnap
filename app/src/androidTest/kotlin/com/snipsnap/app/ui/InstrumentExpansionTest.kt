package com.snipsnap.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.Exports
import com.snipsnap.app.KitShelf
import com.snipsnap.app.PREFS
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitIds
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.OneNote
import com.snipsnap.shell.ArtKnobs
import com.snipsnap.shell.Copy
import com.snipsnap.shell.ExpansionStore
import com.snipsnap.shell.Schemes
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
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

/** An instrument joins an expansion from MAKE ▸ INSTRUMENTS, and EXPORT packs it as a keygroup beside the kits. */
@RunWith(AndroidJUnit4::class)
class InstrumentExpansionTest : ComposeScreenTest() {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "instexp-${System.nanoTime()}")
    private val shelf = File(root, "Kits").apply { mkdirs() }
    private val instruments = File(shelf, KitShelf.INSTRUMENTS_DIR)
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

    private fun kit(name: String): String {
        val dir = File(shelf, name).apply { mkdirs() }
        WavWriter.write(File(dir, "kick.wav"), Snip(FloatArray(4_410) { (it % 40) / 40f - 0.5f }, 1, WavWriter.MPC_SAMPLE_RATE))
        KitStore.save(Kit(name, listOf(KitPad(slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK))), dir)
        return KitIds.ensure(dir)
    }

    /** A two-zone held instrument, as MAKE INSTRUMENT leaves one on the shelf. */
    private fun instrument(name: String): KitShelf.InstrumentEntry {
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = listOf(48, 60).map { midi ->
            val stem = "${name.filter { !it.isWhitespace() }}_$midi"
            samples[stem] = Snip(FloatArray(6_000) { f -> (0.3 * Math.sin(2.0 * Math.PI * 220.0 * f / 44_100)).toFloat() }, 1, 44_100)
            Keygroup(midi - 6, midi + 5, midi, listOf(VelocityLayer(stem, 6_000, 0, 127, loopStartFrame = 3_000)))
        }
        OneNote.writePackage(name, KeygroupProgram(name, keygroups, volumeRelease = .5f), samples, instruments, overwrite = false)
        val sidecar = InstrumentStore.sidecar(instruments, name)
        return KitShelf.InstrumentEntry(sidecar, InstrumentStore.read(sidecar))
    }

    private fun press(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick)
        pump()
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun anInstrumentJoinsAndLeavesAnExpansionFromItsRow() {
        titles += "KEY SHIFT"
        val e = store.create("KEY SHIFT", kit("ONE"), ArtKnobs.default(Schemes.DEFAULT))
        val glass = instrument("GLASS KEYS")
        setPhoneContent {
            InstrumentsBench(listOf(glass), backLabel = "MAKE", onOpen = {}, onBack = {}, expansions = store, onToast = { toasts += it })
        }
        press("ADD GLASS KEYS TO AN EXPANSION")
        waitFor("the sheet lists the expansion") { shows("KEY SHIFT") }
        press("KEY SHIFT")
        waitFor("joined") { store.get(e.id)?.instrumentNames == listOf("GLASS KEYS") }
        assertTrue(toasts.toString(), Copy.kitJoined("GLASS KEYS", "KEY SHIFT") in toasts)
        press("KEY SHIFT")
        waitFor("left") { store.get(e.id)?.instrumentNames == emptyList<String>() }
    }

    @Test
    fun exportListsTheInstrumentAndPacksItAsAKeygroup() {
        titles += "KEY SHIFT"
        val e = store.create("KEY SHIFT", kit("ONE"), ArtKnobs.default(Schemes.DEFAULT))
        instrument("GLASS KEYS")
        store.addInstrument(e.id, "GLASS KEYS")
        subject = e.id
        setPhoneContent {
            ExportScreen(
                entry = null, session = null, onSessionChange = {}, appScope = appScope,
                onToast = { toasts += it }, onNote = {}, onNavigateKits = {}, onAddFromShelf = {},
                shelfRoot = shelf, expansions = store, expansionSubject = subject, onExpansionSubject = { subject = it },
            )
        }
        waitFor("the instruments row") { shows(Copy.EXPANSION_INSTRUMENTS) && shows("GLASS KEYS") }
        press(Copy.EXPANSION_WRITE)
        waitFor("the write to land", timeoutMillis = 30_000) { Copy.expansionWritten("KEY SHIFT", 1, 1) in toasts }
        val pack = File(Exports.dir(context), "Expansions/KEY SHIFT")
        assertTrue(File(pack, "Programs/GLASS KEYS.xpm").isFile)
        assertEquals(setOf("GLASSKEYS_48.wav", "GLASSKEYS_60.wav"), File(pack, "Samples/GLASS KEYS").list()!!.toSet())
    }
}
