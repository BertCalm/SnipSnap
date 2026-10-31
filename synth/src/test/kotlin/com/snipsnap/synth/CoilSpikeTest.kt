package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.CoilSpike.Macros
import com.snipsnap.synth.CoilSpike.Voice
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MAGNET Phase 0 part B, print-only: measures the spike in [CoilSpike] and
 * appends each grid's table to the report as soon as it exists. The only
 * assertion is that every sample is finite. Gated on COIL_SPIKE_OUT.
 */
class CoilSpikeTest {

    // ---------- helpers copied from ForkTest (private there) ----------

    private fun magnitudes(samples: FloatArray, start: Int, n: Int): FloatArray {
        val re = FloatArray(n) { i ->
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (n - 1)) + 0.14128 * cos(4 * PI * i / (n - 1)) - 0.01168 * cos(6 * PI * i / (n - 1))
            (samples.getOrElse(start + i) { 0f } * w).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        return FloatArray(n / 2) { b -> (re[b] * re[b] + im[b] * im[b]) }
    }

    private fun harmonicClarity(samples: FloatArray, rate: Int, f0: Float, start: Int, n: Int = 1 shl 16): Double {
        val mag = magnitudes(samples, start, n)
        var on = 0.0
        var off = 0.0
        for (b in 1 until mag.size) {
            val hz = b.toFloat() * rate / n
            if (hz > 20_000f) break
            if (hz < f0 / 2) continue
            val k = Math.round(hz / f0)
            if (abs(hz - k * f0) < 8f) on += mag[b] else off += mag[b]
        }
        return 10 * log10(on / off)
    }

    // ---------- helpers of the spike's own ----------

    private fun f(x: Double, d: Int = 1): String = if (x.isFinite()) String.format(Locale.ROOT, "%.${d}f", x) else x.toString()
    private fun f(x: Float, d: Int = 1): String = f(x.toDouble(), d)

    private fun table(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("| ").append(headers.joinToString(" | ")).append(" |\n")
        sb.append("|").append(headers.joinToString("|") { "---" }).append("|\n")
        for (r in rows) sb.append("| ").append(r.joinToString(" | ")).append(" |\n")
        return sb.toString()
    }

    private fun report(outDir: String, text: String) {
        val path = System.getenv("COIL_SPIKE_REPORT") ?: "$outDir/tables.md"
        File(path).appendText(text + "\n")
        println(text)
    }

    private fun writeWav(outDir: String, name: String, snip: Snip) {
        File(outDir).mkdirs()
        File(outDir, name).outputStream().use { WavWriter.write(it, snip) }
    }

    private fun nonFinite(a: FloatArray): Int = a.count { !it.isFinite() }
    private fun mean(a: FloatArray): Double { var s = 0.0; for (v in a) s += v; return if (a.isEmpty()) 0.0 else s / a.size }
    private fun peak(a: FloatArray): Float { var p = 0f; for (v in a) if (abs(v) > p) p = abs(v); return p }
    private fun rms(a: FloatArray): Double { var s = 0.0; for (v in a) s += v.toDouble() * v; return sqrt(s / max(1, a.size)) }

    private fun cents(samples: FloatArray, rate: Int, want: Float, fromSec: Float = 0.05f, body: Float = 0.25f): Double =
        runCatching { FineTuning.cents(FineTuning.measuredHz(samples, rate, want, fromSec, body), want.toDouble()) }.getOrElse { Double.NaN }

