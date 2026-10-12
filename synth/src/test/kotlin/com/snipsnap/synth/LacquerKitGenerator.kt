package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.Mpc3Exporter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export the same sixteen dry starter pads as a native MPC 3 track with an MPC 2
 * program twin. HOLD pads contain settled loop material and play once in this
 * drum program; no unsupported attack-plus-loop sample format is invented.
 */
object LacquerKitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit").absoluteFile
        val work = File(root, ".lacquer-work")
        val destination = File(root, "SnipSnap Lacquer Kit")
        check(root.isDirectory || root.mkdirs())
        work.deleteRecursively()
        try {
            val kit = KitAssembler.assembleArranged("SnipSnap Lacquer Kit", SynthKits.lacquer(), work)
            val result = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, mpc2Twin = true)
            val archive = File(root, "SnipSnap-Lacquer-Kit.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                destination.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(destination).invariantSeparatorsPath }
                    .forEach { file ->
                        val path = destination.name + "/" + file.relativeTo(destination).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(path).also { it.time = 0L })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            println("Wrote ${result.program.absolutePath}, ${result.samples.size} samples, MPC 2 twin and ${archive.absolutePath}")
        } finally {
            work.deleteRecursively()
        }
    }
}
