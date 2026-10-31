package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Luthier scan harness (scratch, never committed). Renders every registered engine's voices at
 * defaults, every macro at 0 / 0.5 / 1, two macro corners, HOLD's top step and two velocities,
 * and writes the house probes as JSON per engine: timing, peak, DC, loudness against the melodic
 * target, root pitch and confidence, classifier verdict, spectral shape, envelope shape, the
 * probes.py macro-activity distance, and a wrap-jump proxy for HOLD loops.
 *
 * Args: outDir shardIndex shardCount mode(quick|full) [ENGINE,ENGINE,...]
 */
object LuthierScan {

    private val specsMethod = Velocity::class.java.getDeclaredMethod("macroSpecsFor", Patch::class.java)
        .apply { isAccessible = true }
    private val touchedMethod = Velocity::class.java.getDeclaredMethod("touchedRender", Patch::class.java, java.lang.Float.TYPE)
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun specsOf(p: Patch): List<MacroSpec> = specsMethod.invoke(Velocity, p) as List<MacroSpec>

    private fun touched(p: Patch, v: Float): Snip? = touchedMethod.invoke(Velocity, p, v) as Snip?

    private fun defaultPatch(engine: String, voice: String): Patch = Patches.fromJsonValue(
        JsonValue.Obj(
            linkedMapOf(
                "engine" to JsonValue.Str(engine),
                "version" to JsonValue.Num(1.0),
                "name" to JsonValue.Str("scan"),
                "voice" to JsonValue.Str(voice),
                "macros" to JsonValue.Obj(linkedMapOf()),
            ),
        ),
    )

