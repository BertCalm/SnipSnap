package com.snipsnap.cli

import com.snipsnap.audio.Retime
import com.snipsnap.audio.Tempo
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap retime <wav> --to BPM` — the other tempo move: where
 * `--fit-tempo` repitches SP-style (the revered lo-fi trade), retime
 * changes the tempo and NOT the pitch — PGHI time-scale modification,
 * attacks kept sharp. The source tempo is heard from the material, or
 * named with `--from` when the material won't say.
 */
object RetimeCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--to", "--from", "--seed", "--out"),
            boolean = setOf("--overwrite"),
        )
        val input = opts.positional.getOrNull(0)
            ?: throw CliError("retime wants a source: snipsnap retime <wav> --to BPM [--from BPM]")
        if (opts.positional.size > 1) throw CliError("retime takes one source")
        val file = File(input)
        if (!file.isFile) throw CliError("no such file: $input")
        val to = opts["--to"]?.let {
            it.toFloatOrNull()?.takeIf { t -> t > 0f && t < 1000f } ?: throw CliError("--to wants a BPM, got '$it'")
        } ?: throw CliError("retime wants a target: --to BPM")
        val seed = opts["--seed"]?.let {
            it.toLongOrNull() ?: throw CliError("--seed wants a number, got '$it'")
        } ?: 7L

        val source = WavReader.read(file)
        val from = opts["--from"]?.let {
            it.toFloatOrNull()?.takeIf { t -> t > 0f && t < 1000f } ?: throw CliError("--from wants a BPM, got '$it'")
        } ?: Tempo.estimate(source)?.takeIf { it.confidence >= 0.3f }?.bpm
            ?: throw CliError("no confident source tempo heard - name it with --from BPM")

        val ratio = from / to
        val result = try {
            Retime.retime(source, ratio, seed)
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "can't retime that")
        }
        val dir = opts["--out"]?.let { File(it).apply { mkdirs() } } ?: file.parentFile ?: File(".")
        val dest = File(dir, "${file.nameWithoutExtension} ${Math.round(to)}bpm.wav")
        if (dest.exists() && !opts.has("--overwrite")) {
            throw CliError("destination already exists: $dest (pass --overwrite to replace it)")
        }
        WavWriter.write(dest, result)
        out.println(
            "${file.name}: retimed %.0f -> %.0f bpm (x%.2f, pitch kept)".format(java.util.Locale.ROOT, from, to, ratio),
        )
        out.println("-> ${dest.path}")
        return 0
    }
}
