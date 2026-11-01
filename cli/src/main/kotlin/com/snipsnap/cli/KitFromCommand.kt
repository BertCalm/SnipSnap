package com.snipsnap.cli

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.audio.WavReader
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
 * gate is still in the kit and says so in its provenance, because round
 * three's blend is what acts on that, not silence.
 */
object KitFromCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--key", "--genre", "--out", "--name"),
            boolean = setOf("--overwrite"),
        )
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
        val source = capped.snip
        val result = try {
            KitFromAnything.assemble(source, key, genre)
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "can't make a kit of that")
        }
        val kit = KitAssembler.assembleArranged(name, Balance.apply(result.pads), dir, key = key, tempoBpm = genre.style.bpm)

        val a = result.analysis
        out.println(
            "kit from ${file.name}: ${kit.name} in ${key.label}, ${genre.label} at ${genre.style.bpm.toInt()} bpm - " +
                if (a.pitched) "the source has a note (%.1f Hz)".format(java.util.Locale.ROOT, a.hz) else "the source has no note, every cycle is forced",
        )
        out.println("%-4s %-11s %-10s %s".format("pad", "slot", "gate", "what the gate measured"))
        for (g in result.gates) {
            // Locale.ROOT on the pad number: %d follows the default locale, and on an ar-EG or fa-IR
            // device the pad would print in Eastern-Arabic digits (the house law, FormatLocaleTest)
            out.println("%-4s %-11s %-10s %s".format("A%02d".format(java.util.Locale.ROOT, g.slot.pad), g.slot.name, g.gate.name.lowercase(), g.detail))
        }
        val failed = result.fallbackSet
        val unread = result.unmeasured
        out.println(
            when {
                failed.isEmpty() && unread.isEmpty() -> "every pad cleared its gate"
                else -> "${failed.size} pad(s) failed a gate (kept, marked for the blend): ${failed.joinToString { it.slot.name }}" +
                    if (unread.isNotEmpty()) "; ${unread.size} unmeasured: ${unread.joinToString { it.slot.name }}" else ""
            },
        )
        out.println("wrote $dir")
        return 0
    }
}
