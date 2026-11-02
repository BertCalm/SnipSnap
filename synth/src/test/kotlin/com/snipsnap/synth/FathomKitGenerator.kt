package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the FATHOM starter kit ([SynthKits.fathom]) under testkit/, as an exported
 * MPC program folder ready for an SD card. Run via `./gradlew :synth:generateFathomKit`.
 *
 * The hardware check: does the DEEP walk hold its weight from E1 up through the
 * pentatonic on the MPC's own output, and do GRIND's beating and GLASS's FM read as
 * two different basses rather than one voice with a different filter?
 */
object FathomKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".fathom-work")
        work.deleteRecursively()
        val kit = KitAssembler.assembleArranged("SnipSnap Fathom Kit", SynthKits.fathom(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()
        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
