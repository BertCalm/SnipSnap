package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.XpmWriter
import java.io.File
import java.io.IOException

/**
 * Writes a kit as an MPC 3 **native** standalone track — the primary-format
 * export from docs/MPC3_FORMAT.md: `<Kit Name>.xtd` beside a flat
 * `<Kit Name>_[TrackData]/` folder of WAVs, exactly the pair every
 * commercial MPC 3 program ships as.
 *
 * Same discipline as [KitExporter]: preflight runs first and FAILs block,
 * sample stems are sanitized once and referenced consistently, and the
 * class colours the app assigns ride along (plain packed ints in MPC 3 —
 * no escaped blob).
 */
object Mpc3Exporter {

    fun exportTrack(
        kit: Kit,
        kitDir: File,
        destRoot: File,
        overwrite: Boolean = false,
        trackColour: Int = Mpc3TrackWriter.DEFAULT_TRACK_COLOUR,
        /** Optional embedded pattern — the kit arrives with a groove to play. */
        clip: Mpc3Clip? = null,
        /**
         * Or several (≤4, the container's slot count) — pattern variations
         * the browser flips between. Wins over [clip] when non-empty.
         */
        clips: List<Mpc3Clip> = emptyList(),
        /**
         * Dual-generation export — the Timeless Glow layout the instrument
         * suite already ships: the MPC 2 `.xpm` twin is written *inside*
         * `_[TrackData]/`, beside the samples it references. MPC 3 opens
         * the `.xtd`; an MPC 2 machine browses into the folder and finds a
         * bare program folder. One asset folder, every MPC ever made.
         */
        mpc2Twin: Boolean = false,
    ): ExportResult {
        val findings = Preflight.check(kit, kitDir)
        if (findings.blocked()) throw ExportBlockedException(findings)

        destRoot.mkdirs()
        val trackFile = File(destRoot, "${kit.name}.xtd")
        val dataDir = File(destRoot, Mpc3TrackWriter.trackDataDirName(kit.name))
        if ((trackFile.exists() || dataDir.exists()) && !overwrite) {
            throw DestinationExists(trackFile)
        }
        dataDir.deleteRecursively()

        val (slots, written) = KitExporter.buildSlots(kit, kitDir, samplesDir = dataDir)
        val effectiveClips = clips.ifEmpty { listOfNotNull(clip) }
        val program = Mpc3TrackWriter().writeTo(destRoot, DrumProgram(kit.name, slots), trackColour, effectiveClips)
        if (mpc2Twin) XpmWriter().writeTo(dataDir, DrumProgram(kit.name, slots))
        return ExportResult(destRoot, program, written, findings)
    }

    /**
     * Stage a kit for **project** embedding: preflight, copy its WAVs into
     * [samplesDir] (a project's flat `_[ProjectData]/`), and return the
     * program for `Mpc3ProjectWriter` — which writes the `.xpj` itself.
     */
    fun stageTrack(kit: Kit, kitDir: File, samplesDir: File): DrumProgram {
        val findings = Preflight.check(kit, kitDir)
        if (findings.blocked()) throw ExportBlockedException(findings)
        val (slots, _) = KitExporter.buildSlots(kit, kitDir, samplesDir)
        return DrumProgram(kit.name, slots)
    }
}
