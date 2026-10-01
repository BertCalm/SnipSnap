package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the MAGNET R1 kit under testkit/, as an exported MPC program folder
 * ready for an SD card. Run via `./gradlew :synth:generateMagnetKit`.
 *
 * The hardware check: does the CHUG riff (A01-A08) read as a palm-muted
 * electric string through an amp rather than a plucked string with distortion
 * on it, does the JANGLE chord (A09-A14) ring as an open chord played pad by
 * pad, and do A15-A16, CHUG with BLEND toward the neck through a hotter amp,
 * sit apart from the riff as a lead.
 */
object MagnetKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".magnet-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Magnet Kit", SynthKits.magnet(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
