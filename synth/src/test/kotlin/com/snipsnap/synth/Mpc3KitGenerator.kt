package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.Mpc3Exporter
import java.io.File

/**
 * Renders the factory kit as an MPC 3 **native** track under testkit/ —
 * `SnipSnap MPC3 Kit.xtd` beside its `_[TrackData]/` WAV folder, the exact
 * pair every commercial MPC 3 program ships as. Run via
 * `./gradlew :synth:generateMpc3Kit`.
 *
 * This is the primary-format acceptance artifact: the same sixteen
 * THUMP/TINES pads that already load through the MPC 2 path, written in the
 * Live III's own generation. If it loads and plays, the format target is
 * met natively; if not, the MPC 2 folder remains the shipping path and the
 * failure itself (which screen, what error, or pure silence) is the next
 * clue. Colours ride along — the pads should light in class colours.
 */
object Mpc3KitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".mpc3-work")
        work.deleteRecursively()

        val kit = KitAssembler.assembleArranged("SnipSnap MPC3 Kit", ThumpKits.classic(), work)
        val result = Mpc3Exporter.exportTrack(kit, work, root, overwrite = true)
        work.deleteRecursively()

        println("wrote ${result.program.absolutePath} (${result.samples.size} samples in _[TrackData])")
    }
}