    /** Power-weighted spectral centroid over [startSec]..[endSec], Blackman-Harris over the span, zero-padded. */
    private fun centroid(samples: FloatArray, rate: Int, startSec: Float, endSec: Float): Double {
        val start = (startSec * rate).toInt().coerceAtMost(samples.size)
        val end = min(samples.size, (endSec * rate).toInt())
        val len = end - start
        if (len < 16) return Double.NaN
        var n = 1
        while (n < len) n *= 2
        val re = FloatArray(n) { i ->
            if (i >= len) 0f else {
                val w = 0.35875 - 0.48829 * cos(2 * PI * i / (len - 1)) + 0.14128 * cos(4 * PI * i / (len - 1)) - 0.01168 * cos(6 * PI * i / (len - 1))
                (samples[start + i] * w).toFloat()
            }
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        var num = 0.0
        var den = 0.0
        for (b in 1 until n / 2) {
            val p = re[b].toDouble() * re[b] + im[b].toDouble() * im[b]
            num += b.toDouble() * rate / n * p
            den += p
        }
        return if (den > 0) num / den else 0.0
    }

    /** Harmonic levels h1..h[count] in dB re h1: the peak power within +/-1.5% of k*f0. */
    private fun harmonicsDb(samples: FloatArray, rate: Int, f0: Float, count: Int, startSec: Float = 0.05f): DoubleArray {
        val n = 1 shl 16
        val mag = magnitudes(samples, (startSec * rate).toInt(), n)
        val p = DoubleArray(count) { i ->
            val c = (i + 1) * f0
            val lo = (c * 0.985 * n / rate).toInt().coerceIn(1, mag.size - 1)
            val hi = (c * 1.015 * n / rate).toInt().coerceIn(lo, mag.size - 1)
            var best = 0.0
            for (b in lo..hi) if (mag[b] > best) best = mag[b].toDouble()
            best
        }
        return DoubleArray(count) { 10 * log10((p[it] + 1e-30) / (p[0] + 1e-30)) }
    }

    private fun hRow(label: String, h: DoubleArray, count: Int) = listOf(label) + (0 until count).map { f(h[it]) }

    private fun clarity(s: Snip, f0: Float): Double = harmonicClarity(s.samples, s.sampleRate, f0, (0.05f * s.sampleRate).toInt())

    private fun cls(s: Snip) = Classifier.classify(s).drumClass.name

    // ---------- grid 1: corners, P1 ----------

    @Test
    fun grid1_corners() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        repeat(3) { val m = CoilSpike.defaults(Voice.JANGLE); CoilSpike.p1(CoilSpike.dry(Voice.JANGLE, m), m) } // JIT warm-up, not reported
        val rows = ArrayList<List<String>>()
        val hRows = ArrayList<List<String>>()
        var bad = 0
        for (v in Voice.values()) {
            val base = CoilSpike.defaults(v)
            val cases = listOf<Pair<String, Float?>>("DEFAULTS" to null) +
                CoilSpike.MACRO_NAMES.flatMap { n -> listOf(n to 0f, n to 1f) }
            for ((name, value) in cases) {
                val m = if (value == null) base else CoilSpike.set(base, name, value)
                val label = if (value == null) "$v DEFAULTS" else "$v $name ${f(value, 0)}"
                val f0 = CoilSpike.f0(v, m.tune)
                val d = CoilSpike.dry(v, m)
                val r = CoilSpike.p1(d, m)
                val nf = nonFinite(d.buf) + nonFinite(r.out.samples)
                bad += nf
                val fileName = if (value == null) "P1_${v}_DEFAULTS.wav" else "P1_${v}_${name}_${f(value, 0)}.wav"
                writeWav(outDir, fileName, r.out)
                val dur = r.out.frameCount.toDouble() / r.out.sampleRate
                rows += listOf(
                    label, nf.toString(), f(f0, 2),
                    f(peak(d.buf), 3), f(mean(d.buf), 5), f(r.out.peak(), 3), f(mean(r.out.samples), 5),
                    f(cents(d.buf, CoilSpike.R, f0)), f(cents(r.out.samples, r.out.sampleRate, f0)),
                    Pitch.detect(r.out)?.hz?.let { f(it, 1) } ?: "none",
                    f(clarity(r.out, f0), 1), f(Loudness.of(r.out), 4), cls(r.out), f(dur, 2),
                    f((d.stringMs + d.pickupMs) / d.seconds, 1), f(r.ampMs / dur, 1),
                )
                hRows += hRow(label, harmonicsDb(r.out.samples, r.out.sampleRate, f0, 8), 8)
            }
        }
        report(
            outDir,
            "## Grid 1: corners, P1 (in-render), 34 renders\n\n" +
                "dry = string+pickup at R (176.4 kHz); final = P1 output at 44.1 kHz. Cents from `FineTuning.measuredHz` (dry at R, final at 44.1 kHz), " +
                "want = nominal f0. Clarity from 0.05 s at nominal f0. ms/s = milliseconds per rendered second (string+pickup, and amp alone); " +
                "single timed run after warm-up, so noisy.\n\n" +
                table(
                    listOf("render", "non-finite", "f0 Hz", "dry pk", "dry mean", "fin pk", "fin mean", "dry cents", "fin cents", "detect Hz", "clarity dB", "loudness", "class", "dur s", "str+pu ms/s", "amp ms/s"),
                    rows,
                ) + "\nHarmonics of the final, dB re h1:\n\n" +
                table(listOf("render") + (1..8).map { "h$it" }, hRows),
        )
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 2: tuning ----------

    @Test
    fun grid2_tuning() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val rows = ArrayList<List<String>>()
        var bad = 0
        var dryMin = Double.MAX_VALUE
        var dryMax = -Double.MAX_VALUE
        var finMin = Double.MAX_VALUE
        var finMax = -Double.MAX_VALUE
        for (v in Voice.values()) for (tune in listOf(0f, 0.5f, 1f)) for (mute in listOf(0f, 0.5f, 1f)) for (blend in listOf(0f, 1f)) {
            val m = CoilSpike.defaults(v).copy(tune = tune, mute = mute, blend = blend)
            val f0 = CoilSpike.f0(v, tune)
            val d = CoilSpike.dry(v, m)
            val r = CoilSpike.p1(d, m)
            bad += nonFinite(d.buf) + nonFinite(r.out.samples)
            val dc = cents(d.buf, CoilSpike.R, f0)
            val dcWide = cents(d.buf, CoilSpike.R, f0, 0.05f, 0.35f)
            val fc = cents(r.out.samples, r.out.sampleRate, f0)
            if (dc.isFinite()) { dryMin = min(dryMin, dc); dryMax = max(dryMax, dc) }
            if (fc.isFinite()) { finMin = min(finMin, fc); finMax = max(finMax, fc) }
            rows += listOf(v.name, f(tune, 1), f(mute, 1), f(blend, 0), f(f0, 2), f(dc, 2), f(dcWide, 2), f(fc, 2), f(d.seconds, 2))
        }
        report(
            outDir,
            "## Grid 2: tuning, 36 cells\n\n" +
                "Cents error of the measured peak against nominal f0. `dry` uses the default 0.25 s body (2.7 Hz bins at R); " +
                "`dry wide` a 0.35 s body; `P1 final` is at 44.1 kHz through the amp at default DRIVE/SAG/TONE/CAB.\n\n" +
                table(listOf("voice", "TUNE", "MUTE", "BLEND", "f0 Hz", "dry cents", "dry wide cents", "P1 final cents", "dry dur s"), rows) +
                "\nDry cents range: ${f(dryMin, 2)} .. ${f(dryMax, 2)}. P1 final range: ${f(finMin, 2)} .. ${f(finMax, 2)}.\n",
        )
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 3: the comb ----------

    private fun notchLine(label: String, h: DoubleArray, ks: List<Int>): String {
        val parts = ks.map { k ->
            val lo = h[k - 2]
            val hi = h[k]
            val under = h[k - 1] - max(lo, hi)
            val underQuiet = h[k - 1] - min(lo, hi)
            "h$k ${f(under)} dB under louder neighbour, ${f(underQuiet)} under quieter"
        }
        return "- $label: " + parts.joinToString("; ")
    }

    @Test
    fun grid3_comb() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val v = Voice.JANGLE
        val m = CoilSpike.defaults(v)
        val f0 = CoilSpike.f0(v, m.tune)
        val s = CoilSpike.string(v, m)
        val out = StringBuilder("## Grid 3: the comb\n\n")

        // (a) JANGLE single coil, no resonance
        val h13 = ArrayList<List<String>>()
        val hs = HashMap<Float, DoubleArray>()
        for (p in listOf(0.5f, 0.25f)) {
            val y = CoilSpike.pickup(s, p, false, 0f, 20000f, 1f, f0, CoilSpike.R)
            bad += nonFinite(y)
            val h = harmonicsDb(y, CoilSpike.R, f0, 13)
            hs[p] = h
            h13 += hRow("JANGLE single p=$p", h, 8)
        }
        out.append("### (a) JANGLE single coil, resonance off (20 kHz, k 1), TUNE 0.5, f0 ${f(f0, 2)} Hz, dry at R\n\n")
        out.append(table(listOf("case") + (1..8).map { "h$it" }, h13))
        out.append("\n")
        out.append(notchLine("p=0.5, even harmonics", hs.getValue(0.5f), listOf(2, 4, 6, 8))).append("\n")
        out.append(notchLine("p=0.25, h4 and h8", hs.getValue(0.25f), listOf(4, 8))).append("\n")
        out.append(notchLine("p=0.25, h2 and h6 for contrast (should not notch)", hs.getValue(0.25f), listOf(2, 6))).append("\n\n")

        // (b) CHUG humbucker p = 0.12 with and without the second coil
        val c = Voice.CHUG
        val cm = CoilSpike.defaults(c)
        val cf0 = CoilSpike.f0(c, cm.tune)
        val cs = CoilSpike.string(c, cm)
        val dp = CoilSpike.COIL_SPACING * (cf0 / c.rootHz)
        val rowsB = ArrayList<List<String>>()
        val hb = HashMap<String, DoubleArray>()
        for ((label, hum) in listOf("single coil p=0.12" to false, "humbucker p=0.12" to true)) {
            val y = CoilSpike.pickup(cs, CoilSpike.BRIDGE, hum, dp, 20000f, 1f, cf0, CoilSpike.R)
            bad += nonFinite(y)
            val h = harmonicsDb(y, CoilSpike.R, cf0, 13)
            hb[label] = h
            rowsB += hRow(label, h, 12)
        }
        out.append("### (b) CHUG at p=0.12, resonance off, TUNE 0.5, f0 ${f(cf0, 2)} Hz, dp ${f(dp.toDouble(), 4)} (second coil at p+dp; expected extra notch where k*dp = 0.5, k = ${f(0.5 / dp, 2)})\n\n")
        out.append(table(listOf("case") + (1..12).map { "h$it" }, rowsB))
        out.append("\n")
        out.append(notchLine("humbucker, h9", hb.getValue("humbucker p=0.12"), listOf(9))).append("\n")
        out.append(notchLine("single coil, h9 (control)", hb.getValue("single coil p=0.12"), listOf(9))).append("\n\n")

        // (c) BLEND 0 / 0.5 / 1 on JANGLE, full pickup with resonance
        val rowsC = ArrayList<List<String>>()
        for (b in listOf(0f, 0.5f, 1f)) {
            val y = CoilSpike.voicePickup(s, v, m.copy(blend = b), f0)
            bad += nonFinite(y)
            rowsC += hRow("JANGLE BLEND ${f(b, 1)}", harmonicsDb(y, CoilSpike.R, f0, 13), 12)
        }
        out.append("### (c) JANGLE BLEND, neck p=0.42 / bridge p=0.12, with the 4500 Hz k 0.40 resonance, dry at R\n\n")
        out.append(table(listOf("case") + (1..12).map { "h$it" }, rowsC))
        report(outDir, out.toString())
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 4: PICK sweep ----------

    @Test
    fun grid4_pickSweep() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val sb = StringBuilder("## Grid 4: PICK sweep\n\nPower-weighted spectral centroid of the dry 44.1 kHz render (string + pickup + chain, no amp), 0.05..0.3 s.\n\n")
        for (v in Voice.values()) {
            val rows = ArrayList<List<String>>()
            val cs = ArrayList<Double>()
            for (i in 0..10) {
                val pick = i / 10f
                val m = CoilSpike.defaults(v).copy(pick = pick)
                val d = CoilSpike.dry(v, m)
                bad += nonFinite(d.buf)
                val c = centroid(d.snip.samples, d.snip.sampleRate, 0.05f, 0.3f)
                cs += c
                rows += listOf(f(pick, 1), f(CoilSpike.pickCornerHz(v, pick), 0), f(c, 1), f(d.snip.peak(), 3))
            }
            val drops = (1 until cs.size).count { cs[it] < cs[it - 1] }
            val worst = (1 until cs.size).minOf { cs[it] - cs[it - 1] }
            sb.append("### ${v.name}\n\n").append(table(listOf("PICK", "exciter corner Hz", "centroid Hz", "dry peak"), rows))
            sb.append("\nMonotonic non-decreasing: ${drops == 0} (steps that fall: $drops, smallest step ${f(worst, 1)} Hz).\n\n")
        }
        report(outDir, sb.toString())
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 5: placements ----------

    @Test
    fun grid5_placements() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val cases = listOf(
            Triple(Voice.CHUG, 0.5f, 1.0f),
            Triple(Voice.CHUG, 1.0f, 1.0f),
            Triple(Voice.JANGLE, 0.5f, 0.5f),
        )
        // Warm-up so the timings are not the JIT's.
        run { val m = CoilSpike.defaults(Voice.CHUG).copy(drive = 1f); val d = CoilSpike.dry(Voice.CHUG, m); repeat(2) { CoilSpike.p1(d, m); CoilSpike.rackP2(d.snip, m); CoilSpike.rackP3(d.snip, m) } }
        val rows = ArrayList<List<String>>()
        for ((v, tune, drive) in cases) {
            val m = CoilSpike.defaults(v).copy(tune = tune, drive = drive)
            val f0 = CoilSpike.f0(v, tune)
            val d = CoilSpike.dry(v, m)
            val tag = "${v}_T${f(tune, 1)}_D${f(drive, 1)}"
            val dryPeak = d.snip.peak()
            val renders = listOf(
                "P1" to { CoilSpike.p1(d, m) },
                "P1n" to { CoilSpike.p1(d, m, scaleToPeak = dryPeak) },
                "P2" to { CoilSpike.rackP2(d.snip, m) },
                "P3" to { CoilSpike.rackP3(d.snip, m) },
            )
            rows += listOf(
                "$v T${f(tune, 1)} D${f(drive, 1)}", "dry", "-", f(clarity(d.snip, f0), 1), f(clarity(d.snip, f0), 1),
                f(centroid(d.snip.samples, d.snip.sampleRate, 0.05f, 0.3f), 0), f(mean(d.snip.samples), 5),
                f(cents(d.snip.samples, d.snip.sampleRate, f0), 1), Pitch.detect(d.snip)?.hz?.let { f(it, 1) } ?: "none",
                f(Loudness.of(d.snip), 4), f(dryPeak, 3), "-", "-",
            )
            for ((name, run) in renders) {
                // Min of three timed runs; the output is from the first.
                val first = run()
                var ampMs = first.ampMs
                var pathMs = first.pathMs
                repeat(2) { val r = run(); ampMs = min(ampMs, r.ampMs); pathMs = min(pathMs, r.pathMs) }
                val out = first.out
                bad += nonFinite(out.samples)
                writeWav(outDir, "${name}_$tag.wav", out)
                val dur = out.frameCount.toDouble() / out.sampleRate
                val ownHz = runCatching { FineTuning.measuredHz(out.samples, out.sampleRate, f0) }.getOrElse { Double.NaN }
                rows += listOf(
                    "$v T${f(tune, 1)} D${f(drive, 1)}", name, f(out.frameCount.toDouble() / out.sampleRate, 2),
                    f(clarity(out, f0), 1),
                    if (ownHz.isFinite()) f(harmonicClarity(out.samples, out.sampleRate, ownHz.toFloat(), (0.05f * out.sampleRate).toInt()), 1) else "n/a",
                    f(centroid(out.samples, out.sampleRate, 0.05f, 0.3f), 0), f(mean(out.samples), 5),
                    f(FineTuning.cents(ownHz, f0.toDouble()), 1), Pitch.detect(out)?.hz?.let { f(it, 1) } ?: "none",
                    f(Loudness.of(out), 4), f(out.peak(), 3), f(ampMs / dur, 1), f(pathMs / dur, 1),
                )
            }
        }
        report(
            outDir,
            "## Grid 5: placements\n\n" +
                "P1 = amp at R inside the render (raw string level into the amp), P1n = P1 with the string buffer's peak pre-scaled to the dry snip's peak " +
                "(so the amp sees the same level as P2/P3), P2 = rack pass at 44.1 kHz, P3 = rack pass 4x oversampled. " +
                "clarity(nominal) is harmonicClarity at the nominal f0 from 0.05 s; clarity(own) uses the render's own measured f0. " +
                "centroid is over 0.05..0.3 s. cents are of the measured peak vs nominal f0. amp ms/s is the amp alone per rendered second " +
                "(min of 3 runs); path ms/s is amp plus whatever else that placement adds after the dry render " +
                "(P1: output chain; P3: up-sample, band-limit, decimate).\n\n" +
                table(
                    listOf("case", "placement", "dur s", "clarity nominal dB", "clarity own dB", "centroid Hz", "DC mean", "cents", "detect Hz", "loudness", "peak", "amp ms/s", "path ms/s"),
                    rows,
                ),
        )
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 6: CRUNCH-rule identity ----------

    @Test
    fun grid6_identity() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val m = Macros(drive = 0.6f, sag = 0.35f, tone = 0.5f, cab = 0.6f)
        val rows = ArrayList<List<String>>()
        var snareDry: Snip? = null
        for ((name, voice) in listOf("KICK" to ThumpVoice.KICK, "SNARE" to ThumpVoice.SNARE)) {
            val dry = Thump.render(voice)
            if (voice == ThumpVoice.SNARE) snareDry = dry
            val amped = CoilSpike.rackP3(dry, m).out
            bad += nonFinite(dry.samples) + nonFinite(amped.samples)
            writeWav(outDir, "DRY_$name.wav", dry)
            writeWav(outDir, "AMP_$name.wav", amped)
            for ((label, s) in listOf("dry" to dry, "amp (P3, DRIVE 0.6)" to amped)) {
                rows += listOf(
                    name, label, cls(s), f(s.peak(), 3), f(centroid(s.samples, s.sampleRate, 0f, 0.3f), 0),
                    f(s.frameCount.toDouble() / s.sampleRate, 2), f(rms(s.samples), 4),
                )
            }
        }
        val sb = StringBuilder("## Grid 6: CRUNCH-rule identity\n\n")
        sb.append("Thump KICK and SNARE (default macros) through P3's amp at DRIVE 0.6, SAG 0.35, TONE 0.5, CAB 0.6, peak-matched to the dry peak. Centroid over 0..0.3 s.\n\n")
        sb.append(table(listOf("voice", "state", "class", "peak", "centroid Hz", "dur s", "rms"), rows))

        val dry = snareDry!!
        val t = Macros(drive = 0f, sag = 0f, tone = 0.5f, cab = 0f)
        val out = CoilSpike.rackP3(dry, t).out
        bad += nonFinite(out.samples)
        val n = min(dry.samples.size, out.samples.size)
        val diff = FloatArray(n) { out.samples[it] - dry.samples[it] }
        val dryR = rms(dry.samples.copyOf(n))
        val db = 20 * log10(rms(diff) / dryR)
        // The same, after a best-fit gain (RMS-matched), to separate level from colour.
        val gain = dryR / rms(out.samples.copyOf(n))
        val diffMatched = FloatArray(n) { (out.samples[it] * gain).toFloat() - dry.samples[it] }
        val dbMatched = 20 * log10(rms(diffMatched) / dryR)
        writeWav(outDir, "AMP_SNARE_TRANSPARENT.wav", out)
        sb.append("\nNear-transparent point (DRIVE 0, SAG 0, TONE 0.5, CAB 0) on the snare, P3, peak-matched: RMS of (out - dry) is ${f(db)} dB re dry RMS; ")
        sb.append("after an RMS-matching gain of ${f(gain, 3)}: ${f(dbMatched)} dB. Class after: ${cls(out)}, centroid ${f(centroid(out.samples, out.sampleRate, 0f, 0.3f), 0)} Hz.\n")
        report(outDir, sb.toString())
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 7: cost of the string ----------

    @Test
    fun grid7_cost() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val rows = ArrayList<List<String>>()
        for (v in Voice.values()) {
            val m = CoilSpike.defaults(v)
            repeat(3) { CoilSpike.dry(v, m) }
            var sMs = Double.MAX_VALUE
            var pMs = Double.MAX_VALUE
            var last: CoilSpike.Dry? = null
            repeat(5) {
                val d = CoilSpike.dry(v, m)
                sMs = min(sMs, d.stringMs)
                pMs = min(pMs, d.pickupMs)
                last = d
            }
            val d = last!!
            bad += nonFinite(d.buf)
            rows += listOf(v.name, f(d.seconds, 2), f(sMs, 1), f(pMs, 1), f(sMs / d.seconds, 1), f(pMs / d.seconds, 1), f((sMs + pMs) / d.seconds, 1))
        }
        report(
            outDir,
            "## Grid 7: cost of the string\n\nString (pluck + trim) and pickup at defaults at R = 176.4 kHz, min of 5 runs after 3 warm-ups. ms/s is milliseconds per rendered second of the trimmed buffer.\n\n" +
                table(listOf("voice", "rendered s", "string ms", "pickup ms", "string ms/s", "pickup ms/s", "total ms/s"), rows),
        )
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- extra probes: what the grids above could not separate ----------

    /** A steady, exactly harmonic tone: harmonics 1..[count] of [f0] at 1/k, peak 0.99, [seconds] long. */
    private fun steadyTone(f0: Float, count: Int, seconds: Float, rate: Int): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in 1..count) s += kotlin.math.sin(2 * PI * k * f0.toDouble() * i / rate) / k
            out[i] = s.toFloat()
        }
        val p = peak(out)
        for (i in out.indices) out[i] = out[i] * 0.99f / p
        return out
    }

    /** The amp at [x]'s rate times [factor]: resample up, amp, band-limit, resample back (factor 1 is the plain native-rate amp). */
    private fun ampAt(x: Snip, m: Macros, factor: Int): FloatArray {
        if (factor == 1) return CoilSpike.amp(x.samples, m.drive, m.sag, m.tone, m.cab, x.sampleRate)
        val up = x.sampleRate * factor
        val u = com.snipsnap.audio.Resampler.resample(x, up)
        val a = CoilSpike.amp(u.samples, m.drive, m.sag, m.tone, m.cab, up)
        Tide.bandLimit(a, up)
        return com.snipsnap.audio.Resampler.resample(Snip(a, 1, up), x.sampleRate).samples
    }

    private fun errDb(a: FloatArray, ref: FloatArray, startSec: Float, endSec: Float, rate: Int): Double {
        val s = (startSec * rate).toInt()
        val e = min(min(a.size, ref.size), (endSec * rate).toInt())
        var num = 0.0
        var den = 0.0
        for (i in s until e) { val d = a[i].toDouble() - ref[i]; num += d * d; den += ref[i].toDouble() * ref[i] }
        return 10 * log10(num / den)
    }

    @Test
    fun extra_alias() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val sb = StringBuilder("## Extra A: aliasing, measured where harmonicClarity is not confounded\n\n")
        // (1) A steady, exactly harmonic input, so the only off-harmonic energy is what the amp adds by aliasing.
        val rows = ArrayList<List<String>>()
        for ((f0, drive) in listOf(123.48f to 1.0f, 123.48f to 0.45f, 246.96f to 1.0f, 164.82f to 0.5f)) {
            val m = Macros(drive = drive)
            val n = (5000f / f0).toInt()
            val x = Snip(steadyTone(f0, n, 1.6f, Dsp.RATE), 1, Dsp.RATE)
            val outs = listOf(1 to "1x (P2)", 4 to "4x (P3)", 8 to "8x").map { (fct, label) -> label to ampAt(x, m, fct) }
            val ref = outs.last().second
            for ((label, y) in outs) {
                bad += nonFinite(y)
                val c = harmonicClarity(y, Dsp.RATE, f0, (0.3f * Dsp.RATE).toInt())
                rows += listOf(f(f0, 2), f(drive, 2), label, f(c, 1), f(errDb(y, ref, 0.3f, 1.2f, Dsp.RATE), 1))
            }
        }
        sb.append("Steady input: harmonics of f0 to ~5 kHz at 1/k, peak 0.99, 1.6 s, 44.1 kHz; CHUG-law amp with default SAG/TONE/CAB. clarity from 0.3 s at the exact f0. ")
        sb.append("`err vs 8x` is the residual against the 8x render, dB re its energy over 0.3..1.2 s (includes the small filter-warp difference, so read it as a ranking).\n\n")
        sb.append(table(listOf("f0 Hz", "DRIVE", "amp rate", "clarity dB", "err vs 8x dB"), rows)).append("\n")

        // (2) The string cases: residual against an 8x render of the same dry snip.
        val rows2 = ArrayList<List<String>>()
        for ((v, tune, drive) in listOf(Triple(Voice.CHUG, 0.5f, 1.0f), Triple(Voice.CHUG, 1.0f, 1.0f), Triple(Voice.JANGLE, 0.5f, 0.5f))) {
            val m = CoilSpike.defaults(v).copy(tune = tune, drive = drive)
            val d = CoilSpike.dry(v, m)
            val outs = listOf(1 to "1x (P2)", 4 to "4x (P3)", 8 to "8x").map { (fct, label) -> label to ampAt(d.snip, m, fct) }
            val ref = outs.last().second
            val f0 = CoilSpike.f0(v, tune)
            for ((label, y) in outs) {
                bad += nonFinite(y)
                rows2 += listOf("$v T${f(tune, 1)} D${f(drive, 1)}", label, f(errDb(y, ref, 0.05f, 0.3f, Dsp.RATE), 1), f(harmonicClarity(y, Dsp.RATE, f0, (0.05f * Dsp.RATE).toInt()), 1))
            }
        }
        sb.append("String renders (the grid-5 cases): the dry 44.1 kHz snip through the amp at 1x, 4x, 8x; residual against 8x over 0.05..0.3 s, and clarity at the nominal f0 (no peak match, so levels are the amp's own).\n\n")
        sb.append(table(listOf("case", "amp rate", "err vs 8x dB", "clarity dB"), rows2)).append("\n")

        // (3) Where P3's cost goes.
        val m = CoilSpike.defaults(Voice.CHUG).copy(drive = 1f)
        val d = CoilSpike.dry(Voice.CHUG, m)
        repeat(2) { CoilSpike.rackP3(d.snip, m) }
        var upMs = Double.MAX_VALUE; var ampMs = Double.MAX_VALUE; var downMs = Double.MAX_VALUE; var amp1Ms = Double.MAX_VALUE
        repeat(3) {
            var t = System.nanoTime()
            val u = com.snipsnap.audio.Resampler.resample(d.snip, Dsp.RATE * 4)
            upMs = min(upMs, (System.nanoTime() - t) / 1e6)
            t = System.nanoTime()
            val a = CoilSpike.amp(u.samples, m.drive, m.sag, m.tone, m.cab, Dsp.RATE * 4)
            ampMs = min(ampMs, (System.nanoTime() - t) / 1e6)
            t = System.nanoTime()
            Tide.bandLimit(a, Dsp.RATE * 4); Dsp.decimate(a, Dsp.RATE)
            downMs = min(downMs, (System.nanoTime() - t) / 1e6)
            t = System.nanoTime()
            CoilSpike.amp(d.snip.samples, m.drive, m.sag, m.tone, m.cab, Dsp.RATE)
            amp1Ms = min(amp1Ms, (System.nanoTime() - t) / 1e6)
        }
        val sec = d.snip.frameCount.toDouble() / Dsp.RATE
        sb.append("P3 cost breakdown, CHUG T0.5 DRIVE 1 (${f(sec, 2)} s), ms per rendered second, min of 3: up-sample ${f(upMs / sec)}, amp at 4x ${f(ampMs / sec)}, band-limit + decimate ${f(downMs / sec)}; the amp at 1x is ${f(amp1Ms / sec)}.\n")
        report(outDir, sb.toString())
        assertEquals(0, bad, "non-finite samples")
    }

    @Test
    fun extra_humbuckerNotch() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val c = Voice.CHUG
        val m = CoilSpike.defaults(c)
        val f0 = CoilSpike.f0(c, m.tune)
        val s = CoilSpike.string(c, m)
        val dp = CoilSpike.COIL_SPACING * (f0 / c.rootHz)
        val count = 22
        val r = CoilSpike.R
        fun h(hum: Boolean, aligned: Boolean) =
            harmonicsDb(CoilSpike.pickup(s, CoilSpike.BRIDGE, hum, dp, 20000f, 1f, f0, r, aligned), r, f0, count)
        val single = h(false, false)
        val hum = h(true, false)
        val humA = h(true, true)
        bad += nonFinite(s)
        // Theory, with the delays as actually rounded. Single: |1 - e^-jw D1|. As briefed: |2 - e^-jw D1 - e^-jw D2|.
        // Aligned: the sum of the two centred combs, |sin(pi k p1e) + sin(pi k p2e)|.
        val d1 = CoilSpike.combDelay(CoilSpike.BRIDGE, f0, r)
        val d2 = CoilSpike.combDelay(CoilSpike.BRIDGE + dp, f0, r)
        val per = r / f0.toDouble()
        fun th(k: Int): Triple<Double, Double, Double> {
            val w = 2 * PI * k / per
            val sing = abs(2 * kotlin.math.sin(w * d1 / 2))
            val re = 2 - cos(w * d1) - cos(w * d2)
            val im = kotlin.math.sin(w * d1) + kotlin.math.sin(w * d2)
            val brief = kotlin.math.sqrt(re * re + im * im)
            val al = abs(kotlin.math.sin(w * d1 / 2) + kotlin.math.sin(w * d2 / 2)) * 2
            return Triple(sing, brief, al)
        }
        fun rel(k: Int, pick: (Triple<Double, Double, Double>) -> Double) =
            20 * log10(pick(th(k)) / th(k).first) - 20 * log10(pick(th(1)) / th(1).first)
        val rows = (1..count).map { k ->
            listOf(
                "h$k", f(single[k - 1]), f(hum[k - 1]), f(humA[k - 1]),
                f(hum[k - 1] - single[k - 1]), f(rel(k) { it.second }),
                f(humA[k - 1] - single[k - 1]), f(rel(k) { it.third }),
            )
        }
        report(
            outDir,
            "## Extra C: where the humbucker's second coil notches (CHUG, p=0.12, resonance off, f0 ${f(f0, 2)} Hz, dp ${f(dp.toDouble(), 4)}, D1 $d1, D2 $d2)\n\n" +
                "Three humbuckers on the same string: as briefed (`0.707 * (c(p) + c(p+dp))`, each comb `y[n] - y[n-D]`), and phase-aligned (each comb's taps delayed so its centre is the longer comb's centre). " +
                "The aligned pair is `sin(pi k p1) + sin(pi k p2)`, whose first spacing notch is at k = 1/dp = ${f(1.0 / dp, 2)}; as briefed the two combs' linear-phase terms differ and there is no such notch. " +
                "The exciter's own 0.10 comb notches h10 in every row. `d` columns are humbucker minus single coil, both re h1: measured, then theory from the delays as rounded.\n\n" +
                table(listOf("harmonic", "single dB", "hum briefed dB", "hum aligned dB", "briefed d meas", "briefed d theory", "aligned d meas", "aligned d theory"), rows),
        )
        assertEquals(0, bad, "non-finite samples")
    }

    @Test
    fun extra_pitchProbe() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val rows = ArrayList<List<String>>()
        val cells = listOf(
            Triple(Voice.JANGLE, 0f, 0.5f to 1f), Triple(Voice.CHUG, 0f, 0.5f to 1f),
            Triple(Voice.CHUG, 1f, 0.5f to 1f), Triple(Voice.JANGLE, 0.5f, 0.5f to 0f),
        )
        for ((v, tune, mb) in cells) {
            val m = CoilSpike.defaults(v).copy(tune = tune, mute = mb.first, blend = mb.second)
            val f0 = CoilSpike.f0(v, tune)
            val d = CoilSpike.dry(v, m)
            val r = CoilSpike.p1(d, m)
            for ((label, s) in listOf("dry 44.1k" to d.snip, "P1 final" to r.out)) {
                val body = min(0.25f, s.frameCount.toFloat() / s.sampleRate - 0.15f)
                rows += listOf(
                    "$v T${f(tune, 1)} M${f(mb.first, 1)} B${f(mb.second, 0)}", label,
                    f(cents(s.samples, s.sampleRate, f0, 0.05f, body)), f(cents(s.samples, s.sampleRate, f0, 0.10f, body)),
                    f(cents(s.samples, s.sampleRate, f0, 0.02f, body)), Pitch.detect(s)?.hz?.let { f(it, 2) } ?: "none", f(f0, 2),
                )
            }
        }
        report(
            outDir,
            "## Extra B: is grid 2's large P1-final cents a pitch shift or a measurement artefact?\n\n" +
                "The same cells measured with the analysis window starting at 0.02, 0.05 and 0.10 s (`FineTuning.measuredHz`, cents vs nominal), and `Pitch.detect`'s autocorrelation Hz. " +
                "A real pitch shift would not move with the window; an artefact of a spectral peak read off a decaying, amp-coloured line would.\n\n" +
                table(listOf("cell", "signal", "cents @0.05", "cents @0.10", "cents @0.02", "Pitch.detect Hz", "nominal Hz"), rows),
        )
    }
}
