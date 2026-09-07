package com.snipsnap.cli

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap beat <song.wav>` — the whole ritual as one verb: dig the
 * break (and the air), chop it with the groove, ghosts and the break
 * pad, doctor the mix, robin the core drums, call the Answer and its
 * band, arrange the song and mix it down, and print the inserts — one
 * song in, a release folder out.
 *
 * Every step is the real verb, run in order, and every skip is
 * **named**: no break aborts honestly, no key sits the Answer out, a
 * kit with nothing to roll on gets no turn. Deterministic end to end
 * (`--seed` steers the seeded steps).
 */
object BeatCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--out", "--seed"),
            boolean = setOf("--overwrite"),
        )
        val input = opts.positional.firstOrNull()
            ?: throw CliError("beat wants a song: snipsnap beat <song.wav> [--out DIR] [--seed N]")
        if (opts.positional.size > 1) throw CliError("beat takes one song")
        val song = File(input)
        if (!song.isFile) throw CliError("no such file: $input")
        val root = opts["--out"] ?: "snipsnap-out"
        val seed = opts.int("--seed") ?: 0
        val overwrite = opts.has("--overwrite")
        val skipped = mutableListOf<String>()

        fun step(n: Int, title: String) {
            out.println()
            out.println("== $n. $title ==")
        }

        // 1. The dig: the break (and the air) out of the song, chopped with
        // everything the chopper knows how to give.
        step(1, "the dig")
        val digArgs = mutableListOf(
            song.path, "--chop", "--break-pad", "--air", "--groove", "--ghosts", "--out", root,
        )
        if (overwrite) digArgs += "--overwrite"
        DigCommand.run(digArgs, out)
        val kitName = Names.sanitizeStem(song.nameWithoutExtension) + " Break"
        val kitDir = File(root, kitName)
        if (!File(kitDir, "kit.json").isFile) {
            out.println()
            out.println("no break stood out in ${song.name} - nothing to build a release from")
            return 1
        }
        val airDir = File(root, Names.sanitizeStem(song.nameWithoutExtension) + " Air")
            .takeIf { File(it, "kit.json").isFile }

        // 2. The doctor, before any pad becomes a chain (a chained pad
        // refuses rewrites - that's its boundary promise).
        step(2, "the doctor")
        runCatching { DoctorCommand.run(listOf(kitDir.path, "--fix"), out) }
            .onFailure { skipped += "doctor: ${it.message}" }

        // 3. Robin the core drums - subtle takes so the backbone breathes.
        step(3, "the robins")
        val kit = KitStore.load(kitDir)
        var robins = 0
        for (dc in listOf(DrumClass.KICK, DrumClass.SNARE)) {
            val pad = kit.pads.firstOrNull { it.drumClass == dc && it.chain == null && it.velocityLayers.isEmpty() }
            if (pad == null) {
                skipped += "robin: no plain ${dc.name.lowercase()} pad to take"
                continue
            }
            val label = "%c%02d".format('A' + (pad.slot - 1) / 16, (pad.slot - 1) % 16 + 1)
            runCatching { RobinCommand.run(listOf(kitDir.path, label, "--seed", seed.toString()), out) }
                .onSuccess { robins++ }
                .onFailure { skipped += "robin $label: ${it.message}" }
        }
        if (robins == 0) out.println("(nothing robined)")

        // 4. The Answer and the band - when the material named its key.
        step(4, "the answer")
        runCatching { AnswerCommand.run(listOf(kitDir.path, "--band", "--seed", seed.toString()), out) }
            .onFailure {
                skipped += "answer: ${it.message}"
                out.println("(the Answer sat out - ${it.message})")
            }

        // 5. The song: arranged, sequenced, mixed down.
        step(5, "the arrangement")
        val arrangeArgs = mutableListOf(kitDir.path, "--seed", seed.toString(), "--out", root, "--mixdown")
        if (overwrite) arrangeArgs += "--overwrite"
        ArrangeCommand.run(arrangeArgs, out)

        // 6. The inserts.
        step(6, "the inserts")
        runCatching { NotesCommand.run(listOf(kitDir.path), out) }
            .onFailure { skipped += "notes: ${it.message}" }
        runCatching { JCardCommand.run(listOf(kitDir.path, "--out", kitDir.path), out) }
            .onFailure { skipped += "jcard: ${it.message}" }

        out.println()
        out.println("== the release ==")
        out.println("  kit:         ${kitDir.path}")
        airDir?.let { out.println("  air:         ${it.path}") }
        out.println("  song:        ${File(root, "card/${KitStore.load(kitDir).name} Song.xpj").path}")
        out.println("  mixdown:     ${File(root, "card/${KitStore.load(kitDir).name} Song.wav").path}")
        if (skipped.isNotEmpty()) {
            out.println("  sat out:")
            skipped.forEach { out.println("    - $it") }
        }
        return 0
    }
}
