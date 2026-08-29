package com.snipsnap.cli

import com.snipsnap.audio.Stretch
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap stretch <wav>` — the slow-motion wash: paulstretch big-
 * window resynthesis with seeded random phases, so a 200 ms hit
 * becomes half a minute of evolving texture that still sounds like
 * itself. `--by N` sets the factor; `--freeze [--at sec]` holds one
 * instant of the source forever instead (defaulting to its loudest
 * moment). The result lands beside the source as a stereo twin.
 */
object StretchCommand {

    const val DEFAULT_FACTOR = 8f
    const val DEFAULT_FREEZE_SECONDS = 8f

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--by", "--at", "--seconds", "--seed", "--out"),
            boolean = setOf("--freeze", "--overwrite"),
        )
        val input = opts.positional.getOrNull(0)
            ?: throw CliError("stretch wants a source: snipsnap stretch <wav> [--by N | --freeze [--at sec]]")
        if (opts.positional.size > 1) throw CliError("stretch takes one source")
        val file = File(input)
        if (!file.isFile) throw CliError("no such file: $input")

        val freeze = opts.has("--freeze")
        if (opts["--by"] != null && freeze) throw CliError("--by and --freeze are different moves - pick one")
        if (opts["--at"] != null && !freeze) throw CliError("--at rides on --freeze - add it")
        if (opts["--seconds"] != null && !freeze) throw CliError("--seconds is for --freeze; a stretch's length is the source x the factor")
        val seed = opts["--seed"]?.let {
            it.toLongOrNull() ?: throw CliError("--seed wants a number, got '$it'")
        } ?: 7L

        val source = WavReader.read(file)
        val (result, label) = if (freeze) {
            val seconds = opts["--seconds"]?.let {
                it.toFloatOrNull()?.takeIf { s -> s > 0f && s <= Stretch.MAX_OUT_SEC }
                    ?: throw CliError("--seconds wants (0, ${Stretch.MAX_OUT_SEC.toInt()}], got '$it'")
            } ?: DEFAULT_FREEZE_SECONDS
            val at = opts["--at"]?.let {
                it.toFloatOrNull() ?: throw CliError("--at wants seconds, got '$it'")
            }
            val chosen = at ?: Stretch.loudestSec(source)
            val frozen = try {
                Stretch.freeze(source, seconds, seed, at)
            } catch (e: IllegalArgumentException) {
                throw CliError(e.message ?: "can't freeze that")
            }
            frozen to "frozen at %.2fs for %.0fs".format(chosen, seconds)
        } else {
            val factor = opts["--by"]?.let {
                it.toFloatOrNull()?.takeIf { f -> f in Stretch.MIN_FACTOR..Stretch.MAX_FACTOR }
                    ?: throw CliError("--by wants ${Stretch.MIN_FACTOR.toInt()}..${Stretch.MAX_FACTOR.toInt()}, got '$it'")
            } ?: DEFAULT_FACTOR
            val stretched = try {
                Stretch.stretch(source, factor, seed)
            } catch (e: IllegalArgumentException) {
                throw CliError(e.message ?: "can't stretch that")
            }
            stretched to "stretched x%.0f: %.2fs -> %.1fs".format(factor, source.durationSeconds, stretched.durationSeconds)
        }

        val suffix = if (freeze) "Frozen" else "Stretched"
        val dir = opts["--out"]?.let { File(it).apply { mkdirs() } } ?: file.parentFile ?: File(".")
        val dest = File(dir, "${file.nameWithoutExtension} $suffix.wav")
        if (dest.exists() && !opts.has("--overwrite")) {
            throw CliError("destination already exists: $dest (pass --overwrite to replace it)")
        }
        WavWriter.write(dest, result)
        out.println("${file.name}: $label (seed $seed)")
        out.println("-> ${dest.path}")
        return 0
    }
}
