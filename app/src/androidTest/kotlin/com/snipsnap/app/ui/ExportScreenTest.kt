package com.snipsnap.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.Exports
import com.snipsnap.app.KitShelf
import com.snipsnap.app.PREFS
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.ExportWizardModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * EXPORT on a device: the stage machine actually reaching COMPLETE and
 * back, the checklist blocking the button it should, the format list's
 * own list, and the two half-async states (a held-back card copy, a
 * simulated card copy in flight) that only exist as `mutableStateOf`
 * fields on [ExportSession] — never reachable through a real SAF picker
 * in an instrumented test.
 *
 * **Why this screen.** EXPORT is the one screen in the app whose primary
 * action really writes to disk, and it is the last stop before someone's
 * SD card. `ExportWizardTest`/`ConventionTest` already prove the model
 * exhaustively in the JVM — this file exists for the part they cannot
 * reach: whether the *screen* wires that model up correctly (does the
 * blocked check actually disable the button, does picking a format
 * actually disarm an overwrite, does the button a screen reader hears
 * match the state it is in).
 *
 * **The session is hoisted the same way `App()` hoists it** — `session`
 * below is `by mutableStateOf`, not `remember`, on this plain test class,
 * exactly the shape `ExportSession`'s own `busy`/`overwriting`/
 * `cardPending` already use (see `ExportScreen.kt`'s own KDoc on why
 * those are not `remember`-scoped). That is what makes `session!!.busy =
 * true` from test code below a real write the composition observes, not
 * a local variable the screen never sees.
 *
 * **Two states here are never reached through the real UI, on purpose**:
 * an overwrite arm ([an_armed_overwrite_names_what_it_would_replace_and_a_format_change_disarms_it])
 * and a card copy in flight
 * ([share_stays_tappable_while_a_card_copy_is_in_flight_but_write_another_locks]).
 * Both would otherwise need a real `ACTION_OPEN_DOCUMENT_TREE` round trip
 * through the system picker, which nothing in this suite can drive.
 * `ExportSession`'s fields are public, plain, and in this same package —
 * setting them directly is the same trade [GrooveScreenTest] and
 * [SurfaceScreenTest] never had to make, because neither of their screens
 * has an async leg that only a real picker or a real multi-second copy
 * would otherwise reach.
 *
 * **Never a fixed default format.** `PREF_EXPORT_FORMAT` is real
 * `SharedPreferences`, shared with whatever ran on this device before —
 * this suite included, run after run. [pickFormat] reads
 * `session.model.format` live and picks *from there*, so no test depends
 * on which format a previous run, or a previous test in this class, left
 * behind. The one write these tests actually let land uses a kit name
 * stamped with [System.nanoTime] for the same reason, one level up: unlike
 * GROOVE's or SURFACE's fixtures, a completed EXPORT write lands under
 * `Exports.dir` — a real, persistent, app-external folder — not a fixture
 * only this test's own cache directory holds, and a second run with the
 * same kit name would find its own leftovers there and get
 * `WouldOverwrite` instead of `Done`.
 *
 * **No `assertReadsInFull` here, on purpose.** `PrimaryAction`'s label and
 * the header's kit name both promise `maxLines = 1`, which looked at first
 * like the same one-line contract [SurfaceScreenTest]/[GrooveScreenTest]
 * hold their own fixed-vocabulary labels to — but those are the product's
 * own copy, chosen and measured to fit; a kit name is free text a player
 * typed, with only a length *warning* past 32 chars (`Preflight.kt`), not a
 * cap. `TextOverflow.Ellipsis` is the deliberate fallback for exactly that
 * case, so a long-name variant of this suite's first two tests read
 * "ellipsized" straight off CI, correctly — the assumption, not the
 * screen, was wrong. What this file checks instead is that the label
 * names the *right* file, not how much of its name fits.
 *
 * **MIDI, not XPN, for the SHARE tests.** Both are the only two formats
 * `exportShareMime` ever returns non-null for (its own KDoc says so), but
 * XPN and EXPANSION are also the only two formats that render browser-tile
 * artwork through `AndroidKitArt.png` on the write — real `android.graphics`
 * work this suite has no reason to pay for when MIDI reaches the same
 * SHARE-button code path for free.
 *
 * Nothing here scrolls, for [ComposeScreenTest]'s own reason: every
 * assertion below lives on the checklist/destination/format rows or the
 * completion stage, all of which sit at the top of EXPORT's own scrolling
 * column at this fixture's size.
 */
@RunWith(AndroidJUnit4::class)
class ExportScreenTest : ComposeScreenTest() {

