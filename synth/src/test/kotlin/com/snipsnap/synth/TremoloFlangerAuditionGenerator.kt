package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale

/**
 * Renders Stage 4's one listening gate, TREMOLO and FLANGER together, under testkit/tremflange-audition/
 * (gitignored): the real sections, through the rack, on ONSET's snare (a drum), JANGLE (on its baked pad, both
 * sections after its amp, where they sit) and the choir. Each source is heard dry, then TREMOLO from gentle to its
 * ends (deep, a square chop, the fastest chop at 12 Hz, the slowest swell at 1 Hz), then PHASE (the nearest thing
 * the rack has today) and FLANGER from plain to its ends (the metallic jet, the hollow, the fast warble). JANGLE
 * also carries the brainstorm's AMP TREM A/B: the tremolo before the spring, as the rack orders it, and after it,
 * as a Fender amp wires it. Every clip shares one loudness ([AuditionLevel]). Run via
 * `./gradlew :synth:generateTremoloFlangerAudition`.
 */
object TremoloFlangerAuditionGenerator {

    /** clip id -> (section, macros). */
    internal val SETTINGS = linkedMapOf(
        "trem_gentle" to ("tremolo" to mapOf("DEPTH" to 0.5f, "RATE" to 0.5f, "SHAPE" to 0f)),
        "trem_deep" to ("tremolo" to mapOf("DEPTH" to 1f, "RATE" to 0.5f, "SHAPE" to 0f)),
        "trem_chop" to ("tremolo" to mapOf("DEPTH" to 1f, "RATE" to 0.5f, "SHAPE" to 1f)),
        "trem_fast" to ("tremolo" to mapOf("DEPTH" to 1f, "RATE" to 1f, "SHAPE" to 1f)),
        "trem_slow" to ("tremolo" to mapOf("DEPTH" to 1f, "RATE" to 0f, "SHAPE" to 0f)),
        "phase" to ("phase" to Phase.defaults()),
        "fl_plain" to ("flanger" to mapOf("DEPTH" to 0.7f, "RATE" to 0.4f, "FEEDBACK" to 0.5f)),
        "fl_jet" to ("flanger" to mapOf("DEPTH" to 1f, "RATE" to 0.4f, "FEEDBACK" to 1f)),
        "fl_hollow" to ("flanger" to mapOf("DEPTH" to 1f, "RATE" to 0.4f, "FEEDBACK" to 0f)),
        "fl_fast" to ("flanger" to mapOf("DEPTH" to 1f, "RATE" to 1f, "FEEDBACK" to 0.75f)),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/tremflange-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && it.extension == "wav" }.forEach { it.delete() }

        val bare = FxChain()
        // source -> (the chain it is heard through, the dry pad)
        val sources = linkedMapOf(
            "ONSET_SNARE" to (bare to Onset.render(OnsetVoice.SNARE)),
            "JANGLE" to (Magnet.landingChain(MagnetVoice.JANGLE) to Magnet.render(MagnetVoice.JANGLE)),
            "CHOIR" to (bare to Vox.render(VoxVoice.CHOIR)),
        )

        for ((name, source) in sources) {
            val (chain, pad) = source
            val clips = linkedMapOf<String, Snip>("dry" to chain.process(pad))
            for ((id, setting) in SETTINGS) clips[id] = chain.withSection(setting.first, setting.second).process(pad)
            if (name == "JANGLE") {
                val deep = SETTINGS.getValue("trem_deep").second
                clips["trem_before_spring"] = chain.withSection("tremolo", deep).withSection("spring", Spring.defaults()).process(pad)
                clips["trem_after_spring"] = Tremolo.process(chain.withSection("spring", Spring.defaults()).process(pad), deep)
            }

            val dir = File(root, name).apply { mkdirs() }
            for ((id, clip) in clips) {
                val mono = if (clip.channels == 1) clip else Snip(FloatArray(clip.frameCount) { f -> 0.5f * (clip.samples[f * 2] + clip.samples[f * 2 + 1]) }, 1, clip.sampleRate)
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono), WavWriter.BitDepth.PCM_16)
                println(String.format(Locale.ROOT, "TREM/FLANGE audition %s %-18s %.3f s", name, id, clip.frameCount.toFloat() / clip.sampleRate))
            }
        }
    }
}
