package com.snipsnap.app.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.PREFS
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.shell.EngineCatalog
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

/**
 * SYNTH's engine header and the list behind it, on the real screen: the `◄ NAME ▾ ►`
 * header, the sheet its name opens, and what picking from either does. The sheet's own
 * content is covered with a fixed list in [EnginePickerSheetTest]; this is the wiring.
 *
 * The recents are kept in the app's prefs, which a device run shares with the app, so
 * each test starts from and leaves an empty history.
 */
@RunWith(AndroidJUnit4::class)
class SynthEngineHeaderTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shelf = File(context.cacheDir, "engine-header-${System.nanoTime()}").apply { mkdirs() }
    private val settings = mutableMapOf<String, String>()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Before
    fun startClean() {
        prefs.edit().remove(PREF_RECENT_ENGINES).commit()
    }

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
        prefs.edit().remove(PREF_RECENT_ENGINES).commit()
    }

    private fun show(engine: String? = null) {
        if (engine != null) settings["engine"] = engine
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                SynthScreen(
                    entry = null,
                    shelfRoot = shelf,
                    onToast = {},
                    onKitUpdated = {},
                    onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> },
                    onOpenSurface = {},
                    appScope = appScope,
                    settings = settings,
                )
            }
        }
    }

    private fun isOn(engineDisplayName: String) {
        button("ENGINE $engineDisplayName. CHOOSE ENGINE").assertExists()
    }

    private fun openList() {
        // The name is the tap that opens the list; its label says which engine it is on.
        compose.onNode(hasContentDescription(". CHOOSE ENGINE", substring = true)).performClick()
        pump()
    }

    private fun listIsOpen() = compose.onAllNodesWithText("CHOOSE AN ENGINE", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    /** The list is its own dialog window; this asks whether that window holds input focus yet. */
    private fun listHasFocus(): Boolean {
        val title = compose.onAllNodesWithText("CHOOSE AN ENGINE", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        return (title?.root as? ViewRootForTest)?.view?.hasWindowFocus() == true
    }

    private fun type(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        pump()
    }

    private fun row(name: String) = compose.onNode(hasContentDescription("$name, ", substring = true) and hasClickAction())

    @Test
    fun theHeaderHasAPreviousArrowTheNameAndANextArrowEachAtLeastFortyEightDpTall() {
        show()
        isOn("THUMP")
        for (arrow in listOf("PREVIOUS ENGINE", "NEXT ENGINE")) {
            button(arrow).assertHeightIsAtLeast(48.dp)
            button(arrow).assertWidthIsAtLeast(48.dp)
        }
        button("ENGINE THUMP. CHOOSE ENGINE").assertHeightIsAtLeast(48.dp)
        assertTrue("the name carries the sheet marker", compose.onAllNodesWithText("THUMP ▾", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theArrowsStepOneEngineEitherWayAndWrap() {
        show("LATTICE")
        isOn("LATTICE")
        // LATTICE is the first engine in the enum and CHAMBER the last.
        tap("PREVIOUS ENGINE")
        isOn("CHAMBER")
        tap("NEXT ENGINE")
        isOn("LATTICE")
        tap("NEXT ENGINE")
        isOn("THUMP")
        tap("PREVIOUS ENGINE")
        isOn("LATTICE")
        assertEquals("LATTICE", settings["engine"])
    }

    @Test
    fun stepToAnEngineStartsItOnItsFirstVoice() {
        show("THUMP")
        tap("NEXT ENGINE")
        isOn("SCANNED CHAIN")
        assertEquals("SCANNED_CHAIN", settings["engine"])
        assertEquals("PLUCK", settings["voice"])
    }

    @Test
    fun theNameOpensTheListAndPickingAnEngineArrivesOnItAndClosesTheList() {
        show()
        assertTrue("the list starts closed", !listIsOpen())
        openList()
        assertTrue(listIsOpen())
        // THUMP is far down an A to Z list of 63, out of the first screenful: find it by name.
        type("thump")
        assertEquals("the current engine is marked", 1, compose.onAllNodesWithText("● CURRENT", useUnmergedTree = true).fetchSemanticsNodes().size)
        type("onset")
        assertEquals("only the engine SYNTH is on is marked", 0, compose.onAllNodesWithText("● CURRENT", useUnmergedTree = true).fetchSemanticsNodes().size)
        row("ONSET").performClick()
        pump()
        assertTrue("picking closes the list", !listIsOpen())
        isOn("ONSET")
        assertEquals("ONSET", settings["engine"])
        assertEquals("KICK", settings["voice"])
    }

    @Test
    fun aPickedEngineIsRecentAndIsKeptInThePrefs() {
        show()
        openList()
        assertTrue("every engine is listed", compose.onAllNodesWithText("ALL ENGINES (${EngineCatalog.ENTRIES.size})", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("nothing is recent yet", compose.onAllNodesWithText("RECENT", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        type("onset")
        row("ONSET").performClick()
        pump()
        assertEquals("ONSET", prefs.getString(PREF_RECENT_ENGINES, null))

        openList()
        assertTrue("RECENT heads the list", compose.onAllNodesWithText("RECENT", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        type("skin")
        row("SKIN").performClick()
        pump()
        assertEquals("newest first", "SKIN,ONSET", prefs.getString(PREF_RECENT_ENGINES, null))
    }

    @Test
    fun steppingWithTheArrowsDoesNotTouchTheRecents() {
        show()
        tap("NEXT ENGINE")
        assertEquals(null, prefs.getString(PREF_RECENT_ENGINES, null))
    }

    @Test
    fun aRecentEngineRetiredFromThePickerIsNotShown() {
        prefs.edit().putString(PREF_RECENT_ENGINES, "NO_SUCH_ENGINE,ONSET").commit()
        show()
        openList()
        assertTrue(compose.onAllNodesWithText("RECENT", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasContentDescription("NO SUCH ENGINE", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun backClosesTheListAndLeavesTheEngineAlone() {
        show()
        openList()
        // The window manager hands the new dialog window focus asynchronously, and a key sent before
        // it arrives goes to the window behind. This test sent it at once, and has failed on every CI
        // run since NACRE joined the engines, while the list's other tests, which click through
        // semantics rather than the window, pass; what made the focus later is not pinned down.
        waitFor("the list's window to take focus", timeoutMillis = 10_000) { listHasFocus() }
        // The system's own Back key, to the window that has focus: the list's dialog. Not
        // Espresso's pressBack, which waits for an idle this suite's frozen clock never gives.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        pump()
        assertTrue(!listIsOpen())
        isOn("THUMP")
    }

    @Test
    fun pickingTheEngineYouAreOnKeepsItsVoiceAndJustClosesTheList() {
        // TINES on its second voice. A pick of TINES from the list used to arrive on it afresh: first voice, sound again.
        settings["voice"] = "CHIME"
        show("TINES")
        isOn("TINES")
        button("CHIME").assertIsSelected()
        openList()
        type("tines")
        row("TINES").performClick()
        pump()
        assertTrue("the list closes", !listIsOpen())
        isOn("TINES")
        button("CHIME").assertIsSelected()
        assertEquals("the voice was not reset", "CHIME", settings["voice"])
        assertEquals("it is still recorded as picked", "TINES", prefs.getString(PREF_RECENT_ENGINES, null))
    }

    @Test
    fun theScrimClosesTheListAndLeavesTheEngineAlone() {
        show()
        openList()
        // The panel covers the lower part of the scrim, so the tap goes on the strip above it.
        button("CLOSE").performTouchInput { click(Offset(centerX, 12f)) }
        pump()
        assertTrue(!listIsOpen())
        isOn("THUMP")
    }

    @Test
    fun noEngineNameIsCutShortOnTheHeaderBesideItsFirstVoiceChip() {
        show("LATTICE")
        val seen = mutableListOf<String>()
        repeat(EngineCatalog.ENTRIES.size) {
            val name = compose.onNode(hasContentDescription(". CHOOSE ENGINE", substring = true))
                .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
                .removePrefix("ENGINE ").removeSuffix(". CHOOSE ENGINE")
            seen += name
            assertReadsInFull("$name ▾")
            tap("NEXT ENGINE")
        }
        assertEquals("every engine was visited once", EngineCatalog.ENTRIES.size, seen.toSet().size)
    }
}
