package com.snipsnap.cli

import com.snipsnap.kit.KitMerge
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap merge <a> <b>` — kit A keeps its bank A, kit B's bank A
 * arrives on pads 17–32. A new kit folder comes out; both sources stay
 * untouched. The complement of `remix`: remix invents a bank B, merge
 * earns one from another kit.
 */
object MergeCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--out", "--name"),
            boolean = setOf("--replace"),
        )
        if (opts.positional.size != 2) {
            throw CliError("merge wants two kit folders: snipsnap merge <a> <b> [--out DIR] [--replace]")
        }
        val (aDir, bDir) = opts.positional.map {
            File(it).also { d ->
                if (!File(d, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $it")
            }
        }

        val aName = com.snipsnap.kit.KitStore.load(aDir).name
        val bName = com.snipsnap.kit.KitStore.load(bDir).name
        val name = opts["--name"] ?: com.snipsnap.kit.Names.sanitizeStem("$aName AB")
        val destDir = opts["--out"]?.let { File(it, name) }
            ?: File(aDir.parentFile ?: File("."), name)
        if (File(destDir, "kit.json").exists()) {
            throw CliError("merged kit already exists: $destDir (pick another --name/--out)")
        }

        val merged = try {
            KitMerge.merge(aDir, bDir, destDir, name, replace = opts.has("--replace"))
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "merge refused")
        }

        out.println("merged: $aName (bank A) + $bName (bank B) -> ${destDir.path} (${merged.pads.size} pads)")
        Preflight.check(merged, destDir).filter { it.severity != Severity.OK }.forEach {
            out.println("  [${it.severity}] ${it.message}")
        }
        return 0
    }
}
