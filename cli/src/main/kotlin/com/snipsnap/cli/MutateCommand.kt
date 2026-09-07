package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Mutate
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap mutate <kit-dir> <pad> --with <src>[,<src>…]` — sound
 * design by recombination: one hit from many parents. Default is the
 * transient-aligned **stack**; `--splice [--at ms]` mashes the pad's
 * attack onto the parent's body; `--split [--hz N]` takes the pad's
 * lows and the parent's highs; `--room [--amount]` plays the pad inside
 * the parent's tail; `--transplant [--bands N]` dresses the pad's attack
 * in the parent's long-term tone. Parents are pad refs (`A03`,
 * `other/kit:B02`) or `.wav` files. `--undo` restores the single
 * original byte-identical.
 */
object MutateCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--with", "--at", "--hz", "--seed", "--root", "--amount", "--bands"),
            boolean = setOf("--splice", "--split", "--morph", "--room", "--transplant", "--undo", "--roulette", "--wild"),
        )
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError(
                "mutate wants a kit, a pad and parents: " +
                    "snipsnap mutate <kit-dir> A01 --with A03,other/kit:B02 [--splice|--split]",
            )
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? (A01..A16, B01.., or a slot number)")
        val slot = parsePad(padArg)

        val model = KitBuilderModel.open(kitDir)
        if (opts.has("--undo")) {
            Mutate.undo(model, slot)
            model.save()
            out.println("pad $padArg restored - the pre-mutation sound came back out of the bin")
            return 0
        }

        if (listOf("--splice", "--split", "--morph", "--room", "--transplant").count { opts.has(it) } > 1) {
            throw CliError("--splice, --split, --morph, --room and --transplant are different moves - pick one")
        }
        val mode = when {
            opts.has("--splice") -> Mutate.Mode.SPLICE
            opts.has("--split") -> Mutate.Mode.SPLIT
            opts.has("--morph") -> Mutate.Mode.MORPH
            opts.has("--room") -> Mutate.Mode.ROOM
            opts.has("--transplant") -> Mutate.Mode.TRANSPLANT
            else -> Mutate.Mode.STACK
        }
        if (opts["--bands"] != null && mode != Mutate.Mode.TRANSPLANT) {
            throw CliError("--bands rides on --transplant - add it")
        }
        val bands = opts["--bands"]?.let {
            it.toIntOrNull()?.takeIf { n -> n in com.snipsnap.audio.Transplant.MIN_BANDS..com.snipsnap.audio.Transplant.MAX_BANDS }
                ?: throw CliError("--bands wants ${com.snipsnap.audio.Transplant.MIN_BANDS}..${com.snipsnap.audio.Transplant.MAX_BANDS}, got '$it'")
        } ?: com.snipsnap.audio.Transplant.DEFAULT_BANDS
        if (opts["--amount"] != null && mode != Mutate.Mode.MORPH && mode != Mutate.Mode.ROOM) {
            throw CliError("--amount rides on --morph or --room - add one")
        }
        // One flag, two meanings: --amount is MORPH's blend and ROOM's wet mix.
        val amount = opts["--amount"]?.let {
            it.toFloatOrNull()?.takeIf { a -> a in 0f..1f } ?: throw CliError("--amount wants 0..1, got '$it'")
        } ?: 0.5f
        val morphAmount = if (mode == Mutate.Mode.MORPH) amount else 0.5f
        val roomMix = if (mode == Mutate.Mode.ROOM) amount else 0.5f
        if (opts.has("--roulette") && opts["--with"] != null) {
            throw CliError("--roulette lets the crate pick the parent - drop --with, or spin without it")
        }
        var extraRecipe = emptyMap<String, com.snipsnap.json.JsonValue>()
        val sources = if (opts.has("--roulette")) {
            val root = File(opts["--root"] ?: kitDir.absoluteFile.parent ?: ".")
            if (!root.isDirectory) throw CliError("no such crate root: ${root.path}")
            val seed = opts.int("--seed") ?: 0
            val pick = try {
                Mutate.roulette(model, slot, root, seed = seed, wild = opts.has("--wild"))
            } catch (e: IllegalArgumentException) {
                throw CliError(e.message ?: "the roulette refused")
            }
            val how = if (opts.has("--wild")) "wild" else "distance %.2f".format(pick.distance)
            out.println("roulette: the crate dealt ${pick.label} ($how, seed $seed)")
            extraRecipe = mapOf(
                "roulette" to com.snipsnap.json.JsonValue.Obj(
                    linkedMapOf(
                        "seed" to com.snipsnap.json.JsonValue.Num(seed.toDouble()),
                        "wild" to com.snipsnap.json.JsonValue.Bool(opts.has("--wild")),
                    ),
                ),
            )
            listOf(Mutate.Source(pick.label, WavReader.read(pick.file)))
        } else {
            (opts["--with"] ?: throw CliError("who are the parents? --with A03,other/kit:B02,hit.wav (or --roulette)"))
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { loadSource(it, kitDir) }
        }
        if (sources.isEmpty()) throw CliError("--with named no parents")

        val outcome = Mutate.apply(
            model, slot, sources, mode,
            spliceAtMs = opts.int("--at") ?: Mutate.DEFAULT_SPLICE_MS,
            crossoverHz = opts.int("--hz")?.toFloat() ?: Mutate.DEFAULT_CROSSOVER_HZ,
            morphAmount = morphAmount,
            roomMix = roomMix,
            bands = bands,
            extraRecipe = extraRecipe,
        )
        model.save()

        val what = when (mode) {
            Mutate.Mode.STACK -> "stacked with"
            Mutate.Mode.SPLICE -> "spliced into"
            Mutate.Mode.SPLIT -> "split against"
            Mutate.Mode.MORPH -> "morphed %.0f%% toward".format(morphAmount * 100)
            Mutate.Mode.ROOM -> "placed %.0f%% into the room of".format(roomMix * 100)
            Mutate.Mode.TRANSPLANT -> "dressed in $bands bands of the tone of"
        }
        out.println("pad $padArg $what ${sources.joinToString(", ") { it.label }} - one hit, ${sources.size + 1} parents")
        outcome.flipped.forEach { out.println("  polarity: flipped '$it' - it was cancelling the pad") }
        out.println("  recipe recorded; the original waits in the bin (undo: --undo)")
        return 0
    }

    /** `A03` (this kit), `path/to/kit:B02` (that kit), or a `.wav` path. */
    private fun loadSource(ref: String, kitDir: File): Mutate.Source {
        val padRef = Regex("^([A-Ha-h])(\\d{2})$")
        padRef.matchEntire(ref)?.let {
            return padSource(kitDir, ref)
        }
        val colon = ref.lastIndexOf(':')
        if (colon > 0 && padRef.matches(ref.substring(colon + 1))) {
            val dir = File(ref.substring(0, colon))
            if (!File(dir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dir")
            return padSource(dir, ref.substring(colon + 1))
        }
        val file = File(ref)
        if (!file.isFile) throw CliError("no such parent: $ref (a pad ref, kit:pad, or a .wav)")
        return Mutate.Source(file.name, WavReader.read(file))
    }

    private fun padSource(dir: File, padRef: String): Mutate.Source {
        val kit = KitStore.load(dir)
        val slot = parsePad(padRef)
        val pad = kit.pad(slot)
            ?: throw CliError("'${kit.name}' has no pad ${padRef.uppercase()}")
        return Mutate.Source(
            "${kit.name}:${padRef.uppercase()}",
            WavReader.read(File(dir, pad.sampleFile)),
        )
    }

    /** "A03" → 3, "B01" → 17, "7" → 7. */
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
