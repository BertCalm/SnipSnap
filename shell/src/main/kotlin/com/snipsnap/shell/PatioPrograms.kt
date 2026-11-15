package com.snipsnap.shell

import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.synth.PatioPerformance
import java.io.File

/** Native MPC 3 delivery keeps velocity-zone slice cycling; no lossy MPC 2 twin. */
object PatioPrograms {
    fun groove(genre: PatioPerformance.Genre): Mpc3Clip {
        val notes = mutableListOf<Mpc3Note>()
        fun hit(slot: Int, beat: Double, velocity: Float) { notes += Mpc3Note(Mpc3Note.noteFor(slot), (beat * 960).toLong(), velocity) }
        for (bar in 0..3) {
            val b = bar * 4.0
            when (genre) {
                PatioPerformance.Genre.CUMBIA -> {
                    hit(1, b, .9f); hit(1, b + 2.5, .7f)
                    hit(8, b + 1, .6f); hit(8, b + 3, .8f)
                    hit(6, b + .75, .7f); hit(7, b + 2, .8f); hit(9, b + 3.75, .6f)
                    hit(if (bar % 2 == 0) 14 else 15, b + 2.75, .65f)
                }
                PatioPerformance.Genre.SAMBA -> {
                    hit(5, b, .65f); hit(5, b + 2, .95f)
                    for (i in 0..7) { hit(2, b + i * .5, if (i % 2 == 0) .38f else .72f); hit(13, b + i * .5 + .25, .5f) }
                    for (at in listOf(0.0, .75, 1.5, 2.5, 3.25)) hit(if (at < 2) 15 else 14, b + at, .75f)
                    hit(1, b + 3.5, .65f)
                }
                PatioPerformance.Genre.DUB -> {
                    hit(1, b, .9f); hit(10, b + 2.5, .7f); hit(2, b + 2, .75f)
                    hit(6, b + 1.75, .5f); hit(14, b + 3.25, .55f)
                    if (bar % 2 == 0) hit(16, b + .75, .6f)
                }
            }
            for (i in 0..3) hit(3, b + i + .5, if (i % 2 == 0) .4f else .65f)
        }
        return Mpc3Clip(genre.title, 4, notes.sortedBy { it.timePulses })
    }
    fun export(kit: Kit, sourceDir: File, dest: File, genre: PatioPerformance.Genre): ExportResult {
        val result = Mpc3Exporter.exportTrack(kit, sourceDir, dest, overwrite = true, clip = groove(genre))
        val samples = File(dest, "${kit.name}_[TrackData]")
        // Exact source-store metadata beside byte-identical chain WAVs, under an explicit
        // name: ordinary MPC import must not mistake it for a flattened history sidecar.
        File(sourceDir, KitStore.FILE_NAME).copyTo(File(samples, "PATIO-source.json"), overwrite = true)
        return result
    }
    fun reloadExport(dataDir: File): Kit = KitStore.read(File(dataDir, "PATIO-source.json"))
}
