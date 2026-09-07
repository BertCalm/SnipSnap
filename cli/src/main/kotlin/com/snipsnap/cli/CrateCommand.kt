package com.snipsnap.cli

import com.snipsnap.audio.DrumClass
import com.snipsnap.shell.Crate
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap crate <root>` — your whole output as a collection: an index
 * of every kit pad (cached, so a second pass extracts nothing), plus
 * duplicates, best-of-a-class picks, and a built best-of kit.
 */
object CrateCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--pick", "--top", "--build", "--out"),
            boolean = setOf("--dupes"),
        )
        val rootArg = opts.positional.getOrNull(0)
            ?: throw CliError("crate wants a library root: snipsnap crate <root> [--dupes] [--pick KICK] [--build NAME]")
        if (opts.positional.size > 1) throw CliError("crate takes one folder")
        val root = File(rootArg)
        if (!root.isDirectory) throw CliError("no such folder: $rootArg")

        val index = Crate.index(root)
        if (index.entries.isEmpty()) throw CliError("no kits under ${root.path} - a crate is a folder of kit folders")

        val kits = index.entries.map { it.kitDir }.distinct().size
        out.println(
            "crate: $kits kit(s), ${index.entries.size} pads - " +
                "${index.extracted} measured, ${index.fromCache} from the index",
        )
        val byClass = index.entries.groupBy { it.storedClass }.entries
            .sortedByDescending { it.value.size }
        out.println(
            "  " + byClass.joinToString("  ") { (dc, es) -> "${dc.name.lowercase()}:${es.size}" },
        )

        opts["--pick"]?.let { word ->
            val dc = DrumClass.entries.firstOrNull { it.name.equals(word, ignoreCase = true) }
                ?: throw CliError(
                    "unknown class '$word' - one of: " +
                        DrumClass.entries.filter { it != DrumClass.UNKNOWN }.joinToString(",") { it.name.lowercase() },
                )
            val top = opts.int("--top") ?: 8
            if (top < 1) throw CliError("--top wants at least 1")
            val picks = Crate.pick(index, dc, top)
            if (picks.isEmpty()) {
                out.println("no ${dc.name.lowercase()} in the crate")
            } else {
                out.println("best ${dc.name.lowercase()}${if (picks.size > 1) "s" else ""}:")
                picks.forEachIndexed { i, e ->
                    out.println(
                        "  %d. %-24s %s %-18s confidence %.2f".format(
                            i + 1, e.kitName, e.label, "(${e.padName})", e.confidence,
                        ),
                    )
                }
            }
        }

        if (opts.has("--dupes")) {
            val dupes = Crate.dupes(index)
            if (dupes.isEmpty()) {
                out.println("no duplicates - every pad in the crate is its own sound")
            } else {
                out.println("${dupes.size} duplicate pair(s):")
                dupes.forEach { (a, b, d) ->
                    out.println("  %s %s  ==  %s %s  (distance %.4f)".format(a.kitName, a.label, b.kitName, b.label, d))
                }
            }
        }

        opts["--build"]?.let { name ->
            if (!com.snipsnap.kit.Names.isMpcSafe(name)) throw CliError("kit name isn't MPC-safe: '$name'")
            val destRoot = File(opts["--out"] ?: "snipsnap-out")
            val destDir = File(destRoot, name)
            if (File(destDir, "kit.json").isFile) throw CliError("kit already exists: ${destDir.path}")
            val model = Crate.build(root, index, name, destDir)
            out.println("built: ${destDir.path} - ${model.kit.pads.size} pads, the strongest of every class you have")
            model.kit.pads.sortedBy { it.slot }.forEach {
                out.println("  ${it.displayName} (${it.drumClass.name.lowercase()})")
            }
        }
        return 0
    }
}
