package com.snipsnap.shell

import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.Kit
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3ProjectTrack
import com.snipsnap.mpc3.Mpc3ProjectWriter
import com.snipsnap.mpc3.Mpc3Song
import java.io.File

/**
 * An arranged song written out (KK2, KK3): the `.xpj` a Live III opens,
 * with the plan's sections laid as switchable sequences numbered in plan
 * order, so flipping 01 upward on the hardware IS the song; the song slot
 * wearing the arrangement's name (its steps stay bench-blocked, GG3.2);
 * and, when asked, the song as one WAV beside the project.
 *
 * This was the body of `snipsnap arrange`, and the SONG screen had no way
 * to reach it: what ARRANGE played existed only in memory
 * (docs/plans/2026-10-30-song-making.md, phase 4). Here it is one call
 * the CLI and the phone share, so the two cannot write different songs.
 */
object SongExport {

    /** What [write] put on disk: the project, and the mixdown when one was asked for. */
    data class Written(val project: File, val mixdown: Mixdown?)

    /** The song's WAV and the mix it holds. */
    data class Mixdown(val file: File, val mix: Arranger.Mix)

    /**
     * Write [plan] for [kit] under [destRoot]: `<name>.xpj` and its project
     * data folder, and `<name>.wav` when [mixdown] is set.
     *
     * Every destination is checked before anything is written. Without
     * [overwrite], an existing project, data folder or (when asked for)
     * WAV refuses with [DestinationExists] naming the one on disk, and
     * nothing is touched. The CLI used to check the WAV only after the
     * project was already written, so a refusal left half an export behind.
     */
    fun write(
        kit: Kit,
        kitDir: File,
        plan: Arranger.Arrangement,
        destRoot: File,
        overwrite: Boolean = false,
        mixdown: Boolean = false,
    ): Written {
        val dataDir = File(destRoot, Mpc3ProjectWriter.projectDataDirName(plan.name))
        val xpjDest = File(destRoot, "${plan.name}.xpj")
        val wav = File(destRoot, "${plan.name}.wav")
        if (!overwrite) {
            if (xpjDest.exists() || dataDir.exists()) throw DestinationExists.firstOf(xpjDest, dataDir)
            if (mixdown && wav.exists()) throw DestinationExists(wav)
        }

        dataDir.deleteRecursively()
        val program = Mpc3Exporter.stageTrack(kit, kitDir, dataDir)
        val writer = Mpc3ProjectWriter()
        val tracks = listOf(Mpc3ProjectTrack.Drum(program, clips = sectionClips(plan)))
        val song = Mpc3Song(plan.name)
        val tempo = kit.tempoBpm
        val xpj = if (tempo != null) {
            writer.writeTo(destRoot, plan.name, tracks, tempo, song = song)
        } else {
            writer.writeTo(destRoot, plan.name, tracks, song = song)
        }

        val mixed = if (mixdown) {
            val mix = Arranger.mixdown(kit, kitDir, plan)
            WavWriter.write(wav, mix.snip)
            Mixdown(wav, mix)
        } else {
            null
        }
        return Written(xpj, mixed)
    }

    /** The plan's clips named `01 intro`, `02 theme` … so the sequence list reads as the song. */
    fun sectionClips(plan: Arranger.Arrangement): List<Mpc3Clip> =
        plan.sections.mapIndexed { i, s ->
            s.clip.copy(name = "%02d %s".format(java.util.Locale.ROOT, i + 1, s.name))
        }
}
