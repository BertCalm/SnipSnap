package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitPreview
import java.io.File
import java.util.Locale

/**
 * Renders PUMP's listening gate under testkit/pump-audition/ (gitignored): the real treatment at the kit's default
 * tempo, on held sounds only, since a pad under two divisions is refused (every drum one-shot is). JANGLE and CHUG on
 * their baked pads, after their amps, where ducking belongs; the choir; the organ. Each is heard dry, then PUMP at a
 * tap (AMOUNT 0.7) and at full on the quarter, and a tap on the eighth, then GATE and TREMOLO on the same beat at the
 * same depth, so the gate can say whether it pumps rather than chops or throbs. Every clip shares one loudness
 * ([AuditionLevel]). Run via `./gradlew :synth:generatePumpAudition`.
 */
object PumpAuditionGenerator {

    val BPM = KitPreview.DEFAULT_BPM

    /** TREMOLO's RATE for one throb a quarter at [BPM], on its own log law. */
    private val TREM_RATE_BEAT = (Math.log(BPM / 60.0 / Tremolo.SLOW_HZ) / Math.log((Tremolo.FAST_HZ / Tremolo.SLOW_HZ).toDouble())).toFloat()

    /** TREMOLO's DEPTH whose trough is a tap's dip: 1 - DEPTH is the dip's gain. */
    private val TREM_DEPTH_TAP = 1f - Pump.dipGain(0.7f)

    /** clip id -> what it does to the source's own pad. */
    internal val SETTINGS: LinkedHashMap<String, (Snip) -> Snip> = linkedMapOf(
        "pump_tap" to { s -> Pump.pump(s, BPM, "1/4", 0.7f) },
        "pump_full" to { s -> Pump.pump(s, BPM, "1/4", 1f) },
        "pump_eighth" to { s -> Pump.pump(s, BPM, "1/8", 0.7f) },
        "gate_quarter" to { s -> Gate.chop(s, BPM, "1/4", 0.7f) },
        "trem_beat" to { s -> FxChain(tremolo = mapOf("DEPTH" to TREM_DEPTH_TAP, "RATE" to TREM_RATE_BEAT, "SHAPE" to 0f)).process(s) },
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/pump-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && it.extension == "wav" }.forEach { it.delete() }

        // source -> the pad as the kit holds it (after its own chain), which is what a keyed treatment reads
        val sources = linkedMapOf(
            "JANGLE" to Magnet.landingChain(MagnetVoice.JANGLE).process(Magnet.render(MagnetVoice.JANGLE)),
            "CHUG" to Magnet.landingChain(MagnetVoice.CHUG).process(Magnet.render(MagnetVoice.CHUG)),
            "CHOIR" to Vox.render(VoxVoice.CHOIR),
            "ORGAN" to Otis.render(OtisVoice.ORGAN),
        )

        for ((name, pad) in sources) {
            val why = Pump.refusal(pad, BPM, "1/4")
            if (why != null) {
                println("PUMP audition $name skipped: $why")
                continue
            }
            val clips = linkedMapOf("dry" to pad)
            for ((id, treat) in SETTINGS) clips[id] = treat(pad)
            val dir = File(root, name).apply { mkdirs() }
            for ((id, clip) in clips) {
                val mono = if (clip.channels == 1) clip else Snip(FloatArray(clip.frameCount) { f -> 0.5f * (clip.samples[f * 2] + clip.samples[f * 2 + 1]) }, 1, clip.sampleRate)
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono), WavWriter.BitDepth.PCM_16)
                println(String.format(Locale.ROOT, "PUMP audition %s %-13s %.3f s", name, id, clip.frameCount.toFloat() / clip.sampleRate))
            }
        }
    }
}
