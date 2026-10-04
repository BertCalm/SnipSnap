package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the MERCURY acceptance kit under testkit/, as an exported MPC program folder
 * ready for an SD card. Run via `./gradlew :synth:generateMercuryKit`.
 *
 * The hardware check (docs/superpowers/specs/2026-10-01-mercury-modal-glass-engine-design.md):
 * does a PING on a pad read as struck glass and not a synth bell, does the row of eight play as a
 * tune, do the SING presets sing like a rubbed glass, and do the BLADE presets bend like a sawn
 * blade? A drum program plays every pad once through.
 */
object MercuryKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".mercury-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Mercury Kit", SynthKits.mercury(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
