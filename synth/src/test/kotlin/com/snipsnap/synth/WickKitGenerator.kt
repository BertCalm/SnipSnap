package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Dry provisional MPC 3 pads, with an MPC 2 program twin and exact kit recipes. */
object WickKitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit").absoluteFile
        val work = File(root, ".wick-work")
        val destination = File(root, "SnipSnap Wick Prototype Kit")
        check(root.isDirectory || root.mkdirs())
        work.deleteRecursively()
        try {
            val kit = KitAssembler.assembleArranged("SnipSnap Wick Prototype Kit", SynthKits.wick(), work)
            val result = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, mpc2Twin = true)
            // The MPC files omit SnipSnap's editable recipes. Keep the original
            // kit folder and its referenced WAVs beside the native export.
            val source = File(destination, "Source Kit")
            check(!source.exists() || source.deleteRecursively()) { "Cannot replace $source" }
            check(work.copyRecursively(source, overwrite = true)) { "Cannot preserve source kit in $source" }
            val restored = KitStore.load(source)
            check(restored.pads.size == 16 && restored.pads.all { pad ->
                File(source, pad.sampleFile).isFile &&
                    pad.recipe?.let { PadRecipe.fromJsonValue(it).patch is WickPatch } == true
            }) { "Source kit must preserve sixteen WICK recipes and their original WAVs" }
            val archive = File(root, "SnipSnap-Wick-Prototype-Kit.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                destination.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(destination).invariantSeparatorsPath }.forEach { file ->
                    zip.putNextEntry(ZipEntry(destination.name + "/" + file.relativeTo(destination).invariantSeparatorsPath).also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            println("WICK wrote ${result.program.absolutePath}, ${result.samples.size} samples, MPC 2 twin and ${archive.absolutePath}")
        } finally {
            work.deleteRecursively()
        }
    }
}
