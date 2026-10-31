package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THROWAWAY PROBE for the COIL specification as written. Print-only; the only
 * assertions are finiteness guards. Gated on COIL_SPIKE_OUT.
 */
class CoilProbeTest {

    private val renderRate = Dsp.RATE * Dsp.OVERSAMPLE
    private val macroNames = listOf("TUNE", "DRIVE", "TONE", "CAB", "MUTE", "SAG")

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

    // ---------- measurement helpers ----------

    private class Stat(val nonFinite: Int, val peak: Double, val mean: Double) {
        val dcOverPeak: Double get() = if (peak > 0.0) mean / peak else 0.0
    }

    private fun stat(x: FloatArray): Stat {
        var bad = 0
        var peak = 0.0
        var sum = 0.0
        var count = 0
        for (v in x) {
            if (!v.isFinite()) { bad++; continue }
            val a = abs(v.toDouble())
            if (a > peak) peak = a
            sum += v
            count++
        }
        return Stat(bad, peak, if (count > 0) sum / count else 0.0)
    }

    private fun db(ratio: Double): Double = 10.0 * log10(ratio.coerceAtLeast(1e-30))

    private fun f(v: Double, d: Int = 3): String = "%.${d}f".format(v)

    private fun cents(hz: Double, want: Float): Double =
        runCatching { FineTuning.cents(hz, want.toDouble()) }.getOrDefault(Double.NaN)

    private fun measured(samples: FloatArray, rate: Int, want: Float): Double =
        runCatching { FineTuning.measuredHz(samples, rate, want) }.getOrDefault(Double.NaN)

    private class Timed(val stages: CoilProbe.Stages, val ms: Double)

    private fun timedRender(
        voice: CoilProbeVoice,
        macros: Map<String, Float> = emptyMap(),
        pickupPos: Float? = null,
        humbucker: Boolean? = null,
        skipBandLimit: Boolean = false,
    ): Timed {
        val t0 = System.nanoTime()
        val s = CoilProbe.renderStages(voice, macros, 0, pickupPos, humbucker, skipBandLimit)
        val ms = (System.nanoTime() - t0) / 1e6
        return Timed(s, ms)
    }

    private val nonFiniteFindings = ArrayList<String>()

    /** Finiteness guard: record now, fail at the end of the test so the table is still written. */
    private fun assertFinite(label: String, st: CoilProbe.Stages) {
        for ((n, a) in listOf("raw" to st.raw, "amped" to st.amped, "cabbed" to st.cabbed, "final" to st.final.samples)) {
            val bad = a.count { !it.isFinite() }
            if (bad != 0) { println("NONFINITE $label $n: $bad samples"); nonFiniteFindings += "$label $n: $bad" }
        }
    }

    private fun guard() = assertEquals(emptyList<String>(), nonFiniteFindings.toList(), "non-finite samples found")

    private fun writeWav(outDir: String, name: String, snip: Snip) {
        File(outDir).mkdirs()
        FileOutputStream(File(outDir, "$name.wav")).use { WavWriter.write(it, snip) }
    }

    private fun emit(outDir: String, file: String, lines: List<String>) {
        File(outDir).mkdirs()
        File(outDir, file).writeText(lines.joinToString("\n") + "\n")
        lines.forEach { println(it) }
    }

    // ---------- grid 1 ----------

