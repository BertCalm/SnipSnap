package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale

/**
 * Renders DIVE's listening gate under testkit/dive-audition/ (gitignored): the real section on the four
 * sources the pedal brainstorm's gate names (a snare, a tom, a rim and the CHUG guitar), each as the dry
 * pad, PITCH down six semitones (the nearest thing the rack has today: one fixed ratio, no fall),
 * DIVE at three depths (3, 6 and 12 semitones, at the default fall of about 170 ms) and at six
 * semitones with a fast (60 ms) and a slow (500 ms) fall. CHUG is heard as the kit has it and DIVE sits
 * before its amp, in the rig order. Every clip shares one loudness ([AuditionLevel]). Run via
 * `./gradlew :synth:generateDiveAudition`.
 */
object DiveAuditionGenerator {

    /** The DROP macros heard: 3, 6 and 12 semitones. */
    internal val DROPS = listOf(0.25f to "3st", 0.5f to "6st", 1f to "12st")

    /** OVER macros for a 60 ms and a 500 ms fall. */
    internal val FAST = Dive.overFor(0.06f)
    internal val SLOW = Dive.overFor(0.5f)

    /** PITCH six semitones down: the macro whose snapped interval is -6. */
    internal val PITCHED = mapOf("SEMITONES" to 0.25f)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/dive-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && it.extension == "wav" }.forEach { it.delete() }

        val landing = Magnet.landingChain(MagnetVoice.CHUG)
        val chugDry = Magnet.render(MagnetVoice.CHUG)
        val snare = Thump.render(ThumpVoice.SNARE)
        val tom = Thump.render(ThumpVoice.TOM)
        val rim = Thump.render(ThumpVoice.RIM)
        // source -> (the dry pad as the listener knows it, how to put a section on it)
        val sources = linkedMapOf<String, Pair<Snip, (String, Map<String, Float>) -> Snip>>(
            "SNARE" to (snare to { n: String, m: Map<String, Float> -> FxChain().withSection(n, m).process(snare) }),
            "TOM" to (tom to { n: String, m: Map<String, Float> -> FxChain().withSection(n, m).process(tom) }),
            "RIM" to (rim to { n: String, m: Map<String, Float> -> FxChain().withSection(n, m).process(rim) }),
            "CHUG" to (landing.process(chugDry) to { n: String, m: Map<String, Float> -> landing.withSection(n, m).process(chugDry) }),
        )

        for ((name, source) in sources) {
            val (dry, put) = source
            val clips = linkedMapOf<String, Snip>("dry" to dry)
            clips["pitch_down_6st"] = put("speed", PITCHED)
            for ((drop, label) in DROPS) clips["dive_$label"] = put("dive", mapOf("DROP" to drop))
            clips["dive_6st_fast"] = put("dive", mapOf("DROP" to 0.5f, "OVER" to FAST))
            clips["dive_6st_slow"] = put("dive", mapOf("DROP" to 0.5f, "OVER" to SLOW))

            val dir = File(root, name).apply { mkdirs() }
            for ((id, clip) in clips) {
                val mono = if (clip.channels == 1) clip else Snip(FloatArray(clip.frameCount) { f -> 0.5f * (clip.samples[f * 2] + clip.samples[f * 2 + 1]) }, 1, clip.sampleRate)
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono), WavWriter.BitDepth.PCM_16)
                println(String.format(Locale.ROOT, "DIVE audition %s %-15s %.2f s, dry-relative peak %.3f", name, id, clip.frameCount.toFloat() / clip.sampleRate, clip.peak() / dry.peak()))
            }
        }
    }
}
