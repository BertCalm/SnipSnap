package com.snipsnap.cli

import com.snipsnap.kit.ExportBlockedException
import java.io.PrintStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(Cli.run(args))
}

/** A user-facing mistake: bad arguments, missing files, unknown formats. */
class CliError(message: String) : Exception(message)

/**
 * The SnipSnap CLI — the whole product loop minus live capture, runnable
 * anywhere a JVM runs.
 *
 * Point it at an audio file and out comes a kit: chopped at the hits,
 * classified, laid out on the pads people expect, exportable in every
 * format the writers speak — MPC 2 program folder, browsable expansion,
 * one-file `.xpn`, native MPC 3 `.xtd`, or a whole `.xpj` project.
 *
 * It exists for three reasons: kits can be made from a desktop today,
 * before the Android app ships; it is the first place the classifier
 * meets *real* audio rather than synthetic test material (the calibration
 * pass docs/CONCEPT.md asks for); and when the app misbehaves, this is
 * the same pipeline with no phone in the way.
 */
object Cli {

    val USAGE = """
        |snipsnap - chop audio into MPC kits from the command line
        |
        |usage: snipsnap <command> [options]
        |
        |commands:
        |  chop <input.wav>      chop, classify, auto-place, and build a kit folder
        |  chop-all <folder>     every .wav in the folder through chop; failures
        |                        named, never fatal; one summary table
        |  classify <wav...>     print what the classifier hears in each file
        |  export <kit-dir>      export an existing kit folder to MPC formats
        |  import <file>         unpack an .xpn archive or a native .xtd into a
        |                        kit folder; a .mid needs --into <kit-dir> and
        |                        becomes that kit's groove
        |  keys <notes.wav...>   pitched notes -> a playable chromatic instrument
        |                        (--loop cuts sustain loops: held pads sing forever)
        |  remix <kit-dir>       bank B becomes seeded evil twins of bank A
        |  merge <a> <b>         a new kit: A's bank A + B's bank A on pads
        |                        17-32, everything carried, sources untouched
        |  feel <kit-dir> --from <donor>
        |                        groove transfer: the donor's timing-and-velocity
        |                        pocket (a kit's groove or any .mid) rewrites
        |                        this kit's patterns
        |  treat <kit-dir> <pad> <character>
        |                        crush/reverse/wash one pad (--undo restores)
        |  project <kit-dir>...  whole session -> one .xpj (kits + grooves +
        |                        optional --keys instrument, mixer wired)
        |  backup <kits-root>    every kit as an .xpn inside one archive
        |  restore <backup.zip>  the archive back into kit folders
        |  diff <a> <b>          structured key-path diff of two MPC files,
        |                        either generation (--values lists differing
        |                        values; exit 1 when different)
        |  art <kit-dir>         procedural cover art from the kit itself
        |                        (--style waveform|grid|slices|rings, --scheme,
        |                        --seed N, --size PX, --out DIR; no --style
        |                        renders every style side by side)
        |  help                  this text
        |
        |chop options:
        |  --name NAME       kit name (default: the input's file name, sanitized)
        |  --out DIR         output root (default: snipsnap-out)
        |  --slices N        chop at the N strongest hits (default: auto -
        |                    the count the audio itself asks for)
        |  --grid N          chop into N equal parts instead of following hits
        |  --place / --no-place
        |                    force auto-placement on or off (default: on when
        |                    following hits, off on a grid - a grid's order is
        |                    usually the point)
        |  --balance         set per-pad levels so the kit sits right as a mix
        |  --groove          embed the capture's own rhythm as a clip in the
        |                    native exports (needs a confident tempo)
        |  --swing PCT       with --groove: the tight pattern swings instead,
        |                    hardware-style (50 straight .. 75 heavy; 66 is
        |                    the triplet feel)
        |  --ghosts          render darker soft velocity zones under every
        |                    one-shot pad, so quiet hits sound soft
        |  --melodic         place slices low-to-high by detected pitch
        |                    (unpitched slices follow in capture order)
        |  --key SPEC        retune tonal pads into a key: Am, C, F#m, Eb major,
        |                    Dminpent, Gchromatic
        |  --export LIST     comma-separated: ${Exports.FORMATS.joinToString(",")}
        |  --preview         render the kit playing its own beat into the
        |                    expansion/xpn previews
        |  --art STYLE       the expansion/xpn browser tile's style (default
        |                    waveform; also rings, grid, slices)
        |  --no-art          skip the browser tile
        |  --overwrite       replace same-named output
        |
        |export options: --export LIST, --out DIR, --overwrite, --preview,
        |                --art STYLE, --no-art (as above)
        |
        |Exports land under <out>/card/ - copy its contents onto the MPC's
        |SD card or USB drive as-is.
    """.trimMargin()

