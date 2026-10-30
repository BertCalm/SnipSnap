package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Exports SCOUR's sixteen dry pads as an MPC program folder.
 * Run `./gradlew :synth:generateScourKit`. The first eight pads are builds cut for 120 BPM (PLATE)
 * and 90 BPM (PANE), one beat to two bars long: hit one that far before a downbeat and its slam lands
 * on it. The last eight are the presets. A drum program plays each complete sample once.
 * Use generateScourAudition for raw/matched clips and the drops over a beat.
 */
object ScourKitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".scour-work")
        work.deleteRecursively()
        try {
            val kit = KitAssembler.assembleArranged("SnipSnap Scour Kit", SynthKits.scour(), work)
            val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
            println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
        } finally {
            work.deleteRecursively()
        }
    }
}
