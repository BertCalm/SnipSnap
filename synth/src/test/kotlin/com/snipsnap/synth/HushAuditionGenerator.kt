package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale

/**
 * Renders HUSH's listening gate under testkit/hush-audition/ (gitignored): the real section on the sources
 * the pedal brainstorm's gate names, a snare, a snare through ROOM (the gated reverb), a tom through ROOM
 * and the CHUG guitar through DRIVE, plus an open hat (where a gate is a closed hat and the top of FLOOR a stub). Each is
 * heard dry and gated at 36, 24 and 12 dB under its peak at the default close (about 87 ms), and at 18 dB
 * with a slow (300 ms) and a fast (25 ms) close. Clips are named in dB, so reshaping FLOOR's law after the
 * gate (2026-11-07) left them as heard. The ROOM is baked first, as tapping ROOM and then the gate on the pad sheet would.
 * Every clip shares one loudness ([AuditionLevel]). Run via `./gradlew :synth:generateHushAudition`.
 */
object HushAuditionGenerator {

    /** The thresholds heard, in dB under the peak. */
    internal val FLOORS = listOf(36f, 24f, 12f)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/hush-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && it.extension == "wav" }.forEach { it.delete() }

        val snare = Thump.render(ThumpVoice.SNARE)
        val tom = Thump.render(ThumpVoice.TOM)
        val hat = Thump.render(ThumpVoice.HAT_OPEN)
        val chug = Magnet.landingChain(MagnetVoice.CHUG).process(Magnet.render(MagnetVoice.CHUG))
        val sources = linkedMapOf(
            "SNARE" to snare,
            "SNARE_ROOM" to Treatments.apply("roomed", snare, 1f).snip,
            "TOM_ROOM" to Treatments.apply("roomed", tom, 1f).snip,
            "HAT_OPEN" to hat,
            "CHUG_DRIVE" to Treatments.apply("driven", chug, 0.7f).snip,
        )

        for ((name, dry) in sources) {
            val clips = linkedMapOf<String, Snip>("dry" to dry)
            for (db in FLOORS) clips["hush_${db.toInt()}db"] = Hush.process(dry, mapOf("FLOOR" to Hush.floorFor(db)))
            clips["hush_18db_slow"] = Hush.process(dry, mapOf("FLOOR" to Hush.floorFor(18f), "CLOSE" to 0f))
            clips["hush_18db_fast"] = Hush.process(dry, mapOf("FLOOR" to Hush.floorFor(18f), "CLOSE" to 1f))

            val dir = File(root, name).apply { mkdirs() }
            for ((id, clip) in clips) {
                val mono = if (clip.channels == 1) clip else Snip(FloatArray(clip.frameCount) { f -> 0.5f * (clip.samples[f * 2] + clip.samples[f * 2 + 1]) }, 1, clip.sampleRate)
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono), WavWriter.BitDepth.PCM_16)
                var last = clip.frameCount
                while (last > 0 && clip.samples[(last - 1) * clip.channels] == 0f) last--
                println(String.format(Locale.ROOT, "HUSH audition %s %-15s %.2f s, sounding for %.3f s", name, id, clip.frameCount.toFloat() / clip.sampleRate, last.toFloat() / clip.sampleRate))
            }
        }
    }
}
