package com.snipsnap.cli

import com.snipsnap.shell.Breed
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap breed <kit-a> <kit-b>` — BREEDING: two kits' recipes crossed
 * into a child kit, every macro A's, B's or the average by a seeded
 * coin, every child pad audited to classify as its parent does. `--out`
 * the folder (default beside kit A, named `<A>_x_<B>`), `--name` the
 * kit's name, `--seed` the coin.
 */
object BreedCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out", "--name", "--seed"), boolean = emptySet())
        val aArg = opts.positional.getOrNull(0)
        val bArg = opts.positional.getOrNull(1)
        if (aArg == null || bArg == null) {
            throw CliError("breed wants two kits: snipsnap breed <kit-a> <kit-b> [--out DIR] [--name N] [--seed N]")
        }
        if (opts.positional.size > 2) throw CliError("breed takes two kits, nothing more")
        val aDir = File(aArg)
        val bDir = File(bArg)
        for (d in listOf(aDir, bDir)) {
            if (!File(d, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): ${d.path}")
        }
        val seed = opts.int("--seed") ?: 0
        val name = opts["--name"] ?: "${aDir.name}_x_${bDir.name}"
        val dest = opts["--out"]?.let { File(it) } ?: File(aDir.absoluteFile.parentFile ?: File("."), name)

        val report = try {
            Breed.breed(aDir, bDir, dest, name, seed)
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "breeding refused")
        }
        out.println("bred '${report.kit.name}' from ${aDir.name} x ${bDir.name} (seed $seed) into ${dest.path}")
        out.println(
            "  ${report.crossed.size} pads crossed, ${report.kept.size} kept as ${aDir.name}'s own" +
                if (report.audited.isNotEmpty()) ", ${report.audited.size} sent back by the audit (the child changed class)" else "",
        )
        out.println("  every child pad classifies as its parent does; both parents untouched (reroll: --seed ${seed + 1})")
        return 0
    }
}
