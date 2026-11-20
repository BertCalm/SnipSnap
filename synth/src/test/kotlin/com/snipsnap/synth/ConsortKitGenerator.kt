package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the CONSORT first kit under testkit/, as an exported MPC program folder ready for an SD
 * card. Run via `./gradlew :synth:generateConsortKit`.
 *
 * The hardware check: do the eight FLUTE pads play a tune a step apart, do the solo voices read as
 * their instruments on a pad, and do the five sections sound like players in a room when struck?
 * Nobody has loaded this kit on an MPC.
 */
object ConsortKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".consort-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Consort Kit", SynthKits.consort(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
