package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the chip acceptance kit under testkit/, as an exported MPC program
 * folder ready for an SD card. Run via `./gradlew :synth:generateChipKit`.
 *
 * Fifth acceptance kit and the silliest: crunched THUMP/TINES drums plus a
 * VELVET square-wave pentatonic, all through one virtual converter. On
 * hardware it answers nothing the others don't — it exists because it's fun,
 * which is also a requirement.
 */
object ChipKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".chip-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Chip Kit", SynthKits.chip(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
