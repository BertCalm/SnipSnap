package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
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
 *
 * And the native format earns its keep: the track carries the **demo
 * groove as an embedded clip** — the same seeded [Groove] pattern the
 * expansion preview renders as audio, here as MPC note events the hardware
 * can play, edit and steal from. MPC 2 simply has nowhere to put this.
 */
object Mpc3KitGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".mpc3-work")
        work.deleteRecursively()

        val arranged = ThumpKits.classic()
        val kit = KitAssembler.assembleArranged("SnipSnap MPC3 Kit", arranged, work)

        // Pad N sits on note 36+N under the writer's chromatic map.
        val bars = 4
        val notes = Groove.hits(arranged, bars = bars, seed = 7).map { hit ->
            Mpc3Note(
                note = 36 + hit.padIndex,
                timePulses = hit.step * Mpc3Clip.PULSES_PER_16TH,
                velocity = hit.velocity,
            )
        }
        val clip = Mpc3Clip("SnipSnap Groove", bars, notes)

        val result = Mpc3Exporter.exportTrack(kit, work, root, overwrite = true, clip = clip)
        work.deleteRecursively()

        println("wrote ${result.program.absolutePath} (${result.samples.size} samples, ${notes.size}-note groove clip)")
    }
}
