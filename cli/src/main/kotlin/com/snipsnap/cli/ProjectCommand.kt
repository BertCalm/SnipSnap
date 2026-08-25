package com.snipsnap.cli

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.kit.SessionBuilder
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap project <kit-dir>...` — whole sessions on the card: kits on
 * tracks with their grooves, an optional multisampled instrument from
 * pitched notes, mixer wired, one `.xpj`.
 */
object ProjectCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--name", "--out", "--keys", "--keys-name"),
            boolean = setOf("--overwrite", "--loop", "--mixdown"),
        )
        if (opts.positional.isEmpty()) {
            throw CliError(
                "project wants kit folders: snipsnap project <kit-dir>... " +
                    "[--keys a.wav,b.wav] [--name NAME]",
            )
        }
        val kitDirs = opts.positional.map { path ->
            val dir = File(path)
            if (!File(dir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $path")
            dir
        }

        val instruments = opts["--keys"]?.let { list ->
            val files = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (files.isEmpty()) throw CliError("--keys wants note files, comma-separated")
            val snips = files.map { path ->
                val f = File(path)
                if (!f.isFile) throw CliError("no such file: $path")
                var snip = WavReader.read(f)
                if (snip.sampleRate != ChopCommand.TARGET_RATE) {
                    snip = Resampler.resample(snip, ChopCommand.TARGET_RATE)
                }
                path to Cleanup.process(snip)
            }
            val keysName = opts["--keys-name"] ?: "Session Keys"
            if (!Names.isMpcSafe(keysName)) throw CliError("instrument name isn't MPC-safe: '$keysName'")
            val multi = try {
                OneNote.multiProgram(keysName, snips, sustainLoop = opts.has("--loop"))
            } catch (e: IllegalArgumentException) {
                throw CliError(e.message ?: "couldn't build the instrument")
            }
            listOf(multi.program to multi.samples)
        } ?: emptyList()

        val name = opts["--name"] ?: "SnipSnap Session"
        if (!Names.isMpcSafe(name)) throw CliError("session name isn't MPC-safe: '$name'")
        val cardDir = File(opts["--out"] ?: "snipsnap-out", "card")

        val result = SessionBuilder.build(name, kitDirs, cardDir, instruments, opts.has("--overwrite"))
        out.println("session: ${result.xpj.path} (+ ${result.dataDir.name}/)")
        result.kitTracks.forEach { out.println("  kit track:        $it") }
        result.instrumentTracks.forEach { out.println("  instrument track: $it") }
        result.tempoBpm?.let { out.println("  tempo: ${it.toInt()} bpm (from the first kit that remembered one)") }
        if (opts.has("--mixdown")) {
            // The whole beat as audio, beside the project it came from.
            val mix = com.snipsnap.kit.SessionMixdown.render(kitDirs, result.tempoBpm)
            val wav = File(cardDir, "$name.wav")
            com.snipsnap.audio.WavWriter.write(wav, mix)
            out.println("  mixdown: ${wav.path} (%.1fs - the session playing itself)".format(mix.durationSeconds))
        }
        out.println("(copy the .xpj and its _[ProjectData]/ side by side onto the card, then open it)")
        return 0
    }
}
