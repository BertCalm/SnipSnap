package com.snipsnap.kit

import com.snipsnap.mpc3.MpcXRay
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * READ BACK: the file DUB just wrote, re-read through [MpcXRay] — the
 * same read-only path X-RAY ▸ INSPECT A FILE uses — and diffed pad by pad
 * against what the kit asked for. [Preflight] checks the kit *before*
 * the write; nothing checked the file *after* it until this.
 *
 * Honest about its own reach: it proves the file agrees with *our*
 * reader, not that hardware opens it — a writer and a reader can share
 * one misunderstanding of the format. A field X-Ray has no name for is a
 * [Severity.SKIP] row, never counted as OK.
 *
 * Expected values are built the way the writers build them — stems
 * through [Names.sanitizeStem], shape defaults per dialect, floats to the
 * six places `XpmWriter` prints — so a disagreement here is a real one,
 * not the writer's own rounding coming back to bite.
 */
object ExportReadBack {

    /**
     * `XpmWriter` prints floats to six places, so the file can sit half a
     * unit in the sixth place (5e-7) from the kit's value, plus a float32
     * ulp on the way back in through `toFloat()`. Anything under this is
     * that rounding, not a disagreement; anything over it is real. ACVS
     * writes the double of the kit's float and reads back exact.
     */
    const val FLOAT_TOLERANCE = 1e-6f

    /**
     * What each generation's writer puts in a shape field the kit left
     * null, and how it numbers instruments. `XpmWriter.appendInstrument`
     * writes `VolumeDecay` 0.047244 and `<Instrument number>` from 0
     * (hardware-verified); `Mpc3TrackWriter.synthSection` writes a filled
     * pad's decay as 1.0 and X-Ray counts its 128 instruments from 1.
     * Attack 0, cutoff 1 and resonance 0 are both writers' defaults.
     */
    private class Dialect(val xmlSlots: Boolean, val decayDefault: Float) {
        val attackDefault = 0f
        val cutoffDefault = 1f
        val resonanceDefault = 0f
        fun kitSlot(read: MpcXRay.Pad): Int = if (xmlSlots) read.slot + 1 else read.slot
    }

    private val XML = Dialect(xmlSlots = true, decayDefault = 0.047244f)
    private val ACVS = Dialect(xmlSlots = false, decayDefault = 1.0f)

    /** One velocity zone as both sides describe it: a bare stem and its window. */
    private data class Zone(val stem: String, val velStart: Int?, val velEnd: Int?) {
        fun text(): String = "$stem $velStart..$velEnd"
    }

