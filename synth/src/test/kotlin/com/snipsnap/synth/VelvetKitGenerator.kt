package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the VELVET starter kit ([SynthKits.velvet]) under testkit/, as an exported MPC
 * program folder ready for an SD card. Run via `./gradlew :synth:generateVelvetKit`.
 */
object VelvetKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".velvet-work")
        work.deleteRecursively()
        val kit = KitAssembler.assembleArranged("SnipSnap Velvet Kit", SynthKits.velvet(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()
        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
