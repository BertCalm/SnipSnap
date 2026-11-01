package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the RESIN starter kit ([SynthKits.resin]) under testkit/, as an exported MPC
 * program folder ready for an SD card. Run via `./gradlew :synth:generateResinKit`.
 */
object ResinKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".resin-work")
        work.deleteRecursively()
        val kit = KitAssembler.assembleArranged("SnipSnap Resin Kit", SynthKits.resin(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()
        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
