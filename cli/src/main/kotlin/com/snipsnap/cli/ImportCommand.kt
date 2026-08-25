package com.snipsnap.cli

import com.snipsnap.kit.Kit
import com.snipsnap.kit.Mpc3Importer
import com.snipsnap.kit.XpnImporter
import com.snipsnap.mpc3.MpcFormat
import com.snipsnap.mpc3.MpcFormats
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap import <file>` — the receive half of kit sharing, both
 * directions: an `.xpn` archive, or a native MPC 3 drum track (`.xtd` +
 * its `_[TrackData]/` beside it). Dispatch is by content, never by
 * extension — the MPC's own rule, and ours.
 */
object ImportCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out", "--into"), boolean = setOf("--overwrite"))
        val input = opts.positional.firstOrNull()
            ?: throw CliError("import wants a file: snipsnap import <file.xpn | file.xtd>")
        if (opts.positional.size > 1) {
            throw CliError("import takes one file, got ${opts.positional.size}")
        }
        val file = File(input)
        if (!file.isFile) throw CliError("no such file: $input")

        val destRoot = File(opts["--out"] ?: "snipsnap-out")
        val overwrite = opts.has("--overwrite")

        // A .mid isn't a kit — it's a groove looking for one: --into says which.
        if (isMidi(file)) return importMidi(file, opts["--into"], out)
        if (opts["--into"] != null) {
            throw CliError("--into is for .mid grooves - this file imports as its own kit")
        }

        val (kit: Kit, directory: File, what: String) = when {
            MpcFormats.detect(file) == MpcFormat.MPC3_ACVS -> {
                val project = com.snipsnap.mpc3.Mpc3Project.read(file)
                if (project.isProject) {
                    // A whole session: every drum track becomes its own kit.
                    val r = Mpc3Importer.importProject(file, destRoot, overwrite)
                    out.println("imported ${r.kits.size} kit(s) from project ${file.name}:")
                    r.kits.forEach {
                        out.println("  + '${it.trackName}' -> ${it.directory.path} (${it.kit.pads.size} pads)")
                    }
                    r.skipped.forEach { (name, why) -> out.println("  ! skipped '$name' - $why") }
                    out.println("(edit them, or re-export: snipsnap export <kit-dir> --export ...)")
                    return 0
                }
                val r = Mpc3Importer.import(file, destRoot, overwrite)
                Triple(r.kit, r.directory, "track '${r.trackName}' (MPC 3 native)")
            }
            isZip(file) -> {
                val r = XpnImporter.import(file, destRoot, overwrite)
                Triple(r.kit, r.directory, "'${r.programEntry}' (.xpn archive)")
            }
            else -> throw CliError(
                "can't tell what ${file.name} is - import takes an .xpn archive, a native .xtd, or a whole .xpj",
            )
        }

        out.println("imported $what from ${file.name}")
        out.println("kit folder: ${directory.path} (${kit.pads.size} pads)")
        out.println("(edit it, or re-export: snipsnap export \"${directory.path}\" --export ...)")
        return 0
    }

    private fun importMidi(file: File, intoArg: String?, out: PrintStream): Int {
        val kitDir = File(
            intoArg ?: throw CliError(
                "a .mid is a groove looking for a kit: snipsnap import ${file.name} --into <kit-dir>",
            ),
        )
        if (!File(kitDir, "kit.json").isFile) {
            throw CliError("not a kit folder (no kit.json): $kitDir")
        }
        val imported = try {
            com.snipsnap.kit.MidiGroove.read(file)
        } catch (e: IllegalArgumentException) {
            throw CliError("can't read ${file.name}: ${e.message}")
        }
        val clip = imported.clip
        // The DAW beat becomes the kit's groove, standard four included.
        com.snipsnap.kit.GrooveStore.save(kitDir, com.snipsnap.kit.GrooveVariations.standard(clip))
        val bpm = imported.bpm?.let { " at %.1f bpm".format(it) } ?: ""
        out.println(
            "groove: \"${clip.name}\"$bpm - ${clip.notes.size} notes over ${clip.bars} bar(s), " +
                "now this kit's patterns (captured/tight/half/sparse)",
        )
        out.println("(native exports of ${kitDir.name} carry it from here)")
        return 0
    }

    private fun isMidi(file: File): Boolean {
        val head = ByteArray(4)
        file.inputStream().use { if (it.read(head) < 4) return false }
        return head.toString(Charsets.US_ASCII) == "MThd"
    }

    private fun isZip(file: File): Boolean {
        val head = ByteArray(2)
        file.inputStream().use { if (it.read(head) < 2) return false }
        return head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }
}
