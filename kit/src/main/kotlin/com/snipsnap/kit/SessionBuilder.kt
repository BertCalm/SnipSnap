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
 * Every kit's grooves become the project's **sequences** — sequence k
 * plays each kit's k-th pattern, so a chopped kit's four variations
 * arrive as four sequences and the hardware's sequence switcher is the
 * pattern flip. (The corpus rule for projects still holds: hoisted
 * tracks carry no embedded clips, the timeline does.) Tempo comes from
 * the first kit that remembers one.
 */
object SessionBuilder {

    data class Result(
        val xpj: File,
        val dataDir: File,
        val kitTracks: List<String>,
        val instrumentTracks: List<String>,
        val tempoBpm: Float?,
        /** Answer basslines that joined the session, one track name per kit that had one. */
        val answerTracks: List<String> = emptyList(),
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
            throw DestinationExists(xpj)
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
                clips = grooves.take(Mpc3ProjectWriter.MAX_SEQUENCES),
            )
            kitTracks += kit.name
            if (tempo == null) tempo = kit.tempoBpm
        }

        // A kit that has an answer brings it along: the stored bass note
        // rebuilds its keygroup program deterministically via OneNote, and
        // the bassline clip rides the keys track into the sequences.
        val answerTracks = mutableListOf<String>()
        for (kitDir in kitDirs) {
            val answer = AnswerStore.load(kitDir) ?: continue
            val members = listOf(Triple(answer.name, answer.sampleFile, answer.clip)) +
                answer.band.map { Triple(it.name, it.sampleFile, it.clip) }
            for ((name, sampleFile, memberClip) in members) {
                val wav = File(kitDir, sampleFile)
                if (!wav.isFile) {
                    throw IOException("answer.json in $kitDir points at a missing sample: $sampleFile")
                }
                val one = OneNote.program(name, com.snipsnap.audio.WavReader.read(wav))
                val dest = File(dataDir, "${one.sampleStem}.wav")
                if (!dest.exists()) WavWriter.write(dest, one.sample)
                tracks += Mpc3ProjectTrack.Keys(one.program, clips = listOf(memberClip))
                answerTracks += name
            }
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
        // Song slot 1 wears the session's name (safe plumbing; the step
        // list waits on the GG3.2 bench capture).
        val song = com.snipsnap.mpc3.Mpc3Song(name)
        val file = tempo?.let { writer.writeTo(destRoot, name, tracks, it, song = song) }
            ?: writer.writeTo(destRoot, name, tracks, song = song)
        return Result(file, dataDir, kitTracks, instrumentTracks, tempo, answerTracks)
    }
}
