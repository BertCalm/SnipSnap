package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The strikers HIT is measured and heard with.
 *
 * [ten] are struck-shape's ten (Phase-0 record §3.1, which names them): six
 * synthesised from engine recipes and four factory samples. The record
 * gives no macro values, and nor does anything else in the tree. The
 * values below are copied from Phase 0's harness, `Phase0.kt:54-63` in the
 * evidence folder the record's Appendix C names (local, not in the tree),
 * and match it value for value. TerraTest's `the ten strikers reproduce
 * Phase 0's overtone spread at subtle - recipe provenance` is the in-tree
 * check that they are still the strikers Phase 0 measured.
 *
 * Phase 0's G-C1 counted thirteen strikers: round two's three plus these
 * ten. Round two's THUMP KICK, THUMP SNARE and WRAITH WORD are the same
 * recipes as three of the ten (`TerraStruckR2.kt:39-44` in the same evidence
 * folder), so the ten
 * distinct sources here cover all thirteen.
 *
 * [hostile] is struck-motion M7.1's list (spec, "Testing", test 3), plus:
 * the 1e-4 boundary read both ways, the decision-20 source, the cancelling
 * stereo pair, and four foreign-rate sources (a 48 kHz kick after 500 ms of
 * silence, 48 kHz silence, a 3-sample 48 kHz source and a 22.05 kHz kick).
 */
internal object TerraStrikers {

    class Source(val id: String, val name: String, val snip: Snip)

    class Hostile(val label: String, val snip: Snip, val expectsHit: Boolean)

