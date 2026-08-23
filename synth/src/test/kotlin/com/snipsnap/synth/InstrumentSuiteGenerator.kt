package com.snipsnap.synth

import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import java.io.File

/**
 * Renders the S5 instrument suite under `testkit/Instruments/`, each
 * instrument in the dual-generation layout the Timeless Glow pack taught us
 * — one asset folder serving every MPC ever made:
 *
 * ```
 * Instruments/
 * ├── SnipSnap EP.xty                  ← MPC 3 native instrument track
 * └── SnipSnap EP_[TrackData]/
 *     ├── EP_F2.wav …                  ← the samples
 *     └── SnipSnap EP.xpm              ← MPC 2 twin, beside them
 * ```
 *
 * MPC 3 opens the `.xty`; an MPC 2 machine browses into `_[TrackData]/` and
 * finds a bare program folder. The organ carries mathematically-cut sustain
 * loops in both formats. Run via `./gradlew :synth:generateInstrumentSuite`.
 */
object InstrumentSuiteGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val out = File(root, "Instruments")

        val instruments: List<Pair<String, (File) -> KeygroupProgram>> = listOf(
            "SnipSnap EP" to InstrumentSuite::renderEp,
            "SnipSnap Organ" to InstrumentSuite::renderOrgan,
            "SnipSnap Harp" to InstrumentSuite::renderHarp,
            "SnipSnap Music Box" to InstrumentSuite::renderMusicBox,
        )

        var samples = 0
        for ((name, render) in instruments) {
            val dataDir = File(out, Mpc3TrackWriter.trackDataDirName(name))
            dataDir.deleteRecursively()
            val program = render(dataDir)
            check(program.name == name) { "instrument name drifted: ${program.name} != $name" }
            Mpc3TrackWriter().writeKeygroupTo(out, program)
            KeygroupWriter().writeTo(dataDir, program)
            samples += program.keygroups.sumOf { it.layers.size }
            println("wrote ${File(out, "$name.xty").absolutePath} (${program.keygroups.size} zones)")
        }
        println("suite complete: ${instruments.size} instruments, $samples samples")
    }
}
