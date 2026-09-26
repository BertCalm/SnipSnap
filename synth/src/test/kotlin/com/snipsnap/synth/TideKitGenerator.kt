package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the TIDE acceptance kit under testkit/, as an exported MPC program
 * folder ready for an SD card. Run via `./gradlew :synth:generateTideKit`.
 *
 * The West Coast engine's hardware check (docs/SYNTH_ROADMAP.md, S9): does
 * the low-pass gate's knock survive the trip, and do the bongo rows play in
 * tune on the real pads?
 */
object TideKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".tide-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Tide Kit", SynthKits.tide(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
