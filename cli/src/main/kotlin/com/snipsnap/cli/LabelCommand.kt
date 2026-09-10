package com.snipsnap.cli

import com.snipsnap.shell.Label
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap label <root> [--init NAME [--prefix XYZ]]` — run your own
 * imprint. `--init` starts a label at the crate root; every run (init
 * included) catalogs any new kits with the next numbers in name order,
 * never moving an existing one, and rewrites `catalog.txt`. The J-card
 * spine and liner notes wear the number once the kit's crate is
 * labeled.
 */
object LabelCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--init", "--prefix"), boolean = emptySet())
        val rootArg = opts.positional.getOrNull(0)
            ?: throw CliError("label wants a crate root: snipsnap label <root> [--init NAME [--prefix XYZ]]")
        if (opts.positional.size > 1) throw CliError("label takes one crate root")
        val root = File(rootArg)
        if (!root.isDirectory) throw CliError("no such crate root: $rootArg")
        if (opts["--prefix"] != null && opts["--init"] == null) {
            throw CliError("--prefix rides on --init - the imprint's prefix is set once")
        }

        val info = when (val name = opts["--init"]) {
            null -> {
                Label.load(root) ?: throw CliError(
                    "not a label yet: $rootArg - start the imprint with --init NAME",
                )
                Label.assign(root)
            }
            else -> {
                val prefix = opts["--prefix"]?.let {
                    val up = it.uppercase()
                    if (!Regex("^[A-Z0-9]{2,5}$").matches(up)) {
                        throw CliError("--prefix wants 2..5 letters/digits, got '$it'")
                    }
                    up
                }
                try {
                    Label.init(root, name, prefix)
                } catch (e: java.io.IOException) {
                    throw CliError(e.message ?: "label init refused")
                }
            }
        }

        out.println("${info.name} [${info.prefix}] - ${info.catalog.size} release(s) in the catalog")
        for ((kitName, n) in info.catalog.entries.sortedBy { it.value }) {
            out.println("  %s-%03d  %s".format(java.util.Locale.ROOT, info.prefix, n, kitName))
        }
        out.println("ledger: ${File(root, Label.CATALOG_NAME).path}")
        return 0
    }
}
