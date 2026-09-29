package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the BORE acceptance kit under testkit/, as an exported MPC program folder
 * ready for an SD card. Run via `./gradlew :synth:generateBoreKit`.
 *
 * The hardware check (docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md):
 * do FLUTE and SAX read as breath rather than as an oscillator with a tremolo, does
 * the triad play as a chord on the pads, and do the two LOOP pads hold when a finger
 * stays down?
 */
object BoreKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".bore-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Bore Kit", SynthKits.bore(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
