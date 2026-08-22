package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the atmosphere acceptance kit under testkit/. Run via
 * `./gradlew :synth:generateCloudKit`.
 *
 * VOX formant choirs on the bottom rows, GRAINS clouds and drones above.
 * On hardware it answers whether texture pads (including honest LOOP-length
 * ones) load and play sensibly alongside one-shots.
 */
object CloudKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".cloud-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap Cloud Kit", SynthKits.cloud(), work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
