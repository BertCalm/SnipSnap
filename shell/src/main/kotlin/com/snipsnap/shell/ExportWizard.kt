package com.snipsnap.shell

import com.snipsnap.kit.ExportBlockedException
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.Finding
import com.snipsnap.kit.Kit
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import com.snipsnap.kit.blocked
import java.io.File

/**
 * The EXPORT screen: preflight as a visible checklist, the format cycler,
 * the dub, the eject — the last screen before somebody's SD card, which
 * is why it never writes a kit it knows is broken and never pretends a
 * write went better than it did.
 *
 * Stages: READY (cycle formats, read the checklist) → WRITING (the label
 * says DO NOT EJECT and means it) → COMPLETE (EJECT CARD ✓ resets for the
 * next dub). The dub-progress *animation* is the UI's clock
 * ([Motion.DUB_FILE_MS] × [fileCount]); the write itself is [write], run
 * on a worker, and the truth about what landed is its return value.
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
    }

    /**
     * WRITE KIT. Runs the real export through [Exporters] — same driver as
     * the CLI — and moves the stage machine. Never throws for a preflight
     * block; that comes back as [WriteResult.Blocked] with the checklist.
     */
    fun write(destRoot: File, overwrite: Boolean = true): WriteResult {
        check(stage == Stage.READY) { "write() only from READY, stage is $stage" }
        runPreflight()
        if (blocked) return WriteResult.Blocked(preflight)

        stage = Stage.WRITING
        return try {
            val outcome = Exporters.export(format, kit, kitDir, destRoot, overwrite)
            stage = Stage.COMPLETE
            WriteResult.Done(outcome)
        } catch (e: ExportBlockedException) {
            stage = Stage.READY
            WriteResult.Blocked(e.findings)
        } catch (e: Exception) {
            // A half-written card must never show EJECT ✓.
            stage = Stage.READY
            throw e
        }
    }

    /** EJECT CARD ✓ — back to READY for the next format or the next kit. */
    fun eject() {
        check(stage == Stage.COMPLETE) { "eject() only from COMPLETE" }
        stage = Stage.READY
    }

    // ---------- LCD labels, straight from the prototype ----------

    val writeLabel: String
        get() = when (stage) {
            Stage.READY -> "WRITE KIT"
            Stage.WRITING -> "WRITING — DO NOT EJECT"
            Stage.COMPLETE -> "EJECT CARD ✓"
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