    // ---------- expected root via reflection (best effort; null when the signature is unusual) ----------
    private fun expectedHz(p: Patch, macros: Map<String, Float>): Float? {
        val simple = p.javaClass.simpleName.removeSuffix("Patch")
        val tune = macros["TUNE"] ?: return null
        val voice = runCatching { p.javaClass.getMethod("getVoice").invoke(p) }.getOrNull()
        val midi = runCatching { p.javaClass.getMethod("getMidi").invoke(p) }.getOrNull()
        for (cn in listOf(simple, simple + "Engine")) {
            val cls = runCatching { Class.forName("com.snipsnap.synth.$cn") }.getOrNull() ?: continue
            val inst = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull() ?: continue
            for (m in cls.methods.filter { it.name == "frequencyFor" }) {
                val pt = m.parameterTypes
                try {
                    if (pt.size == 2 && voice != null && pt[0].isInstance(voice) && pt[1] == java.lang.Float.TYPE) {
                        return m.invoke(inst, voice, tune) as Float
                    }
                    if (pt.size == 1 && pt[0] == java.lang.Float.TYPE) return m.invoke(inst, tune) as Float
                    if (pt.size == 3 && voice != null && pt[0].isInstance(voice) && pt[1] == java.lang.Float.TYPE) {
                        if (pt[2] == Integer.TYPE && midi is Int) return m.invoke(inst, voice, tune, midi) as Float
                        if (pt[2] == Integer::class.java) return m.invoke(inst, voice, tune, midi as Int?) as Float
                    }
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    // ---------- signal helpers ----------
    private fun mono(s: Snip): FloatArray =
        if (s.channels == 1) s.samples else FloatArray(s.samples.size / 2) { (s.samples[2 * it] + s.samples[2 * it + 1]) * 0.5f }

    private const val N_FFT = 4096
    private val hann = FloatArray(N_FFT) { (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / N_FFT)).toFloat() }

    /** Welch power spectrum, Hann 4096, 50 % overlap, at most 200 frames (~9 s). Power per bin. */
    private fun welch(x: FloatArray, len: Int = x.size): DoubleArray {
        val out = DoubleArray(N_FFT / 2 + 1)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        var frames = 0
        var start = 0
        while (start + N_FFT <= len && frames < 200) {
            for (i in 0 until N_FFT) {
                val v = x[start + i]
                re[i] = if (v.isFinite()) v * hann[i] else 0f
                im[i] = 0f
            }
            Fft.forward(re, im)
            for (k in out.indices) out[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            frames++
            start += N_FFT / 2
        }
        if (frames == 0) {
            // shorter than one frame: zero-pad once
            for (i in 0 until N_FFT) {
                val v = if (i < len) x[i] else 0f
                re[i] = if (v.isFinite()) v * hann[i] else 0f
                im[i] = 0f
            }
            Fft.forward(re, im)
            for (k in out.indices) out[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            frames = 1
        }
        for (k in out.indices) out[k] /= frames
        return out
    }

    private fun db(p: Double) = 10 * log10(max(p, 1e-14))
    private fun binHz(k: Int, sr: Int) = k.toDouble() * sr / N_FFT

    /** RMS envelope in 10 ms blocks, dBFS. */
    private fun envelope(x: FloatArray, sr: Int): DoubleArray {
        val block = sr / 100
        val n = x.size / block
        return DoubleArray(n) { b ->
            var e = 0.0
            for (i in b * block until (b + 1) * block) {
                val v = x[i]
                if (v.isFinite()) e += v.toDouble() * v
            }
            10 * log10(max(e / block, 1e-14))
        }
    }

    private fun r(v: Double, d: Int = 4): Double {
        if (!v.isFinite()) return v
        val f = Math.pow(10.0, d.toDouble())
        return Math.round(v * f) / f
    }

    private fun cents(hz: Double, ref: Double) = 1200.0 * ln(hz / ref) / ln(2.0)

    private class Rendered(val snip: Snip, val ms: Double, val mono: FloatArray, val psd: DoubleArray, val env: DoubleArray)

    private fun rendered(snip: Snip, ms: Double): Rendered {
        val m = mono(snip)
        return Rendered(snip, ms, m, welch(m), envelope(m, snip.sampleRate))
    }

    private fun timed(p: Patch): Rendered {
        val t0 = System.nanoTime()
        val s = p.render()
        return rendered(s, (System.nanoTime() - t0) / 1e6)
    }

    private fun metrics(rr: Rendered, expected: Float?): LinkedHashMap<String, Any?> {
        val s = rr.snip
        val x = rr.mono
        val sr = s.sampleRate
        val out = LinkedHashMap<String, Any?>()
        var peak = 0.0
        var sum = 0.0
        var nonFinite = 0
        var clip = 0
        for (v in x) {
            if (!v.isFinite()) {
                nonFinite++; continue
            }
            val a = abs(v.toDouble())
            if (a > peak) peak = a
            if (a >= 0.999) clip++
            sum += v
        }
        out["ms"] = r(rr.ms, 1)
        out["seconds"] = r(x.size.toDouble() / sr, 3)
        out["frames"] = x.size
        out["channels"] = s.channels
        out["sampleRate"] = sr
        out["peak"] = r(peak)
        out["clipSamples"] = clip
        out["nonFinite"] = nonFinite
        out["dc"] = r(sum / max(x.size, 1), 6)
        val loud = runCatching { Loudness.of(s).toDouble() }.getOrNull()
        out["loudness"] = loud?.let { r(it) }
        out["loudVsTarget"] = loud?.let { r(it / Dsp.MELODIC_LOUDNESS_TARGET, 3) }
        val pe = runCatching { Pitch.detect(s) }.getOrNull()
        out["pitchHz"] = pe?.let { r(it.hz.toDouble(), 2) }
        out["pitchConf"] = pe?.let { r(it.confidence.toDouble(), 3) }
        if (expected != null) {
            out["expectedHz"] = r(expected.toDouble(), 2)
            if (pe != null && pe.hz > 0f) {
                val c = cents(pe.hz.toDouble(), expected.toDouble())
                // fold octave errors out so a detector octave slip reads as such, not as 1200 cents
                val folded = ((c % 1200.0) + 1800.0) % 1200.0 - 600.0
                out["centsVsExpected"] = r(c, 1)
                out["centsFolded"] = r(folded, 1)
            }
        }
        val cls = runCatching { Classifier.classify(s) }.getOrNull()
        out["class"] = cls?.drumClass?.name
        out["classConf"] = cls?.confidence?.let { r(it.toDouble(), 3) }
        // spectral shape
        val psd = rr.psd
        var tot = 0.0
        var wsum = 0.0
        var maxP = 0.0
        for (k in 1 until psd.size) {
            tot += psd[k]; wsum += psd[k] * binHz(k, sr); if (psd[k] > maxP) maxP = psd[k]
        }
        out["centroidHz"] = if (tot > 0) r(wsum / tot, 1) else null
        var acc = 0.0
        var roll = 0.0
        for (k in 1 until psd.size) {
            acc += psd[k]; if (acc >= 0.95 * tot) {
                roll = binHz(k, sr); break
            }
        }
        out["rolloff95Hz"] = r(roll, 1)
        var above16 = 0.0
        var above18 = 0.0
        for (k in 1 until psd.size) {
            val f = binHz(k, sr)
            if (f >= 16000) above16 += psd[k]
            if (f >= 18000) above18 += psd[k]
        }
        out["energyAbove16kDb"] = if (tot > 0) r(db(above16 / tot), 1) else null
        out["energyAbove18kDb"] = if (tot > 0) r(db(above18 / tot), 1) else null
        // highest bin within 60 dB of the strongest bin: where the spectrum actually stops
        var top60 = 0.0
        for (k in psd.size - 1 downTo 1) if (db(psd[k]) > db(maxP) - 60) {
            top60 = binHz(k, sr); break
        }
        out["topBinWithin60dBHz"] = r(top60, 1)
        // spectral flatness 40 Hz..16 kHz (0 tonal .. 1 white)
        var lsum = 0.0
        var asum = 0.0
        var cnt = 0
        for (k in 1 until psd.size) {
            val f = binHz(k, sr)
            if (f < 40 || f > 16000) continue
            lsum += ln(max(psd[k], 1e-20)); asum += psd[k]; cnt++
        }
        out["flatness"] = if (cnt > 0 && asum > 0) r(Math.exp(lsum / cnt) / (asum / cnt), 4) else null
        // envelope shape
        val env = rr.env
        if (env.isNotEmpty()) {
            var pk = env[0]
            var pkI = 0
            for (i in env.indices) if (env[i] > pk) {
                pk = env[i]; pkI = i
            }
            out["envPeakDbFS"] = r(pk, 1)
            out["attackToPeakMs"] = pkI * 10
            var t60: Int? = null
            for (i in pkI until env.size) if (env[i] < pk - 60) {
                t60 = i; break
            }
            out["decay60Sec"] = t60?.let { r((it - pkI) * 0.01, 2) }
            out["endLevelDbRelPeak"] = r(env.last() - pk, 1)
            // never-grows on an independent window: loudest block after 100 ms vs loudest in the first 100 ms
            val head = env.take(10).maxOrNull() ?: pk
            val tail = if (env.size > 10) env.drop(10).maxOrNull() ?: Double.NEGATIVE_INFINITY else Double.NEGATIVE_INFINITY
            out["growthAfter100msDb"] = r(tail - head, 1)
        }
        // abrupt end: last sample relative to peak
        if (x.isNotEmpty() && peak > 0) out["lastSampleRelPeak"] = r(abs(x.last().toDouble()) / peak, 5)
        return out
    }

    /** probes.py macro_activity: dB RMS over all Welch bins on the common length, floor 1e-14. */
    private fun activity(a: Rendered, b: Rendered): Pair<Double, Double> {
        val n = min(a.mono.size, b.mono.size)
        val pa = welch(a.mono, n)
        val pb = welch(b.mono, n)
        var all = 0.0
        var band = 0.0
        var bandN = 0
        var mx = 0.0
        for (k in pa.indices) mx = max(mx, max(pa[k], pb[k]))
        val sr = a.snip.sampleRate
        for (k in pa.indices) {
            val d = db(pa[k]) - db(pb[k])
            all += d * d
            val f = binHz(k, sr)
            if (f in 40.0..16000.0 && max(pa[k], pb[k]) > mx * 1e-9) {
                band += d * d; bandN++
            }
        }
        return Pair(sqrt(all / pa.size), if (bandN > 0) sqrt(band / bandN) else 0.0)
    }

    /** Envelope distance: RMS of dB difference over common 10 ms blocks, each clamped at -80 dBFS. */
    private fun envDist(a: Rendered, b: Rendered): Double {
        val n = min(a.env.size, b.env.size)
        if (n == 0) return 0.0
        var s = 0.0
        for (i in 0 until n) {
            val d = max(a.env[i], -80.0) - max(b.env[i], -80.0)
            s += d * d
        }
        return sqrt(s / n)
    }

    private fun compare(a: Rendered, b: Rendered): LinkedHashMap<String, Any?> {
        val (all, band) = activity(a, b)
        val m = LinkedHashMap<String, Any?>()
        m["activityDb"] = r(all, 2)
        m["activityBandDb"] = r(band, 2)
        m["envDistDb"] = r(envDist(a, b), 2)
        m["lenRatio"] = r(b.mono.size.toDouble() / max(a.mono.size, 1), 3)
        return m
    }

    /** Wrap-jump proxy for a whole-buffer loop: |x[0]-x[n-1]| over the 99th percentile |first difference|. */
    private fun wrapJump(x: FloatArray): Double? {
        if (x.size < 1024) return null
        val diffs = DoubleArray(x.size - 1) { abs(x[it + 1].toDouble() - x[it]) }
        diffs.sort()
        val p99 = diffs[(diffs.size * 0.99).toInt().coerceAtMost(diffs.size - 1)]
        if (p99 <= 0) return null
        return abs(x[0].toDouble() - x.last()) / p99
    }

    private fun writeWav(dir: File, name: String, s: Snip) {
        runCatching { WavWriter.write(File(dir, "$name.wav"), s, WavWriter.BitDepth.PCM_16, allowNonMpcRate = true) }
    }

    // ---------- tiny JSON writer ----------
    private fun json(v: Any?, sb: StringBuilder = StringBuilder(), ind: String = ""): StringBuilder {
        when (v) {
            null -> sb.append("null")
            is String -> {
                sb.append('"'); for (c in v) when (c) {
                    '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }; sb.append('"')
            }
            is Double -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Float -> if (v.isFinite()) sb.append(v.toDouble()) else sb.append("null")
            is Number, is Boolean -> sb.append(v)
            is Map<*, *> -> {
                sb.append("{")
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(","); first = false
                    sb.append("\n").append(ind).append("  ")
                    json(k.toString(), sb); sb.append(": "); json(x, sb, "$ind  ")
                }
                if (v.isNotEmpty()) sb.append("\n").append(ind)
                sb.append("}")
            }
            is List<*> -> {
                sb.append("[")
                var first = true
                for (x in v) {
                    if (!first) sb.append(","); first = false
                    sb.append("\n").append(ind).append("  "); json(x, sb, "$ind  ")
                }
                if (v.isNotEmpty()) sb.append("\n").append(ind)
                sb.append("]")
            }
            else -> json(v.toString(), sb)
        }
        return sb
    }

    private fun log(msg: String) {
        System.err.println("[scan] $msg"); System.err.flush()
    }

    // ---------- the scan ----------
    private fun scanVoice(engine: String, voice: String, full: Boolean, wavDir: File): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["voice"] = voice
        val p0 = defaultPatch(engine, voice)
        val specs = specsOf(p0)
        out["macros"] = specs.map { linkedMapOf("name" to it.name, "default" to it.default.toDouble(), "neutral" to it.neutral.toDouble()) }
        val defaults = specs.associate { it.name to it.default }
        val pd = p0.withMacros(defaults)
        val expDefault = expectedHz(pd, defaults)
        val warm = timed(pd)
        val a = timed(pd)
        val b = timed(pd)
        out["firstRenderMs"] = r(warm.ms, 1)
        out["deterministic"] = a.snip.samples.contentEquals(b.snip.samples) && a.snip.samples.contentEquals(warm.snip.samples)
        val def = if (a.ms <= b.ms) a else b
        out["default"] = metrics(def, expDefault)
        writeWav(wavDir, "${voice}_default", def.snip)
        if (!full) return out

        val defPitch = (out["default"] as Map<*, *>)["pitchHz"] as Double?
        val defConf = (out["default"] as Map<*, *>)["pitchConf"] as Double?

        // macro sweeps
        val sweeps = LinkedHashMap<String, Any?>()
        for (spec in specs) {
            val pts = LinkedHashMap<String, Any?>()
            val rs = HashMap<Float, Rendered>()
            for (v in listOf(0f, 0.5f, 1f)) {
                val mm = defaults + (spec.name to v)
                val rr = try {
                    timed(p0.withMacros(mm))
                } catch (e: Throwable) {
                    pts[v.toString()] = linkedMapOf("error" to "${e.javaClass.simpleName}: ${e.message}")
                    continue
                }
                rs[v] = rr
                val m = metrics(rr, expectedHz(pd, mm))
                m["vsDefault"] = compare(def, rr)
                val ph = m["pitchHz"] as Double?
                val pc = m["pitchConf"] as Double?
                if (ph != null && defPitch != null && (pc ?: 0.0) >= 0.3 && (defConf ?: 0.0) >= 0.3) {
                    m["centsVsDefaultPitch"] = r(cents(ph, defPitch), 1)
                }
                pts[v.toString()] = m
            }
            val sw = LinkedHashMap<String, Any?>()
            sw["points"] = pts
            if (rs[0f] != null && rs[1f] != null) sw["zeroToOne"] = compare(rs[0f]!!, rs[1f]!!)
            if (rs[0f] != null && rs[0.5f] != null) sw["zeroToHalf"] = compare(rs[0f]!!, rs[0.5f]!!)
            if (rs[0.5f] != null && rs[1f] != null) sw["halfToOne"] = compare(rs[0.5f]!!, rs[1f]!!)
            sweeps[spec.name] = sw
        }
        out["sweeps"] = sweeps

        // corners (HOLD kept at its default)
        val corners = LinkedHashMap<String, Any?>()
        for ((label, v) in listOf("allZero" to 0f, "allOne" to 1f)) {
            val mm = specs.associate { it.name to if (it.name == "HOLD") it.default else v }
            try {
                val rr = timed(p0.withMacros(mm))
                val m = metrics(rr, expectedHz(pd, mm))
                m["vsDefault"] = compare(def, rr)
                corners[label] = m
                writeWav(wavDir, "${voice}_$label", rr.snip)
            } catch (e: Throwable) {
                corners[label] = linkedMapOf("error" to "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        out["corners"] = corners

        // HOLD's top step
        if (specs.any { it.name == "HOLD" }) {
            try {
                val rr = timed(p0.withMacros(defaults + ("HOLD" to 1f)))
                val m = metrics(rr, expDefault)
                m["wrapJumpRatio"] = wrapJump(rr.mono)?.let { r(it, 2) }
                m["vsDefault"] = compare(def, rr)
                out["holdTop"] = m
                writeWav(wavDir, "${voice}_holdTop", rr.snip)
            } catch (e: Throwable) {
                out["holdTop"] = linkedMapOf("error" to "${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // velocity: what the house velocity path does to this voice at 0.3 vs 1.0
        try {
            val brightness = Velocity.brightnessSpec(pd)
            val native = touched(pd, 0.3f) != null
            out["velocityPath"] = when {
                native -> "touchedRender (engine-native)"
                brightness != null -> "brightness macro ${brightness.name}"
                else -> "soften fallback (post low-pass)"
            }
            val lo = rendered(Velocity.atVelocity(pd, 0.3f), 0.0)
            val hi = rendered(Velocity.atVelocity(pd, 1.0f), 0.0)
            val ml = metrics(lo, null)
            val mh = metrics(hi, null)
            out["velocity"] = linkedMapOf(
                "lowPeak" to ml["peak"], "highPeak" to mh["peak"],
                "lowLoudness" to ml["loudness"], "highLoudness" to mh["loudness"],
                "lowCentroidHz" to ml["centroidHz"], "highCentroidHz" to mh["centroidHz"],
                "lowVsHigh" to compare(lo, hi),
            )
        } catch (e: Throwable) {
            out["velocity"] = linkedMapOf("error" to "${e.javaClass.simpleName}: ${e.message}")
        }
        return out
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args[0])
        val shard = args.getOrNull(1)?.toInt() ?: 0
        val shards = args.getOrNull(2)?.toInt() ?: 1
        val full = (args.getOrNull(3) ?: "full") == "full"
        val only = args.getOrNull(4)?.split(",")?.filter { it.isNotBlank() }?.toSet()
        val types = PatchTypes.all.sortedBy { PatchTypes.engineOf(it) }
        val mine = types.filterIndexed { i, t -> (only == null && i % shards == shard) || (only != null && PatchTypes.engineOf(t) in only) }
        outDir.mkdirs()
        log("shard $shard/$shards: ${mine.size} engines, mode ${if (full) "full" else "quick"}")
        for (t in mine) {
            val engine = PatchTypes.engineOf(t)
            val t0 = System.nanoTime()
            val rep = LinkedHashMap<String, Any?>()
            rep["engine"] = engine
            rep["patchClass"] = t.simpleName
            rep["rosterless"] = engine in PatchTypes.ROSTERLESS
            rep["prototype"] = engine in PatchTypes.PROTOTYPES
            val voices = runCatching { PatchTypes.voicesOf(t) }.getOrElse { emptyList() }
            rep["voiceCount"] = voices.size
            val wavDir = File(outDir, "wav/$engine")
            val vs = ArrayList<Any?>()
            for (v in voices) {
                val tv = System.nanoTime()
                try {
                    vs += scanVoice(engine, v, full, wavDir)
                } catch (e: Throwable) {
                    vs += linkedMapOf("voice" to v, "error" to "${e.javaClass.simpleName}: ${e.message}",
                        "at" to e.stackTrace.take(4).joinToString(" | "))
                }
                log("$engine/$v ${"%.1f".format((System.nanoTime() - tv) / 1e9)} s")
            }
            rep["voices"] = vs
            rep["scanSeconds"] = r((System.nanoTime() - t0) / 1e9, 1)
            File(outDir, "metrics").mkdirs()
            File(outDir, "metrics/$engine.json").writeText(json(rep).toString())
            log("DONE $engine in ${rep["scanSeconds"]} s")
        }
        log("shard $shard complete")
    }
}
