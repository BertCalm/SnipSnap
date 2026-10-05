package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the FLOTILLA acceptance kit under testkit/, as an exported MPC program
 * folder. Run via `./gradlew :synth:generateFlotillaKit`.
 *
 * The hardware check: do A01–A08 play as a rising wake, do the wood and hollow
 * pads read as bodies rather than drums, and do the two held pads wrap without
 * a click? A drum program plays every pad once through.
 */
object FlotillaKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".flotilla-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Flotilla Kit", SynthKits.flotilla(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
