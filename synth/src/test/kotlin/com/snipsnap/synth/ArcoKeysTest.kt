package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ARCO held ([ArcoKeys], [Arco.renderHeldMeasured]): the keygroup zone that is the bow's own stroke, then the shipped LOOP's loop.
 *
 *  - **Every zone closes.** Both voices, every zone ([ArcoKeys.zoneMidis]: CELLO's nine, ERHU's eight), both layers, at the defaults: the
 *    file's own seam at its loop start under the Organ's bar ([Keys.MAX_SEAM_ERROR]), finite, under [Arco.HELD_PEAK_CEILING], levelled on
 *    its loop region, the loop exactly the plan's whole periods. The per-zone render time is measured (this thread's CPU time) and printed.
 *  - **The corners close.** GRIP 0 and 1, BODY 0 and 1, BUZZ 1, BOW 0 and 1, at each voice's lowest, middle and top zone, both layers.
 *  - **The attack is the stroke.** The held stretch's raw string is the one-shot's, sample for sample, for as long as the one-shot's bow is
 *    on; the finished file's first 300 ms follow the one-shot's envelope at the same BOW and not at another; and it starts from silence,
 *    where the loop-only instrument's zone starts at its loop's level.
 *  - **The held loop is the LOOP's.** At BOW 0.5 (no bite, the LOOP's own stroke) the held stretch is the LOOP's stretch, sample for sample.
 *  - **The soft layer is another sound.** Darker on CELLO at every zone (a lower spectral centroid on the loop), a slower stroke everywhere.
 *  - **Determinism, refusal, cancellation.** The same inputs give the same samples; a key outside the voice is refused by name; a zone that
 *    cannot close (the free-running BUZZ noise, the LOOP's own failing control) is retried at twice the warm-up and then refused by name.
 */
class ArcoKeysTest {

    private fun e(v: Double) = "%.2e".format(Locale.ROOT, v)
    private fun f(v: Double, d: Int = 2) = "%.${d}f".format(Locale.ROOT, v)

    private class Rendered(val voice: ArcoVoice, val midi: Int, val layer: ArcoKeys.Layer, val label: String, val zone: ArcoKeys.Zone, val cpuMs: Long) {
        val samples: FloatArray get() = zone.note.snip.samples
        val loopStart: Int get() = zone.render.loopStart
        val loop: FloatArray get() = samples.copyOfRange(loopStart, samples.size)
    }

    private companion object {
        val threads = ManagementFactory.getThreadMXBean()
        val cpu = threads.isCurrentThreadCpuTimeSupported && threads.isThreadCpuTimeEnabled
        fun now(): Long = if (cpu) threads.currentThreadCpuTime else System.nanoTime()

        fun render(voice: ArcoVoice, midi: Int, layer: ArcoKeys.Layer, knobs: Map<String, Float> = emptyMap(), label: String = "defaults"): Rendered {
            val t0 = now()
            val zone = ArcoKeys.padMeasured(voice, Arco.defaults(voice) + knobs, midi, layer)
            return Rendered(voice, midi, layer, label, zone, (now() - t0) / 1_000_000L)
        }

        /** Every zone of both voices, both layers, at the defaults: rendered once, shared by the tests that read them. */
        val defaults: List<Rendered> by lazy {
            ArcoVoice.entries.flatMap { v -> ArcoKeys.zoneMidis(v).flatMap { midi -> ArcoKeys.Layer.entries.map { Triple(v, midi, it) } } }
                .parallelStream().map { (v, midi, layer) -> render(v, midi, layer) }.toList()
        }

        fun peak(x: FloatArray): Float = x.maxOf { abs(it) }

        fun rms(x: FloatArray, from: Int, until: Int): Double {
            var acc = 0.0
            for (i in from until until) acc += x[i].toDouble() * x[i]
            return sqrt(acc / (until - from).coerceAtLeast(1))
        }

        /** RMS in consecutive windows of [window] frames over the first [frames] frames. */
        fun envelope(x: FloatArray, frames: Int, window: Int): DoubleArray = DoubleArray(frames / window) { rms(x, it * window, (it + 1) * window) }

        fun pearson(a: DoubleArray, b: DoubleArray): Double {
            val n = minOf(a.size, b.size)
            val ma = (0 until n).sumOf { a[it] } / n
            val mb = (0 until n).sumOf { b[it] } / n
            var ab = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in 0 until n) {
                ab += (a[i] - ma) * (b[i] - mb); aa += (a[i] - ma) * (a[i] - ma); bb += (b[i] - mb) * (b[i] - mb)
            }
            return ab / sqrt(aa * bb).coerceAtLeast(1e-30)
        }

        /** The power-weighted mean frequency of [n] frames of [x] from [from] (a power of two), Hann-weighted. */
        fun centroid(x: FloatArray, from: Int, n: Int): Double {
            val re = DoubleArray(n) { x[from + it] * (0.5 - 0.5 * cos(2.0 * PI * it / n)) }
            val im = DoubleArray(n)
            ArcoBodyMeasure.fft(re, im)
            var num = 0.0
            var den = 0.0
            for (b in 1 until n / 2) {
                val p = re[b] * re[b] + im[b] * im[b]
                num += p * b.toDouble() * Dsp.RATE / n
                den += p
            }
            return num / den
        }

        /** Seconds until the 10 ms RMS envelope first reaches 90 percent of the loop's own RMS. */
        fun riseSeconds(r: Rendered): Double {
            val w = Dsp.RATE / 100
            val target = 0.9 * rms(r.samples, r.loopStart, r.samples.size)
            val env = envelope(r.samples, r.loopStart, w)
            val i = env.indexOfFirst { it >= target }
            return if (i < 0) Double.NaN else (i + 1) * w.toDouble() / Dsp.RATE
        }

        fun noteName(midi: Int) = Scales.nameOf(midi)
    }

    private fun checkZone(r: Rendered): List<String> {
        val s = r.samples
        val bad = ArrayList<String>()
        val name = "${r.voice} ${noteName(r.midi)} ${r.layer} ${r.label}"
        if (r.loopStart <= 0) bad += "$name: loop start ${r.loopStart}"
        if (s.size != r.loopStart + r.zone.render.loopFrames) bad += "$name: the file is not the stroke plus one loop"
        if (r.zone.render.loopFrames != Bore.planLoop(Keys.midiHz(r.midi)).frames) bad += "$name: the loop is not the plan's whole periods"
        val seam = Keys.seamError(s, r.loopStart)
        if (!(seam < Keys.MAX_SEAM_ERROR)) bad += "$name: seam ${e(seam)}"
        if (seam != r.zone.render.seam) bad += "$name: the measured seam is not the file's"
        if (!s.all { it.isFinite() }) bad += "$name: non-finite"
        if (peak(s) > Arco.HELD_PEAK_CEILING + 1e-6f) bad += "$name: peak ${peak(s)}"
        if (r.loopStart < r.zone.render.warmFrames + 256) bad += "$name: the loop starts inside the warm-up"
        return bad
    }

    private fun loudnessDb(x: FloatArray): Double = 20 * log10(Loudness.of(Snip(x, channels = 1, sampleRate = Dsp.RATE)).toDouble() / Dsp.MELODIC_LOUDNESS_TARGET)

    @Test
    fun `every zone of both voices closes in both layers at the defaults, levelled on its loop`() {
        val all = defaults
        assertEquals(2 * (ArcoKeys.zoneMidis(ArcoVoice.CELLO).size + ArcoKeys.zoneMidis(ArcoVoice.ERHU).size), all.size)
        assertEquals(listOf(36, 39, 42, 45, 48, 51, 54, 57, 60), ArcoKeys.zoneMidis(ArcoVoice.CELLO))
        assertEquals(listOf(62, 65, 68, 71, 74, 77, 80, 81), ArcoKeys.zoneMidis(ArcoVoice.ERHU))
        val bad = all.flatMap { checkZone(it) }.toMutableList()
        for (r in all) {
            // Levelled on the loop: at the target, or below it by exactly what the peak guard took.
            val off = loudnessDb(r.loop) - r.zone.render.limitDb
            if (abs(off) > 0.05) bad += "${r.voice} ${noteName(r.midi)} ${r.layer}: the loop sits ${f(off, 3)} dB from the target after the peak guard"
        }
        for (voice in ArcoVoice.entries) for (layer in ArcoKeys.Layer.entries) {
            val mine = all.filter { it.voice == voice && it.layer == layer }.sortedBy { it.midi }
            val worst = mine.maxBy { it.zone.render.seam }
            println("ARCO held $voice $layer: ${mine.size} zones, worst seam ${e(worst.zone.render.seam)} at ${noteName(worst.midi)} (bar ${e(Keys.MAX_SEAM_ERROR)}); retried ${mine.count { it.zone.retried }}; " +
                "peak guard bit at ${mine.count { it.zone.render.limitDb < 0f }} (deepest ${f(mine.minOf { it.zone.render.limitDb }.toDouble())} dB); retune passes ${mine.map { it.zone.render.passes }}")
            println("ARCO held $voice $layer per zone: " + mine.joinToString("; ") { r ->
                "${noteName(r.midi)} seam ${e(r.zone.render.seam)}, loop at ${f(r.loopStart.toDouble() / Dsp.RATE, 3)} s, file ${f(r.samples.size.toDouble() / Dsp.RATE, 3)} s, ${r.cpuMs} ms"
            })
        }
        for (voice in ArcoVoice.entries) {
            val mine = all.filter { it.voice == voice }
            val frames = mine.sumOf { it.samples.size.toLong() }
            val clock = if (cpu) "this thread's CPU time" else "the wall clock"
            println("ARCO held $voice instrument: ${mine.size} zone-layers, ${mine.sumOf { it.cpuMs }} ms of render summed ($clock; per zone-layer ${mine.minOf { it.cpuMs }} to ${mine.maxOf { it.cpuMs }} ms), " +
                "${f(frames.toDouble() / Dsp.RATE, 1)} s of audio, ${f(frames * 3 / 1e6, 1)} MB as 24-bit mono")
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun `the corners close at each voice's lowest, middle and top zones, both layers`() {
        val corners = listOf(
            "GRIP 0" to mapOf("GRIP" to 0f), "GRIP 1" to mapOf("GRIP" to 1f),
            "BODY 0" to mapOf("BODY" to 0f), "BODY 1" to mapOf("BODY" to 1f),
            "BUZZ 1" to mapOf("BUZZ" to 1f),
            "BOW 0" to mapOf("BOW" to 0f), "BOW 1" to mapOf("BOW" to 1f),
        )
        val cases = ArcoVoice.entries.flatMap { v ->
            val z = ArcoKeys.zoneMidis(v)
            listOf(z.first(), z[z.size / 2], z.last()).flatMap { midi -> corners.flatMap { c -> ArcoKeys.Layer.entries.map { Triple(v, midi, it) to c } } }
        }
        val out = cases.parallelStream().map { (k, c) -> render(k.first, k.second, k.third, c.second, c.first) }.toList()
        val bad = out.flatMap { checkZone(it) }.toMutableList()
        for (r in out) {
            val base = defaults.first { it.voice == r.voice && it.midi == r.midi && it.layer == r.layer }
            // GRIP 0 is GRIP 0 in both layers, so only the hard layer need differ from the default there; every corner moves the hard layer.
            if (r.layer == ArcoKeys.Layer.HARD && r.samples.contentEquals(base.samples)) bad += "${r.voice} ${noteName(r.midi)} ${r.label}: the corner renders the default"
        }
        for (voice in ArcoVoice.entries) for ((corner, _) in corners) {
            val mine = out.filter { it.voice == voice && it.label == corner }
            val worst = mine.maxBy { it.zone.render.seam }
            val lift = if (corner == "BODY 1") ", lift delivered ${mine.joinToString("/") { f(it.zone.render.lift.delivered.toDouble()) }} dB" else ""
            println("ARCO held $voice $corner: ${mine.size} zone-layers, worst seam ${e(worst.zone.render.seam)} at ${noteName(worst.midi)} ${worst.layer}; retried ${mine.count { it.zone.retried }}; " +
                "peak guard deepest ${f(mine.minOf { it.zone.render.limitDb }.toDouble())} dB; ${mine.sumOf { it.cpuMs }} ms summed$lift")
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun `the held stretch's raw string is the one-shot's for as long as the one-shot's bow is on`() {
        for (voice in ArcoVoice.entries) for (bow in listOf(0f, 0.5f, 1f)) {
            val midi = ArcoKeys.zoneMidis(voice)[3]
            val m = ArcoKeys.macrosFor(voice, Arco.defaults(voice) + ("BOW" to bow), midi, ArcoKeys.Layer.HARD)
            val hz = Arco.frequencyFor(voice, m.getValue("TUNE"))
            val plan = Bore.planLoop(hz)
            val held = Arco.heldStretch(voice, m, hz, Arco.warmFramesFor(hz), plan.frames)
            val shot = Arco.bow(voice, hz, m, vibrato = false)
            val bowOn = (Arco.holdSeconds(m.getValue("HOLD")) * (Dsp.RATE * Dsp.OVERSAMPLE)).toInt()
            assertContentEquals(shot.copyOf(bowOn), held.copyOf(bowOn), "$voice ${noteName(midi)} BOW $bow: the held stroke is not the one-shot's")
            // The control: past the one-shot's bow-on the one-shot's bow ramps off and lifts; the held bow does not.
            assertTrue(!shot.copyOfRange(bowOn, shot.size).contentEquals(held.copyOfRange(bowOn, shot.size)), "$voice BOW $bow: the held bow lifts with the one-shot's")
        }
    }

    @Test
    fun `at the default BOW the held stretch is the LOOP's stretch, sample for sample, and at BOW 1 it is not`() {
        for (voice in ArcoVoice.entries) {
            val midi = ArcoKeys.zoneMidis(voice)[4]
            val m = ArcoKeys.macrosFor(voice, Arco.defaults(voice), midi, ArcoKeys.Layer.HARD)
            val hz = Arco.frequencyFor(voice, m.getValue("TUNE"))
            val plan = Bore.planLoop(hz)
            val warm = Arco.warmFramesFor(hz)
            assertContentEquals(Arco.stretch(voice, m, hz, warm, plan.frames), Arco.heldStretch(voice, m, hz, warm, plan.frames), "$voice: the held stretch at BOW 0.5 is not the LOOP's")
            val bitten = m + ("BOW" to 1f)
            assertTrue(!Arco.stretch(voice, bitten, hz, warm, plan.frames).contentEquals(Arco.heldStretch(voice, bitten, hz, warm, plan.frames)), "$voice: control, BOW 1's stroke is the LOOP's")
        }
    }

    @Test
    fun `the held key's first 300 ms follow the one-shot's stroke at the same BOW, and start from silence where the loop-only zone does not`() {
        val window = Dsp.RATE / 200
        val span = (0.3 * Dsp.RATE).toInt()
        val bad = ArrayList<String>()
        for (voice in ArcoVoice.entries) {
            val midi = ArcoKeys.zoneMidis(voice)[4]
            val bows = listOf(0f, 1f)
            val held = bows.parallelStream().map { b -> render(voice, midi, ArcoKeys.Layer.HARD, mapOf("BOW" to b), "BOW $b") }.toList()
            val tune = ArcoKeys.macrosFor(voice, Arco.defaults(voice), midi, ArcoKeys.Layer.HARD).getValue("TUNE")
            val shots = bows.map { b -> Arco.render(voice, Arco.defaults(voice) + mapOf("TUNE" to tune, "BOW" to b)).samples }
            val corr = Array(2) { h -> DoubleArray(2) { s -> pearson(envelope(held[h].samples, span, window), envelope(shots[s], span, window)) } }
            println("ARCO held $voice ${noteName(midi)}: the first 300 ms' 5 ms envelope against the one-shot's, held BOW 0 vs shot BOW 0 ${f(corr[0][0], 3)}, vs shot BOW 1 ${f(corr[0][1], 3)}; " +
                "held BOW 1 vs shot BOW 1 ${f(corr[1][1], 3)}, vs shot BOW 0 ${f(corr[1][0], 3)}")
            for (b in 0..1) {
                if (corr[b][b] < 0.95) bad += "$voice BOW ${bows[b]}: the held stroke's envelope follows the one-shot's at ${f(corr[b][b], 3)} (bar 0.95)"
                if (corr[b][1 - b] >= corr[b][b]) bad += "$voice BOW ${bows[b]}: control, the held stroke follows the other BOW's one-shot as well (${f(corr[b][1 - b], 3)})"
            }
            // From silence: the held key's first 30 ms against its loop; the loop-only zone (the LOOP, which LoopPadMaker played) against its own.
            val first = (0.03 * Dsp.RATE).toInt()
            val d = defaults.first { it.voice == voice && it.midi == midi && it.layer == ArcoKeys.Layer.HARD }
            val heldOnset = 20 * log10(rms(d.samples, 0, first) / rms(d.loop, 0, d.loop.size))
            val loopOnly = Arco.renderLoop(voice, Arco.defaults(voice) + mapOf("TUNE" to tune, "HOLD" to 1f))
            val loopOnset = 20 * log10(rms(loopOnly, 0, first) / rms(loopOnly, 0, loopOnly.size))
            println("ARCO held $voice ${noteName(midi)}: the first 30 ms sit ${f(heldOnset, 1)} dB against the held loop; the loop-only zone's first 30 ms ${f(loopOnset, 1)} dB against its loop")
            if (heldOnset > -6.0) bad += "$voice: the held key's first 30 ms are ${f(heldOnset, 1)} dB against its loop (bar -6 dB): no onset"
            if (abs(loopOnset) > 3.0) bad += "$voice: control, the loop-only zone's first 30 ms are ${f(loopOnset, 1)} dB from its loop"
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun `the soft layer is darker on CELLO at every zone and strokes slower everywhere`() {
        val bad = ArrayList<String>()
        val n = 65536
        for (voice in ArcoVoice.entries) {
            val rows = ArcoKeys.zoneMidis(voice).map { midi ->
                val soft = defaults.first { it.voice == voice && it.midi == midi && it.layer == ArcoKeys.Layer.SOFT }
                val hard = defaults.first { it.voice == voice && it.midi == midi && it.layer == ArcoKeys.Layer.HARD }
                val cs = centroid(soft.loop, 0, n)
                val ch = centroid(hard.loop, 0, n)
                val rs = riseSeconds(soft)
                val rh = riseSeconds(hard)
                if (soft.samples.contentEquals(hard.samples)) bad += "$voice ${noteName(midi)}: the two layers are the same samples"
                if (voice == ArcoVoice.CELLO && !(cs < ch)) bad += "$voice ${noteName(midi)}: the soft loop's centroid ${f(cs, 0)} Hz is not under the hard's ${f(ch, 0)}"
                if (!(rs > rh)) bad += "$voice ${noteName(midi)}: the soft stroke reaches its loop's level in ${f(rs, 3)} s, the hard in ${f(rh, 3)}"
                "${noteName(midi)} centroid ${f(cs, 0)}/${f(ch, 0)} Hz (${f(100 * (cs / ch - 1), 1)} %), rise ${f(rs, 2)}/${f(rh, 2)} s"
            }
            println("ARCO held $voice soft/hard: ${rows.joinToString("; ")}")
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun `the same inputs render the same samples`() {
        for ((voice, midi, layer) in listOf(Triple(ArcoVoice.CELLO, 48, ArcoKeys.Layer.HARD), Triple(ArcoVoice.ERHU, 81, ArcoKeys.Layer.SOFT))) {
            val again = ArcoKeys.pad(voice, Arco.defaults(voice), midi, layer)
            val first = defaults.first { it.voice == voice && it.midi == midi && it.layer == layer }
            assertContentEquals(first.samples, again.snip.samples, "$voice ${noteName(midi)} $layer")
            assertEquals(first.loopStart.toLong(), again.loopStartFrame)
        }
        val buzzing = Arco.defaults(ArcoVoice.CELLO) + ("BUZZ" to 0.5f)
        val a = ArcoKeys.pad(ArcoVoice.CELLO, buzzing, 36, ArcoKeys.Layer.HARD)
        val b = ArcoKeys.pad(ArcoVoice.CELLO, buzzing, 36, ArcoKeys.Layer.HARD)
        assertContentEquals(a.snip.samples, b.snip.samples, "CELLO C2 at BUZZ 0.5")
        assertEquals(a.loopStartFrame, b.loopStartFrame)
    }

    @Test
    fun `a key outside the voice is refused by name, and a zone that cannot close is retried and then refused by name`() {
        val b5 = assertFailsWith<IllegalArgumentException> { ArcoKeys.pad(ArcoVoice.ERHU, Arco.defaults(ArcoVoice.ERHU), 83, ArcoKeys.Layer.HARD) }
        assertTrue("ERHU" in b5.message!! && "B5" in b5.message!! && "A5" in b5.message!!, b5.message)
        assertFailsWith<IllegalArgumentException> { ArcoKeys.pad(ArcoVoice.CELLO, Arco.defaults(ArcoVoice.CELLO), 35, ArcoKeys.Layer.SOFT) }
        // The LOOP's own failing control: BUZZ 1 with its noise running free does not repeat with the loop, so the seam opens at both tries.
        val free = Arco.BuzzLaw(Arco.BUZZ_THRESHOLD_FROM, Arco.BUZZ_THRESHOLD_TO, Arco.BUZZ_GAIN, loopTable = false, top = Arco.BUZZ_TOP, curve = Arco.BUZZ_CURVE)
        val refused = assertFailsWith<IllegalArgumentException> {
            ArcoKeys.padMeasured(ArcoVoice.CELLO, Arco.defaults(ArcoVoice.CELLO) + ("BUZZ" to 1f), 48, ArcoKeys.Layer.HARD, law = free)
        }
        println("ARCO held, the free-noise control: ${refused.message}")
        val msg = refused.message!!
        assertTrue("ARCO CELLO" in msg && "C3" in msg && "twice the warm-up" in msg && "does not close" in msg, msg)
    }

    @Test
    fun `a cancelled key stops before it renders`() {
        assertFailsWith<CancellationException> { ArcoKeys.pad(ArcoVoice.CELLO, Arco.defaults(ArcoVoice.CELLO), 36, ArcoKeys.Layer.HARD) { true } }
    }
}
