package com.snipsnap.cli

import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.synth.TapeWear
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap wear <kit-dir>` — the wear ledger: the kit as a living tape.
 * Opted in, plays and saves accrue mileage and the kit's *renders* age by
 * `w = 1 − exp(−mileage/K)` — patina physics, capped at well-worn, never
 * ruined. The audio on disk is never touched, which is why `--reset` is a
 * genuinely new tape.
 */
object WearCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--plays", "--k"),
            boolean = setOf("--on", "--off", "--reset"),
        )
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("wear wants a kit: snipsnap wear <kit-dir> [--on] [--plays N]")
        if (opts.positional.size > 1) throw CliError("wear takes one kit folder")
        if (opts.has("--on") && opts.has("--off")) throw CliError("--on and --off contradict each other")
        if (opts["--k"] != null && !opts.has("--on")) throw CliError("--k rides --on: wear --on --k 500")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")

        val model = KitBuilderModel.open(kitDir)
        var changed = false

        if (opts.has("--on")) {
            val k = opts["--k"]?.let {
                it.toDoubleOrNull()?.takeIf { v -> v > 0 && v.isFinite() }
                    ?: throw CliError("--k wants a positive number of plays, got '$it'")
            }
            model.enableWear(k)
            changed = true
        }
        if (opts.has("--off")) {
            if (model.kit.wear == null) throw CliError("this kit has no wear ledger to switch off")
            model.disableWear()
            changed = true
        }
        if (opts.has("--reset")) {
            if (model.kit.wear == null) throw CliError("this kit has no wear ledger to reset")
            model.resetWear()
            changed = true
        }
        opts.int("--plays")?.let { n ->
            if (n <= 0) throw CliError("--plays wants a positive count, got $n")
            if (model.kit.wear?.enabled != true) {
                throw CliError("the tape isn't aging - switch it on first: wear ${kitDir.path} --on")
            }
            model.recordPlays(n)
            changed = true
        }

        // Managing the deck isn't playing the tape: no accrual on this save.
        if (changed) model.save(accrueWear = false)

        val wear = model.kit.wear
        if (wear == null) {
            out.println("${model.name}: not aging - this kit sounds new forever")
            out.println("  switch it on: snipsnap wear ${kitDir.path} --on")
        } else {
            out.println(
                "${model.name}: %.0f mile(s) on the tape - wear %.1f%%, aging %s (K = %.0f)".format(
                    wear.mileage, wear.w * 100, if (wear.enabled) "on" else "off", wear.k,
                ),
            )
            out.println(
                ("  caps at full wear: flutter <=+/-%.0f cents, hiss <=%.0f dBFS, " +
                    "shelf >=%.0f kHz, dropouts rare and never on a hit").format(
                    TapeWear.MAX_FLUTTER_CENTS, TapeWear.MAX_HISS_DB, TapeWear.MIN_SHELF_HZ / 1000,
                ),
            )
            out.println("  the audio on disk stays pristine - wear renders at preview/export time")
        }
        return 0
    }
}
