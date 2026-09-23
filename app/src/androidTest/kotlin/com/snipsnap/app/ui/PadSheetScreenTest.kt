package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PadSheetBoxes
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

/**
 * PAD SHEET (`PadSheetScreen.kt`, ~3,900 lines, `:app`'s largest UI file)
 * has had zero on-device coverage until now — this suite is deliberately
 * narrow, not a sweep of it.
 *
 * **What this covers, and why these three things.** `KitBuilderModel`'s
 * own edits (`assign`/`save`/undo/etc.) are already exhaustively proven by
 * `KitBuilderTest`/`KitBuilderStemBoundTest`/`KitBuilderLocaleTest` in
 * `:shell` — the same JVM/on-device split `ExportScreenTest`'s own KDoc
 * describes for `ExportWizardModel`. What is untested is the screen's own
 * UI-specific glue, so that is what these tests exercise:
 *
 * 1. The pad-nav footer (`◄`/`►`) — real logic (circular wraparound over
 *    `kit.pads`, degenerating to nowhere-to-go on one pad) that a compiler
 *    and `ConventionTest` are both silent on.
 * 2. `ConventionTest`'s own
 *    `` `law - PAD SHEET state that is not a property of one pad is not
 *    keyed on the slot` `` — a source-regex over three `remember(...)` key
 *    sites (`measuredRoom`, `pendingDepth`, `pendingBloom`) added after
 *    J23/J26 (`c47d8339`): moving to the next pad used to silently discard
 *    a live mic measurement, or reset MAKE PAD's own dialled DEPTH/BLOOM,
 *    because both were keyed on `slot` instead of `entry.dir`. The law
 *    proves the *mechanism* (the right key is in the source); this suite
 *    proves the *outcome* (the value really does survive a pad-nav trip)
 *    for the one pair reachable without a live mic capture, `pendingDepth`/
 *    `pendingBloom`.
 * 3. `PadSheetBoxes.summaries` wired correctly into the screen's five
 *    `GroupBox` strips — a `:shell`-pure function, but the wiring from it
 *    to `PadSheetScreen`'s own `boxes.getValue(...)` calls is `:app`-only
 *    and untested.
 *
 * **What is deliberately not here.** Every treatment/MUTATE/OUTSIDE apply
 * path sets `busy` from *inside* a coroutine it launches on `appScope`
 * (see `applyTreatment`/`onOutside`), never before, and `busy` itself is a
 * local `remember(model) { mutableStateOf(false) }` — unlike
 * `ExportSession.busy`, there is no hoisted object this suite can poke to
 * force a busy state deterministically. Racing the real coroutine for a
 * one-frame window would be exactly the kind of timing-fragile assertion
 * this repo's own suites have learned to avoid (see `ComposeScreenTest`'s
 * own KDoc on the hand-driven clock), so the busy-gating behaviour
 * (buttons disabled, a selected segment staying selected while busy) is
 * left untested here rather than tested unreliably.
 *
 * **How this suite reaches the MAKE box without ever scrolling.**
 * `PadSheetScreen`'s `openBox` parameter is caller-hoisted (unlike GROOVE/
 * EXPORT, which have no "start pre-opened" knob), so [show] hoists it the
 * same way `ExportScreenTest` hoists `session` — a plain `mutableStateOf`
 * field on this test class, read and written from inside the composable
 * lambda. Starting a test already on `openBox = "MAKE"` means its DEPTH/
 * BLOOM steppers exist from the very first frame; nothing is tapped open,
 * and [ComposeScreenTest]'s own `performScrollTo()` hazard never comes up.
 * Reading and setting a stepper's value goes through [sliderText]/
 * [dialSlider] below, fetching the `SemanticsNode` and invoking its
 * `SetProgress`/`StateDescription` entries directly — the same
 * direct-invoke idiom `ComposeScreenTest.assertReadsInFull` already uses
 * for `GetTextLayoutResult`, and for the same reason: it reads/writes the
 * semantics tree itself rather than a real touch gesture, so it works
 * regardless of whether the node's `Modifier.verticalScroll` ancestor has
 * it within the visible fold.
 *
 * Nothing here taps a `GroupBox`'s own toggle strip, so this suite never
 * needs to know whether the tapped strip's position survives the box it
 * opens pushing everything below it further down — the one thing every
 * prior suite here learned to avoid re-discovering the hard way.
 */
