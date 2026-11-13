package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.ExportResult
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Finite evolving notes, with genuine excitation layers and complete main-sample recipes. */
object OsteriaKitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit").absoluteFile
        val work = File(root, ".osteria-work")
        check(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        check(!work.exists() || work.deleteRecursively()) { "Could not clear $work" }
        try {
            val kit = KitAssembler.assembleArranged(
                "SnipSnap Osteria Kit", SynthKits.osteria(), work, KeySpec.parse("Cminpent"),
            )
            val result = export(kit, work, root)
            println("Wrote ${result.program.absolutePath}, ${result.samples.size} samples, MPC 2 twin and native kit.json")
            println("Wrote ${File(root, "SnipSnap-Osteria-Kit.zip").absolutePath}")
            println("Wrote ${File(root, "SnipSnap-Osteria-MPC2.zip").absolutePath}")
        } finally {
            work.deleteRecursively()
        }
    }

    internal fun export(kit: Kit, kitDir: File, root: File): ExportResult {
        val destination = File(root, kit.name)
        val result = Mpc3Exporter.exportTrack(kit, kitDir, destination, overwrite = true, mpc2Twin = true)
        val samplesDirectory = result.samples.first().parentFile
        // The native sidecar lives beside the WAVs, so its paths remain valid when
        // this folder is opened directly in SnipSnap or extracted from the MPC 2 ZIP.
        File(kitDir, KitStore.FILE_NAME).copyTo(File(samplesDirectory, KitStore.FILE_NAME), overwrite = true)
        File(samplesDirectory, "README.txt").writeText(
            """
            OSTERIA — one evolving note per pad

            A01–A04  Anchored: C3, Eb3, G3, C4
            A05–A08  Evolving: C3, Eb3, G3, C4
            A09–A12  Lingering Color: C3, Eb3, G3, C4
            A13–A15  Evolving C3: soft, medium, hard excitation
            A16      Evolving C3: short tap

            A01–A15 contain a 2.2-second note followed by its 0.8-second release.
            A16 contains a 0.2-second tap followed by its 0.8-second release.
            Every pad plays its complete finite sample once; lifting a pad does
            not move the authored release. These samples do not loop.

            A01–A12 have three excitation zones: MIDI 1–41 soft (0.25),
            42–84 medium (0.60), and 85–127 hard (1.00). These are separately
            rendered timbres, not quieter copies of the main sample.

            Load the .xtd for MPC 3 or the .xpm beside these WAVs for MPC 2.
            kit.json retains every main sample's full editable recipe, including
            note, velocity, motion, memory and articulation. Soft zones are saved
            as WAVs without per-zone recipes; editing the main sample does not
            rebuild those zones. Regenerate the whole bank with:
            ./gradlew :synth:generateOsteriaKit
            """.trimIndent() + "\n",
        )
        zip(destination, File(root, "SnipSnap-Osteria-Kit.zip"), destination.name)
        zip(samplesDirectory, File(root, "SnipSnap-Osteria-MPC2.zip"), kit.name)
        return result
    }

    private fun zip(directory: File, archive: File, prefix: String) {
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            directory.walkTopDown().filter { it.isFile }
                .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
                .forEach { file ->
                    val path = "$prefix/" + file.relativeTo(directory).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(path).also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }
}
