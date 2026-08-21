package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the velocity-layered acceptance kit under testkit/. Run via
 * `./gradlew :synth:generateVelocityKit`.
 *
 * The factory kit with three velocity zones per pad — soft and mid renders
 * darker, main on top. On hardware it answers the fun question: do ghost
 * notes finally sound like ghost notes, and does the format's four-layer
 * velocity mapping behave as documented.
 */
object VelocityKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".velocity-work")
        work.deleteRecursively()

        val arranged = ThumpKits.classic().map { pad ->
            pad?.copy(softVariants = Velocity.variants(pad.snip, count = 2))
        }
        val kit = KitAssembler.assembleArranged("SnipSnap Velocity Kit", arranged, work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
