package com.snipsnap.cli

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.shell.Arranger
import com.snipsnap.shell.Label
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap album <root> --title NAME` — the crate's release: every kit
 * under the root with a groove is arranged into a song (the Arranger's
 * own grammar) and mixed down; the songs land as tracks across SIDE A
 * and SIDE B — individual WAVs plus each side as one continuous tape
 * with leader gaps — with `tracklist.txt`, a cover, and, when the root
 * is a **label**, catalog numbers on every line. Kits without a groove
 * are skipped and named. Deterministic: same crate, same seed, same
 * album.
 */
object AlbumCommand {

    /** Leader between tracks on a side tape, seconds. */
    const val GAP_SEC = 1.5f

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--title", "--seed", "--out"),
            boolean = setOf("--overwrite"),
        )
        val rootArg = opts.positional.getOrNull(0)
            ?: throw CliError("album wants a crate: snipsnap album <root> --title NAME [--seed N]")
        if (opts.positional.size > 1) throw CliError("album takes one crate root")
        val root = File(rootArg)
        if (!root.isDirectory) throw CliError("no such crate root: $rootArg")
        val title = opts["--title"] ?: throw CliError("every release has a name: --title NAME")
        if (!Names.isMpcSafe(title)) throw CliError("title isn't file-safe: '$title'")
        val seed = opts.int("--seed") ?: 0

        val kitDirs = KitStore.list(root)
        if (kitDirs.isEmpty()) throw CliError("no kits under $rootArg - nothing to release")
        // A labeled crate numbers its releases; an unlabeled one just plays.
        val label = try {
            Label.load(root)?.let { Label.assign(root) }
        } catch (e: Exception) {
            null
        }

        val dest = File(opts["--out"] ?: "snipsnap-out", title)
        if (dest.exists() && !opts.has("--overwrite")) {
            throw CliError("album already exists: $dest (pass --overwrite to replace it)")
        }
        dest.deleteRecursively()
        dest.mkdirs()

        data class Track(val kitName: String, val catalog: String?, val song: String, val mix: Snip)

        val skipped = mutableListOf<String>()
        val tracks = mutableListOf<Track>()
        for (dir in kitDirs) {
            val kit = KitStore.load(dir)
            val plan = try {
                Arranger.arrange(kit, dir, seed)
            } catch (e: IllegalArgumentException) {
                skipped += "${kit.name} - ${e.message}"
                continue
            }
            out.println("arranging \"${kit.name}\" (${plan.totalBars} bars)...")
            tracks += Track(kit.name, label?.numberFor(kit.name), plan.name, Arranger.mixdown(kit, dir, plan).snip)
        }
        if (tracks.isEmpty()) {
            throw CliError("nothing to release: " + skipped.joinToString("; "))
        }

        // Tracks split across the sides, first half on A - the cassette way.
        val aCount = (tracks.size + 1) / 2
        val sides = listOf("SIDE A" to tracks.take(aCount), "SIDE B" to tracks.drop(aCount))
            .filter { it.second.isNotEmpty() }
        for ((side, sideTracks) in sides) {
            val sideDir = File(dest, side).apply { mkdirs() }
            sideTracks.forEachIndexed { i, t ->
                WavWriter.write(File(sideDir, "%02d %s.wav".format(i + 1, t.song)), t.mix)
            }
            // The side as one continuous tape, leader gaps between tracks.
            val gap = (GAP_SEC * KitPreview.RATE).toInt()
            val total = sideTracks.sumOf { it.mix.frameCount } + gap * (sideTracks.size - 1)
            val tape = FloatArray(total * 2)
            var at = 0
            for (t in sideTracks) {
                t.mix.samples.copyInto(tape, at * 2)
                at += t.mix.frameCount + gap
            }
            WavWriter.write(File(dest, "$side.wav"), Snip(tape, 2, KitPreview.RATE))
        }

        // The tracklist - catalog numbers when the crate runs a label.
        val tracklist = buildString {
            appendLine(title.uppercase())
            appendLine("=".repeat(maxOf(title.length, 8)))
            label?.let { appendLine("a ${it.name} release") }
            for ((side, sideTracks) in sides) {
                appendLine()
                appendLine(side)
                sideTracks.forEachIndexed { i, t ->
                    val num = t.catalog?.let { "$it  " } ?: ""
                    appendLine(
                        "  %s%d  %s%s  (%d:%02d)".format(
                            side.last(), i + 1, num, t.song,
                            t.mix.frameCount / KitPreview.RATE / 60,
                            t.mix.frameCount / KitPreview.RATE % 60,
                        ),
                    )
                }
            }
            if (skipped.isNotEmpty()) {
                appendLine()
                skipped.forEach { appendLine("left off: $it") }
            }
        }
        File(dest, "tracklist.txt").writeText(tracklist, Charsets.UTF_8)

        // The cover: the first track's kit drawing the title tile.
        val firstDir = kitDirs.first { KitStore.load(it).name == tracks.first().kitName }
        File(dest, "cover.png").writeBytes(
            com.snipsnap.shell.KitArt.png(
                KitStore.load(firstDir), firstDir,
                style = com.snipsnap.shell.KitArt.Style.WAVEFORM, label = title,
            ),
        )

        out.println()
        out.print(tracklist)
        out.println()
        out.println("album: ${dest.path} (${tracks.size} track(s) across ${sides.size} side(s))")
        return 0
    }
}
