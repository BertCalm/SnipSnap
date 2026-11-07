package com.snipsnap.cli

import com.snipsnap.shell.ScannedChainKeysMaker
import com.snipsnap.synth.ScannedChainPatch
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Finite Short Pluck samples, packaged through the same instrument writers as the phone. */
object ScannedChainKeysCommand {
    const val ZIP_NAME = "Scanned-Short-Pluck-Keys.zip"

    fun run(args: List<String>, out: PrintStream): Int {
        val options = Options.parse(args, valued = setOf("--out", "--patch"), boolean = setOf("--overwrite"))
        if (options.positional.isNotEmpty())
            throw CliError("scanned-chain-keys takes no positional arguments; use [--patch FILE] --out DIR [--overwrite]")
        val spec = options["--patch"]?.let { path ->
            val file = File(path)
            if (!file.isFile) throw CliError("source patch not found: $path")
            ScannedChainKeysMaker.Spec(ScannedChainPatch.fromJsonText(file.readText()))
        } ?: ScannedChainKeysMaker.Spec()
        val root = File(options["--out"] ?: "snipsnap-out/scanned-chain-keys").absoluteFile
        val playable = File(root, "playable")
        val archive = File(root, ZIP_NAME)
        if (!options.has("--overwrite") && (playable.exists() || archive.exists()))
            throw CliError("Short Pluck keys output already exists under $root; choose another --out or use --overwrite")
        if (!root.isDirectory && !root.mkdirs()) throw CliError("could not create output directory $root")

        val staging = Files.createTempDirectory(root.toPath(), ".scanned-chain-keys-").toFile()
        val stagedZip = File(root, "${staging.name}.zip")
        try {
            val card = File(staging, "card").apply { mkdirs() }
            val roots = ScannedChainKeysMaker.zoneMidis(spec)
            out.println("rendering ${spec.patch.name}: ${roots.size} pitch roots x ${ScannedChainKeysMaker.TOUCHES.size} physical touch layers")
            val notes = roots.map { ScannedChainKeysMaker.renderZone(spec, it) }
            ScannedChainKeysMaker.export(ScannedChainKeysMaker.DEFAULT_NAME, spec, notes, card)
            File(staging, "LOADME.txt").writeText(loadme(spec))
            ZipOutputStream(stagedZip.outputStream().buffered()).use { zip ->
                staging.walkTopDown().filter { it.isFile }
                    .sortedBy { it.relativeTo(staging).invariantSeparatorsPath }
                    .forEach { file ->
                        val path = file.relativeTo(staging).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            // Rendering or packaging failure leaves the previous delivery intact.
            if (playable.exists()) check(playable.deleteRecursively()) { "could not replace $playable" }
            Files.move(staging.toPath(), playable.toPath())
            Files.move(stagedZip.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
            out.println("instrument: ${File(playable, "card").path} (.xty, .xpm and phone sidecar)")
            out.println("download: ${archive.path}")
            out.println("C2-C5; 39 finite samples; source patches and gain policy retained in TrackData")
            return 0
        } finally {
            staging.deleteRecursively()
            stagedZip.delete()
        }
    }

    private fun loadme(spec: ScannedChainKeysMaker.Spec): String = """
        SCANNED SHORT PLUCK

        Source: ${spec.patch.name}, PLUCK, saved render model ${spec.patch.model}.
        13 native roots in minor thirds, MIDI 36..72 (C2..C5), each with three physical
        touch captures at .30/.65/1. All 39 mono 44.1 kHz, 24-bit WAVs are finite ${spec.patch.config.seconds} s
        sounds. No sustain loop is present: holding a key lets the sound finish naturally.
        Early note-off uses a short release (${spec.releaseSeconds} sampler setting;
        ${spec.releaseSeconds} seconds in SnipSnap's reference player, not calibrated MPC time).
        Velocity windows are 0..42, 43..84 and 85..127;
        a MIDI note-on velocity of zero is conventionally note-off.

        MPC 3: copy card/ contents together and load SCANNED SHORT PLUCK.xty.
        MPC 2: open SCANNED SHORT PLUCK_[TrackData]/ and load SCANNED SHORT PLUCK.xpm.
        Keep each descriptor beside its matching TrackData folder; preserve filenames.
        All descriptors map C2-C5, including the end zones.

        The TrackData folder retains the source patch, every layer's exact patch recipe,
        and a versioned manifest describing capture velocity and gain policy. A source
        recipe renders with its native velocity gain; the exported bank divides that
        finishing gain out once so the playing sampler applies velocity gain once.
        The physical excitation differences remain in the samples.

        To make another version, edit TrackData/scanned-chain.patch.json and run:
        snipsnap scanned-chain-keys --patch PATH/TO/scanned-chain.patch.json --out NEW_DIRECTORY
        The bank supplies all root notes and physical touches, overriding saved MIDI and
        velocity. The patch retains its saved model, shape, macros, duration and envelopes.

        SnipSnap phone: this ZIP is not a tap-to-import kit. The current ZIP importer
        rejects keygroups. For development provisioning only, place the .instrument.json
        sidecar plus matching TrackData folder in the app shelf's Instruments directory,
        preserving relative paths. This directory is app-private; ordinary Files copy is
        not a promised workflow. Then open MAKE > INSTRUMENTS and choose the instrument.
    """.trimIndent() + "\n"
}
