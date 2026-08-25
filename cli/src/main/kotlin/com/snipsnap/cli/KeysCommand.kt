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
        val input = opts.positional.firstOrNull()
            ?: throw CliError("keys wants a pitched note: snipsnap keys <note.wav>")
        if (opts.positional.size > 1) throw CliError("keys takes one input file, got ${opts.positional.size}")
        val file = File(input)
        if (!file.isFile) throw CliError("no such file: $input")

        var snip = WavReader.read(file)
        if (snip.sampleRate != ChopCommand.TARGET_RATE) {
            snip = Resampler.resample(snip, ChopCommand.TARGET_RATE)
        }
        snip = Cleanup.process(snip)

        val name = opts["--name"] ?: Names.sanitizeStem(file.nameWithoutExtension)
        if (!Names.isMpcSafe(name)) throw CliError("instrument name isn't MPC-safe: '$name'")
        val cardDir = File(opts["--out"] ?: "snipsnap-out", "card")

        val result = OneNote.export(name, snip, cardDir, opts.has("--overwrite"))
        out.println(
            "detected %s (%.1f Hz, confidence %.2f) - rooted there, playable 0..127".format(
                result.rootName, result.detectedHz, result.confidence,
            ),
        )
        out.println("instrument: ${File(cardDir, "$name.xty").path} (+ ${name}_[TrackData]/ with the .xpm twin)")
        out.println("(copy both onto the card; MPC 3 opens the .xty, MPC 2 browses the folder)")
        return 0
    }
}
