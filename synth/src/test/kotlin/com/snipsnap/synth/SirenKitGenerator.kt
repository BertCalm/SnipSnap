package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the SIREN acceptance kit under testkit/, as an exported MPC program
 * folder ready for an SD card. Run via `./gradlew :synth:generateSirenKit`.
 *
 * The dub siren's hardware check (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md):
 * do the wails cut through on the real pads, does the baked echo read as
 * a dub tail, and do the four LOOP pads on the top row end without a click?
 */
object SirenKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".siren-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Siren Kit", SynthKits.siren(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
