package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the TONEWHEEL starter kit ([SynthKits.tonewheel]) under testkit/, as an exported MPC
 * program folder ready for an SD card. Run via `./gradlew :synth:generateTonewheelKit`.
 */
object TonewheelKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".tonewheel-work")
        work.deleteRecursively()
        val kit = KitAssembler.assembleArranged("SnipSnap Tonewheel Kit", SynthKits.tonewheel(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()
        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
