package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale

/**
 * Renders VIBRATO's listening gate under testkit/vibrato-audition/ (gitignored): the real section on the
 * three sources the pedal gates have used (the snare, the choir and JANGLE), each as the dry pad, TAPE's
 * wobble at 0.8 (the closest thing the rack has today, and the pedal brainstorm's "play TAPE WOBBLE 0.8
 * beside it"), VIBRATO at three depths (21, 42 and 60 cents, at the default 5.5 Hz) and at 42 cents with
 * a slow (3 Hz) and a fast (8 Hz) rate. JANGLE is heard as the kit has it and VIBRATO sits after its amp,
 * in the rig order. Every clip shares one loudness ([AuditionLevel]). Run via
 * `./gradlew :synth:generateVibratoAudition`.
 */
object VibratoAuditionGenerator {

    /** The depth macros heard: 21, 42 and 60 cents. */
    internal val DEPTHS = listOf(0.35f to "21c", 0.7f to "42c", 1f to "60c")

    /** RATE macros for 3 Hz and 8 Hz. */
    internal val SLOW = (3f - Vibrato.SLOW_HZ) / (Vibrato.FAST_HZ - Vibrato.SLOW_HZ)
    internal val FAST = (8f - Vibrato.SLOW_HZ) / (Vibrato.FAST_HZ - Vibrato.SLOW_HZ)

    /** TAPE's wobble alone: the wow and flutter with no saturation and no head wear. */
    internal val TAPE_WOBBLE = mapOf("WOBBLE" to 0.8f, "DRIVE" to 0f, "AGE" to 0f)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/vibrato-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && it.extension == "wav" }.forEach { it.delete() }

        val landing = Magnet.landingChain(MagnetVoice.JANGLE)
        val jangleDry = Magnet.render(MagnetVoice.JANGLE)
        val snare = Thump.render(ThumpVoice.SNARE)
        val choir = Vox.render(VoxVoice.CHOIR)
        // source -> (the dry pad as the listener knows it, how to put a section on it)
        val sources = linkedMapOf<String, Pair<Snip, (String, Map<String, Float>) -> Snip>>(
            "JANGLE" to (landing.process(jangleDry) to { n: String, m: Map<String, Float> -> landing.withSection(n, m).process(jangleDry) }),
            "CHOIR" to (choir to { n: String, m: Map<String, Float> -> FxChain().withSection(n, m).process(choir) }),
            "SNARE" to (snare to { n: String, m: Map<String, Float> -> FxChain().withSection(n, m).process(snare) }),
        )

        for ((name, source) in sources) {
            val (dry, put) = source
            val clips = linkedMapOf<String, Snip>("dry" to dry)
            clips["tape_wobble_80"] = put("tape", TAPE_WOBBLE)
            for ((depth, label) in DEPTHS) clips["vib_$label"] = put("vibrato", mapOf("DEPTH" to depth))
            clips["vib_42c_slow"] = put("vibrato", mapOf("DEPTH" to 0.7f, "RATE" to SLOW))
            clips["vib_42c_fast"] = put("vibrato", mapOf("DEPTH" to 0.7f, "RATE" to FAST))

            val dir = File(root, name).apply { mkdirs() }
            for ((id, clip) in clips) {
                val mono = if (clip.channels == 1) clip else Snip(FloatArray(clip.frameCount) { f -> 0.5f * (clip.samples[f * 2] + clip.samples[f * 2 + 1]) }, 1, clip.sampleRate)
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono), WavWriter.BitDepth.PCM_16)
                println(String.format(Locale.ROOT, "VIBRATO audition %s %-15s %.2f s, dry-relative peak %.3f", name, id, clip.frameCount.toFloat() / clip.sampleRate, clip.peak() / dry.peak()))
            }
        }
    }
}
