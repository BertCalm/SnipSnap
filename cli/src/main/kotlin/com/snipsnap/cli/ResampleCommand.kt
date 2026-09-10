package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.shell.Wear
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap resample <kit-dir>` — the most MPC gesture there is: bounce
 * what you have and chop it again. The kit renders its own groove —
 * treatments and eras already live in its files, wear applies at render
 * time — and that bounce re-enters the chop pipeline as source
 * material. Generation loss becomes a creative tool; the source kit is
 * never touched, and every new pad's provenance counts the generation.
 */
object ResampleCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--name", "--out", "--slices", "--wear"),
            boolean = setOf("--overwrite", "--no-wear"),
        )
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("resample wants a kit: snipsnap resample <kit-dir> [--name NAME]")
        if (opts.positional.size > 1) throw CliError("resample takes one kit folder")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")

        val kit = KitStore.load(kitDir)
        if (kit.pads.isEmpty()) throw CliError("an empty kit has nothing to bounce")

        val wearOverride = opts["--wear"]?.let {
            it.toFloatOrNull()?.takeIf { v ->
                v > 0f && v <= com.snipsnap.synth.TapeWear.MAX_OVERRIDE_W
            } ?: throw CliError(
                "--wear wants a number in (0, ${com.snipsnap.synth.TapeWear.MAX_OVERRIDE_W}], got '$it'",
            )
        }
        if (opts.has("--no-wear") && wearOverride != null) {
            throw CliError("--wear and --no-wear contradict each other")
        }
        val wearW = if (opts.has("--no-wear")) null else wearOverride ?: Wear.earnedW(kit)

        // The generation counter rides pad provenance: this bounce is one
        // more pass of the machine than the deepest pad it came from.
        val generation = (kit.pads.mapNotNull { it.source["generation"]?.toIntOrNull() }.maxOrNull() ?: 1) + 1
        val base = kit.name.replace(Regex(" Gen \\d+$"), "")
        val name = opts["--name"] ?: "$base Gen $generation"
        if (!Names.isMpcSafe(name)) throw CliError("kit name isn't MPC-safe: '$name'")
        val outDir = opts["--out"] ?: "snipsnap-out"

        // The bounce: the kit playing its own groove, worn per its ledger.
        val render = Wear.render(kit, KitPreview.render(kit, kitDir), wearW)
        out.println(
            "bounced: ${kit.name} playing itself (%.1fs%s)".format(java.util.Locale.ROOT, 
                render.durationSeconds,
                if (wearW != null) " at %.1f%% worn".format(java.util.Locale.ROOT, wearW * 100) else "",
            ),
        )

        val tmpDir = java.nio.file.Files.createTempDirectory("snipsnap-resample").toFile()
        try {
            val tmp = File(tmpDir, "${kit.name}.wav")
            WavWriter.write(tmp, render)
            val chopArgs = mutableListOf(tmp.path, "--name", name, "--out", outDir)
            opts["--slices"]?.let { chopArgs += listOf("--slices", it) }
            if (opts.has("--overwrite")) chopArgs += "--overwrite"
            val code = ChopCommand.run(chopArgs, out)
            if (code != 0) return code

            // Stamp the lineage on every pad of the new kit.
            val newDir = File(outDir, name)
            val chopped = KitStore.load(newDir)
            KitStore.save(
                chopped.copy(
                    pads = chopped.pads.map { p ->
                        p.copy(
                            source = p.source + mapOf(
                                "resampledFrom" to kit.name,
                                "generation" to generation.toString(),
                            ),
                        )
                    },
                ),
                newDir,
            )
            out.println("generation $generation from ${kit.name} - the source kit is untouched")
            return 0
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