    /** The factory samples: from `:synth`'s test working directory, or from the repository root. Fails loudly, never silently skips. */
    fun factoryDir(): File {
        val candidates = listOf(File("../testkit/Expansions/SnipSnap Factory/Samples"), File("testkit/Expansions/SnipSnap Factory/Samples"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("the factory samples are missing; looked in ${candidates.joinToString { it.absolutePath }}")
    }

    fun factory(file: String, dir: File = factoryDir()): Snip {
        val f = File(dir, file)
        require(f.isFile) { "factory sample missing: ${f.absolutePath}" }
        return WavReader.read(f)
    }

    fun ten(dir: File = factoryDir()): List<Source> = listOf(
        Source("bbkick", "BEATBOX KICK", Vox.render(VoxVoice.BEATBOX, mapOf("HIT" to 0f, "TUNE" to 0.30f, "DECAY" to 0.20f))),
        Source("bbrim", "BEATBOX RIM", Vox.render(VoxVoice.BEATBOX, mapOf("HIT" to 1f, "TUNE" to 0.65f, "DECAY" to 0.20f))),
        Source("tkick", "THUMP KICK", Thump.render(ThumpVoice.KICK, mapOf("TUNE" to 0.45f, "DECAY" to 0.22f, "CLICK" to 0.85f, "DRIVE" to 0.4f))),
        Source("tsnare", "THUMP SNARE", Thump.render(ThumpVoice.SNARE, mapOf("TUNE" to 0.50f, "DECAY" to 0.25f, "SNAP" to 0.80f))),
        Source(
            "wraith", "WRAITH WORD",
            Vox.render(VoxVoice.WRAITH, mapOf("WORD" to 0.2f, "TUNE" to 0.40f, "DECAY" to 0.60f, "TUNED" to 0.5f, "ALIEN" to 0.3f, "BREATH" to 0.2f)),
        ),
        Source("noise", "NOISE HAMMER", Snip(Fork.excite(ForkVoice.TINE, 220f, 0.5f, null, Fork.STRIKER_SAMPLES, Dsp.RATE), 1, Dsp.RATE)),
        Source("kick01", "FACTORY KICK", factory("A01_Kick_01.wav", dir)),
        Source("snare01", "FACTORY SNARE", factory("A02_Snare_01.wav", dir)),
        Source("hat01", "FACTORY HAT", factory("A03_HatClosed_01.wav", dir)),
        Source("clap01", "FACTORY CLAP", factory("A06_Clap_01.wav", dir)),
    )

    val TEN: List<Source> by lazy { ten() }

    fun source(id: String): Snip = TEN.first { it.id == id }.snip

    fun head(id: String): FloatArray = Terra.captureStriker(source(id)) ?: error("striker $id captured as silence")

    /** White noise scaled so its finite peak is exactly [peak]. */
    private fun noise(peak: Float, seconds: Float, seed: Int): FloatArray {
        val r = Random(seed)
        val x = FloatArray((seconds * Dsp.RATE).toInt()) { r.nextFloat() * 2f - 1f }
        var pk = 0f
        for (v in x) pk = maxOf(pk, abs(v))
        for (i in x.indices) x[i] = x[i] / pk * peak
        return x
    }

    private fun mono(x: FloatArray) = Snip(x, 1, Dsp.RATE)

    private fun silence(ms: Int) = FloatArray(ms * Dsp.RATE / 1000)

    private fun spike(at: Int) = FloatArray(6000).also { it[at] = 0.5f }

    private fun square(hz: Float) = FloatArray(Dsp.RATE / 5) { i -> if (sin(2.0 * PI * hz * i / Dsp.RATE) >= 0.0) 1f else -1f }

    /**
     * Decision 20's source: a click under the floor (6e-5), then 50 ms later
     * a hit whose peak (5e-3) is between 1e-4 and 1e-2. The onset is the
     * click, because 6e-5 is above 1 % of 5e-3. The aligned head's peak is
     * the click's, under 1e-4, so the head rule refuses it. The whole-source
     * rule would have captured the click as a hammer.
     */
    private fun quietClickThenHit(): FloatArray {
        val x = FloatArray(6000)
        x[0] = 6e-5f
        for (i in 0 until 2205) x[2205 + i] = (5e-3 * sin(2.0 * PI * 300.0 * i / Dsp.RATE)).toFloat()
        return x
    }

    /**
     * White noise at half of [peak], with one sample of exactly [peak] at
     * [at]. The onset is within the first few samples, so the head covers
     * samples 0 to about 880: a peak at 100 is inside it, one at 5000 is not.
     */
    private fun noisePeakingAt(peak: Float, at: Int, seed: Int): FloatArray =
        noise(peak / 2f, 0.2f, seed).also { it[at] = peak }

    /**
     * The 1e-4 boundary. Spec test 3 says white noise "at 1e-4 and below"
     * falls back. The capture rule it tests says a head peak *below* 1e-4
     * gives no striker (decision 20; Phase 0's `SILENT_PEAK` test is a
     * strict `<` too). The two disagree at exactly 1e-4, and this list
     * follows the rule:
     * - a head whose own peak is exactly 1e-4 is a hit;
     * - noise peaking at 1e-4 only after the head falls back, because the
     *   head's own peak is half that.
     * The disagreement is flagged for the spec.
     */
    fun hostile(): List<Hostile> {
        val kick = source("tkick")
        require(kick.channels == 1) { "THUMP KICK renders mono at WIDTH 0" }
        val k = kick.samples
        val nan = k.copyOf().also { it[10] = Float.NaN }
        val inf = k.copyOf().also { it[5] = Float.POSITIVE_INFINITY }
        val at48 = Resampler.resample(kick, 48_000).samples
        val at22 = Resampler.resample(kick, 22_050).samples
        return listOf(
            Hostile("digital silence", mono(FloatArray(4410)), false),
            Hostile("an empty source", mono(FloatArray(0)), false),
            Hostile("white noise just under 1e-4", mono(noise(0.9e-4f, 0.2f, 1)), false),
            Hostile("white noise peaking at exactly 1e-4 inside the head", mono(noisePeakingAt(1e-4f, 100, 6)), true),
            Hostile("white noise peaking at exactly 1e-4 after the head", mono(noisePeakingAt(1e-4f, 5000, 7)), false),
            Hostile("white noise at 1e-5", mono(noise(1e-5f, 0.2f, 2)), false),
            Hostile("white noise at 1e-3", mono(noise(1e-3f, 0.2f, 3)), true),
            Hostile("white noise at 1e-2", mono(noise(1e-2f, 0.2f, 4)), true),
            Hostile("a kick with a NaN at sample 10", mono(nan), true),
            Hostile("a kick with +Inf at sample 5", mono(inf), true),
            Hostile("a kick after 20 ms of silence", mono(silence(20) + k), true),
            Hostile("a kick after 40 ms of silence", mono(silence(40) + k), true),
            Hostile("a kick after 100 ms of silence", mono(silence(100) + k), true),
            Hostile("a kick after 30 ms of -90 dBFS noise", mono(noise(3.16e-5f, 0.03f, 5) + k), true),
            Hostile("a single sample at 0", mono(spike(0)), true),
            Hostile("a single sample at 881", mono(spike(881)), true),
            Hostile("a single sample at 882", mono(spike(882)), true),
            Hostile("a single sample at 5000", mono(spike(5000)), true),
            Hostile("a DC step", mono(FloatArray(5000) { if (it < 1000) 0f else 0.5f }), true),
            Hostile("a 5 ms DC pulse", mono(FloatArray(5000) { if (it in 1000 until 1000 + 5 * Dsp.RATE / 1000) 0.5f else 0f }), true),
            Hostile("a clipped square at 100 Hz", mono(square(100f)), true),
            Hostile("a clipped square at 1000 Hz", mono(square(1000f)), true),
            Hostile("a clipped square at 4000 Hz", mono(square(4000f)), true),
            Hostile("a quiet click before a louder hit (decision 20)", mono(quietClickThenHit()), false),
            Hostile("a stereo source whose channels cancel", Snip(FloatArray(k.size * 2) { i -> if (i % 2 == 0) k[i / 2] else -k[i / 2] }, 2, Dsp.RATE), false),
            Hostile("a 48 kHz kick after 500 ms of silence", Snip(FloatArray(24_000) + at48 + FloatArray(48_000 * 3), 1, 48_000), true),
            Hostile("48 kHz digital silence", Snip(FloatArray(48_000), 1, 48_000), false),
            Hostile("a 48 kHz source of three samples, one of them 0.5", Snip(floatArrayOf(0f, 0.5f, 0f), 1, 48_000), true),
            Hostile("a 22.05 kHz kick", Snip(at22, 1, 22_050), true),
        )
    }
}
