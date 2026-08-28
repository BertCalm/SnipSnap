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
        |  dig <file-or-folder>  find the drum breaks inside full songs and
        |                        name where they live (--top N candidates;
        |                        --chop sends each song's best break through
        |                        the chop pipeline, provenance stamped;
        |                        --air also cuts the song's most tonal,
        |                        least percussive stretch into a companion
        |                        texture kit of LOOP pads)
        |  classify <wav...>     print what the classifier hears in each file
        |  crate <root>          the whole library as a collection: cached
        |                        pad index + class census (--dupes finds
        |                        same-sound files; --pick KICK [--top N]
        |                        ranks a class; --build NAME assembles the
        |                        strongest of every class into a new kit)
        |  similar <target> <library-root>
        |                        find me another one like this: nearest
        |                        sounds across every kit under the root
        |                        (target = a .wav, or a kit dir --pad A02;
        |                        --top N matches)
        |  export <kit-dir>      export an existing kit folder to MPC formats
        |  import <file>         unpack an .xpn archive, a native .xtd, or an
        |                        .sfz instrument into a kit folder; a .mid
        |                        needs --into <kit-dir> and becomes that
        |                        kit's groove
        |  keys <notes.wav...>   pitched notes -> a playable chromatic instrument
        |                        (--loop cuts sustain loops: held pads sing forever)
        |  resample <kit-dir>    the ritual: bounce the kit playing its own
        |                        groove (wear and eras in the sound) and chop
        |                        the bounce into a NEW kit - generation loss
        |                        as a tool, lineage stamped, source untouched
        |  remix <kit-dir>       bank B becomes seeded evil twins of bank A
        |  merge <a> <b>         a new kit: A's bank A + B's bank A on pads
        |                        17-32, everything carried, sources untouched
        |  era <kit-dir> <machine>
        |                        the Time Machine: the kit through sp1200,
        |                        mpc60, tape, or phone - the specific math of
        |                        the machine (--amount, --pads, --undo)
        |  answer <kit-dir>      the B-side: an S5 bassline in the kit's key,
        |                        playing the gaps of its groove with its feel
        |                        (--seed N rerolls; --band adds stab chords
        |                        and a shaker tick; project lands every one
        |                        as a keys track playing its clip)
        |  learn <beat.wav> --into <kit-dir>
        |                        the Ear - bite a beat: a recorded performance
        |                        transcribed (onsets heard, hits classified)
        |                        and played by YOUR kit's pads, timing kept
        |                        raw, velocities from the hits' dynamics;
        |                        uncertain hits are marked, never invented
        |  feel <kit-dir> --from <donor>
        |                        groove transfer: the donor's timing-and-velocity
        |                        pocket (a kit's groove, any .mid, or a bottled
        |                        .pocket file) rewrites this kit's patterns;
        |                        --save x.pocket bottles this kit's own feel
        |                        as a tradeable file instead
        |  treat <kit-dir> <pad> <character>
        |                        crush/reverse/wash one pad (--undo restores)
        |  mutate <kit-dir> <pad> --with <src>[,<src>..]
        |                        one hit from many parents: transient-aligned
        |                        stack (with a polarity check), --splice (the
        |                        pad's attack onto the parent's body, --at ms),
        |                        or --split (pad lows + parent highs, --hz N);
        |                        parents are pad refs, kit:pad, or .wav files
        |                        (--undo restores); --roulette lets the crate
        |                        deal the parent instead: Similar's nearest
        |                        few under --root, seeded, or --wild chance
        |  robin <kit-dir> <pad> round robin: N seeded subtle takes rendered
        |                        into a chain WAV - the MPC 3 cycles one per
        |                        hit (Slice Motion), the MPC 2 plays take one
        |                        ([--takes N] [--seed S]; --undo restores the
        |                        single take byte-identical); --zones Z (2..4)
        |                        renders the full velocity x robin grid - a
        |                        dynamics-graded chain where soft hits play
        |                        quieter, darker takes
        |  shape <kit-dir> <pad> pad shape as metadata: --attack/--decay/
        |                        --cutoff/--res (0..1) land in the exported
        |                        programs' own fields - the HARDWARE renders
        |                        them, audio untouched; --reset clears
        |  wear <kit-dir>        the wear ledger: an opted-in kit is a living
        |                        tape - plays and saves accrue mileage and its
        |                        renders age, capped patina-style (--on [--k N],
        |                        --off, --reset, --plays N; audio never touched)
        |  sidea <kit-dir>... --title NAME
        |                        the beat tape: N kits, a few bars each,
        |                        tape-stop and pull-up transitions -> one
        |                        folder with the WAV, tracklist.txt, cover,
        |                        and the session .xpj ([--bars N] per kit)
        |  arrange <kit-dir>     songs, not loops: the kit's own variations
        |                        laid into intro/theme/variation/turn/
        |                        reprise/outro and written as numbered
        |                        switchable sequences in an .xpj - flip
        |                        01.. in order, that's the song ([--seed N];
        |                        --mixdown also renders it as one WAV with
        |                        SIDE A's transitions and the Answer under
        |                        the body sections)
        |  project <kit-dir>...  whole session -> one .xpj (kits + grooves +
        |                        optional --keys instrument, mixer wired;
        |                        --mixdown also renders the session as one WAV)
        |  pack <kit-dir>... --title NAME
        |                        N kits under one expansion tile, the
        |                        commercial-pack shape (--xpn also zips it;
        |                        per-kit previews included, and each kit
        |                        with a groove ships its .pocket under
        |                        [Pockets]/)
        |  backup <kits-root>    every kit as an .xpn inside one archive
        |  restore <backup.zip>  the archive back into kit folders
        |  doctor <kit-dir>      the mix doctor: measured findings about the
        |                        SOUND (sub masking, clashing hats, level
        |                        outliers, DC) - --fix applies the safe
        |                        subset, bin-backed; exit 1 while findings
        |                        remain, so it scripts like a check
        |  diff <a> <b>          structured key-path diff of two MPC files,
        |                        either generation (--values lists differing
        |                        values; exit 1 when different)
        |  label <root>          run your own imprint: --init NAME [--prefix
        |                        XYZ] starts a label at the crate root; every
        |                        run catalogs new kits (XYZ-001...) without
        |                        ever moving an existing number and rewrites
        |                        catalog.txt; J-card spines and liner notes
        |                        wear the number under a labeled root
        |  lineage <kit-dir>     the kit's family tree from the provenance
        |                        every verb stamps: resample generations,
        |                        merge parents, dig songs, chops, imports
        |                        (--root <crate> resolves parents by name;
        |                        --png renders the tree as a card)
        |  notes <kit-dir>       liner notes: the kit's story as prose from
        |                        what it tracks (where it was dug or chopped,
        |                        generation, mileage, key, patterns, pads);
        |                        expansion exports carry it beside the J-card
        |  jcard <kit-dir>       the kit's cassette insert: front, spine and
        |                        back panels in one fold-ready PNG (art, pads
        |                        with classes and sources, groove notation,
        |                        key/tempo/mileage; --width PX, --out DIR)
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
        |  --break-pad       one extra pad carrying the whole break as a
        |                    chain whose slices are the chop's own cuts -
        |                    tap through the break on one pad (also rides
        |                    dig --chop)
        |  --melodic         place slices low-to-high by detected pitch
        |                    (unpitched slices follow in capture order)
        |  --fit-tempo BPM   repitch LOOP pads from the detected tempo to BPM,
        |                    SP-style (pitch rides along); stems and kit
        |                    metadata restamp to the new tempo
        |  --key SPEC        retune tonal pads into a key: Am, C, F#m, Eb major,
        |                    Dminpent, Gchromatic - or "auto" to let the
        |                    capture name its own key
        |  --export LIST     comma-separated: ${Exports.FORMATS.joinToString(",")}
        |  --preview         render the kit playing its own beat into the
        |                    expansion/xpn previews
        |  --art STYLE       the expansion/xpn browser tile's style (default
        |                    waveform; also rings, grid, slices)
        |  --no-art          skip the browser tile
        |  --overwrite       replace same-named output
        |
        |export options: --export LIST, --out DIR, --overwrite, --preview,
        |                --art STYLE, --no-art (as above); --no-wear exports
        |                the pristine kit, --wear W forces a wear level (past
        |                the earned ceiling - chosen destruction, on purpose)
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
                "dig" -> DigCommand.run(args.drop(1), out)
                "classify" -> ClassifyCommand.run(args.drop(1), out)
                "similar" -> SimilarCommand.run(args.drop(1), out)
                "crate" -> CrateCommand.run(args.drop(1), out)
                "export" -> ExportCommand.run(args.drop(1), out)
                "import" -> ImportCommand.run(args.drop(1), out)
                "keys" -> KeysCommand.run(args.drop(1), out)
                "remix" -> RemixCommand.run(args.drop(1), out)
                "resample" -> ResampleCommand.run(args.drop(1), out)
                "merge" -> MergeCommand.run(args.drop(1), out)
                "treat" -> TreatCommand.run(args.drop(1), out)
                "robin" -> RobinCommand.run(args.drop(1), out)
                "mutate" -> MutateCommand.run(args.drop(1), out)
                "shape" -> ShapeCommand.run(args.drop(1), out)
                "feel" -> FeelCommand.run(args.drop(1), out)
                "learn" -> LearnCommand.run(args.drop(1), out)
                "era" -> EraCommand.run(args.drop(1), out)
                "wear" -> WearCommand.run(args.drop(1), out)
                "answer" -> AnswerCommand.run(args.drop(1), out)
                "sidea" -> SideACommand.run(args.drop(1), out)
                "project" -> ProjectCommand.run(args.drop(1), out)
                "arrange" -> ArrangeCommand.run(args.drop(1), out)
                "pack" -> PackCommand.run(args.drop(1), out)
                "backup" -> BackupCommand.backup(args.drop(1), out)
                "restore" -> BackupCommand.restore(args.drop(1), out)
                "diff" -> DiffCommand.run(args.drop(1), out)
                "doctor" -> DoctorCommand.run(args.drop(1), out)
                "art" -> ArtCommand.run(args.drop(1), out)
                "jcard" -> JCardCommand.run(args.drop(1), out)
                "notes" -> NotesCommand.run(args.drop(1), out)
                "lineage" -> LineageCommand.run(args.drop(1), out)
                "label" -> LabelCommand.run(args.drop(1), out)
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
        } catch (e: RuntimeException) {
            // A reader threw a typed-but-uncatalogued refusal (a corrupt
            // container, say) - a bad *file*, not a crashed program. One
            // honest line, exit 1, never a Java stack trace at the user.
            err.println("snipsnap: couldn't read that file (${e.javaClass.simpleName}: ${e.message})")
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