    @Test
    fun `grid 1 macro corners`() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val lines = ArrayList<String>()
        lines += "| voice | macro | value | NF r/a/c/f | peak raw | peak amp | peak cab | peak fin | DC raw | DC amp | DC cab | DC fin | DC/pk raw | DC/pk amp | DC/pk cab | DC/pk fin | cents raw | cents fin | Pitch.detect | want Hz | h2 | h3 | h4 | h5 | h6 | h7 | h8 | clarity dB | loud | loud/0.1834 | class | dur s | ms/s |"
        lines += "|" + "---|".repeat(33)
        var count = 0
        for (voice in CoilProbeVoice.entries) {
            val cases = ArrayList<Triple<String, String, Map<String, Float>>>()
            cases += Triple("DEFAULTS", "-", emptyMap())
            for (mn in macroNames) for (v in listOf(0f, 1f)) cases += Triple(mn, v.toInt().toString(), mapOf(mn to v))
            for ((macro, value, macros) in cases) {
                val t = timedRender(voice, macros)
                val st = t.stages
                val label = "$voice $macro $value"
                assertFinite(label, st)
                val fin = st.final
                val tune = macros["TUNE"] ?: CoilProbe.defaults(voice).getValue("TUNE")
                val want = CoilProbe.frequencyFor(voice, tune)
                val sr = stat(st.raw); val sa = stat(st.amped); val sc = stat(st.cabbed); val sf = stat(fin.samples)
                val cRaw = cents(measured(st.raw, renderRate, want), want)
                val cFin = cents(measured(fin.samples, fin.sampleRate, want), want)
                val pd = Pitch.detect(fin)
                val e1 = PluckSpectra.toneEnergy(fin, want)
                val hs = (2..8).map { k -> f(db(PluckSpectra.toneEnergy(fin, want * k) / e1), 1) }
                val clarity = harmonicClarity(fin.samples, fin.sampleRate, want, (0.05f * fin.sampleRate).toInt())
                val loud = Loudness.of(fin)
                val cls = Classifier.classify(fin).drumClass
                lines += "| $voice | $macro | $value | ${sr.nonFinite}/${sa.nonFinite}/${sc.nonFinite}/${sf.nonFinite} | ${f(sr.peak)} | ${f(sa.peak)} | ${f(sc.peak)} | ${f(sf.peak)} | ${f(sr.mean, 5)} | ${f(sa.mean, 5)} | ${f(sc.mean, 5)} | ${f(sf.mean, 5)} | ${f(sr.dcOverPeak, 4)} | ${f(sa.dcOverPeak, 4)} | ${f(sc.dcOverPeak, 4)} | ${f(sf.dcOverPeak, 4)} | ${f(cRaw, 1)} | ${f(cFin, 1)} | ${pd?.let { "${f(it.hz.toDouble(), 1)}@${f(it.confidence.toDouble(), 2)}" } ?: "null"} | ${f(want.toDouble(), 2)} | ${hs.joinToString(" | ")} | ${f(clarity, 1)} | ${f(loud.toDouble(), 4)} | ${f(loud / Dsp.MELODIC_LOUDNESS_TARGET.toDouble(), 3)} | $cls | ${f(fin.durationSeconds.toDouble(), 2)} | ${f(t.ms / fin.durationSeconds, 1)} |"
                val wavName = if (macro == "DEFAULTS") "${voice}_DEFAULTS_0" else "${voice}_${macro}_$value"
                writeWav(outDir, wavName, fin)
                count++
            }
        }
        lines += ""
        lines += "renders: $count"
        emit(outDir, "grid1.md", lines)
        guard()
    }

    // ---------- grid 2 ----------

    @Test
    fun `grid 2 tuning grid`() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val lines = ArrayList<String>()
        lines += "| voice | TUNE | MUTE | want Hz | cents raw | cents final |"
        lines += "|---|---|---|---|---|---|"
        for (voice in CoilProbeVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) for (mute in listOf(0f, 0.5f, 1f)) {
            val t = timedRender(voice, mapOf("TUNE" to tune, "MUTE" to mute))
            assertFinite("$voice tune=$tune mute=$mute", t.stages)
            val fin = t.stages.final
            val want = CoilProbe.frequencyFor(voice, tune)
            val cRaw = cents(measured(t.stages.raw, renderRate, want), want)
            val cFin = cents(measured(fin.samples, fin.sampleRate, want), want)
            lines += "| $voice | $tune | $mute | ${f(want.toDouble(), 2)} | ${f(cRaw, 1)} | ${f(cFin, 1)} |"
        }
        emit(outDir, "grid2.md", lines)
        guard()
    }

    // ---------- grid 3 ----------

    @Test
    fun `grid 3 pickup position`() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val positions = listOf(0.05f, 0.12f, 0.28f, 0.42f, 0.5f)
        val want = CoilProbe.frequencyFor(CoilProbeVoice.JANGLE, CoilProbe.defaults(CoilProbeVoice.JANGLE).getValue("TUNE"))
        val lines = ArrayList<String>()
        lines += "JANGLE defaults, f0 = ${f(want.toDouble(), 2)} Hz, raw at $renderRate Hz, toneEnergy over first 0.25 s; dB are absolute 10*log10(power)"
        for (humbucker in listOf(false, true)) {
            lines += ""
            lines += "humbuckerOverride = $humbucker"
            lines += "| harmonic | Hz | " + positions.joinToString(" | ") { "pos $it dB" } + " | max spread dB |"
            lines += "|---|---|" + "---|".repeat(positions.size + 1)
            val energies = positions.map { pos ->
                val t = timedRender(CoilProbeVoice.JANGLE, emptyMap(), pickupPos = pos, humbucker = humbucker)
                assertFinite("JANGLE pos=$pos hb=$humbucker", t.stages)
                val snip = Snip(t.stages.raw, 1, renderRate)
                (1..12).map { k -> db(PluckSpectra.toneEnergy(snip, want * k)) }
            }
            var worst = 0.0
            for (k in 1..12) {
                val col = energies.map { it[k - 1] }
                val spread = col.max() - col.min()
                if (spread > worst) worst = spread
                lines += "| h$k | ${f(want.toDouble() * k, 1)} | ${col.joinToString(" | ") { f(it, 2) }} | ${f(spread, 2)} |"
            }
            lines += "worst spread across harmonics: ${f(worst, 2)} dB"
        }
        emit(outDir, "grid3.md", lines)
        guard()
    }

    // ---------- grid 4 ----------

    @Test
    fun `grid 4 aliasing at the top`() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val lines = ArrayList<String>()
        lines += "| voice | TUNE | DRIVE | want Hz | clarity with bandLimit dB | clarity without bandLimit dB | delta dB | peak with | peak without |"
        lines += "|---|---|---|---|---|---|---|---|---|"
        for (voice in listOf(CoilProbeVoice.CHUG, CoilProbeVoice.FUZZBOX)) {
            val macros = mapOf("TUNE" to 1f, "DRIVE" to 1f)
            val want = CoilProbe.frequencyFor(voice, 1f)
            val with = timedRender(voice, macros).stages
            val without = timedRender(voice, macros, skipBandLimit = true).stages
            assertFinite("$voice with", with)
            assertFinite("$voice without", without)
            val start = (0.05f * Dsp.RATE).toInt()
            val cw = harmonicClarity(with.final.samples, Dsp.RATE, want, start)
            val cn = harmonicClarity(without.final.samples, Dsp.RATE, want, start)
            lines += "| $voice | 1 | 1 | ${f(want.toDouble(), 2)} | ${f(cw, 1)} | ${f(cn, 1)} | ${f(cw - cn, 1)} | ${f(stat(with.final.samples).peak)} | ${f(stat(without.final.samples).peak)} |"
        }
        emit(outDir, "grid4.md", lines)
        guard()
    }
}
