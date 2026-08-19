package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the THUMP acceptance kit under testkit/, as an exported MPC program
 * folder ready for an SD card. Run via `./gradlew :synth:generateThumpKit`.
 *
 * Third acceptance kit: the diag kit answers numbering, the test kit answers
 * playability of captured-style content, this one answers whether synthesized
 * kits sound right on the real machine.
 */
object ThumpKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".thump-work")
        work.deleteRecursively()

        val kit = KitAssembler.assemble("SnipSnap Thump Kit", ThumpKits.classic(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
