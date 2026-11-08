package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale

/**
 * Renders SUB's listening gate under testkit/octaver-audition/ (gitignored): the real section on the kit's
 * drums, where the pedal brainstorm said SUB is worth most, a snare, a low and a mid tom, plus the short set
 * (a rim and a closed hat) and the kick, whose octave (29 Hz here) is mostly under 40 Hz and is heard to
 * show the hazard. Every source is heard dry, pitched down an octave by PITCH (the whole pad, twice as long,
 * which is what the rack already does), and with the octave under it at DEPTH 0.35, 0.7 and 1 at the
 * section's defaults (DRIVE 1, Grit, the owner's pick). At DEPTH 0.7 each is also heard clean (DRIVE 0),
 * at Fuzz ([Octaver.FUZZ]) and halfway from Fuzz to Grit, and Fuzz at DEPTH 1. The snare and the low tom are also heard with
 * the copy held to the hit (HANG 0), and the snare and the mid tom with the copy dark (TONE 0, 150 Hz) and
 * open (TONE 1, 2.4 kHz), at DEPTH 0.7. The snare, the low tom and the kick are also heard across DRIVE in
 * eight steps at DEPTH 0.5, to judge that the knob moves evenly. Every clip shares one loudness
 * ([AuditionLevel]). Run via
 * `./gradlew :synth:generateOctaverAudition`.
 */
object OctaverAuditionGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/octaver-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && it.extension == "wav" }.forEach { it.delete() }

        val sources = linkedMapOf(
            "SNARE" to Thump.render(ThumpVoice.SNARE),
            "LOW_TOM" to Thump.render(ThumpVoice.TOM, mapOf("TUNE" to 0.2f)),
            "MID_TOM" to Thump.render(ThumpVoice.TOM, mapOf("TUNE" to 0.5f)),
            "RIM" to Thump.render(ThumpVoice.RIM),
            "HAT_CLOSED" to Thump.render(ThumpVoice.HAT_CLOSED),
            "KICK" to Thump.render(ThumpVoice.KICK),
        )

        for ((name, dry) in sources) {
            val clips = linkedMapOf<String, Snip>("dry" to dry)
            clips["pitch_down_12"] = Speed.process(dry, mapOf("SEMITONES" to 0f))
            for (d in listOf(35, 70, 100)) clips["sub_$d"] = Octaver.process(dry, mapOf("DEPTH" to d / 100f))
            clips["sub_70_clean"] = Octaver.process(dry, mapOf("DEPTH" to 0.7f, "DRIVE" to 0f))
            clips["sub_70_between"] = Octaver.process(dry, mapOf("DEPTH" to 0.7f, "DRIVE" to (Octaver.FUZZ + 1f) / 2f))
            clips["sub_70_fuzz"] = Octaver.process(dry, mapOf("DEPTH" to 0.7f, "DRIVE" to Octaver.FUZZ))
            clips["sub_100_fuzz"] = Octaver.process(dry, mapOf("DEPTH" to 1f, "DRIVE" to Octaver.FUZZ))
            if (name == "SNARE" || name == "LOW_TOM" || name == "KICK") {
                for (d in listOf(0f, 0.16f, 0.33f, 0.49f, Octaver.FUZZ, 0.77f, 0.88f, 1f)) {
                    clips[String.format(Locale.ROOT, "drive_%03d", (d * 100).toInt())] = Octaver.process(dry, mapOf("DEPTH" to 0.5f, "DRIVE" to d))
                }
            }
            if (name == "SNARE" || name == "LOW_TOM") {
                clips["sub_70_hang0"] = Octaver.process(dry, mapOf("DEPTH" to 0.7f, "HANG" to 0f))
            }
            if (name == "SNARE" || name == "MID_TOM") {
                clips["sub_70_dark"] = Octaver.process(dry, mapOf("DEPTH" to 0.7f, "TONE" to 0f))
                clips["sub_70_open"] = Octaver.process(dry, mapOf("DEPTH" to 0.7f, "TONE" to 1f))
            }

            val dir = File(root, name).apply { mkdirs() }
            for ((id, clip) in clips) {
                val mono = if (clip.channels == 1) clip else Snip(FloatArray(clip.frameCount) { f -> 0.5f * (clip.samples[f * 2] + clip.samples[f * 2 + 1]) }, 1, clip.sampleRate)
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono), WavWriter.BitDepth.PCM_16)
                println(String.format(Locale.ROOT, "SUB audition %s %-14s %.3f s", name, id, clip.frameCount.toFloat() / clip.sampleRate))
            }
        }
    }
}
