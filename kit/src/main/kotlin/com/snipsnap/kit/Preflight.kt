package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.io.IOException

enum class Severity { OK, WARN, FAIL }

data class Finding(
    val severity: Severity,
    val message: String,
    /** Pad slot the finding is about, when it's about one. */
    val slot: Int? = null,
)

/** True when any finding blocks the export. */
fun List<Finding>.blocked(): Boolean = any { it.severity == Severity.FAIL }

/**
 * The export wizard's checklist as real checks.
 *
 * Every rule here matches something that actually goes wrong on hardware:
 * a missing file is a silent pad, a 48 kHz WAV loads wrong or not at all,
 * colliding sample names overwrite each other on the card, and a kit name
 * with the wrong characters never survives FAT. WARNs export anyway; FAILs
 * block, because "it exported but the kit is broken" is the worst outcome
 * a tool that writes to someone's SD card can produce.
 */
object Preflight {

    fun check(kit: Kit, kitDir: File): List<Finding> {
        val out = mutableListOf<Finding>()

        if (kit.pads.isEmpty()) {
            out += Finding(Severity.FAIL, "no pads assigned — nothing to export")
            return out
        }
        out += Finding(Severity.OK, "${kit.pads.size} pads assigned")

        if (!Names.isMpcSafe(kit.name)) {
            out += Finding(Severity.FAIL, "kit name won't survive the SD card: \"${kit.name}\"")
        } else if (kit.name.length > 32) {
            out += Finding(Severity.WARN, "kit name is long for the MPC browser (${kit.name.length} chars)")
        }

        var formatClean = true
        val stems = HashMap<String, Int>()

        for (p in kit.pads.sortedBy { it.slot }) {
            val file = File(kitDir, p.sampleFile)
            if (!file.isFile) {
                out += Finding(Severity.FAIL, "missing sample file ${p.sampleFile}", p.slot)
                formatClean = false
                continue
            }
            val info = try {
                WavInfo.read(file)
            } catch (e: IOException) {
                out += Finding(Severity.FAIL, "unreadable WAV ${p.sampleFile}: ${e.message}", p.slot)
                formatClean = false
                continue
            }
            if (info.sampleRate != 44_100) {
                out += Finding(
                    Severity.FAIL,
                    "${p.sampleFile} is ${info.sampleRate} Hz — resample to 44.1 kHz before export",
                    p.slot,
                )
                formatClean = false
            }
            if (info.bitsPerSample != 16 && info.bitsPerSample != 24) {
                out += Finding(
                    Severity.WARN,
                    "${p.sampleFile} is ${info.bitsPerSample}-bit — the MPC may refuse it",
                    p.slot,
                )
                formatClean = false
            }
            if (info.channels > 2) {
                out += Finding(Severity.FAIL, "${p.sampleFile} has ${info.channels} channels", p.slot)
                formatClean = false
            }

            val exportStem = Names.sanitizeStem(p.sampleStem)
            if (exportStem != p.sampleStem) {
                out += Finding(Severity.WARN, "${p.sampleFile} will be renamed to $exportStem.wav on export", p.slot)
            }
            // Observed hardware limit (XO_OX edge-case notes): filenames
            // past ~64 chars may load but display blank or truncated.
            if (exportStem.length + 4 > 64) {
                out += Finding(
                    Severity.WARN,
                    "${p.sampleFile} exceeds 64 characters — the MPC UI may blank or truncate it",
                    p.slot,
                )
            }
            stems.put(exportStem.lowercase(), p.slot)?.let { previous ->
                out += Finding(
                    Severity.FAIL,
                    "pads $previous and ${p.slot} collide on sample name \"$exportStem\"",
                    p.slot,
                )
            }

            // Velocity zones ship their own WAVs; each must exist and claim
            // a unique exported stem, same as any pad sample.
            for (l in p.velocityLayers) {
                if (l.sampleFile == p.sampleFile) continue
                if (!File(kitDir, l.sampleFile).isFile) {
                    out += Finding(Severity.FAIL, "missing layer file ${l.sampleFile}", p.slot)
                    formatClean = false
                    continue
                }
                val layerStem = Names.sanitizeStem(l.sampleStem)
                stems.put(layerStem.lowercase(), p.slot)?.let { previous ->
                    out += Finding(
                        Severity.FAIL,
                        "pads $previous and ${p.slot} collide on sample name \"$layerStem\"",
                        p.slot,
                    )
                }
            }
        }

        if (formatClean) {
            out += Finding(Severity.OK, "all WAVs 44.1 kHz, 16/24-bit")
        }

        val hats = kit.pads.filter {
            it.drumClass == DrumClass.HAT_CLOSED || it.drumClass == DrumClass.HAT_OPEN
        }
        if (hats.size >= 2) {
            val groups = hats.map { it.muteGroup }.toSet()
            if (groups.size == 1 && groups.single() != 0) {
                out += Finding(Severity.OK, "hats share choke group ${groups.single()}")
            } else {
                out += Finding(Severity.WARN, "open and closed hats don't share a choke group — open hats will ring")
            }
        }

        return out
    }
}
