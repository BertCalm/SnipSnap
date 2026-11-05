package com.snipsnap.cli

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Balance
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.Names
import com.snipsnap.synth.KitFromAnything
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap kitfrom <wav> [--key Am] [--genre house] [--out <dir>] [--name <name>]`
 * — KIT FROM ANYTHING: one sound into sixteen pads in a key and a style.
 * Purist: every pad is the source, transformed. The table it prints is the
 * gate every pad was held to and what the gate said; a pad that failed its
 * gate is blended with a synth partner at half and says so, in the table
 * and in its provenance, never silence. The kit folder keeps the source
 * (`kitfrom-source.wav`) and every pad carries a recipe that rebuilds it
 * from that file and the sidecar alone; `kitfrom --check <kit-dir>`
 * rebuilds every such pad and says, pad by pad, whether the WAV on disk
 * is the rebuild bit for bit.
 */
object KitFromCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--key", "--genre", "--out", "--name"),
            boolean = setOf("--overwrite", "--check"),
        )
        if (opts.has("--check")) return check(opts, out)
        val path = opts.positional.getOrNull(0)
            ?: throw CliError("kitfrom wants a source: snipsnap kitfrom <wav> [--key Am] [--genre house|techno|boom-bap] [--out <dir>] [--name <name>]")
        if (opts.positional.size > 1) throw CliError("kitfrom takes one source")
        val file = File(path)
        if (!file.isFile) throw CliError("no such file: $path")

        val key = opts["--key"]?.let {
            try { KeySpec.parse(it) } catch (e: IllegalArgumentException) { throw CliError(e.message ?: "can't read key '$it'") }
        } ?: KeySpec(9, Scale.MINOR)
        val genre = opts["--genre"]?.let {
            KitFromAnything.Genre.parse(it) ?: throw CliError("unknown genre '$it': ${KitFromAnything.Genre.entries.joinToString(", ") { g -> g.label }}")
        } ?: KitFromAnything.Genre.HOUSE
        val name = opts["--name"] ?: (Names.sanitizeStem(file.nameWithoutExtension) + " Kit")
        if (!Names.isMpcSafe(name)) throw CliError("kit name isn't MPC-safe: '$name'")
        val dir = File(opts["--out"] ?: "snipsnap-out", name)
        if (dir.exists() && !opts.has("--overwrite")) throw CliError("$dir exists; pass --overwrite to replace it")

        // bounded: the 30 s limit is enforced by the reader before a long recording is decoded whole,
        // not by analyse after the heap has already paid for it
        val capped = WavReader.readCapped(file, maxDurationSec = KitFromAnything.MAX_SOURCE_SECONDS + 0.5f)
        if (capped.truncated) throw CliError("${file.name} is over ${KitFromAnything.MAX_SOURCE_SECONDS.toInt()} s: a recording, not a hit; chop it first")
        // the kit keeps its source, and the kit is made from THAT copy read back, so the recipe's hash
        // is the hash of the file in the folder and a rebuild later starts from the same samples
        dir.mkdirs()
        val kept = File(dir, KitFromAnything.SOURCE_FILE)
        WavWriter.write(kept, capped.snip)
        val source = WavReader.read(kept)
        val result = try {
            KitFromAnything.assemble(source, key, genre, sourceFile = KitFromAnything.SOURCE_FILE)
        } catch (e: IllegalArgumentException) {
            kept.delete()
            throw CliError(e.message ?: "can't make a kit of that")
        }
        val kit = KitAssembler.assembleArranged(name, Balance.apply(result.pads), dir, key = key, tempoBpm = genre.style.bpm)

        val report = KitFromAnything.describe(result, kit.name, key, genre)
        out.println("kit from ${file.name}: ${report.header}")
        report.lines.forEach { out.println(it) }
        out.println("wrote $dir, the source kept as ${KitFromAnything.SOURCE_FILE}; every pad carries a recipe (kitfrom --check <dir> rebuilds them)")
        return 0
    }

    /** `kitfrom --check <kit-dir>`: rebuild every KIT FROM ANYTHING pad from the sidecar and the kept source, byte for byte against the WAV on disk. */
    private fun check(opts: Options, out: PrintStream): Int {
        val path = opts.positional.getOrNull(0) ?: throw CliError("kitfrom --check wants a kit folder: snipsnap kitfrom --check <kit-dir>")
        val dir = File(path)
        if (!File(dir, "kit.json").isFile) throw CliError("no kit.json in $path")
        val checks = KitFromAnything.check(dir)
        if (checks.isEmpty()) throw CliError("no KIT FROM ANYTHING pads in ${dir.name}: nothing carries a kitFromAnything recipe")
        for (c in checks) {
            out.println("%-4s %-11s %-9s %s".format("A%02d".format(java.util.Locale.ROOT, c.pad), c.slot.name, if (c.identical) "identical" else "DIFFERS", c.detail))
        }
        val same = checks.count { it.identical }
        out.println("$same of ${checks.size} pad(s) rebuilt bit for bit from the sidecar and ${KitFromAnything.SOURCE_FILE}")
        return if (same == checks.size) 0 else 1
    }
}
