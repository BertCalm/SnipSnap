package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/**
 * Renders the shuffled A/B acceptance kit under testkit/. Run via
 * `./gradlew :synth:generateShuffleKit`.
 *
 * Bank A is a dice-rolled factory kit (classifier-audited, so the dice
 * can't break it); bank B is the same sixteen pads re-treated through
 * seeded FX. First export to put anything on pads 17–32, so on hardware it
 * also answers whether bank B's note mapping holds.
 */
object ShuffleKitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".shuffle-work")
        work.deleteRecursively()

        val arranged = Shuffle.withRemixBank(Shuffle.kit(seed = 2026), seed = 2026)
        val kit = KitAssembler.assembleArranged("SnipSnap Shuffle Kit", arranged, work)
        val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
