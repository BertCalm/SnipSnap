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

    /** What one chop produced — the batch verb's summary line reads this. */
    data class Result(val kit: com.snipsnap.kit.Kit, val kitDir: File, val tempoLabel: String?)

    fun run(args: List<String>, out: PrintStream): Int {
        chop(args, out)
        return 0
    }

    fun chop(args: List<String>, out: PrintStream): Result {
        val opts = Options.parse(
            args,
            valued = setOf("--name", "--out", "--slices", "--grid", "--key", "--export", "--swing", "--art", "--fit-tempo"),
            boolean = setOf("--balance", "--overwrite", "--place", "--no-place", "--groove", "--ghosts", "--melodic", "--preview", "--no-art", "--break-pad"),
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
        if (opts.has("--no-art") && opts["--art"] != null) {
            throw CliError("--art and --no-art contradict each other")
        }
        val artStyle = Exports.parseArtStyle(opts["--art"])
        val autoKey = opts["--key"]?.lowercase() == "auto"
        val explicitKey = opts["--key"]?.takeUnless { autoKey }?.let {
            try {
                KeySpec.parse(it)
            } catch (e: IllegalArgumentException) {
                throw CliError(e.message ?: "can't read key '$it'")
            }
        }
        val fitTempo = opts["--fit-tempo"]?.let {
            it.toFloatOrNull()?.takeIf { t -> t > 0f && t < 1000f }
                ?: throw CliError("--fit-tempo wants a BPM, got '$it'")
        }
        val swing = opts.int("--swing")
        if (swing != null && !opts.has("--groove")) {
            throw CliError("--swing rides on --groove - add it")
        }
        if (swing != null && swing !in 50..75) {
            throw CliError("--swing wants 50..75 (50 straight, 66 triplet feel), got $swing")
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
            // No count given: let the audio answer. The knee in the
            // onset-strength curve says where the real hits end.
            val target = maxSlices ?: Chopper.autoSliceCount(snip).also {
                if (it > 0) out.println("auto slice count: $it hits above the knee")
            }
            Chopper.byTransients(snip, maxSlices = target, cleanup = Chopper.SLICE_CLEANUP)
        }
        if (slices.isEmpty()) throw CliError("no slices came out - is the file silent?")
        out.println(
            if (grid != null) "chopped into ${slices.size} equal parts"
            else "chopped at ${slices.size} detected hits",
        )

        val classified = slices.map { it to Classifier.classify(it.snip) }

        // The capture can name its own key - a guess from the pitched slices.
        val guess = com.snipsnap.audio.KeyGuess.guess(
            classified.mapNotNull { (slice, _) ->
                com.snipsnap.audio.Pitch.detect(slice.snip)?.takeIf { it.confidence >= 0.5f }?.hz
            },
        )
        val key = when {
            explicitKey != null -> explicitKey
            autoKey -> {
                val sure = guess?.takeIf { it.confidence >= com.snipsnap.audio.KeyGuess.SURE_CONFIDENCE }
                    ?: throw CliError(
                        "couldn't hear a key in this material - name one (--key Am) or drop --key",
                    )
                out.println("key: sounds like ${sure.key.label} (confidence %.2f)".format(sure.confidence))
                sure.key
            }
            else -> null
        }
        // No key asked for: a confident guess still gets remembered (metadata
        // only - retuning uninvited would be a different kit than captured).
        val stampedKey = key ?: guess
            ?.takeIf { it.confidence >= com.snipsnap.audio.KeyGuess.SURE_CONFIDENCE }
            ?.key
            ?.also { out.println("key: sounds like ${it.label} - remembered in kit.json (retune with --key auto)") }

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
        // Fit before balance: repitched audio is what the levels should sit on.
        if (fitTempo != null) {
            if (tempo == null) {
                throw CliError("--fit-tempo needs a confident source tempo - none was heard in this material")
            }
            var fitted = 0
            arranged = arranged.map { pad ->
                if (pad?.drumClass == com.snipsnap.audio.DrumClass.LOOP) {
                    fitted++
                    try {
                        pad.copy(snip = com.snipsnap.audio.TempoFit.repitch(pad.snip, tempo.bpm, fitTempo))
                    } catch (e: IllegalArgumentException) {
                        throw CliError(e.message ?: "tempo fit refused")
                    }
                } else {
                    pad
                }
            }
            if (fitted == 0) {
                out.println("(no LOOP pads - nothing to tempo-fit)")
            } else {
                out.println(
                    "tempo fit: %d loop(s) repitched %s -> %dbpm (%+.1f semitones, SP-style)".format(
                        fitted, tempo.label, Math.round(fitTempo),
                        com.snipsnap.audio.TempoFit.semitones(tempo.bpm, fitTempo),
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
            (fitTempo ?: tempo?.bpm)?.let { append(' ').append("${Math.round(it)}bpm") }
        }
        if (!Names.isMpcSafe(name)) throw CliError("kit name isn't MPC-safe: '$name'")
        val outRoot = File(opts["--out"] ?: "snipsnap-out")
        val kitDir = File(outRoot, name)
        if (File(kitDir, "kit.json").exists() && !opts.has("--overwrite")) {
            throw CliError("kit already exists: $kitDir (pass --overwrite to replace it)")
        }

        // A fitted kit *is* at the target tempo now - stems and metadata agree.
        val kitBpm = fitTempo ?: tempo?.bpm
        var kit = KitAssembler.assembleArranged(name, arranged, kitDir, stampedKey, kitBpm)

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

        // --break-pad: one extra pad carrying the whole break as a chain
        // whose slices ARE the chop's boundaries - tap through the break
        // on one pad, the workflow MPC users build by hand in Sample Edit.
        if (opts.has("--break-pad")) {
            val first = slices.first().sourceFrame
            val boundaries = slices.map { (it.sourceFrame - first).toLong() }.distinct()
            when {
                boundaries.size < 2 -> out.println("(fewer than 2 slices - no break pad to tap through)")
                kit.highestSlot >= 128 -> out.println("(no free pad slot left for the break pad)")
                else -> {
                    // The pad's WAV starts at the first hit, so slice one
                    // begins at frame 0 the way ChainInfo (and the ear) expect.
                    val breakSnip = com.snipsnap.audio.Snip(
                        snip.samples.copyOfRange(first * snip.channels, snip.samples.size),
                        snip.channels, snip.sampleRate,
                    )
                    val slot = kit.highestSlot + 1
                    val stem = Names.sanitizeStem("${PadNoteMap.labelForPad(slot)}_Break")
                    com.snipsnap.audio.WavWriter.write(File(kitDir, "$stem.wav"), breakSnip)
                    kit = kit.copy(
                        pads = kit.pads + com.snipsnap.kit.KitPad(
                            slot = slot,
                            sampleFile = "$stem.wav",
                            displayName = "Break",
                            drumClass = com.snipsnap.audio.DrumClass.LOOP,
                            colorHex = AutoPlace.colorFor(com.snipsnap.audio.DrumClass.LOOP),
                            source = mapOf(
                                "file" to file.name,
                                "sourceFrame" to first.toString(),
                                "lengthFrames" to breakSnip.frameCount.toString(),
                            ),
                            chain = com.snipsnap.kit.ChainInfo(boundaries, cycle = boundaries.size),
                        ),
                    )
                    com.snipsnap.kit.KitStore.save(kit, kitDir)
                    out.println(
                        "break pad: ${PadNoteMap.labelForPad(slot)} carries the whole break as a " +
                            "chain of ${boundaries.size} slices - tap through it in order",
                    )
                }
            }
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
                val variations = com.snipsnap.kit.GrooveVariations.standard(clip, swingPercent = swing)
                    .toMutableList()
                // A fifth pattern when the kit can roll one: the fill. It
                // rides the .xpj's sequences; the .xtd keeps its four-slot
                // budget with the original four.
                val hasFill = try {
                    variations += com.snipsnap.kit.GrooveVariations.fill(clip, kit)
                    true
                } catch (e: IllegalArgumentException) {
                    false
                }
                // And a sixth: the ghost-note grammar, when the kit can whisper.
                val hasGhosts = try {
                    variations += com.snipsnap.kit.GrooveVariations.ghosted(clip, kit)
                    true
                } catch (e: IllegalArgumentException) {
                    false
                }
                com.snipsnap.kit.GrooveStore.save(kitDir, variations)
                val second = if (swing != null) "swing $swing" else "tight"
                out.println(
                    "groove: \"${clip.name}\" - ${clip.notes.size} notes over ${clip.bars} bar(s), " +
                        "saved as ${variations.size} patterns (captured/$second/half/sparse" +
                        (if (hasFill) "/fill" else "") + (if (hasGhosts) "/ghosted" else "") + ")",
                )
            }
        }

        if (exportFormats != null) {
            out.println()
            val preview = if (opts.has("--preview")) {
                com.snipsnap.kit.KitPreview.render(kit, kitDir).also {
                    out.println("preview: rendered the kit playing its own beat (%.1fs)".format(it.durationSeconds))
                }
            } else {
                null
            }
            val artwork = Exports.renderArtwork(
                kit, kitDir, exportFormats, artStyle, opts.has("--no-art"), out,
            )
            // No explicit clip: groove.json (all four variations) drives the
            // native exports through Exporters' own fallback.
            Exports.write(
                kit, kitDir, File(outRoot, "card"), exportFormats, opts.has("--overwrite"), out,
                tempoBpm = kitBpm, preview = preview, artworkPng = artwork,
            )
        } else {
            out.println("(no --export given - kit folder only; formats: ${Exports.FORMATS.joinToString(",")})")
        }

        return Result(kit, kitDir, tempo?.label)
    }
}
