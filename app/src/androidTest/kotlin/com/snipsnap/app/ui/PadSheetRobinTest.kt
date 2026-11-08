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
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.RobinSettings
import com.snipsnap.kit.isRobinChain
import com.snipsnap.shell.BoardSheet
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PadSheetPage
import com.snipsnap.shell.PedalSlate
import com.snipsnap.shell.Robin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * ROUND ROBIN on the real PadSheetScreen (2026-11-03): the chip switches a pad's takes on and off
 * with the original coming back byte for byte, a robin pad keeps every treatment door, the AMOUNT
 * and TAKES sliders re-render the pad on its own setting, and a chain round robin did not make says
 * why the switch is off. The rules are RobinTest's and RobinEditsTest's; these tests check the
 * wiring. Same harness as PadSheetUndoScreenTest: semantic clicks, every wait on something
 * observable.
 */
@RunWith(AndroidJUnit4::class)
class PadSheetRobinTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kitDirs = mutableListOf<File>()
    private val toasts = CopyOnWriteArrayList<String>()
    private var openBoxState by mutableStateOf<String?>(null)
    /** The page the sheet draws, hoisted the way App's `Navigation` holds it: a door opens EFFECTS and Back returns to the editor. */
    private var pageState by mutableStateOf(PadSheetPage.EDITOR)
    private val heard = CopyOnWriteArrayList<Snip>()

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
    }

    private fun sine(hz: Double): Snip {
        val samples = FloatArray(4410) { i -> (.4 * sin(2.0 * PI * hz * i / 44100.0) * exp(-i / 1400.0)).toFloat() }
        return Snip(samples, channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE)
    }

    private fun kit(foreignChain: Boolean = false): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "pad-sheet-robin-test-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        val one = sine(240.0)
        val pad = if (foreignChain) {
            // Two takes end to end, the way a captured kit chains them: no robin settings on the pad.
            val both = Snip(one.samples + sine(300.0).samples, channels = 1, sampleRate = one.sampleRate)
            WavWriter.write(File(dir, "hit1.wav"), both)
            KitPad(slot = 1, sampleFile = "hit1.wav", drumClass = DrumClass.KICK, chain = ChainInfo(listOf(0L, one.frameCount.toLong()), cycle = 2))
        } else {
            WavWriter.write(File(dir, "hit1.wav"), one)
            KitPad(slot = 1, sampleFile = "hit1.wav", drumClass = DrumClass.KICK)
        }
        val kit = Kit(name = "PAD SHEET ROBIN TEST", pads = listOf(pad), tempoBpm = 92f)
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun show(entry: KitShelf.Entry, openBox: String? = null) {
        openBoxState = openBox
        pageState = PadSheetPage.EDITOR
        setPhoneContent {
            PadSheetScreen(
                entry = entry,
                slot = 1,
                backLabel = "◄ PADS",
                onSlotChange = {},
                page = pageState,
                onOpenEffects = { pageState = PadSheetPage.EFFECTS },
                onOpenVariations = { pageState = PadSheetPage.VARIATIONS },
                onBack = { pageState = PadSheetPage.EDITOR },
                onToast = { toasts += it },
                onNavigateTape = {},
                onGrainField = {},
                onSplice = {},
                onStack = {},
                onKitUpdated = {},
                onHeard = { heard += it },
                onShelfAssetWritten = {},
                appScope = appScope,
                openBox = openBoxState,
                onOpenBox = { openBoxState = it },
                pedalRow = PedalSlate.DEFAULT,
                onPedalRowChange = {},
            )
        }
        waitFor("the pad sheet", timeoutMillis = 60_000) { hasText("PAD ${PadBanks.tag(1)}") }
    }

    private fun nodes(description: String) =
        compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes()

    private fun hasText(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun isDisabled(description: String): Boolean {
        val node = nodes(description).singleOrNull() ?: error("no single \"$description\"")
        return node.config.getOrNull(SemanticsProperties.Disabled) != null
    }

    /** A busy sheet greys every control; a click before it is live again could vanish, so wait for it. */
    private fun activate(description: String) {
        waitFor("\"$description\" to be live", timeoutMillis = 60_000) {
            val node = nodes(description).singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
        }
        compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue("\"$description\" refused its click", click()) }
        pump()
    }

    private fun dial(description: String, target: Float) {
        waitFor("\"$description\" to be live", timeoutMillis = 60_000) {
            val node = nodes(description).singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
        }
        val node = compose.onNodeWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNode("no stepper \"$description\"")
        val action = node.config.getOrNull(SemanticsActions.SetProgress)?.action ?: error("\"$description\" has no SetProgress action")
        assertTrue("setProgress on \"$description\" to $target was refused", action.invoke(target))
        pump()
    }

    private fun stateText(description: String): String =
        compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNode("no stepper \"$description\"").config.getOrNull(SemanticsProperties.StateDescription) ?: error("no state")

    private fun waitForToast(line: String) {
        waitFor("the toast \"$line\"", timeoutMillis = 60_000) { line in toasts }
        pump()
    }

    private fun savedPad(entry: KitShelf.Entry): KitPad = KitStore.load(entry.dir).pad(1) ?: error("pad A01 disappeared")

    @Test
    fun roundRobinOnMakesSixTakesAndOffRestores() {
        val entry = kit()
        val before = File(entry.dir, "hit1.wav").readBytes()
        show(entry)

        activate("ROUND ROBIN, OFF")
        waitForToast(Copy.ROBIN_ON)
        val on = savedPad(entry)
        assertEquals("the kit has no setting, so the defaults deal six takes", 6, on.chain!!.cycle)
        assertTrue(on.isRobinChain)
        assertTrue("a pad switched on follows the kit", on.robin!!.followsKit)
        assertEquals(0.6f, on.robin!!.amount)

        activate("ROUND ROBIN, ON")
        waitForToast(Copy.ROBIN_OFF)
        assertNull(savedPad(entry).chain)
        assertTrue("the original, byte for byte", before.contentEquals(File(entry.dir, "hit1.wav").readBytes()))
    }

    @Test
    fun treatmentsStayAvailableOnARobinPad() {
        val entry = kit()
        val m = KitBuilderModel.open(entry.dir)
        Robin.apply(m, 1, RobinSettings(0.6f, 6))
        m.save()

        show(KitShelf.Entry(entry.dir, KitStore.load(entry.dir)), openBox = "TREATMENT")
        // KEEP is the board card's, on EFFECTS.
        activate(Copy.PAD_EFFECTS_DOOR)
        assertFalse("no refusal text on a robin pad", hasText("Undo round-robin", substring = true))
        assertFalse("KEEP is enabled", isDisabled("KEEP"))
        activate("KEEP")
        waitFor("the board to land", timeoutMillis = 60_000) { toasts.any { it.startsWith("WIDE kept on") } }
        val kept = savedPad(entry)
        assertTrue("the takes survive the board", kept.isRobinChain)
        assertNotNull("the board is on the pad", BoardSheet.read(kept.recipe))
    }

    @Test
    fun aForeignChainShowsWhyTheSwitchIsOff() {
        val entry = kit(foreignChain = true)
        show(entry)
        waitFor("the reason", timeoutMillis = 60_000) { hasText(Copy.ROBIN_FOREIGN) }
        assertTrue("the chip is off the table", isDisabled("ROUND ROBIN, OFF"))
    }

    @Test
    fun movingAmountOrTakesPutsThePadOnItsOwnSetting() {
        val entry = kit()
        val m = KitBuilderModel.open(entry.dir)
        Robin.apply(m, 1, RobinSettings(0.6f, 6))
        m.save()

        show(KitShelf.Entry(entry.dir, KitStore.load(entry.dir)))
        assertEquals("60", stateText("ROUND ROBIN AMOUNT"))
        assertEquals("6", stateText("ROUND ROBIN TAKES"))

        dial("ROUND ROBIN AMOUNT", 1f)
        waitForToast(Copy.ROBIN_OWN)
        val own = savedPad(entry)
        assertEquals(1f, own.robin!!.amount)
        assertFalse(own.robin!!.followsKit)
        assertEquals(6, own.chain!!.cycle)

        toasts.clear()
        dial("ROUND ROBIN TAKES", 1f)
        waitForToast(Copy.ROBIN_OWN)
        assertEquals(8, savedPad(entry).chain!!.cycle)

        toasts.clear()
        activate("FOLLOW KIT, OFF")
        waitForToast(Copy.ROBIN_FOLLOWS)
        waitFor("the pad to follow the kit again", timeoutMillis = 60_000) { savedPad(entry).robin!!.followsKit }
        assertEquals("the kit has no setting, so the defaults", 6, savedPad(entry).chain!!.cycle)
        assertEquals(0.6f, savedPad(entry).robin!!.amount)
    }

    @Test
    fun aLegacyPadShowsOnWithItsTakesAndACustomAmount() {
        val entry = kit()
        val m = KitBuilderModel.open(entry.dir)
        Robin.apply(m, 1, takes = 3, seed = 2)
        m.save()

        show(KitShelf.Entry(entry.dir, KitStore.load(entry.dir)))
        assertEquals("CUSTOM", stateText("ROUND ROBIN AMOUNT"))
        assertEquals("3", stateText("ROUND ROBIN TAKES"))
        assertTrue("the chip reads ON", nodes("ROUND ROBIN, ON").isNotEmpty())
    }

    @Test
    fun consecutiveHitsOnARobinPadDealDifferentTakes() {
        val entry = kit()
        val m = KitBuilderModel.open(entry.dir)
        Robin.apply(m, 1, RobinSettings(0.6f, 6))
        m.save()

        show(KitShelf.Entry(entry.dir, KitStore.load(entry.dir)))
        // The first taps may land before the pad has been read; keep tapping until one is heard.
        waitFor("a first hit to sound", timeoutMillis = 60_000) {
            if (heard.isEmpty()) activate("▶ HIT")
            heard.isNotEmpty()
        }
        activate("▶ HIT")
        waitFor("a second hit to sound", timeoutMillis = 60_000) { heard.size >= 2 }
        assertFalse("two hits, two takes", heard[0].samples.contentEquals(heard[1].samples))
        assertTrue("each hit is one take, not the whole chain", heard[0].frameCount == heard[1].frameCount || heard[0].frameCount < 4410 * 2)
    }

    @Test
    fun aReleaseThatMovedNothingChangesNothing() {
        val entry = kit()
        val m = KitBuilderModel.open(entry.dir)
        Robin.apply(m, 1, RobinSettings(0.6f, 6))
        m.save()
        val before = File(entry.dir, "hit1.wav").readBytes()

        show(KitShelf.Entry(entry.dir, KitStore.load(entry.dir)))
        dial("ROUND ROBIN AMOUNT", 0.6f)
        pump()
        assertTrue("the pad still follows the kit", savedPad(entry).robin!!.followsKit)
        assertTrue("no re-render", before.contentEquals(File(entry.dir, "hit1.wav").readBytes()))
        assertFalse("no toast", Copy.ROBIN_OWN in toasts)
    }
}
