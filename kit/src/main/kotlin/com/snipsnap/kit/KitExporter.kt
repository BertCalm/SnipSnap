package com.snipsnap.kit

import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.WavInfo
import com.snipsnap.xpm.XpmWriter
import java.io.File
import java.io.IOException

/**
 * Thrown by every writer that refuses to replace something already on disk —
 * the `overwrite = false` answer, carrying the path that is in the way.
 *
 * September UAT, finding 18: EXPORT hardcoded `overwrite = true`, so
 * re-exporting to the same destination silently wrote over whatever was
 * there. On someone's SD card that is the one write worth pausing on. The
 * screen can only pause on it if it can tell "there is already a kit here"
 * apart from "the write failed" — a disk that filled up, a card pulled
 * mid-write, a folder it may not touch. Both used to arrive as a bare
 * [IOException] with a message, so telling them apart meant matching on
 * prose, and treating a full disk as a confirmable overwrite would be worse
 * than the bug being fixed.
 *
 * It stays an [IOException] so every existing `catch` and every test that
 * asserts on the message keeps working; the message is verbatim what the
 * thirteen hand-written copies of it used to say.
 */
class DestinationExists(val path: File) : IOException(
    "destination already exists: $path (pass overwrite=true to replace same-named files)",
)

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
            throw DestinationExists(dest)
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
        val (slots, written) = buildSlots(kit, kitDir, samplesDir)
        val program = XpmWriter().writeTo(programDir, DrumProgram(kit.name, slots))
        return program to written
    }

    /**
     * Sample copying and slot assembly, shared by every program writer —
     * MPC 2 XPM and MPC 3 track alike: copy each pad's WAVs (stems
     * sanitized) into [samplesDir] and return the [Pad] slots referencing
     * them, class colours included.
     */
    internal fun buildSlots(
        kit: Kit,
        kitDir: File,
        samplesDir: File,
        /**
         * Prepended to every stem — how several kits share one flat
         * `_[ProjectData]/` without their `A01_Kick_01`s colliding.
         */
        stemPrefix: String = "",
    ): Pair<List<Pad?>, List<File>> {
        samplesDir.mkdirs()

        val written = mutableListOf<File>()
        val stemBySlot = HashMap<Int, String>()
        val frameCountBySlot = HashMap<Int, Long>()
        val layersBySlot = HashMap<Int, List<VelocityLayer>>()
        val used = HashSet<String>()

        fun copySample(file: String): Pair<String, Long> {
            val stem = Names.sanitizeStem(stemPrefix + file.substringBeforeLast('.'))
            check(used.add(stem.lowercase())) { "preflight let a name collision through: $stem" }
            val dst = File(samplesDir, "$stem.wav")
            File(kitDir, file).copyTo(dst, overwrite = true)
            written += dst
            return stem to WavInfo.read(dst).frameCount
        }

        for (p in kit.pads.sortedBy { it.slot }) {
            if (p.velocityLayers.isEmpty()) {
                val (stem, frames) = copySample(p.sampleFile)
                stemBySlot[p.slot] = stem
                frameCountBySlot[p.slot] = frames
            } else {
                // Every zone's WAV travels; the pad's headline sample fields
                // point at the loudest zone so single-layer readers agree.
                val zones = p.velocityLayers.map { l ->
                    val (stem, frames) = copySample(l.sampleFile)
                    VelocityLayer(stem, frames, l.velStart, l.velEnd)
                }
                layersBySlot[p.slot] = zones
                stemBySlot[p.slot] = zones.last().sampleName
                frameCountBySlot[p.slot] = zones.last().frameCount
            }
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
                velocityLayers = layersBySlot[p.slot],
                color = p.packedColor(),
                attack = p.attack,
                decay = p.decay,
                cutoff = p.cutoff,
                resonance = p.resonance,
                humanize = p.humanize,
                chain = p.chain?.toPlay(frameCountBySlot.getValue(p.slot)),
            )
        }
        return slots.toList() to written
    }

    /**
     * `#rrggbb` → the packed 24-bit int the ProgramPads blob carries, so the
     * pads light up on the MPC in the same class colours the app shows.
     * Pure black maps to unset — `0` means "no colour" in the format.
     */
    internal fun KitPad.packedColor(): Int? =
        colorHex?.substring(1)?.toInt(16)?.takeIf { it > 0 }
}
