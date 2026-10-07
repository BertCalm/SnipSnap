package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Exports THAW's sixteen dry acceptance pads as an MPC program folder.
 * Run `./gradlew :synth:generateThawKit`. The first eight pads play C minor
 * pentatonic; six more demonstrate the material roster and two carry settled
 * HOLD buffers. A drum program plays each complete sample once, including HOLD.
 * Use generateThawAudition for raw/matched material and loop comparisons.
 */
object ThawKitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".thaw-work")
        work.deleteRecursively()
        try {
            val kit = KitAssembler.assembleArranged("SnipSnap Thaw Kit", SynthKits.thaw(), work)
            val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
            println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
        } finally {
            work.deleteRecursively()
        }
    }
}
