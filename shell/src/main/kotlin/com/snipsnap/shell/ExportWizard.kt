package com.snipsnap.shell

import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.ExportBlockedException
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.Finding
import com.snipsnap.kit.Kit
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import com.snipsnap.kit.blocked
import java.io.File

/**
 * The EXPORT screen: preflight as a visible checklist, the format cycler,
 * the dub, the reset — the last screen before somebody's SD card, which
 * is why it never writes a kit it knows is broken and never pretends a
 * write went better than it did.
 *
 * Stages: READY (cycle formats, read the checklist) → WRITING (the label
 * says DO NOT EJECT and means it — pulling the real storage mid-write is
 * the one thing this stage warns against) → COMPLETE (WRITE ANOTHER ✓
 * resets for the next dub; nothing is actually ejected — the file already
 * landed and stays exactly where it landed). The dub-progress *animation*
 * is the UI's clock ([Motion.DUB_FILE_MS] × [fileCount]); the write itself
 * is [write], run on a worker, and the truth about what landed is its
 * return value.
 */
class ExportWizardModel(
    private val kit: Kit,
    private val kitDir: File,
) {

    enum class Stage { READY, WRITING, COMPLETE }

    var stage: Stage = Stage.READY
        private set

    var formatIx: Int = 0
        private set

    val format: ExportFormat get() = ExportFormat.entries[formatIx]

    /** The cycler line under the format arrows. */
    val formatLabel: String get() = format.cyclerLabel

    /** Tap the cycler: next format. Locked while writing. */
    fun cycleFormat() {
        if (stage == Stage.READY) formatIx = (formatIx + 1) % ExportFormat.entries.size
    }

    /**
     * Pick a format outright, in one move rather than by walking to it.
     *
     * The September UAT's finding 7: [cycleFormat] was the only way in, and
     * it goes one direction with no back step, so DECENT_SAMPLER cost seven
     * taps and overshooting your target cost seven more. That is a picker's
     * job, not a cycler's, and the screen has one now.
     *
     * Locked off READY exactly as [cycleFormat] is — the destination cannot
     * change out from under a dub that has already started choosing its
     * writer.
     */
    fun setFormat(f: ExportFormat) {
        if (stage == Stage.READY) formatIx = ExportFormat.entries.indexOf(f)
    }

    // ---------- preflight ----------

    var preflight: List<Finding> = Preflight.check(kit, kitDir)
        private set

    /** Re-run the checklist (after edits, before enabling WRITE). */
    fun runPreflight(): List<Finding> {
        preflight = Preflight.check(kit, kitDir)
        return preflight
    }

    /** FAILs block the write button outright — law 3 has nothing on data safety. */
    val blocked: Boolean get() = preflight.blocked()

    val warnings: List<Finding> get() = preflight.filter { it.severity == Severity.WARN }

    // ---------- the dub ----------

    /** Files the dub will land: every pad WAV (layers included) + the program. */
    val fileCount: Int
        get() = kit.pads.sumOf { maxOf(1, it.velocityLayers.size) } + 1

    sealed interface WriteResult {
        /** Preflight said no. The checklist is the message. */
        data class Blocked(val findings: List<Finding>) : WriteResult

        /** On the card. [outcome] says exactly what and where. */
        data class Done(val outcome: ExportOutcome) : WriteResult

        /**
         * Something is already there and [write] was told not to replace it
         * (September UAT, finding 18). [path] is the exact thing in the way,
         * as the writer named it, so the screen can say what it would be
         * writing over instead of asking an abstract "are you sure?".
         *
         * A value, not an exception, because this is an ordinary answer to
         * "write this here" — the same reason [Blocked] is a value. A real
         * write failure (a full card, one pulled mid-write) still throws.
         */
        data class WouldOverwrite(val path: File) : WriteResult
    }

    /**
     * The browser tile for expansion/`.xpn` writes — the Z6.2 prototyping
     * verdict made waveform the default; null skips artwork entirely.
     */
    var artStyle: KitArt.Style? = KitArt.Style.WAVEFORM

    /**
     * WRITE KIT. Runs the real export through [Exporters] — same driver as
     * the CLI — and moves the stage machine. Never throws for a preflight
     * block; that comes back as [WriteResult.Blocked] with the checklist,
     * nor for a destination that already exists when [overwrite] is false —
     * that comes back as [WriteResult.WouldOverwrite] naming what is there.
     *
     * [overwrite] still defaults to true: the CLI and the tests that drive
     * this model want the old behaviour, and a default that silently changed
     * under them would be a second bug. EXPORT passes false on the first tap
     * and true only after the user has seen what they would replace.
     */
    fun write(destRoot: File, overwrite: Boolean = true): WriteResult {
        check(stage == Stage.READY) { "write() only from READY, stage is $stage" }
        runPreflight()
        if (blocked) return WriteResult.Blocked(preflight)

        stage = Stage.WRITING
        return try {
            val artwork = artStyle
                ?.takeIf { format == ExportFormat.EXPANSION || format == ExportFormat.XPN }
                ?.let { KitArt.png(kit, kitDir, it) }
            val outcome = Exporters.export(format, kit, kitDir, destRoot, overwrite, artworkPng = artwork)
            stage = Stage.COMPLETE
            WriteResult.Done(outcome.copy(readBack = readBack(outcome)))
        } catch (e: ExportBlockedException) {
            stage = Stage.READY
            WriteResult.Blocked(e.findings)
        } catch (e: DestinationExists) {
            // Caught ahead of the general handler below and *before* the
            // IOException family it belongs to: nothing was written, so the
            // wizard goes back to READY exactly as a preflight block does,
            // and the caller decides whether to come back with overwrite.
            stage = Stage.READY
            WriteResult.WouldOverwrite(e.path)
        } catch (e: Exception) {
            // A half-written card must never show WRITE ANOTHER ✓.
            stage = Stage.READY
            throw e
        }
    }

    /**
     * READ BACK, after the write landed: the file re-read through X-Ray
     * and diffed against the kit ([ExportReadBack.verify]). The file is
     * already on the card by now, so a checker that itself falls over is
     * one FAIL row in words — never a throw that would hide a finished
     * write behind an error.
     */
    private fun readBack(outcome: ExportOutcome): List<Finding> = try {
        ExportReadBack.verify(kit, outcome)
    } catch (e: Exception) {
        // FindingRow shows this row's own message verbatim (ExportScreen.kt)
        // - the reader's own words when it has them, a domain phrase when it
        // doesn't, never the raw exception class.
        listOf(Finding(Severity.FAIL, "read back fell over: ${e.message ?: "the reader itself refused"}"))
    }

    /**
     * WRITE ANOTHER ✓ — back to READY for the next format or the next kit.
     * Named `eject` internally (the stage-machine verb predating this
     * label), but nothing is ejected: the file DUB already wrote stays
     * exactly where it landed; this only resets the wizard.
     */
    fun eject() {
        check(stage == Stage.COMPLETE) { "eject() only from COMPLETE" }
        stage = Stage.READY
    }

    // ---------- LCD labels, straight from the prototype ----------

    val writeLabel: String
        get() = when (stage) {
            Stage.READY -> "WRITE KIT"
            Stage.WRITING -> "WRITING — DO NOT EJECT"
            // Not "EJECT CARD ✓": nothing is ejected here, and EJECT
            // already means "stop listening" on the shelf's ArmControl
            // and "delete" on a pad's own DELETE → BIN. This button only
            // resets the wizard for another write — Copy.CARD_EJECTED
            // (the toast this fires) says exactly that.
            Stage.COMPLETE -> "WRITE ANOTHER ✓"
        }

    val dubLabel: String
        get() = when (stage) {
            Stage.READY -> "READY TO DUB"
            Stage.WRITING -> "DUBBING…"
            Stage.COMPLETE -> "DUB COMPLETE"
        }

    /**
     * The progress caption. [filesShown] is the UI animation's counter
     * (0..[fileCount]); the queued and done lines bracket it.
     */
    fun dubFilesLine(filesShown: Int): String = when (stage) {
        Stage.READY -> "SIDE A: PROGRAMS · SIDE B: SAMPLES · $fileCount FILES QUEUED"
        Stage.WRITING -> "SIDE A: PROGRAMS · SIDE B: SAMPLES · ${filesShown.coerceIn(0, fileCount)} OF $fileCount FILES"
        Stage.COMPLETE -> "ALL $fileCount FILES ON TAPE. GO MAKE THE THING."
    }
}
