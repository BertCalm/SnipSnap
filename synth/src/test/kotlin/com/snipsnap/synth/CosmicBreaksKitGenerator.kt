package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Exports the approved printed samples; read-back verifies every delivered pad. */
object CosmicBreaksKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/cosmic-breaks-kit").absoluteFile.apply { mkdirs() }
        val staging = File(root, ".work")
        val folder = File(root, "Cosmic Breaks Kit").apply { mkdirs() }
        try {
            val pads = CosmicBreaksKits.build()
            val kit = KitAssembler.assembleArranged(CosmicBreaksKits.NAME, pads, staging, tempoBpm = CosmicBreaksKits.TEMPO)
            val export = Mpc3Exporter.exportTrack(kit, staging, folder, overwrite = true, mpc2Twin = true)
            val data = File(folder, Mpc3TrackWriter.trackDataDirName(kit.name))
            val findings = ExportReadBack.verify(kit, ExportOutcome(ExportFormat.MPC3_TRACK, export.program, data, export.findings)) +
                ExportReadBack.verify(kit, ExportOutcome(ExportFormat.PROGRAM_FOLDER, File(data, "${kit.name}.xpm"), null, export.findings))
            check(!findings.blocked()) { findings.toString() }
            val saved = KitStore.load(data)
            check(saved.pads.size == 16)
            for (pad in saved.pads) {
                val actual = WavReader.read(File(data, pad.sampleFile))
                val expected = pads[pad.slot - 1].snip
                check(actual.frameCount == expected.frameCount && actual.sampleRate == 44100 && actual.channels == 1)
                check(actual.samples.indices.all { abs(actual.samples[it] - expected.samples[it]) < 2e-7f })
                check(pad.recipe == null && pad.oneShot)
            }
            File(folder, "manifest.json").writeBytes(CosmicBreaksKits.resource("manifest.json"))
            File(folder, "README.txt").writeText("""
                COSMIC BREAKS v1 / 170 BPM
                Sixteen original synthesized one-shots frozen from the approved browser audition.
                Load the MPC 3 track or its MPC 2 XPM twin. Keep the program and sample folders together.
                A01–A08: Kick, Snare, Closed hat, Open hat, Low tom, High tom, Rim, Metal.
                A09–A16: Data tick, Packet roll, Laser chip, Relay rattle, Ride bell, Ride cymbal, Sub kick, Crack snare.
                One velocity layer per pad. The printed compressor, reverb and amp are already in the samples.
                The source is 16-bit mono / 44.1 kHz; 24-bit export does not add source resolution.
                Hats share the standard choke group. Rides are percussion and ring independently.
                This kit preserves Low Orbit one-shots, not the audition's shared live bus interaction.
                Automated file read-back passed. Physical MPC loading and phone playback are unverified.
            """.trimIndent())
            File(root, "verification.txt").writeText("16 pads; both MPC formats passed ExportReadBack; all WAVs match frozen source within 2e-7; hardware unverified.\n")
        } finally { staging.deleteRecursively() }
        ZipOutputStream(File(root, "cosmic-breaks-kit.zip").outputStream().buffered()).use { zip ->
            folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        println("Cosmic Breaks: sixteen verified exported pads in ${root.absolutePath}")
    }
}
