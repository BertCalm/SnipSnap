package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the TERRA acceptance kit under testkit/, as an exported MPC program
 * folder ready for an SD card. Run via `./gradlew :synth:generateTerraKit`.
 *
 * The world-percussion hardware check: do the four topologies (udu/cajón,
 * djembe/dholak/dumbek/tabla, agogô, balafon) sit apart on the pad grid, does
 * the agogô clack read as a pre-strike squeeze rather than its own hit, and
 * does the balafon's buzz survive export.
 */
object TerraKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".terra-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Terra Kit", TerraKits.classic(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
