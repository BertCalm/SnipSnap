package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale

/**
 * Renders ROTARY's listening gate under testkit/rotary-audition/ (gitignored): the real section, through the rack,
 * on ONSET's snare (a drum), JANGLE and CHUG (on their baked pads, after their amps, where a rotary cabinet sits) and
 * the choir. Each source is heard dry, then ROTARY from the slow chorale to the fast tremolo, the spin-up (RAMP),
 * the brainstorm's candidate tap (`spun` at AMT 70), and TREMOLO plus VIBRATO at the same speed, the nearest thing the
 * rack has today, so the gate can say whether ROTARY is more than the two at once. Every clip shares one loudness
 * ([AuditionLevel]). Run via `./gradlew :synth:generateRotaryAudition`.
 */
object RotaryAuditionGenerator {

    /** The fast speed both comparisons run at: TREMOLO's and VIBRATO's RATE for 7 Hz on their own log laws. */
    private val TREM_RATE_7HZ = (Math.log(7.0 / Tremolo.SLOW_HZ) / Math.log((Tremolo.FAST_HZ / Tremolo.SLOW_HZ).toDouble())).toFloat()
    private val VIB_RATE_7HZ = (Math.log(7.0 / Vibrato.SLOW_HZ) / Math.log((Vibrato.FAST_HZ / Vibrato.SLOW_HZ).toDouble())).toFloat()

    /** clip id -> the chain it adds to the source's (section name -> macros, in rack order). */
    internal val SETTINGS: LinkedHashMap<String, List<Pair<String, Map<String, Float>>>> = linkedMapOf(
        "rot_chorale" to listOf("rotary" to mapOf("SPIN" to 1f, "SPEED" to 0f, "RAMP" to 0f)),
        // The section's defaults at the gate (2026-11-13), before SPEED and RAMP took the chosen Spin-up's.
        "rot_middle" to listOf("rotary" to mapOf("SPIN" to 0.8f, "SPEED" to 0.5f, "RAMP" to 0f)),
        "rot_fast" to listOf("rotary" to mapOf("SPIN" to 1f, "SPEED" to 1f, "RAMP" to 0f)),
        "rot_spinup" to listOf("rotary" to mapOf("SPIN" to 1f, "SPEED" to 1f, "RAMP" to 1f)),
        // The brainstorm's `spun` = {SPIN 1.0, RAMP 0.6} at the pad sheet's AMT 70: SPIN 0.7, RAMP 0.42, SPEED its neutral.
        "rot_spun_tap" to listOf("rotary" to mapOf("SPIN" to 0.7f, "SPEED" to 0.5f, "RAMP" to 0.42f)),
        // The horn's pitch swings about 40 cents each way and its level about 11 dB at 7 Hz: VIBRATO at 36 cents and
        // TREMOLO at DEPTH 0.65, both at 7 Hz, over the whole band.
        "trem_plus_vib" to listOf(
            "vibrato" to mapOf("DEPTH" to 0.6f, "RATE" to VIB_RATE_7HZ, "LATE" to 0f),
            "tremolo" to mapOf("DEPTH" to 0.65f, "RATE" to TREM_RATE_7HZ, "SHAPE" to 0f),
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/rotary-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && it.extension == "wav" }.forEach { it.delete() }

        val bare = FxChain()
        // source -> (the chain it is heard through, the dry pad)
        val sources = linkedMapOf(
            "ONSET_SNARE" to (bare to Onset.render(OnsetVoice.SNARE)),
            "JANGLE" to (Magnet.landingChain(MagnetVoice.JANGLE) to Magnet.render(MagnetVoice.JANGLE)),
            "CHUG" to (Magnet.landingChain(MagnetVoice.CHUG) to Magnet.render(MagnetVoice.CHUG)),
            "CHOIR" to (bare to Vox.render(VoxVoice.CHOIR)),
        )

        for ((name, source) in sources) {
            val (chain, pad) = source
            val clips = linkedMapOf<String, Snip>("dry" to chain.process(pad))
            for ((id, sections) in SETTINGS) {
                var c = chain
                for ((section, macros) in sections) c = c.withSection(section, macros)
                clips[id] = c.process(pad)
            }

            val dir = File(root, name).apply { mkdirs() }
            for ((id, clip) in clips) {
                val mono = if (clip.channels == 1) clip else Snip(FloatArray(clip.frameCount) { f -> 0.5f * (clip.samples[f * 2] + clip.samples[f * 2 + 1]) }, 1, clip.sampleRate)
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono), WavWriter.BitDepth.PCM_16)
                println(String.format(Locale.ROOT, "ROTARY audition %s %-14s %.3f s", name, id, clip.frameCount.toFloat() / clip.sampleRate))
            }
        }
    }
}
