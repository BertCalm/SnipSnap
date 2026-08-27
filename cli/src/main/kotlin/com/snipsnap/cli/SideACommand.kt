package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.BeatTape
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.SessionBuilder
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap sidea <kit-dir>... --title NAME` — the beat tape: N kits
 * become one postable folder. Each kit plays its patterns for a few
 * bars, chained with tape-stop and pull-up transitions, and out comes
 * the continuous WAV, the tracklist, a labelled cover, and the whole
 * session as an `.xpj` — the artifact, not just the parts.
 */
object SideACommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--title", "--bars", "--out"),
            boolean = setOf("--overwrite"),
        )
        if (opts.positional.isEmpty()) {
            throw CliError("sidea wants kit folders: snipsnap sidea <kit-dir>... --title NAME [--bars N]")
        }
        val kitDirs = opts.positional.map { path ->
            val dir = File(path)
            if (!File(dir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $path")
            dir
        }
        val title = opts["--title"] ?: throw CliError("a tape needs a title: --title NAME")
        if (!Names.isMpcSafe(title)) throw CliError("title isn't MPC-safe: '$title'")
        val bars = opts.int("--bars") ?: BeatTape.DEFAULT_BARS
        if (bars !in 1..64) throw CliError("--bars is 1..64, got $bars")

        val tapeDir = File(opts["--out"] ?: "snipsnap-out", title)
        if (tapeDir.exists() && !opts.has("--overwrite")) {
            throw CliError("destination already exists: ${tapeDir.path} (--overwrite replaces it)")
        }
        tapeDir.deleteRecursively()
        tapeDir.mkdirs()

        val tape = BeatTape.render(kitDirs, bars)
        val wav = File(tapeDir, "$title.wav")
        WavWriter.write(wav, tape.audio)

        // The tracklist, timestamped to the sample-accurate track starts.
        fun stamp(frame: Int): String {
            val secs = frame / BeatTape.RATE
            return "%d:%02d".format(secs / 60, secs % 60)
        }
        val tracklist = buildString {
            appendLine("SIDE A - $title")
            appendLine()
            tape.tracks.forEachIndexed { i, t ->
                val transition = t.transition?.let { ", $it" } ?: ""
                appendLine("%2d. %-24s %s  (%d bars at %.0f bpm%s)".format(i + 1, t.name, stamp(t.startFrame), t.bars, t.bpm, transition))
            }
        }
        File(tapeDir, "tracklist.txt").writeText(tracklist)

        // The cover: the first kit's waveform tile wearing the tape's title.
        val firstKit = KitStore.load(kitDirs.first())
        File(tapeDir, "cover.png").writeBytes(
            com.snipsnap.shell.KitArt.png(
                firstKit, kitDirs.first(), com.snipsnap.shell.KitArt.Style.WAVEFORM, label = title,
            ),
        )

        // And the whole thing as a session, openable on the hardware.
        val session = SessionBuilder.build(title, kitDirs, tapeDir, overwrite = true)

        out.println("SIDE A: ${tapeDir.path}")
        out.println("  tape:      ${wav.name} (%.1fs, ${tape.tracks.size} tracks)".format(tape.audio.durationSeconds))
        tape.tracks.forEachIndexed { i, t ->
            out.println("    %d. %s at %s%s".format(i + 1, t.name, stamp(t.startFrame), t.transition?.let { " -> $it" } ?: ""))
        }
        out.println("  tracklist: tracklist.txt")
        out.println("  cover:     cover.png")
        out.println("  session:   ${session.xpj.name} (+ ${session.dataDir.name}/)")
        return 0
    }
}
