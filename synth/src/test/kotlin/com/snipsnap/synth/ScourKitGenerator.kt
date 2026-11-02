package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Exports SCOUR's forty-eight dry pads, three banks, as an MPC program folder.
 * Run `./gradlew :synth:generateScourKit`. Each bank's first eight pads are builds cut for 120 BPM and
 * 90 BPM, one beat to two bars long: hit one that far before a downbeat and its slam lands on it. Bank A
 * is PLATE and PANE at C3 with their eight presets; bank B is SHEET and bank C BEAM, each build on its own
 * note and character, then four presets and four short hits on a low line. A drum program plays each
 * complete sample once. Use generateScourAudition for raw/matched clips and the drops over a beat.
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
