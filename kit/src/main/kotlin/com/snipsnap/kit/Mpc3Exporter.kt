package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.DrumProgram
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
    ): ExportResult {
        val findings = Preflight.check(kit, kitDir)
        if (findings.blocked()) throw ExportBlockedException(findings)

        destRoot.mkdirs()
        val trackFile = File(destRoot, "${kit.name}.xtd")
        val dataDir = File(destRoot, Mpc3TrackWriter.trackDataDirName(kit.name))
        if ((trackFile.exists() || dataDir.exists()) && !overwrite) {
            throw IOException("destination already exists: $trackFile (pass overwrite=true to replace same-named files)")
        }
        dataDir.deleteRecursively()

        val (slots, written) = KitExporter.buildSlots(kit, kitDir, samplesDir = dataDir)
        val program = Mpc3TrackWriter().writeTo(destRoot, DrumProgram(kit.name, slots), trackColour, clip)
        return ExportResult(destRoot, program, written, findings)
    }
}
