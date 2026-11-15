package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitPreview
import java.io.File
import java.util.Locale

/**
 * Renders ON BEAT's listening gate under testkit/onbeat-audition/ (gitignored): the real treatment at the kit's
 * default tempo on ONSET's snare and closed hat (a delay is most for drums), JANGLE on its baked pad after its amp,
 * and the choir. Each is heard dry, then ON BEAT at a tap (AMOUNT 0.7) on the dotted eighth, the eighth and the
 * quarter, and at full on the dotted eighth, then the DELAY and SLAP chips' taps as they are today, so the gate can
 * say whether repeats on the beat are more than repeats in seconds. Every clip shares one loudness ([AuditionLevel]).
 * Run via `./gradlew :synth:generateOnBeatAudition`.
 */
object OnBeatAuditionGenerator {

    val BPM = KitPreview.DEFAULT_BPM

    /** clip id -> what it does to the source's own pad. */
    internal val SETTINGS: LinkedHashMap<String, (Snip) -> Snip> = linkedMapOf(
        "onbeat_dotted_tap" to { s -> OnBeat.echo(s, BPM, "3/16", 0.7f) },
        "onbeat_dotted_full" to { s -> OnBeat.echo(s, BPM, "3/16", 1f) },
        "onbeat_eighth_tap" to { s -> OnBeat.echo(s, BPM, "1/8", 0.7f) },
        "onbeat_quarter_tap" to { s -> OnBeat.echo(s, BPM, "1/4", 0.7f) },
        "delay_tap" to { s -> Treatments.apply("delayed", s, 0.7f).snip },
        "slap_tap" to { s -> Treatments.apply("slapback", s, 0.7f).snip },
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/onbeat-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && it.extension == "wav" }.forEach { it.delete() }

        // source -> the pad as the kit holds it (after its own chain), which is what a keyed treatment reads
        val sources = linkedMapOf(
            "ONSET_SNARE" to Onset.render(OnsetVoice.SNARE),
            "ONSET_HAT" to Onset.render(OnsetVoice.HAT_CLOSED),
            "JANGLE" to Magnet.landingChain(MagnetVoice.JANGLE).process(Magnet.render(MagnetVoice.JANGLE)),
            "CHOIR" to Vox.render(VoxVoice.CHOIR),
        )

        for ((name, pad) in sources) {
            val clips = linkedMapOf("dry" to pad)
            for ((id, treat) in SETTINGS) clips[id] = treat(pad)
            val dir = File(root, name).apply { mkdirs() }
            for ((id, clip) in clips) {
                val mono = if (clip.channels == 1) clip else Snip(FloatArray(clip.frameCount) { f -> 0.5f * (clip.samples[f * 2] + clip.samples[f * 2 + 1]) }, 1, clip.sampleRate)
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono), WavWriter.BitDepth.PCM_16)
                println(String.format(Locale.ROOT, "ON BEAT audition %s %-19s %.3f s", name, id, clip.frameCount.toFloat() / clip.sampleRate))
            }
        }
    }
}
