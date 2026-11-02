package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale

/**
 * Renders VOLUME's (FADE IN's) listening gate under testkit/volume-audition/ (gitignored): the real
 * section on the three sources the pedal gates have used (the snare, the choir and JANGLE), each as
 * the dry pad, SWELL for comparison (the brainstorm's "different from SWELL side by side?"), and
 * VOLUME at three rises. JANGLE is heard as the kit has it, and VOLUME sits in front of its landing amp
 * in the rig order, so the fade blooms through the amp. Every clip shares one loudness
 * ([AuditionLevel]) so loudness does not decide the comparison; that also means the snare's fade is
 * heard at the same level as the dry hit, which the shipped section would not give it (it lifts at
 * most 12 dB; the printed peaks say how much it keeps).
 *
 * Also writes one strip per source (dry, SWELL, then the rises, a second of silence between) so the
 * comparison plays in one go. Run via `./gradlew :synth:generateVolumeAudition`.
 */
object VolumeAuditionGenerator {

    /** The rises heard, as RISE values: 240, 448 and 640 ms. 0.56 is a pad-sheet tap (AMT 70) of RISE 0.8. */
    internal val RISES = listOf(0.3f, 0.56f, 0.8f)

    private const val GAP_SEC = 1.0f

    private fun ms(rise: Float) = (rise * Volume.RISE_MAX_SEC * 1000).toInt()

    private fun silence(sec: Float, rate: Int) = FloatArray((sec * rate).toInt())

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/volume-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && it.extension == "wav" }.forEach { it.delete() }

        val landing = Magnet.landingChain(MagnetVoice.JANGLE)
        val jangleDry = Magnet.render(MagnetVoice.JANGLE)
        // source -> (the dry pad as the listener knows it, how to put a section on it)
        val sources = linkedMapOf<String, Pair<Snip, (String, Map<String, Float>) -> Snip>>(
            "SNARE" to (Thump.render(ThumpVoice.SNARE).let { s -> s to { n: String, m: Map<String, Float> -> FxChain().withSection(n, m).process(s) } }),
            "CHOIR" to (Vox.render(VoxVoice.CHOIR).let { s -> s to { n: String, m: Map<String, Float> -> FxChain().withSection(n, m).process(s) } }),
            "JANGLE" to (landing.process(jangleDry) to { n: String, m: Map<String, Float> -> landing.withSection(n, m).process(jangleDry) }),
        )

        for ((name, source) in sources) {
            val (dry, put) = source
            val clips = linkedMapOf<String, Snip>("dry" to dry)
            clips["swell"] = if (name == "JANGLE") put("swell", mapOf("RISE" to 0.6f)) else FxChain(swell = mapOf("RISE" to 0.6f)).process(dry)
            for (r in RISES) clips["fade_${ms(r)}ms"] = put("volume", mapOf("RISE" to r))

            val dir = File(root, name).apply { mkdirs() }
            val strip = ArrayList<Float>()
            for ((id, clip) in clips) {
                val mono = if (clip.channels == 1) clip else Snip(FloatArray(clip.frameCount) { f -> 0.5f * (clip.samples[f * 2] + clip.samples[f * 2 + 1]) }, 1, clip.sampleRate)
                val leveled = AuditionLevel.level(mono)
                WavWriter.write(File(dir, "$id.wav"), leveled, WavWriter.BitDepth.PCM_16)
                strip.addAll(leveled.samples.toList())
                strip.addAll(silence(GAP_SEC, leveled.sampleRate).toList())
                println(String.format(Locale.ROOT, "VOLUME audition %s %-12s %.2f s, dry-relative peak %.3f", name, id, clip.frameCount.toFloat() / clip.sampleRate, clip.peak() / dry.peak()))
            }
            WavWriter.write(File(root, "${name}_strip.wav"), Snip(strip.toFloatArray(), 1, dry.sampleRate), WavWriter.BitDepth.PCM_16)
        }
    }
}
