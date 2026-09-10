package com.snipsnap.cli

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Similar
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap similar <target> <library-root>` — "find me another snare
 * like this one": nearest-neighbour over the classifier's own features,
 * across every kit under the library root. The target is a `.wav`, or a
 * kit folder plus `--pad A02`. Matches come back named well enough to
 * go grab them.
 */
object SimilarCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--pad", "--top"), boolean = emptySet())
        if (opts.positional.size != 2) {
            throw CliError("similar wants a target and a library: snipsnap similar <wav-or-kit-dir> <library-root> [--pad A02]")
        }
        val top = opts.int("--top") ?: 5
        if (top < 1) throw CliError("--top wants at least 1")

        // The target: a .wav directly, or one pad of a kit.
        val targetArg = File(opts.positional[0])
        val targetFile: File
        val targetLabel: String
        if (targetArg.isFile && targetArg.extension.lowercase() == "wav") {
            if (opts["--pad"] != null) throw CliError("--pad goes with a kit folder, not a .wav")
            targetFile = targetArg
            targetLabel = targetArg.name
        } else if (targetArg.isDirectory && File(targetArg, "kit.json").isFile) {
            val padSpec = opts["--pad"] ?: throw CliError("which pad? add --pad A02")
            val slot = parsePad(padSpec)
            val kit = KitStore.load(targetArg)
            val pad = kit.pad(slot) ?: throw CliError("no pad on $padSpec in ${kit.name}")
            targetFile = File(targetArg, pad.sampleFile)
            targetLabel = "${kit.name} $padSpec (${pad.displayName})"
        } else {
            throw CliError("target must be a .wav or a kit folder: ${opts.positional[0]}")
        }
        if (!targetFile.isFile) throw CliError("no such file: $targetFile")

        val libraryRoot = File(opts.positional[1])
        if (!libraryRoot.isDirectory) throw CliError("no such library folder: ${opts.positional[1]}")

        // Every pad of every kit under the root, features extracted once.
        data class Candidate(val label: String, val drumClass: String, val file: File)
        val kitDirs = libraryRoot.walkTopDown()
            .filter { it.isDirectory && File(it, "kit.json").isFile }
            .sortedBy { it.path.lowercase() }
            .toList()
        if (kitDirs.isEmpty()) throw CliError("no kits under ${libraryRoot.path} - a library is a folder of kit folders")
        val candidates = mutableListOf<Pair<Candidate, com.snipsnap.audio.Features>>()
        for (dir in kitDirs) {
            val kit = try {
                KitStore.load(dir)
            } catch (e: Exception) {
                out.println("${dir.name}: couldn't read it (${e.message}) - skipped")
                continue
            }
            for (pad in kit.pads.sortedBy { it.slot }) {
                val f = File(dir, pad.sampleFile)
                if (!f.isFile) continue
                if (f.canonicalPath == targetFile.canonicalPath) continue // the target itself isn't a match
                candidates += Candidate(
                    "${kit.name} ${padLabel(pad.slot)} (${pad.displayName})",
                    pad.drumClass.name,
                    f,
                ) to FeatureExtractor.extract(WavReader.read(f))
            }
        }
        if (candidates.isEmpty()) throw CliError("the library has no samples to compare against")

        val target = FeatureExtractor.extract(WavReader.read(targetFile))
        val ranked = Similar.rank(target, candidates)

        out.println("more like $targetLabel:")
        ranked.take(top).forEachIndexed { i, (c, d) ->
            out.println("  %d. %-40s %-11s distance %.3f".format(java.util.Locale.ROOT, i + 1, c.label, c.drumClass, d))
        }
        return 0
    }

    private fun padLabel(slot: Int): String = "%s%02d".format(java.util.Locale.ROOT, 'A' + (slot - 1) / 16, (slot - 1) % 16 + 1)

    /** "A03" → 3, "B01" → 17, "7" → 7. */
    private fun parsePad(arg: String): Int {
        arg.toIntOrNull()?.let { return it }
        val m = Regex("^([A-Ha-h])(\\d{1,2})$").matchEntire(arg.trim())
            ?: throw CliError("can't read pad '$arg' - try A03, B01, or a slot number")
        val bank = m.groupValues[1].uppercase()[0] - 'A'
        val n = m.groupValues[2].toInt()
        if (n !in 1..16) throw CliError("pad number in a bank is 1..16, got $n")
        return bank * 16 + n
    }
}
