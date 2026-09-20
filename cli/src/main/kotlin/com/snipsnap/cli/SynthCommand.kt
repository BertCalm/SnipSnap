package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Presets
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.Locale

/**
 * `snipsnap synth <ENGINE> <VOICE> [--preset N | --all] --out <dir>` — the
 * audition path. Renders factory presets straight to WAV so a sound can be
 * *heard* before it is judged; the engines' presets were authored without
 * one, which is the failure `docs/superpowers/specs/2026-09-18-synth-depth-design.md`
 * exists to correct.
 */
object SynthCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--preset", "--out"),
            boolean = setOf("--all"),
        )
        val engine = opts.positional.getOrNull(0)?.uppercase()
            ?: throw CliError("synth wants an engine and a voice: snipsnap synth TINES BELL --all --out <dir>")
        val voice = opts.positional.getOrNull(1)?.uppercase()
            ?: throw CliError("which voice? e.g. snipsnap synth TINES BELL --all --out <dir>")
        if (opts.positional.size > 2) throw CliError("synth takes an engine and a voice, nothing more")

        val presets = Presets.forVoice(engine, voice)
        if (presets.isEmpty()) throw CliError("no such engine/voice: $engine $voice")

        val dirArg = opts["--out"] ?: throw CliError("--out wants a folder to write the wavs into")
        val dir = File(dirArg)
        if (!dir.isDirectory && !dir.mkdirs()) throw CliError("could not make the output folder: $dirArg")

        val chosen: List<Patch> = when {
            opts.has("--all") -> presets
            opts["--preset"] != null -> {
                val n = opts["--preset"]!!.toIntOrNull()
                    ?: throw CliError("--preset wants a number, got '${opts["--preset"]}'")
                if (n !in 1..presets.size) throw CliError("--preset is 1..${presets.size} for $engine $voice, got $n")
                listOf(presets[n - 1])
            }
            else -> listOf(presets.first())
        }

        for ((i, patch) in chosen.withIndex()) {
            val snip = patch.render()
            val safe = patch.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "PRESET" }
            val file = File(dir, "%s_%s_%02d_%s.wav".format(Locale.ROOT, engine, voice, i + 1, safe))
            FileOutputStream(file).use { WavWriter.write(it, snip) }
            out.println("${file.name}  ${"%.2f".format(Locale.ROOT, snip.durationSeconds)}s")
        }
        out.println("${chosen.size} rendered into ${dir.path}")
        return 0
    }
}
