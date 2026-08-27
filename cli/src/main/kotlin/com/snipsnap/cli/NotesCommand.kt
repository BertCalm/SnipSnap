package com.snipsnap.cli

import com.snipsnap.kit.KitStore
import com.snipsnap.shell.LinerNotes
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap notes <kit-dir>` — the kit's liner notes: its story as
 * prose, from what it already tracks. Printed, and written beside the
 * kit as `liner-notes.txt` (or into `--out`).
 */
object NotesCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out"), boolean = emptySet())
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("notes wants a kit: snipsnap notes <kit-dir> [--out DIR]")
        if (opts.positional.size > 1) throw CliError("notes takes one kit folder")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")

        val kit = KitStore.load(kitDir)
        val text = LinerNotes.render(kit, kitDir)
        out.print(text)

        val destDir = opts["--out"]?.let { File(it).apply { mkdirs() } } ?: kitDir
        val file = LinerNotes.writeTo(kit, kitDir, File(destDir, LinerNotes.FILE_NAME))
        out.println("-> ${file.path}")
        return 0
    }
}
