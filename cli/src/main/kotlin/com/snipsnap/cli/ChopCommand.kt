package com.snipsnap.cli

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Chopper
import com.snipsnap.audio.Classification
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Slice
import com.snipsnap.audio.Tempo
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.Balance
import com.snipsnap.kit.CapturedGroove
import com.snipsnap.kit.InKey
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.Names
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap chop <input.wav>` — the auto-chop pipeline end to end:
 * read → resample → chop → classify → place → (balance, in-key) → kit
 * folder, with the export fan-out riding on the result.
 */
object ChopCommand {

    /** Everything downstream — cleanup, writers, the MPC itself — expects this. */
    const val TARGET_RATE = 44_100

    /**
     * Below this the UI draws the class dashed and says NOT SURE; the CLI
     * marks it with a `?` for the same honesty.
     */
    const val SURE_CONFIDENCE = 0.5f

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--name", "--out", "--slices", "--grid", "--key", "--export"),
            boolean = setOf("--balance", "--overwrite", "--place", "--no-place", "--groove", "--ghosts", "--melodic"),
        )
        val input = opts.positional.firstOrNull()
            ?: throw CliError("chop wants an input file: snipsnap chop <input.wav>")
        if (opts.positional.size > 1) {
            throw CliError("chop takes one input file, got ${opts.positional.size}")
        }
        if (opts.has("--place") && opts.has("--no-place")) {
            throw CliError("--place and --no-place contradict each other")
        }
        if (opts.has("--melodic") && (opts.has("--place") || opts.has("--no-place"))) {
            throw CliError("--melodic is its own placement - drop --place/--no-place")
        }

        // Validate every option before touching audio: a typo'd format
        // should fail in a millisecond, not after a minute of chopping.
        val exportFormats = opts["--export"]?.let(Exports::parseFormats)
        val key = opts["--key"]?.let {
            try {
                KeySpec.parse(it)
            } catch (e: IllegalArgumentException) {
                throw CliError(e.message ?: "can't read key '$it'")
            }
        }
        val grid = opts.int("--grid")
        val maxSlices = opts.int("--slices")
        if (grid != null && maxSlices != null) {
            throw CliError("--slices and --grid are different chop modes - pick one")
        }
        if (grid != null && grid !in 1..128) throw CliError("--grid wants 1..128, got $grid")
        if (maxSlices != null && maxSlices !in 1..128) {
            throw CliError("--slices wants 1..128, got $maxSlices")
        }

        val file = File(input)
        if (!file.isFile) throw CliError("no such file: $input")

        var snip = WavReader.read(file)
        out.println(
            "read %s: %.2fs, %d ch @ %d Hz".format(
                file.name, snip.durationSeconds, snip.channels, snip.sampleRate,
            ),
        )
        if (snip.sampleRate != TARGET_RATE) {
            snip = Resampler.resample(snip, TARGET_RATE)
            out.println("resampled to $TARGET_RATE Hz")
        }

        val tempo = Tempo.estimate(snip)?.takeIf { it.confidence >= 0.3f }
        tempo?.let { out.println("tempo: ~%s (confidence %.2f)".format(it.label, it.confidence)) }

        val slices = if (grid != null) {
            Chopper.intoEqualParts(snip, grid, cleanup = Chopper.SLICE_CLEANUP)
        } else {
            Chopper.byTransients(snip, maxSlices = maxSlices ?: 16, cleanup = Chopper.SLICE_CLEANUP)
        }
        if (slices.isEmpty()) throw CliError("no slices came out - is the file silent?")
        out.println(
            if (grid != null) "chopped into ${slices.size} equal parts"
            else "chopped at ${slices.size} detected hits",
        )

        val classified = slices.map { it to Classifier.classify(it.snip) }

        // Following hits usually means a break, where the playable layout is
        // the point; a grid usually means bars or a chromatic run, where the
        // source order is. Both defaults yield to an explicit flag.
        val place = when {
            opts.has("--no-place") -> false
            opts.has("--place") -> true
            else -> grid == null
        }
        val onPads: List<Pair<Slice, Classification>?> = when {
            opts.has("--melodic") -> {
                // Pitched slices low → high (the SCALE-layout spirit),
                // unpitched after in capture order.
                val withPitch = classified.map { pair ->
                    pair to com.snipsnap.audio.Pitch.detect(pair.first.snip)
                        ?.takeIf { it.confidence >= 0.5f }
                }
                val pitched = withPitch.filter { it.second != null }
                    .sortedBy { it.second!!.hz }.map { it.first }
                val unpitched = withPitch.filter { it.second == null }.map { it.first }
                out.println("melodic: ${pitched.size} pitched slices low to high, ${unpitched.size} unpitched after")
                pitched + unpitched
            }
            place -> {
                // Round up to whole banks so nothing gets dropped: the core
                // classes claim their bank-A pads, the rest overflow upward.
                val padCount = (((classified.size + 15) / 16) * 16).coerceAtMost(128)
                AutoPlace.arrange(classified, padCount) { it.second.drumClass }
            }
            else -> classified
        }

        var arranged = onPads.map { entry ->
            entry?.let { (slice, c) ->
                ArrangedPad(
                    slice.snip, c.drumClass,
                    source = mapOf(
                        "file" to file.name,
                        "sourceFrame" to slice.sourceFrame.toString(),
                        "lengthFrames" to slice.snip.frameCount.toString(),
                    ),
                )
            }
        }
        if (opts.has("--balance")) {
            arranged = Balance.apply(arranged)
            out.println("balanced pad levels")
        }
        if (key != null) {
            arranged = InKey.apply(arranged, key.rootSemitone, key.scale)
            out.println("tonal pads retuned into ${key.label}")
        }

        // The default name carries what detection learned: "break 92bpm".
        val name = opts["--name"] ?: buildString {
            append(Names.sanitizeStem(file.nameWithoutExtension))
            tempo?.let { append(' ').append(it.label) }
        }
        if (!Names.isMpcSafe(name)) throw CliError("kit name isn't MPC-safe: '$name'")
        val outRoot = File(opts["--out"] ?: "snipsnap-out")
        val kitDir = File(outRoot, name)
        if (File(kitDir, "kit.json").exists() && !opts.has("--overwrite")) {
            throw CliError("kit already exists: $kitDir (pass --overwrite to replace it)")
        }

        var kit = KitAssembler.assembleArranged(name, arranged, kitDir, key, tempo?.bpm)

        if (opts.has("--ghosts")) {
            val model = com.snipsnap.shell.KitBuilderModel.open(kitDir)
            var layered = 0
            for (pad in kit.pads) {
                if (pad.oneShot && pad.velocityLayers.isEmpty()) {
                    model.addGhostLayers(pad.slot)
                    layered++
                }
            }
            model.save()
            kit = model.kit
            out.println("ghost notes: $layered pads gained darker soft zones")
        }

        out.println()
        out.println("pad  class       conf   source     length")
        onPads.forEachIndexed { i, entry ->
            val (slice, c) = entry ?: return@forEachIndexed
            val sure = if (c.confidence < SURE_CONFIDENCE) "?" else " "
            out.println(
                "%-4s %-11s %s%.2f  %7.3fs  %7.3fs".format(
                    PadNoteMap.labelForPad(i + 1),
                    c.drumClass,
                    sure,
                    c.confidence,
                    slice.sourceFrame.toFloat() / TARGET_RATE,
                    slice.snip.durationSeconds,
                ),
            )
        }
        out.println()
        out.println("kit folder: ${kitDir.path} (${kit.pads.size} pads)")

        val findings = Preflight.check(kit, kitDir)
        findings.filter { it.severity != Severity.OK }.forEach {
            out.println("  [${it.severity}] ${it.message}")
        }

        // --groove: the capture's own rhythm rides along in the native formats.
        if (opts.has("--groove")) {
            if (tempo == null) {
                out.println("(no confident tempo - groove skipped)")
            } else {
                val maxPeak = onPads.filterNotNull().maxOf { it.first.snip.peak() }.coerceAtLeast(1e-6f)
                val hits = onPads.mapIndexedNotNull { i, entry ->
                    entry?.let { (slice, _) ->
                        CapturedGroove.Hit(
                            padSlot = i + 1,
                            sourceFrame = slice.sourceFrame.toLong(),
                            lengthFrames = slice.snip.frameCount.toLong().coerceAtLeast(1),
                            velocity = (slice.snip.peak() / maxPeak).coerceIn(0.05f, 1f),
                        )
                    }
                }
                val clip = CapturedGroove.clip("$name Groove", hits, tempo.bpm, TARGET_RATE)
                // The folder is the kit: the groove persists beside kit.json
                // as the standard four variations, so exporting tomorrow
                // still carries today's rhythm - four ways.
                val variations = com.snipsnap.kit.GrooveVariations.standard(clip)
                com.snipsnap.kit.GrooveStore.save(kitDir, variations)
                out.println(
                    "groove: \"${clip.name}\" - ${clip.notes.size} notes over ${clip.bars} bar(s), " +
                        "saved as ${variations.size} patterns (captured/tight/half/sparse)",
                )
            }
        }

        if (exportFormats != null) {
            out.println()
            // No explicit clip: groove.json (all four variations) drives the
            // native exports through Exporters' own fallback.
            Exports.write(
                kit, kitDir, File(outRoot, "card"), exportFormats, opts.has("--overwrite"), out,
                tempoBpm = tempo?.bpm,
            )
        } else {
            out.println("(no --export given - kit folder only; formats: ${Exports.FORMATS.joinToString(",")})")
        }

        return 0
    }
}
