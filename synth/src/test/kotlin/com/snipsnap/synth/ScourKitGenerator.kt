package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Exports SCOUR's thirty-two dry pads, two banks, as an MPC program folder.
 * Run `./gradlew :synth:generateScourKit`. In bank A the first eight pads are builds cut for 120 BPM
 * (PLATE) and 90 BPM (PANE), one beat to two bars long: hit one that far before a downbeat and its slam
 * lands on it. The last eight are their presets. Bank B is SHEET: the same builds an octave lower, its
 * four presets and four short hits on C2, E-flat 2, F2 and G2. A drum program plays each complete
 * sample once. Use generateScourAudition for raw/matched clips and the drops over a beat.
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
