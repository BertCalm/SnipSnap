package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the melodic acceptance kit under testkit/, as an exported MPC
 * program folder ready for an SD card. Run via
 * `./gradlew :synth:generateMelodicKit`.
 *
 * Fourth acceptance kit, and the first musical one: two octaves of plucks
 * plus a row of organ stabs. On hardware it answers whether note-per-pad
 * kits feel playable on the real grid — the keys-on-pads bet, testable
 * before any keygroup work exists.
 */
object MelodicKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".melodic-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Melodic Kit", SynthKits.melodic(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
