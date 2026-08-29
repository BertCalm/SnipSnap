package com.snipsnap.cli

import com.snipsnap.audio.CaptureDoctor
import com.snipsnap.audio.WavReader
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap checkup [dir]` — the reference scorecard: every capture
 * dropped into `reference/` (or the named dir) gets one measured card
 * — hum, flat-tops, clicks and dropouts, the noise floor, the room's
 * tail knee, and how much of the signal WPE could predict away —
 * every number from the same detectors the treatments trust.
 * Read-only: it measures, names, and writes nothing, so the day the
 * phone-mic capture of real hardware in a real room lands, it becomes
 * an instant verdict on the whole Capture Doctor.
 */
object CheckupCommand {

    const val DEFAULT_DIR = "reference"

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = emptySet(), boolean = emptySet())
        if (opts.positional.size > 1) throw CliError("checkup takes one directory")
        val dir = File(opts.positional.getOrNull(0) ?: DEFAULT_DIR)
        val wavs = dir.listFiles { f: File -> f.isFile && f.extension.lowercase() == "wav" }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (wavs.isEmpty()) {
            out.println(
                "${dir.path} holds no captures yet - drop a phone-mic recording of real " +
                    "hardware in a real room there, and every detector scores it here.",
            )
            return 0
        }

        for (file in wavs) {
            val snip = try {
                WavReader.read(file)
            } catch (e: Exception) {
                out.println("${file.name}: couldn't read it (${e.message}) - skipped")
                continue
            }
            out.println("${file.name}: %.2fs, %d ch @ %d Hz".format(snip.durationSeconds, snip.channels, snip.sampleRate))

            val hum = CaptureDoctor.detectHum(snip)
            out.println(
                "  hum:      " + (hum?.let { "%.0f Hz, %d harmonic(s), %.0f dBFS".format(it.hz, it.harmonics, it.levelDb) }
                    ?: "none stands out"),
            )
            val clip = CaptureDoctor.detectClipping(snip)
            out.println(
                "  clipping: " + (clip?.let { "%.1f%% of samples pinned at %.2f (%d runs)".format(it.fraction * 100, it.ceiling, it.runs) }
                    ?: "no flat tops"),
            )
            val repair = runCatching { CaptureDoctor.repairClicks(snip) }
            out.println(
                "  clicks:   " + repair.fold(
                    { "${it.clicks} click(s), ${it.dropouts} dropout(s)" },
                    { e -> e.message ?: "refused" },
                ),
            )
            val floor = CaptureDoctor.measureFloor(snip)
            out.println(
                "  floor:    " + (floor?.let {
                    "%.0f dBFS%s".format(it, if (it < CaptureDoctor.CLEAN_FLOOR_DB) " - clean" else " - the gate would act")
                } ?: "too short to say"),
            )
            val knee = CaptureDoctor.trimRoomTail(snip)
            out.println(
                "  room:     " + (knee?.let {
                    "tail hands off %.0f ms after the hit (%.0f dB/s -> %.0f dB/s)"
                        .format(it.kneeSec * 1000, it.hitSlopeDbPerSec, it.tailSlopeDbPerSec)
                } ?: "no knee - single slope or not a one-shot"),
            )
            // How much of the signal is linearly predictable room: run
            // WPE and report the energy it would take away. A dry
            // capture loses almost nothing.
            val dv = CaptureDoctor.deverb(snip)
            fun energy(s: FloatArray): Double = s.sumOf { (it * it).toDouble() }
            val eIn = energy(snip.samples).coerceAtLeast(1e-12)
            val removed = ((eIn - energy(dv.samples)) / eIn * 100).coerceIn(-100.0, 100.0)
            out.println("  wpe:      %.1f%% of the energy reads as predictable room".format(removed))
        }
        out.println("(measurements only - nothing was written; `clean` applies the treatments)")
        return 0
    }
}
