package com.snipsnap.kit

import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.WavInfo
import com.snipsnap.xpm.XpmWriter
import java.io.File
import java.io.IOException

/** Thrown when preflight found blocking problems; carries the full checklist. */
class ExportBlockedException(val findings: List<Finding>) : Exception(
    "export blocked: " + findings.filter { it.severity == Severity.FAIL }.joinToString("; ") { it.message },
)

data class ExportResult(
    val directory: File,
    val program: File,
    val samples: List<File>,
    val findings: List<Finding>,
)

/**
 * Writes a kit as an MPC 2-era program folder — the tier-1 export from
 * docs/MPC_EXPORT.md: `<Kit Name>/<Kit Name>.xpm` plus the WAVs beside it.
 * Drop the folder anywhere on the MPC's card and load the program.
 *
 * Preflight runs first and FAILs block: a tool that writes to someone's SD
 * card never writes a kit it knows is broken. Sample names are sanitized on
 * the way out and the program references the sanitized stems, so what's in
 * the folder and what the program asks for can't disagree.
 */
object KitExporter {

    fun exportProgramFolder(
        kit: Kit,
        kitDir: File,
        destRoot: File,
        overwrite: Boolean = false,
    ): ExportResult {
        val findings = Preflight.check(kit, kitDir)
        if (findings.blocked()) throw ExportBlockedException(findings)

        val dest = File(destRoot, kit.name)
        if (dest.exists() && !overwrite) {
            throw IOException("destination already exists: $dest (pass overwrite=true to replace same-named files)")
        }
        dest.mkdirs()
        if (!dest.isDirectory) throw IOException("could not create $dest")

        val (program, written) = writeProgramAndSamples(kit, kitDir, programDir = dest, samplesDir = dest)
        return ExportResult(dest, program, written, findings)
    }

    /**
     * The emission both export shapes share: copy each pad's WAV (stem
     * sanitized) into [samplesDir], then write the `.xpm` referencing those
     * stems into [programDir]. Tier 1 passes the same directory twice; the
     * expansion layout separates them.
     */
    internal fun writeProgramAndSamples(
        kit: Kit,
        kitDir: File,
        programDir: File,
        samplesDir: File,
    ): Pair<File, List<File>> {
        programDir.mkdirs()
        samplesDir.mkdirs()

        val written = mutableListOf<File>()
        val stemBySlot = HashMap<Int, String>()
        val frameCountBySlot = HashMap<Int, Long>()
        val used = HashSet<String>()

        for (p in kit.pads.sortedBy { it.slot }) {
            val stem = Names.sanitizeStem(p.sampleStem)
            check(used.add(stem.lowercase())) { "preflight let a name collision through: $stem" }
            val src = File(kitDir, p.sampleFile)
            val dst = File(samplesDir, "$stem.wav")
            src.copyTo(dst, overwrite = true)
            stemBySlot[p.slot] = stem
            frameCountBySlot[p.slot] = WavInfo.read(dst).frameCount
            written += dst
        }

        val slots = arrayOfNulls<Pad>(kit.highestSlot)
        for (p in kit.pads) {
            slots[p.slot - 1] = Pad(
                sampleName = stemBySlot.getValue(p.slot),
                frameCount = frameCountBySlot.getValue(p.slot),
                level = p.level,
                pan = p.pan,
                tuneCoarse = p.tuneCoarse,
                tuneFine = p.tuneFine,
                muteGroup = p.muteGroup,
                oneShot = p.oneShot,
            )
        }
        val program = XpmWriter().writeTo(programDir, DrumProgram(kit.name, slots.toList()))
        return program to written
    }
}