    private val toasts = mutableListOf<String>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kitDirs = mutableListOf<File>()
    private val writtenKitNames = mutableListOf<String>()

    /** Hoisted exactly as `App()` hoists `exportSession` — see this class's own KDoc. */
    private var session by mutableStateOf<ExportSession?>(null)

    /**
     * `PREF_EXPORT_FORMAT`/`PREF_CARD_TREE` live in `"tapeos"` — the same
     * `SharedPreferences` file the real, installed app reads and writes
     * during ordinary use, not a test-only store. [pickFormat] already
     * defends the format half by reading `session.model.format` live
     * rather than assuming a start value, but
     * [the_destination_row_defaults_to_this_phone] has no such defense: on
     * a device or emulator image that ever ran the real app (or this
     * suite, before this method existed) and picked a card,
     * `PREF_CARD_TREE` restores non-null and that test fails on host
     * state rather than anything it changed.
     * Clearing both before every test makes this suite's own on-device
     * history — and any real app run before it — irrelevant.
     */
    @Before
    fun resetPersistedExportState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PREF_CARD_TREE)
            .remove(PREF_EXPORT_FORMAT)
            .apply()
    }

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Exports.dir(context)?.let { root -> writtenKitNames.forEach { File(root, it).deleteRecursively() } }
    }

    /**
     * A one-pad kit, on disk where [KitStore.load] can read it back — the
     * `LaunchedEffect` in `ExportScreen` reloads from [entry]'s directory
     * rather than trusting `entry.kit` (see that effect's own KDoc), so
     * without this file on disk the screen would never leave its loading
     * panel.
     *
     * [name] defaults to one stamped with [System.nanoTime] — see this
     * class's own KDoc on why a fixed name is not safe to reuse across runs.
     */
    private fun exportKit(name: String = "EXPORT TEST ${System.nanoTime()}"): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "export-test-kit-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        writtenKitNames += name
        WavWriter.write(File(dir, "kick.wav"), Snip(FloatArray(4410), channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE))
        val kit = Kit(name = name, pads = listOf(KitPad(slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK)), tempoBpm = 92f)
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    /** A kit whose one pad names a sample file that was never written — Preflight's own FAIL. */
    private fun blockedKit(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "export-test-blocked-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        val kit = Kit(name = "BLOCKED", pads = listOf(KitPad(slot = 1, sampleFile = "missing.wav", drumClass = DrumClass.KICK)), tempoBpm = 92f)
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun show(entry: KitShelf.Entry) {
        session = null
        setPhoneContent {
            ExportScreen(
                entry = entry,
                session = session,
                onSessionChange = { session = it },
                appScope = appScope,
                onToast = { toasts += it },
                onNote = {},
                onNavigateKits = {},
            )
        }
        // The session loads off disk in a LaunchedEffect (KitStore.load +
        // ExportWizardModel's own Preflight.check); the header carries the
        // kit's name only once that has landed and ExportContent is what
        // is actually composed.
        waitFor("the kit's name in the header") {
            compose.onAllNodesWithText(entry.kit.name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForComplete() =
        waitFor("the completion stage") {
            compose.onAllNodesWithText(Copy.EXPORT_DONE, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    /** Opens the format list from whatever format is actually current and picks [target] — never a fixed starting index; see this class's own KDoc. */
    private fun pickFormat(target: ExportFormat) {
        val current = session!!.model.format
        if (current == target) return
        tap("FORMAT: ${current.cyclerLabel}, OPEN")
        tap("${target.cyclerLabel}: ${target.why}")
    }

    /**
     * The whole semantics tree as text — content descriptions, visible
     * text, and whether a node is disabled — for a failure message that
     * shows what actually rendered instead of just naming what didn't.
     * Only [SemanticsNode.children] and the same `getOrNull` pattern
     * [assertReadsInFull] already uses, so no untested API.
     */
    private fun dumpTree(node: SemanticsNode = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), depth: Int = 0): String {
        val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
        val disabled = node.config.getOrNull(SemanticsProperties.Disabled) != null
        val bits = listOfNotNull(cd?.let { "cd=\"$it\"" }, text?.let { "text=\"$it\"" }, "disabled".takeIf { disabled })
        val self = if (bits.isEmpty()) null else "${"  ".repeat(depth)}- ${bits.joinToString(" ")}"
        return (listOfNotNull(self) + node.children.map { dumpTree(it, depth + 1) }).joinToString("\n")
    }

    @Test
    fun writing_a_kit_reaches_complete_and_write_another_resets_it() {
        show(exportKit())
        tap("WRITE KIT")
        waitForComplete()
        button("WRITE ANOTHER ✓").assertExists()
        tap("WRITE ANOTHER ✓")
        button("WRITE KIT").assertExists()
    }

    @Test
    fun a_preflight_fail_disables_write_kit() {
        show(blockedKit())
        compose.onNodeWithText("FAIL", useUnmergedTree = true).assertExists()
        button("WRITE KIT").assertIsNotEnabled()
    }

    @Test
    fun the_destination_row_defaults_to_this_phone() {
        show(exportKit())
        compose.onNodeWithText(Copy.CARD_NONE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun the_format_list_opens_lists_every_format_and_picking_one_closes_it() {
        show(exportKit())
        val start = session!!.model.format
        button("FORMAT: ${start.cyclerLabel}, OPEN").assertExists()
        tap("FORMAT: ${start.cyclerLabel}, OPEN")
        for (f in ExportFormat.entries) {
            button("${f.cyclerLabel}: ${f.why}").assertExists()
        }
        val target = ExportFormat.entries.first { it != start }
        tap("${target.cyclerLabel}: ${target.why}")
        // Closed again, and the header now names the format just picked.
        button("FORMAT: ${target.cyclerLabel}, OPEN").assertExists()
    }

    @Test
    fun an_armed_overwrite_names_what_it_would_replace_and_a_format_change_disarms_it() {
        val entry = exportKit()
        show(entry)
        // Simulated: see this class's own KDoc on why the real round trip
        // (write once, write again, read WouldOverwrite back) is not what
        // this test is checking.
        val fakeExisting = File(entry.dir, "${entry.kit.name}.xpn")
        session!!.overwriting = fakeExisting
        pump()
        // Not assertReadsInFull: replaceWhat's filename comes straight from
        // a kit name, which is free text with no length cap PrimaryAction's
        // `maxLines = 1` promises anything about — TextOverflow.Ellipsis is
        // the deliberate fallback for a name too long to say in full, not a
        // defect. This checks the label names the right file, not its width.
        compose.onNodeWithText(Copy.replaceWhat(fakeExisting.name), useUnmergedTree = true).assertExists()

        val armed = session!!.model.format
        pickFormat(ExportFormat.entries.first { it != armed })

        button("WRITE KIT").assertExists()
        compose.onNodeWithText(Copy.replaceWhat(fakeExisting.name), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun share_stays_tappable_while_a_card_copy_is_in_flight_but_write_another_locks() {
        show(exportKit())
        pickFormat(ExportFormat.MIDI)
        assertEquals("pickFormat did not land on MIDI", ExportFormat.MIDI, session!!.model.format)
        tap("WRITE KIT")
        waitForComplete()

        // Pinned explicitly, not just through the SHARE button's presence:
        // a failure here says which of exportShareMime's three conditions
        // broke, instead of a bare "node not found" for the button itself.
        assertEquals(ExportWizardModel.Stage.COMPLETE, session!!.model.stage)
        val outcome = session!!.lastOutcome!!
        assertEquals(ExportFormat.MIDI, outcome.format)
        assertNull("a MIDI outcome should carry no companion file", outcome.companion)
        assertTrue("outcome.primary should be the .mid file MidiGroove.writeTo just wrote", outcome.primary.isFile)
        // Every check above passed once already (a prior CI run pinned
        // them individually) and the SHARE button still didn't render —
        // so if this still fails, the tree dump is what finally shows why.
        try {
            button(Copy.EXPORT_SHARE_LABEL).assertIsEnabled()
        } catch (e: Throwable) {
            throw AssertionError("SHARE button missing for outcome=$outcome. Screen:\n${dumpTree()}", e)
        }

        val what = "AN OLDER EXPORT ALREADY ON THE CARD.MID"
        session!!.cardPending = CardPending(outcome, Uri.EMPTY, what)
        pump()
        compose.onNodeWithText(Copy.putOnCardOver(what), useUnmergedTree = true).assertExists()
        button(Copy.putOnCardOver(what)).assertIsEnabled()

        // A card copy in flight: shareExport() only ever reads the already-
        // settled `lastOutcome` and calls a synchronous ShareOut.send, so it
        // never needed to gate on `busy` the way the card-offer button and
        // WRITE ANOTHER (which resets the wizard under a running copy) do.
        // Not a bug — see this suite's own history with this exact button.
        session!!.busy = true
        pump()
        button(Copy.EXPORT_SHARE_LABEL).assertIsEnabled()
        button(Copy.putOnCardOver(what)).assertIsNotEnabled()
        button("WRITE ANOTHER ✓").assertIsNotEnabled()
    }
}