@RunWith(AndroidJUnit4::class)
class PadSheetScreenTest : ComposeScreenTest() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kitDirs = mutableListOf<File>()

    private var currentSlot by mutableStateOf(1)
    private var openBoxState by mutableStateOf<String?>(null)

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
    }

    /** A kit with one silent pad per [slots], each its own tiny WAV. */
    private fun padSheetKit(slots: List<Int>): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "padsheet-test-kit-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        val pads = slots.map { slot ->
            val file = "kick$slot.wav"
            WavWriter.write(File(dir, file), Snip(FloatArray(4410), channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE))
            KitPad(slot = slot, sampleFile = file, drumClass = DrumClass.KICK)
        }
        val kit = Kit(name = "PAD SHEET TEST", pads = pads, tempoBpm = 92f)
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun show(entry: KitShelf.Entry, slot: Int, openBox: String? = null) {
        currentSlot = slot
        openBoxState = openBox
        setPhoneContent {
            PadSheetScreen(
                entry = entry,
                slot = currentSlot,
                onSlotChange = { currentSlot = it },
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
                openBox = openBoxState,
                onOpenBox = { openBoxState = it },
            )
        }
        // KitBuilderModel.open runs inside a LaunchedEffect(entry.dir), so
        // the header doesn't exist on the first frame — mirrors
        // GrooveScreenTest's own "CAPTURED" wait and ExportScreenTest's
        // kit-name wait for the same reason.
        waitFor("the pad header") {
            compose.onAllNodesWithText("PAD ${PadBanks.tag(slot)}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertOnPad(slot: Int, position: String) {
        compose.onNodeWithText("PAD ${PadBanks.tag(slot)}", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(position, useUnmergedTree = true).assertExists()
    }

    private fun sliderNode(label: String) = compose.onNodeWithContentDescription(label, useUnmergedTree = true)

    /** The stepper's own `stateDescription` — exactly what it draws as its value text. */
    private fun sliderText(label: String): String {
        val node = sliderNode(label).fetchSemanticsNode("no stepper named \"$label\"")
        return node.config.getOrNull(SemanticsProperties.StateDescription) ?: error("\"$label\" has no stateDescription")
    }

    /** Invokes the stepper's `SetProgress` action directly — see this file's own KDoc for why. */
    private fun dialSlider(label: String, target: Float) {
        val node = sliderNode(label).fetchSemanticsNode("no stepper named \"$label\"")
        val action = node.config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("\"$label\" has no SetProgress action - is it disabled?")
        assertTrue("setProgress on \"$label\" to $target was refused", action.invoke(target))
        pump()
    }

    /**
     * The footer's own circular wraparound over `kit.pads` — `assignedSlots`
     * mods around both ends, so a 3-pad kit is the smallest fixture that can
     * tell "wraps" apart from "just toggles between two neighbours."
     */
    @Test
    fun pad_nav_wraps_around_a_three_pad_kit_and_the_header_follows() {
        show(padSheetKit(listOf(1, 2, 3)), slot = 1)
        assertOnPad(1, "1 OF 3")
        // Wrapping backward from the first pad reaches the last, forward the second.
        button("◄ ${PadBanks.tag(3)}").assertExists()
        button("${PadBanks.tag(2)} ►").assertExists()

        tap("${PadBanks.tag(2)} ►")
        assertOnPad(2, "2 OF 3")

        tap("${PadBanks.tag(3)} ►")
        assertOnPad(3, "3 OF 3")
        // Wrapping forward from the last pad reaches the first.
        button("${PadBanks.tag(1)} ►").assertExists()

        tap("${PadBanks.tag(1)} ►")
        assertOnPad(1, "1 OF 3")

        tap("◄ ${PadBanks.tag(3)}")
        assertOnPad(3, "3 OF 3")
    }

    /** `assignedSlots.size > 1` gates both arrows off entirely rather than letting a lone pad wrap onto itself. */
    @Test
    fun a_single_pad_kit_has_nowhere_for_the_pad_nav_to_go() {
        show(padSheetKit(listOf(5)), slot = 5)
        compose.onNodeWithText("1 OF 1", useUnmergedTree = true).assertExists()
        button("◄").assertExists()
        button("◄").assertIsNotEnabled()
        button("►").assertExists()
        button("►").assertIsNotEnabled()
    }

    /**
     * `PadSheetBoxes.summaries` wired into the screen's five strips. MAKE
     * is deliberately excluded from the count — its own KDoc explains it
     * never reads UNTOUCHED, since it advertises what the box can do
     * rather than reporting what the pad carries.
     */
    @Test
    fun an_untouched_pad_shows_untouched_on_every_bench_strip_but_make() {
        show(padSheetKit(listOf(1)), slot = 1)
        assertEquals(
            4,
            compose.onAllNodesWithText(PadSheetBoxes.UNTOUCHED, useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    /**
     * J26 (`c47d8339`): MAKE PAD's own DEPTH/BLOOM are tool settings, not a
     * pad property, so they're keyed on `entry.dir` — keyed on `slot` they
     * used to reset on every pad-nav arrow. This drives the actual runtime
     * outcome rather than re-reading the `remember(...)` key `ConventionTest`
     * already checks in source.
     */
    @Test
    fun dialling_depth_and_bloom_survives_a_trip_to_another_pad_and_back() {
        show(padSheetKit(listOf(1, 2)), slot = 1, openBox = PadSheetBoxes.Box.MAKE.name)

        val defaultDepth = sliderText("DEPTH")
        val defaultBloom = sliderText("BLOOM")

        dialSlider("DEPTH", 0.9f)
        dialSlider("BLOOM", 0.75f)

        val dialledDepth = sliderText("DEPTH")
        val dialledBloom = sliderText("BLOOM")
        assertTrue("dialling DEPTH did not move its value off the default", dialledDepth != defaultDepth)
        assertTrue("dialling BLOOM did not move its value off the default", dialledBloom != defaultBloom)

        tap("${PadBanks.tag(2)} ►")
        assertOnPad(2, "2 OF 2")
        tap("◄ ${PadBanks.tag(1)}")
        assertOnPad(1, "1 OF 2")

        assertEquals(
            "DEPTH reset on the trip to pad 2 and back - it's remember(slot)-keyed again (J26)",
            dialledDepth,
            sliderText("DEPTH"),
        )
        assertEquals(
            "BLOOM reset on the trip to pad 2 and back - it's remember(slot)-keyed again (J26)",
            dialledBloom,
            sliderText("BLOOM"),
        )
    }
}