    fun verify(kit: Kit, outcome: ExportOutcome): List<Finding> {
        val dialect = when (outcome.format) {
            ExportFormat.PROGRAM_FOLDER, ExportFormat.EXPANSION, ExportFormat.XPN -> XML
            ExportFormat.MPC3_TRACK, ExportFormat.MPC3_PROJECT -> ACVS
            ExportFormat.MIDI, ExportFormat.SFZ, ExportFormat.DECENT_SAMPLER -> return listOf(
                Finding(Severity.SKIP, "read back: ${outcome.format.cyclerLabel} isn't an MPC program — nothing X-Ray can read"),
            )
        }
        val file = programFile(outcome)
            ?: return listOf(Finding(Severity.FAIL, "read back: no program file found at ${outcome.primary}"))
        val reading = try {
            MpcXRay.read(file)
        } catch (e: Exception) {
            return listOf(Finding(Severity.FAIL, "read back: ${file.name} won't open: ${e.message ?: e.javaClass.simpleName}"))
        }
        reading.unreadable?.let { return listOf(Finding(Severity.FAIL, "read back: ${file.name}: $it")) }

        val program = reading.programs.firstOrNull { it.trackName == kit.name }
            ?: reading.programs.firstOrNull { it.trackName == Names.sanitizeStem(kit.name) }
            ?: return listOf(
                Finding(
                    Severity.FAIL,
                    "read back: no program named \"${kit.name}\" in ${file.name} (${reading.programs.size} found)",
                ),
            )

        val out = mutableListOf<Finding>()
        out += Finding(Severity.OK, "read back: ${file.name} reads as ${reading.kindLabel}, program \"${program.trackName}\"")
        if (program.isKeygroup) out += Finding(Severity.FAIL, "program reads as keygroup — asked drum")

        val bySlot = program.pads.associateBy { dialect.kitSlot(it) }
        for (p in kit.pads.sortedBy { it.slot }) {
            val label = PadNoteMap.labelForPad(p.slot)
            val read = bySlot[p.slot]
            if (read == null) {
                out += Finding(Severity.FAIL, "$label: not in the file at all", p.slot)
                continue
            }
            val problems = mutableListOf<String>()
            if (!read.hasSample) {
                problems += "no sample"
            } else if (p.chain != null) {
                // A chain pad's zones all reference one WAV, differing by
                // slice anchors the reader doesn't surface — said as a SKIP
                // row of its own. The pad's numbers below are still checked
                // like any other pad's.
                out += Finding(Severity.SKIP, "$label: a chain pad — its slice zones aren't checked yet", p.slot)
            } else {
                val asked = expectedZones(p)
                val got = read.layers.filter { it.sampleName != null }
                    .sortedBy { it.velocityStart ?: 0 }
                    .map { Zone(stripWav(it.sampleName!!), it.velocityStart, it.velocityEnd) }
                if (got != asked) {
                    problems += "samples ${got.joinToString { it.text() }} (asked ${asked.joinToString { it.text() }})"
                }
            }
            floatCheck(problems, "level", read.level, p.level)
            floatCheck(problems, "pan", read.pan, p.pan)
            intCheck(problems, "tune", read.tuneCoarse, p.tuneCoarse)
            intCheck(problems, "fine", read.tuneFine, p.tuneFine)
            intCheck(problems, "mute group", read.muteGroup, p.muteGroup)
            floatCheck(problems, "attack", read.attack, p.attack ?: dialect.attackDefault)
            floatCheck(problems, "decay", read.decay, p.decay ?: dialect.decayDefault)
            floatCheck(problems, "cutoff", read.cutoff, p.cutoff ?: dialect.cutoffDefault)
            floatCheck(problems, "resonance", read.resonance, p.resonance ?: dialect.resonanceDefault)
            out += if (problems.isEmpty()) {
                Finding(Severity.OK, "$label reads back", p.slot)
            } else {
                Finding(Severity.FAIL, "$label: " + problems.joinToString("; "), p.slot)
            }
        }

        // A slot the kit left empty must read back empty — a sample
        // landing on the wrong pad is exactly the shift-by-one bug a
        // 0-vs-1-based mistake would produce.
        val kitSlots = kit.pads.map { it.slot }.toSet()
        val strays = bySlot.filter { (slot, read) -> slot !in kitSlots && read.hasSample }.keys.sorted()
        if (strays.isNotEmpty()) {
            out += Finding(
                Severity.FAIL,
                "slots ${strays.joinToString { PadNoteMap.labelForPad(it) }} carry a sample the kit never asked for",
            )
        }
        out += Finding(Severity.SKIP, "not checked: pad colour, one-shot, humanize — X-Ray has no name for them yet")
        return out
    }

    /** The one file X-Ray reads for this outcome: the program itself, or the expansion's `Programs/` entry. */
    private fun programFile(outcome: ExportOutcome): File? = when (outcome.format) {
        ExportFormat.EXPANSION -> {
            val xpms = File(outcome.primary, "Programs")
                .listFiles { f -> f.isFile && f.name.endsWith(".xpm", ignoreCase = true) }
                .orEmpty()
            xpms.singleOrNull() ?: xpms.firstOrNull()
        }
        else -> outcome.primary.takeIf { it.isFile }
    }

    /** The zones the writers were handed, soft first — [KitExporter.buildSlots]'s own stems and windows. */
    private fun expectedZones(p: KitPad): List<Zone> =
        if (p.velocityLayers.isEmpty()) {
            listOf(Zone(Names.sanitizeStem(p.sampleStem), 0, 127))
        } else {
            p.velocityLayers.sortedBy { it.velStart }.map { Zone(Names.sanitizeStem(it.sampleStem), it.velStart, it.velEnd) }
        }

    /** ACVS names the sample twice and X-Ray prefers the `.wav`-suffixed one; XML names the bare stem. One shape for both. */
    private fun stripWav(name: String): String =
        if (name.endsWith(".wav", ignoreCase = true)) name.dropLast(4) else name

    private fun floatCheck(problems: MutableList<String>, what: String, read: Float?, asked: Float) {
        when {
            read == null -> problems += "$what not in file (asked ${fmt(asked)})"
            abs(read - asked) > FLOAT_TOLERANCE -> problems += "$what ${fmt(read)} (asked ${fmt(asked)})"
        }
    }

    private fun intCheck(problems: MutableList<String>, what: String, read: Int?, asked: Int) {
        when {
            read == null -> problems += "$what not in file (asked $asked)"
            read != asked -> problems += "$what $read (asked $asked)"
        }
    }

    /** Six places — the writer's own precision — so a FAIL row never shows two values that print the same. */
    private fun fmt(v: Float): String = String.format(Locale.ROOT, "%.6f", v)
}
