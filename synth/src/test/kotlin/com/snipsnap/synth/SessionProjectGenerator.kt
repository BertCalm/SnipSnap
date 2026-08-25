package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3ProjectTrack
import com.snipsnap.mpc3.Mpc3ProjectWriter
import java.io.File

/**
 * Renders the whole session as one MPC 3 project under testkit/ —
 * `SnipSnap Session.xpj` beside its flat `SnipSnap Session_[ProjectData]/`:
 * the factory kit, all four suite instruments, and the demo groove on the
 * sequence timeline. Open the `.xpj` on the Live III and the entire
 * SnipSnap session is standing there — kit red-to-teal on the pads, EP,
 * organ, harp and music box on their own tracks, "SnipSnap Groove" ready
 * on sequence 1. Run via `./gradlew :synth:generateSessionProject`.
 */
object SessionProjectGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val name = "SnipSnap Session"
        val dataDir = File(root, Mpc3ProjectWriter.projectDataDirName(name))
        dataDir.deleteRecursively()
        dataDir.mkdirs()
        val work = File(root, ".session-work")
        work.deleteRecursively()

        val arranged = ThumpKits.classic()
        val kit = KitAssembler.assembleArranged("Session Kit", arranged, work)
        val drum = Mpc3Exporter.stageTrack(kit, work, samplesDir = dataDir)
        work.deleteRecursively()

        val bars = 4
        val clip = Mpc3Clip(
            "SnipSnap Groove", bars,
            Groove.hits(arranged, bars = bars, seed = 7).map {
                Mpc3Note(36 + it.padIndex, it.step * Mpc3Clip.PULSES_PER_16TH, it.velocity)
            },
        )

        val instruments = InstrumentSuite.renderAll(dataDir)
        val tracks = listOf<Mpc3ProjectTrack>(Mpc3ProjectTrack.Drum(drum, clip = clip)) +
            instruments.map { Mpc3ProjectTrack.Keys(it) }

        val file = Mpc3ProjectWriter().writeTo(root, name, tracks, tempoBpm = 92f)
        val wavs = dataDir.listFiles { f -> f.extension == "wav" }!!.size
        println("wrote ${file.absolutePath} (${tracks.size} content tracks, $wavs samples, ${clip.notes.size}-note groove)")
    }
}
