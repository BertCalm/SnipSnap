package com.snipsnap.cli

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap keys <note.wav>` — one captured note becomes a playable
 * chromatic instrument, both generations, ready for the card.
 */
object KeysCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--name", "--out"), boolean = setOf("--overwrite"))
        if (opts.positional.isEmpty()) {
            throw CliError("keys wants pitched notes: snipsnap keys <note.wav> [more.wav ...]")
        }

        val snips = opts.positional.map { path ->
            val file = File(path)
            if (!file.isFile) throw CliError("no such file: $path")
            var snip = WavReader.read(file)
            if (snip.sampleRate != ChopCommand.TARGET_RATE) {
                snip = Resampler.resample(snip, ChopCommand.TARGET_RATE)
            }
            path to Cleanup.process(snip)
        }

        val name = opts["--name"] ?: Names.sanitizeStem(File(opts.positional.first()).nameWithoutExtension)
        if (!Names.isMpcSafe(name)) throw CliError("instrument name isn't MPC-safe: '$name'")
        val cardDir = File(opts["--out"] ?: "snipsnap-out", "card")

        val result = OneNote.multiExport(name, snips, cardDir, opts.has("--overwrite"))
        if (result.zones.size == 1) {
            val z = result.zones.single()
            out.println(
                "detected %s (%.1f Hz, confidence %.2f) - rooted there, playable 0..127".format(
                    z.rootName, z.detectedHz, z.confidence,
                ),
            )
        } else {
            out.println("${result.zones.size} zones, tiled at the midpoints:")
            result.program.keygroups.forEachIndexed { i, kg ->
                val z = result.zones[i]
                out.println(
                    "  %-4s root, keys %3d..%3d  (%.1f Hz, conf %.2f, %s)".format(
                        z.rootName, kg.lowNote, kg.highNote, z.detectedHz, z.confidence, File(z.label).name,
                    ),
                )
            }
        }
        out.println("instrument: ${File(cardDir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("(copy both onto the card; MPC 3 opens the .xty, MPC 2 browses the folder)")
        return 0
    }
}
