package com.snipsnap.cli

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Separate
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.shell.KitBuilderModel
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap dissect <wav> | <kit-dir> <pad>` — the anatomy lesson:
 * one sound split into **sines** (the body), **transient** (the
 * attack) and **air** (the noise between), fuzzy STN masks that sum
 * back to the whole. The three parts land as a "<Name> Dissected"
 * kit, each a pad you can mutate, era, robin or sculpt on its own —
 * the layers of a hit, finally on separate pads.
 */
object DissectCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out", "--name"), boolean = setOf("--overwrite"))
        val (source, sourceLabel, defaultName) = resolveSource(opts)
        val name = opts["--name"] ?: defaultName
        if (!Names.isMpcSafe(name)) throw CliError("kit name isn't MPC-safe: '$name'")
        val outRoot = File(opts["--out"] ?: "snipsnap-out")
        val kitDir = File(outRoot, name)
        if (kitDir.exists()) {
            if (!opts.has("--overwrite")) {
                throw CliError("output already exists: $kitDir (pass --overwrite to replace it)")
            }
            kitDir.deleteRecursively()
        }
        outRoot.mkdirs()

        val stn = Separate.stn(source)
        fun energy(s: Snip): Double = s.samples.sumOf { (it * it).toDouble() }
        val total = energy(source).coerceAtLeast(1e-12)
        val parts = listOf(
            Triple("Sines", stn.sines, DrumClass.TONAL),
            Triple("Transient", stn.transients, DrumClass.PERC),
            Triple("Air", stn.noise, DrumClass.LOOP),
        )

        val model = KitBuilderModel.create(name, kitDir)
        for ((i, part) in parts.withIndex()) {
            val (partName, snip, dc) = part
            val slot = i + 1
            model.assign(slot, snip, dc, displayName = partName)
            model.update(slot) {
                it.copy(
                    source = mapOf("dissectedFrom" to sourceLabel, "part" to partName.lowercase()),
                    recipe = JsonValue.Obj(
                        linkedMapOf<String, JsonValue>(
                            "dissect" to JsonValue.Obj(
                                linkedMapOf("part" to JsonValue.Str(partName.lowercase())),
                            ),
                        ),
                    ),
                )
            }
            out.println("  A%02d %-9s %2.0f%% of the energy".format(slot, partName, 100.0 * energy(snip) / total))
        }
        model.save()
        out.println("-> ${kitDir.path} (the parts sum back to the whole; provenance stamped)")
        return 0
    }

    private fun resolveSource(opts: Options): Triple<Snip, String, String> {
        val first = opts.positional.getOrNull(0)
            ?: throw CliError("dissect wants a source: snipsnap dissect <wav> | <kit-dir> <pad>")
        val target = File(first)
        return when {
            File(target, "kit.json").isFile -> {
                val padRef = opts.positional.getOrNull(1)
                    ?: throw CliError("which pad? dissect <kit-dir> <pad> (like A03)")
                if (opts.positional.size > 2) throw CliError("dissect takes one source")
                val slot = parsePad(padRef)
                val kit = KitStore.load(target)
                val pad = kit.pads.firstOrNull { it.slot == slot }
                    ?: throw CliError("no pad on slot $padRef in ${kit.name}")
                Triple(
                    WavReader.read(File(target, pad.sampleFile)),
                    "${kit.name}:${padRef.uppercase()}",
                    Names.sanitizeStem("${pad.displayName} Dissected"),
                )
            }
            target.isFile -> {
                if (opts.positional.size > 1) throw CliError("dissect takes one source")
                Triple(
                    WavReader.read(target),
                    target.name,
                    Names.sanitizeStem(target.nameWithoutExtension) + " Dissected",
                )
            }
            else -> throw CliError("no such source: $first")
        }
    }

    private fun parsePad(arg: String): Int {
        arg.toIntOrNull()?.let { return it }
        val m = Regex("^([A-Ha-h])(\\d{1,2})$").matchEntire(arg.trim())
            ?: throw CliError("can't read pad '$arg' - try A03, B01, or a slot number")
        val bank = m.groupValues[1].uppercase()[0] - 'A'
        val n = m.groupValues[2].toInt()
        if (n !in 1..16) throw CliError("pad number in a bank is 1..16, got $n")
        return bank * 16 + n
    }
}