    fun run(
        args: Array<String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
    ): Int {
        if (args.isEmpty()) {
            err.println(USAGE)
            return 2
        }
        return try {
            when (args[0]) {
                "chop" -> ChopCommand.run(args.drop(1), out)
                "chop-all" -> ChopAllCommand.run(args.drop(1), out)
                "classify" -> ClassifyCommand.run(args.drop(1), out)
                "export" -> ExportCommand.run(args.drop(1), out)
                "import" -> ImportCommand.run(args.drop(1), out)
                "keys" -> KeysCommand.run(args.drop(1), out)
                "remix" -> RemixCommand.run(args.drop(1), out)
                "merge" -> MergeCommand.run(args.drop(1), out)
                "treat" -> TreatCommand.run(args.drop(1), out)
                "feel" -> FeelCommand.run(args.drop(1), out)
                "project" -> ProjectCommand.run(args.drop(1), out)
                "backup" -> BackupCommand.backup(args.drop(1), out)
                "restore" -> BackupCommand.restore(args.drop(1), out)
                "diff" -> DiffCommand.run(args.drop(1), out)
                "art" -> ArtCommand.run(args.drop(1), out)
                "help", "--help", "-h" -> {
                    out.println(USAGE)
                    0
                }
                else -> {
                    err.println("snipsnap: unknown command '${args[0]}'")
                    err.println(USAGE)
                    2
                }
            }
        } catch (e: CliError) {
            err.println("snipsnap: ${e.message}")
            2
        } catch (e: ExportBlockedException) {
            // Preflight refused to write a kit it knows is broken; show the
            // full checklist, not just the failure line.
            err.println("snipsnap: export blocked by preflight")
            e.findings.forEach { err.println("  [${it.severity}] ${it.message}") }
            1
        } catch (e: IllegalArgumentException) {
            err.println("snipsnap: ${e.message}")
            1
        } catch (e: java.io.IOException) {
            err.println("snipsnap: ${e.message}")
            1
        }
    }
}

/**
 * Just enough option parsing: `--flag`, `--opt value`, `--opt=value`,
 * everything else positional. Unknown options are errors — a typo that
 * silently becomes a positional argument is how files get misread.
 */
class Options private constructor(
    val positional: List<String>,
    private val values: Map<String, String>,
    private val flags: Set<String>,
) {
    operator fun get(name: String): String? = values[name]
    fun has(name: String): Boolean = name in flags

    fun int(name: String): Int? = values[name]?.let {
        it.toIntOrNull() ?: throw CliError("$name wants a number, got '$it'")
    }

    companion object {
        fun parse(args: List<String>, valued: Set<String>, boolean: Set<String>): Options {
            val positional = mutableListOf<String>()
            val values = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    !a.startsWith("--") -> positional += a
                    a.substringBefore('=') in valued && a.contains('=') ->
                        values[a.substringBefore('=')] = a.substringAfter('=')
                    a in boolean -> flags += a
                    a in valued -> {
                        if (i + 1 >= args.size) throw CliError("$a wants a value")
                        values[a] = args[++i]
                    }
                    else -> throw CliError("unknown option '$a' (see: snipsnap help)")
                }
                i++
            }
            return Options(positional, values, flags)
        }
    }
}
