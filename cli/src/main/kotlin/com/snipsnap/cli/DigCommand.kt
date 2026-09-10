package com.snipsnap.cli

import com.snipsnap.audio.BreakFinder
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap dig <file-or-folder>` — the crate-digging ritual from the
 * top: point it at full songs and it names where the drum breaks live.
 * `--chop` sends each song's best break straight through the chop
 * pipeline, provenance stamped with the song and the timestamp it came
 * from. Failures are named per song and never fatal, `chop-all` style.
 */
object DigCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--top", "--out", "--slices"),
            boolean = setOf("--chop", "--overwrite", "--break-pad", "--air", "--groove", "--ghosts", "--clean", "--denoise", "--unearth"),
        )
        val targetArg = opts.positional.getOrNull(0)
            ?: throw CliError("dig wants songs: snipsnap dig <file-or-folder> [--top N] [--chop]")
        if (opts.positional.size > 1) throw CliError("dig takes one file or folder")
        val target = File(targetArg)
        val files = when {
            target.isFile -> listOf(target)
            target.isDirectory -> target.listFiles { f: File -> f.isFile && f.extension.lowercase() == "wav" }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
            else -> throw CliError("no such file or folder: $targetArg")
        }
        if (files.isEmpty()) throw CliError("nothing to dig - no .wav files in $targetArg")
        val top = opts.int("--top") ?: 3
        if (top < 1) throw CliError("--top wants at least 1")

        fun stamp(sec: Float): String {
            val s = sec.toInt()
            return "%d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
        }

        var chopped = 0
        var aired = 0
        for (file in files) {
            // --unearth: the Split first, then everything downstream reads
            // the layer it cares about - the dig scores drums that are
            // actually audible as drums, even buried under the song.
            val (digAudio, airAudio) = try {
                val song = WavReader.read(file)
                if (opts.has("--unearth")) {
                    val split = com.snipsnap.audio.Separate.hpss(song)
                    split.percussive to split.harmonic
                } else {
                    song to song
                }
            } catch (e: Exception) {
                out.println("${file.name}: couldn't read it (${e.message}) - skipped")
                continue
            }
            val candidates = try {
                BreakFinder.find(digAudio)
            } catch (e: Exception) {
                out.println("${file.name}: couldn't dig it (${e.message}) - skipped")
                continue
            }
            if (candidates.isEmpty()) {
                out.println("${file.name}: no break heard")
                continue
            }
            out.println("${file.name}:")
            candidates.take(top).forEachIndexed { i, c ->
                out.println(
                    "  %d. %s-%s  (%.0fs, score %.2f)".format(java.util.Locale.ROOT, 
                        i + 1, stamp(c.startSec), stamp(c.endSec), c.durationSec, c.score,
                    ),
                )
            }

            if (opts.has("--chop")) {
                val best = candidates.first()
                val kitName = Names.sanitizeStem(file.nameWithoutExtension) + " Break"
                val outDir = opts["--out"] ?: "snipsnap-out"
                // The excerpt goes through chop from a temp copy that keeps
                // the song's name, so pad provenance reads the real source.
                val tmpDir = java.nio.file.Files.createTempDirectory("snipsnap-dig").toFile()
                try {
                    val song = digAudio
                    val startFrame = (best.startSec * song.sampleRate).toInt().coerceIn(0, song.frameCount - 1)
                    val endFrame = (best.endSec * song.sampleRate).toInt().coerceIn(startFrame + 1, song.frameCount)
                    val excerpt = com.snipsnap.audio.Snip(
                        song.samples.copyOfRange(startFrame * song.channels, endFrame * song.channels),
                        song.channels, song.sampleRate,
                    )
                    val tmp = File(tmpDir, file.name)
                    com.snipsnap.audio.WavWriter.write(tmp, excerpt)

                    val chopArgs = mutableListOf(tmp.path, "--name", kitName, "--out", outDir)
                    opts["--slices"]?.let { chopArgs += listOf("--slices", it) }
                    if (opts.has("--overwrite")) chopArgs += "--overwrite"
                    if (opts.has("--break-pad")) chopArgs += "--break-pad"
                    if (opts.has("--groove")) chopArgs += "--groove"
                    if (opts.has("--ghosts")) chopArgs += "--ghosts"
                    if (opts.has("--clean")) chopArgs += "--clean"
                    if (opts.has("--denoise")) chopArgs += "--denoise"
                    val code = try {
                        ChopCommand.run(chopArgs, out)
                    } catch (e: CliError) {
                        out.println("  couldn't chop the break: ${e.message}")
                        continue
                    }
                    if (code != 0) continue

                    // Stamp where in the song the kit came from.
                    val kitDir = File(outDir, kitName)
                    val kit = KitStore.load(kitDir)
                    KitStore.save(
                        kit.copy(
                            pads = kit.pads.map { p ->
                                p.copy(
                                    source = p.source + mapOf("song" to file.name, "at" to stamp(best.startSec)) +
                                        (if (opts.has("--unearth")) mapOf("unearthed" to "true") else emptyMap()),
                                )
                            },
                        ),
                        kitDir,
                    )
                    out.println("  -> $kitName (from ${stamp(best.startSec)} of ${file.name})")
                    chopped++
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            // --air: the inverse dig - the same song's most tonal, least
            // percussive stretch becomes a companion texture kit.
            if (opts.has("--air")) {
                val airSections = try {
                    BreakFinder.air(airAudio)
                } catch (e: Exception) {
                    emptyList()
                }
                if (airSections.isEmpty()) {
                    out.println("  no air heard in ${file.name}")
                } else {
                    val best = airSections.first()
                    val kitName = Names.sanitizeStem(file.nameWithoutExtension) + " Air"
                    val outDir = opts["--out"] ?: "snipsnap-out"
                    val tmpDir = java.nio.file.Files.createTempDirectory("snipsnap-air").toFile()
                    try {
                        val song = airAudio
                        val startFrame = (best.startSec * song.sampleRate).toInt().coerceIn(0, song.frameCount - 1)
                        val endFrame = (best.endSec * song.sampleRate).toInt().coerceIn(startFrame + 1, song.frameCount)
                        val excerpt = com.snipsnap.audio.Snip(
                            song.samples.copyOfRange(startFrame * song.channels, endFrame * song.channels),
                            song.channels, song.sampleRate,
                        )
                        val tmp = File(tmpDir, file.name)
                        com.snipsnap.audio.WavWriter.write(tmp, excerpt)

                        // Long cuts on a grid - a texture wants sustained
                        // material in source order, not hit-chopped shards.
                        val airArgs = mutableListOf(tmp.path, "--name", kitName, "--out", outDir, "--grid", "4")
                        if (opts.has("--overwrite")) airArgs += "--overwrite"
                        val code = try {
                            ChopCommand.run(airArgs, out)
                        } catch (e: CliError) {
                            out.println("  couldn't cut the air: ${e.message}")
                            continue
                        }
                        if (code != 0) continue

                        // Texture pads by declaration: whatever the classifier
                        // heard in the cuts, these are LOOPs - and the kit
                        // knows which song and where its air came from.
                        val kitDir = File(outDir, kitName)
                        val kit = KitStore.load(kitDir)
                        KitStore.save(
                            kit.copy(
                                pads = kit.pads.map { p ->
                                    p.copy(
                                        drumClass = com.snipsnap.audio.DrumClass.LOOP,
                                        colorHex = com.snipsnap.audio.AutoPlace.colorFor(com.snipsnap.audio.DrumClass.LOOP),
                                        source = p.source + mapOf("song" to file.name, "at" to stamp(best.startSec)),
                                    )
                                },
                            ),
                            kitDir,
                        )
                        out.println("  -> $kitName (air from ${stamp(best.startSec)}-${stamp(best.endSec)} of ${file.name})")
                        aired++
                    } finally {
                        tmpDir.deleteRecursively()
                    }
                }
            }
        }
        if (opts.has("--chop")) {
            out.println(if (chopped > 0) "$chopped break(s) dug and chopped" else "nothing chopped - no break stood out")
        }
        if (opts.has("--air")) {
            out.println(if (aired > 0) "$aired air kit(s) cut" else "no air cut - nothing calm and tonal stood apart")
        }
        return 0
    }
}
