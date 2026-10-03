package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the ARCO acceptance kit under testkit/, as an exported MPC program folder
 * ready for an SD card. Run via `./gradlew :synth:generateArcoKit`.
 *
 * The hardware check (docs/superpowers/specs/2026-09-29-arco-bowed-string-engine-design.md):
 * does a CELLO stab on a pad read as a bowed string and not a plucked or a synth one, does the row
 * of eight play as a tune a step apart, do the six ERHU presets read as a fiddle, and do the two
 * LOOP pads (A15 and A16) end cleanly? A drum program plays them once through (`Loop=False`, as
 * BORE's and SIREN's LOOP pads do): the held wrap is heard in the audition page's REPEAT and its
 * SURFACE stand-in.
 */
object ArcoKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".arco-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Arco Kit", SynthKits.arco(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
