package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the BALLAST acceptance kit under testkit/, as an exported MPC program folder
 * ready for an SD card. Run via `./gradlew :synth:generateBallastKit`.
 *
 * The hardware check (docs/superpowers/specs/2026-10-04-ballast-bass-engine-design.md): does the row of
 * four play as a bass line, does the bass read as exciting a body (wires, frame, glass) and not as a kick or a
 * tom, and do the presets' glass taps sound like tiles and not clicks? A drum program plays every pad once through.
 */
object BallastKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".ballast-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Ballast Kit", SynthKits.ballast(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
