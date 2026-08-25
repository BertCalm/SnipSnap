package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3ProjectTrack
import com.snipsnap.mpc3.Mpc3ProjectWriter
import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.KeygroupProgram
import java.io.File
import java.io.IOException

/**
 * Whole sessions, not just kits: N kit folders and M instruments become
 * one `.xpj` the MPC opens with everything standing — kits on tracks in
 * their colours, each carrying its own grooves, instruments beside them,
 * mixer wired.
 *
 * Every kit's samples land in the shared flat `_[ProjectData]/` with a
 * per-kit stem prefix, so two chopped kits' `A01_Kick_01`s can't collide.
 * The first kit's first groove rides the sequence — the project plays
 * something the moment it opens. (The corpus rule for projects: hoisted
 * tracks carry no embedded clips, the timeline does — so the other kits'
 * grooves stay in their folders, riding their standalone exports.)
 * Tempo comes from the first kit that remembers one.
 */
object SessionBuilder {

    data class Result(
        val xpj: File,
        val dataDir: File,
        val kitTracks: List<String>,
        val instrumentTracks: List<String>,
        val tempoBpm: Float?,
    )

    fun build(
        name: String,
        kitDirs: List<File>,
        destRoot: File,
        /** Instruments as program + its samples (see `OneNote.MultiResult`). */
        instruments: List<Pair<KeygroupProgram, Map<String, Snip>>> = emptyList(),
        overwrite: Boolean = false,
    ): Result {
        require(Names.isMpcSafe(name)) { "session name isn't MPC-safe: '$name'" }
        require(kitDirs.isNotEmpty()) { "a session needs at least one kit" }

        destRoot.mkdirs()
        val xpj = File(destRoot, "$name.xpj")
        val dataDir = File(destRoot, Mpc3ProjectWriter.projectDataDirName(name))
        if ((xpj.exists() || dataDir.exists()) && !overwrite) {
            throw IOException("destination already exists: $xpj (pass overwrite=true to replace same-named files)")
        }
        dataDir.deleteRecursively()
        dataDir.mkdirs()

        var tempo: Float? = null
        val kitTracks = mutableListOf<String>()
        val tracks = mutableListOf<Mpc3ProjectTrack>()

        kitDirs.forEachIndexed { index, kitDir ->
            val kit = KitStore.load(kitDir)
            val findings = Preflight.check(kit, kitDir)
            if (findings.blocked()) throw ExportBlockedException(findings)
            val prefix = Names.sanitizeStem("K${index + 1}") + "_"
            val (slots, _) = KitExporter.buildSlots(kit, kitDir, dataDir, stemPrefix = prefix)
            val grooves = GrooveStore.load(kitDir)
            tracks += Mpc3ProjectTrack.Drum(
                DrumProgram(kit.name, slots),
                clip = grooves.firstOrNull(),
            )
            kitTracks += kit.name
            if (tempo == null) tempo = kit.tempoBpm
        }

        val instrumentTracks = mutableListOf<String>()
        for ((program, samples) in instruments) {
            for ((stem, snip) in samples) {
                val dest = File(dataDir, "$stem.wav")
                if (dest.exists()) {
                    throw IllegalArgumentException(
                        "instrument sample '$stem' collides with an existing file in the session",
                    )
                }
                WavWriter.write(dest, snip)
            }
            tracks += Mpc3ProjectTrack.Keys(program)
            instrumentTracks += program.name
        }

        val writer = Mpc3ProjectWriter()
        val file = tempo?.let { writer.writeTo(destRoot, name, tracks, it) }
            ?: writer.writeTo(destRoot, name, tracks)
        return Result(file, dataDir, kitTracks, instrumentTracks, tempo)
    }
}
