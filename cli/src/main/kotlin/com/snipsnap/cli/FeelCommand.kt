package com.snipsnap.cli

import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.MidiGroove
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
        val opts = Options.parse(args, valued = setOf("--from"), boolean = emptySet())
        val dirArg = opts.positional.firstOrNull()
            ?: throw CliError("feel wants a kit folder: snipsnap feel <kit-dir> --from <kit-dir or beat.mid>")
        if (opts.positional.size > 1) {
            throw CliError("feel takes one kit folder, got ${opts.positional.size}")
        }
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) {
            throw CliError("not a kit folder (no kit.json): $dirArg")
        }
        val from = opts["--from"]
            ?: throw CliError("say where the feel comes from: --from <kit-dir or beat.mid>")

        val (donor, donorLabel) = donorClip(File(from))
        val base = GrooveStore.load(kitDir).firstOrNull()
            ?: throw CliError("this kit has no groove to re-feel - chop with --groove, or import a .mid")

        val template = GrooveFeel.extract(donor)
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
