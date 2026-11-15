package com.snipsnap.shell

import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.synth.PatioPerformance
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PatioPerformanceGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/patio-performance").absoluteFile
        root.mkdirs()
        for (genre in PatioPerformance.Genre.entries) {
            val source = File(root, "source/${genre.id}")
            val kit = requireNotNull(StarterKits.byId(genre.id)).render(genre.title, source)
            val dest = File(root, "packages/${genre.id}")
            // This generated package must not retain samples from a replaced pad.
            if (dest.exists()) check(dest.deleteRecursively())
            PatioPrograms.export(kit, source, dest, genre)
            File(dest, "README.txt").writeText("${genre.title}\nLoad ${genre.title}.xtd on MPC 3.\nKeep the matching _[TrackData] folder beside it.\n18 pads: 16 core voices, B01 hand roll, B02 cuica-inspired friction drum.\nEvery pad has 3 velocity zones and 4 cycling takes per zone.\nMPC 2 XPM is intentionally not supplied: it would lose combined cycling.\nPATIO-source.json beside the samples preserves SnipSnap's exact kit and source recipes.\nThe embedded pattern is an authored genre-inspired audition, not a traditional transcription.\n")
            ZipOutputStream(File(root, "${genre.id}-mpc3.zip").outputStream().buffered()).use { out ->
                dest.walkTopDown().filter { it.isFile }.forEach { f ->
                    out.putNextEntry(ZipEntry(f.relativeTo(dest).invariantSeparatorsPath).apply { time = 0 })
                    f.inputStream().use { it.copyTo(out) }; out.closeEntry()
                }
            }
            fun emit(id: String, clip: Mpc3Clip, bpm: Float = 100f) {
                val audio = KitPreview.render(KitStore.load(source), source, clip = clip, tempoBpm = bpm)
                check(audio.samples.all { it.isFinite() } && audio.peak() <= 1f)
                WavWriter.write(File(root, "listen/${genre.id}-$id.wav"), AuditionLevel.level(audio), WavWriter.BitDepth.PCM_16)
            }
            emit("groove", PatioPrograms.groove(genre))
            for (pad in kit.pads) {
                // Fixed playback level within the take: four repeats at each velocity.
                val longPhrase = pad.slot == 17
                val cuica = pad.slot == 18
                val spacing = if (longPhrase) 8L else if (cuica) 1920L else 960L
                val notes = (0 until 12).map { i ->
                    Mpc3Note(Mpc3Note.noteFor(pad.slot), if (longPhrase) i * spacing * 960 else i * spacing,
                        listOf(.25f, .55f, .95f)[i / 4])
                }
                emit("pad-${pad.slot}", Mpc3Clip("Velocity and repeats", if (longPhrase) 24 else if (cuica) 6 else 3, notes), if (longPhrase || cuica) 120f else 160f)
            }
            for ((label, hold) in listOf("short" to 0f, "long" to 1f)) {
                val voice = PatioPerformance.render(PatioPerformance.Recipe("cuica", genre, hold), 2, 0)
                WavWriter.write(File(root, "listen/${genre.id}-cuica-$label.wav"),
                    AuditionLevel.level(voice), WavWriter.BitDepth.PCM_16)
            }
            println("${genre.title}: 18 pads × 3 velocities × 4 takes, native MPC 3 and saved source kit.")
        }
        File(root, "index.html").writeText(PatioPerformanceGenerator::class.java.getResource("/audition/patio-performance.html")!!.readText())
    }
}
