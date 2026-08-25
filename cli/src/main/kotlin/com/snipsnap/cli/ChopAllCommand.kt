package com.snipsnap.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap chop-all <folder>` — the crate-digging verb: every WAV in the
 * folder through the whole chop pipeline with shared options, one summary
 * table out. A file that fails is *named*, never fatal — an afternoon of
 * sample folders shouldn't die on file forty-one.
 *
 * The folder comes first, then any chop options, applied to every file.
 * Names come from the files themselves, so `--name` is refused.
 */
object ChopAllCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val folderArg = args.firstOrNull()
            ?: throw CliError("chop-all wants a folder first: snipsnap chop-all <folder> [chop options]")
        if (folderArg.startsWith("--")) {
            throw CliError("chop-all wants the folder before the options: snipsnap chop-all <folder> [chop options]")
        }
        val passthrough = args.drop(1)
        if (passthrough.any { it == "--name" || it.startsWith("--name=") }) {
            throw CliError("chop-all names kits after their files - drop --name")
        }
        val folder = File(folderArg)
        if (!folder.isDirectory) throw CliError("not a folder: $folderArg")

        val files = folder.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
        val wavs = files.filter { it.extension.lowercase() == "wav" }
        val skipped = files.size - wavs.size
        if (wavs.isEmpty()) throw CliError("no .wav files in $folderArg")

        val made = mutableListOf<Pair<String, ChopCommand.Result>>()
        val failed = mutableListOf<Pair<String, String>>()
        for (wav in wavs) {
            // Each file chops against its own quiet buffer; the detail only
            // surfaces when something goes wrong.
            val quiet = ByteArrayOutputStream()
            try {
                made += wav.name to ChopCommand.chop(
                    listOf(wav.path) + passthrough,
                    PrintStream(quiet, true, "UTF-8"),
                )
            } catch (e: Exception) {
                failed += wav.name to (e.message ?: e.javaClass.simpleName)
            }
        }

        out.println("chopped ${made.size} of ${wavs.size} files:")
        for ((file, r) in made) {
            val tempo = r.tempoLabel?.let { " ~$it" } ?: ""
            out.println("  %-28s -> %s (%d pads%s)".format(file, r.kit.name, r.kit.pads.size, tempo))
        }
        if (failed.isNotEmpty()) {
            out.println()
            out.println("failed (${failed.size}):")
            failed.forEach { (file, why) -> out.println("  %-28s %s".format(file, why)) }
        }
        if (skipped > 0) out.println("(skipped $skipped non-wav files)")

        return if (made.isEmpty()) 1 else 0
    }
}
