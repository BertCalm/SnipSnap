package com.snipsnap.cli

import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.MidiGroove
import com.snipsnap.kit.PocketStore
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap feel <kit-dir> --from <donor>` — groove transfer, the MPC's
 * own legendary feature: extract the timing-and-velocity pocket from a
 * donor (another kit's groove, or any `.mid`) and rewrite this kit's
 * patterns with it. Your notes, that drummer's feel.
 */
object FeelCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--from", "--save"), boolean = emptySet())
        val dirArg = opts.positional.firstOrNull()
            ?: throw CliError("feel wants a kit folder: snipsnap feel <kit-dir> --from <kit-dir, beat.mid or x.pocket>")
        if (opts.positional.size > 1) {
            throw CliError("feel takes one kit folder, got ${opts.positional.size}")
        }
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) {
            throw CliError("not a kit folder (no kit.json): $dirArg")
        }
        if (opts["--from"] != null && opts["--save"] != null) {
            throw CliError("--save writes this kit's own pocket; --from applies someone else's - pick one")
        }

        // --save: this kit's own feel, bottled as a tradeable .pocket file.
        opts["--save"]?.let { save ->
            val base = GrooveStore.load(kitDir).firstOrNull()
                ?: throw CliError("this kit has no groove to bottle - chop with --groove, or import a .mid")
            val template = GrooveFeel.extract(base)
            val dest = File(if (save.endsWith(".${PocketStore.EXTENSION}")) save else "$save.${PocketStore.EXTENSION}")
            PocketStore.save(PocketStore.Pocket(base.name, template), dest)
            val covered = template.offsets.count { it != null }
            out.println(
                "pocket saved: ${dest.path} (\"${base.name}\", " +
                    "$covered of ${GrooveFeel.POSITIONS} positions)",
            )
            return 0
        }

        val from = opts["--from"]
            ?: throw CliError("say where the feel comes from: --from <kit-dir, beat.mid or x.pocket>")

        val (template, donorLabel) = donorTemplate(File(from))
        val base = GrooveStore.load(kitDir).firstOrNull()
            ?: throw CliError("this kit has no groove to re-feel - chop with --groove, or import a .mid")

        val felt = GrooveFeel.apply(template, base)
        GrooveStore.save(kitDir, GrooveVariations.standard(felt))

        val covered = template.offsets.count { it != null }
        out.println(
            "feel: \"$donorLabel\" pocket ($covered of ${GrooveFeel.POSITIONS} positions) " +
                "laid onto \"${base.name}\"",
        )
        out.println("patterns rewritten: \"${felt.name}\" and its tight/half/sparse follow the donor now")
        return 0
    }

    /** A donor is a kit's groove, a `.mid` — or a bottled `.pocket`, applied as-is. */
    private fun donorTemplate(src: File): Pair<GrooveFeel.Template, String> =
        if (src.isFile && src.name.endsWith(".${PocketStore.EXTENSION}")) {
            val pocket = try {
                PocketStore.read(src)
            } catch (e: Exception) {
                throw CliError("can't read ${src.name} as a pocket: ${e.message}")
            }
            pocket.template to pocket.name
        } else {
            val (clip, label) = donorClip(src)
            GrooveFeel.extract(clip) to label
        }

    private fun donorClip(src: File): Pair<com.snipsnap.mpc3.Mpc3Clip, String> = when {
        File(src, "kit.json").isFile -> {
            val clip = GrooveStore.load(src).firstOrNull()
                ?: throw CliError("the donor kit has no groove: $src")
            clip to clip.name
        }
        src.isFile -> {
            val imported = try {
                MidiGroove.read(src)
            } catch (e: IllegalArgumentException) {
                throw CliError("can't read ${src.name} as a donor: ${e.message}")
            }
            imported.clip to imported.clip.name
        }
        else -> throw CliError("--from wants a kit folder or a .mid file, got: $src")
    }
}
