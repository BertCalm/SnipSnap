package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale

/**
 * Renders REWIND's listening gate under testkit/rewind-audition/ (gitignored): the real section, through the rack
 * so its tail budget applies as on a pad, on ONSET's snare and tom (the drums the owner liked best at SUB's gate),
 * JANGLE (on its baked pad, REWIND after its amp, where a reverse delay sits) and the choir. Every source is heard
 * dry, flipped whole by REVERSE (what the rack does today), and with REWIND at MIX 1 and one backwards copy at
 * TIME 0, 0.5 and 1 (the backwards attack about 0.23, 0.48 and 1 s after the hit). At TIME 0.5 it is also heard
 * with REPEAT 0.5 and 1 (backwards, then forwards, then backwards, each darker) and at MIX 0.5. Every clip shares
 * one loudness ([AuditionLevel]). Run via `./gradlew :synth:generateRewindAudition`.
 */
object RewindAuditionGenerator {

    /** clip id -> REWIND's macros. */
    internal val SETTINGS = linkedMapOf(
        "rw_t0" to mapOf("MIX" to 1f, "TIME" to 0f, "REPEAT" to 0f),
        "rw_t50" to mapOf("MIX" to 1f, "TIME" to 0.5f, "REPEAT" to 0f),
        "rw_t100" to mapOf("MIX" to 1f, "TIME" to 1f, "REPEAT" to 0f),
        "rw_t50_rep50" to mapOf("MIX" to 1f, "TIME" to 0.5f, "REPEAT" to 0.5f),
        "rw_t50_rep100" to mapOf("MIX" to 1f, "TIME" to 0.5f, "REPEAT" to 1f),
        "rw_t50_mix50" to mapOf("MIX" to 0.5f, "TIME" to 0.5f, "REPEAT" to 0f),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/rewind-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && it.extension == "wav" }.forEach { it.delete() }

        val landing = Magnet.landingChain(MagnetVoice.JANGLE)
        val jangleDry = Magnet.render(MagnetVoice.JANGLE)
        val bare = FxChain()
        // source -> (the dry pad, the chain it is heard through, what that chain renders)
        val sources = linkedMapOf(
            "ONSET_SNARE" to (bare to Onset.render(OnsetVoice.SNARE)),
            "ONSET_TOM" to (bare to Onset.render(OnsetVoice.TOM)),
            "JANGLE" to (landing to jangleDry),
            "CHOIR" to (bare to Vox.render(VoxVoice.CHOIR)),
        )

        for ((name, source) in sources) {
            val (chain, pad) = source
            val dry = chain.process(pad)
            val clips = linkedMapOf<String, Snip>("dry" to dry)
            clips["flip"] = chain.copy(reverse = true).process(pad)
            for ((id, m) in SETTINGS) clips[id] = chain.withSection("rewind", m).process(pad)

            val dir = File(root, name).apply { mkdirs() }
            for ((id, clip) in clips) {
                val mono = if (clip.channels == 1) clip else Snip(FloatArray(clip.frameCount) { f -> 0.5f * (clip.samples[f * 2] + clip.samples[f * 2 + 1]) }, 1, clip.sampleRate)
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono), WavWriter.BitDepth.PCM_16)
                println(String.format(Locale.ROOT, "REWIND audition %s %-14s %.3f s", name, id, clip.frameCount.toFloat() / clip.sampleRate))
            }
        }
    }
}
