package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the FORK acceptance kit under testkit/, as an exported MPC
 * program folder ready for an SD card. Run via
 * `./gradlew :synth:generateForkKit`.
 *
 * The electric piano's hardware check
 * (docs/superpowers/specs/2026-09-27-fork-electric-piano-engine-design.md):
 * does the pickup's bark read as bite rather than harshness across the
 * TINE row's own tune sweep, and does BAR's own preset row sound like a
 * distinct instrument rather than the same voice retuned?
 */
object ForkKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".fork-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Fork Kit", SynthKits.fork(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
