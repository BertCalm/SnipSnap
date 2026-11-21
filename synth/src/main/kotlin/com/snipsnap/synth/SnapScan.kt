package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * What SNAP SCAN read off one photo, kept on the pad so the pad rebuilds bit for bit without the
 * photo (docs/superpowers/specs/2026-11-21-snap-scan-design.md). Everything here is decided once,
 * at read time; the render only reads these numbers.
 *
 * - [strips]: [SnapScan.STRIPS] cycles of [Snap.TABLE_SIZE] points, 0..255, one per horizontal
 *   strip of the picture, top first, already aligned to each other and already through
 *   spectral contrast (§6.4), so the render pays for neither.
 * - The readings are ranks, 0..1, against calibration v1/v1b: where this photo sat among the
 *   owner's 99 calibration images. A rank, not a raw number, so a typical photo lands mid-range.
 * - [register]: octaves above or below SNAP's A2 (−1..2), from brightness (§6.6).
 * - [seed]: the fingerprint (§7), from the capture time and the pixels.
 */
class SnapPortrait(
    val version: Int,
    strips: List<IntArray>,
    /** 1 when the top of the picture is the bright part (a sky): a strike. 0: a swell. */
    val light: Float,
    /** True when the bottom is brighter than the top: the scan reads bottom to top. */
    val fromBelow: Boolean,
    val symmetry: Float,
    val warmth: Float,
    val grain: Float,
    /** Clipping rank; 0 for an image that is not a camera photo (§8). */
    val clip: Float,
    val register: Int,
    val seed: Int,
) {
    val strips: List<IntArray> = strips.map { it.copyOf() }

    init {
        require(version == SnapScan.VERSION) { "SNAP portrait version $version is not ${SnapScan.VERSION}" }
        require(strips.size == SnapScan.STRIPS) { "a SNAP portrait has ${SnapScan.STRIPS} strips, got ${strips.size}" }
        for ((k, s) in strips.withIndex()) {
            require(s.size == Snap.TABLE_SIZE) { "strip $k has ${s.size} points, not ${Snap.TABLE_SIZE}" }
            for (v in s) require(v in 0..255) { "strip $k holds $v, outside 0..255" }
        }
        for ((n, v) in listOf("light" to light, "symmetry" to symmetry, "warmth" to warmth, "grain" to grain, "clip" to clip)) {
            require(v.isFinite() && v in 0f..1f) { "portrait $n is a rank 0..1, got $v" }
        }
        require(register in -1..2) { "register is -1..2 octaves, got $register" }
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "version" to JsonValue.Num(version.toDouble()),
        "light" to JsonValue.Num(light.toDouble()),
        "fromBelow" to JsonValue.Bool(fromBelow),
        "symmetry" to JsonValue.Num(symmetry.toDouble()),
        "warmth" to JsonValue.Num(warmth.toDouble()),
        "grain" to JsonValue.Num(grain.toDouble()),
        "clip" to JsonValue.Num(clip.toDouble()),
        "register" to JsonValue.Num(register.toDouble()),
        "seed" to JsonValue.Num(seed.toDouble()),
        "strips" to JsonValue.Arr(strips.map { s -> JsonValue.Arr(s.map { JsonValue.Num(it.toDouble()) }) }),
    ))

    override fun equals(other: Any?): Boolean = other is SnapPortrait && version == other.version &&
        light == other.light && fromBelow == other.fromBelow && symmetry == other.symmetry &&
        warmth == other.warmth && grain == other.grain && clip == other.clip &&
        register == other.register && seed == other.seed &&
        strips.size == other.strips.size && strips.indices.all { strips[it].contentEquals(other.strips[it]) }

    override fun hashCode(): Int = listOf(version, light, fromBelow, symmetry, warmth, grain, clip, register, seed)
        .hashCode() * 31 + strips.fold(0) { h, s -> h * 31 + s.contentHashCode() }

    override fun toString(): String =
        "SnapPortrait(v$version, light=$light, fromBelow=$fromBelow, symmetry=$symmetry, warmth=$warmth, " +
            "grain=$grain, clip=$clip, register=$register, seed=$seed, ${strips.size} strips)"

    companion object {
        fun fromJsonValue(value: JsonValue): SnapPortrait {
            val o = value.obj()
            fun f(key: String): Float = (o[key] ?: throw JsonException("SNAP portrait has no $key")).num().toFloat()
            val raw = (o["strips"] ?: throw JsonException("SNAP portrait has no strips")).arr()
            val strips = raw.mapIndexed { k, s ->
                val pts = s.arr()
                IntArray(pts.size) { i -> pts[i].int().also { if (it !in 0..255) throw JsonException("SNAP strip $k[$i] is $it") } }
            }
            return try {
                SnapPortrait(
                    version = (o["version"] ?: throw JsonException("SNAP portrait has no version")).int(),
                    strips = strips,
                    light = f("light"),
                    fromBelow = (o["fromBelow"] ?: throw JsonException("SNAP portrait has no fromBelow")).bool(),
                    symmetry = f("symmetry"),
                    warmth = f("warmth"),
                    grain = f("grain"),
                    clip = f("clip"),
                    register = (o["register"] ?: throw JsonException("SNAP portrait has no register")).int(),
                    seed = (o["seed"] ?: throw JsonException("SNAP portrait has no seed")).int(),
                )
            } catch (e: IllegalArgumentException) {
                throw JsonException(e.message ?: "bad SNAP portrait")
            }
        }
    }
}

/**
 * SNAP SCAN round 1a: the photo read as movement (16 strips scanned over the note), a portrait
 * (readings ranked against calibration, each tied to one sound quantity) and a fingerprint (a
 * stored seed whose takes are SNAP's round robin). The TONE family only; BREATH, PLUCK and
 * STRIKE are round 1b. Every constant marked "shape" came from the Python prototype
 * (`prototype/snap-scan/`) and has not yet been heard by the owner.
 */
object SnapScan {

    const val VERSION = 1
    const val STRIPS = 16

    /** The fingerprint's strength: the owner's "subtle to moderate" (D3, fixed). */
    const val FINGERPRINT = 0.5f

    /** The SCAN macro. Its default on a fresh read is the photo's own (§6.5); this is only the fallback. */
    val SCAN_MACRO = MacroSpec("SCAN", 0.6f)

    // ---------- calibration (the owner's 99 images; 0th to 100th percentile in steps of 12.5) ----------

    internal val CALIBRATION: Map<String, FloatArray> = mapOf(
        "lum" to floatArrayOf(0.1455f, 0.2900f, 0.3750f, 0.4301f, 0.4794f, 0.5107f, 0.5461f, 0.6310f, 0.9471f),
        "sat" to floatArrayOf(0.0000f, 0.0937f, 0.1519f, 0.1858f, 0.2280f, 0.2608f, 0.3095f, 0.3948f, 0.8077f),
        "detail" to floatArrayOf(0.0075f, 0.0364f, 0.0460f, 0.0574f, 0.0643f, 0.0708f, 0.0818f, 0.0980f, 0.1374f),
        "light" to floatArrayOf(-0.4816f, -0.1627f, -0.0780f, -0.0244f, 0.0215f, 0.1343f, 0.2033f, 0.3283f, 0.6006f),
        "warmth" to floatArrayOf(-0.1810f, -0.0143f, 0.0003f, 0.0232f, 0.0341f, 0.0745f, 0.1092f, 0.1538f, 0.5468f),
        "symmetry" to floatArrayOf(-0.2796f, 0.0219f, 0.1482f, 0.2406f, 0.3852f, 0.4682f, 0.5994f, 0.7159f, 0.9804f),
        "clipped" to floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0001f, 0.0009f, 0.0059f, 0.0397f, 0.2088f, 0.8669f),
        "grain" to floatArrayOf(0.0005f, 0.0033f, 0.0045f, 0.0054f, 0.0067f, 0.0090f, 0.0109f, 0.0162f, 0.0291f),
        // v1b, measured by families.py on the same set
        "contrast" to floatArrayOf(0.0868f, 0.1768f, 0.1966f, 0.2137f, 0.2297f, 0.2391f, 0.2578f, 0.2851f, 0.3816f),
        "vchange" to floatArrayOf(4.2688f, 6.8932f, 7.2837f, 7.5056f, 7.7285f, 7.9946f, 8.3119f, 9.5089f, 26.9344f),
    )

    /** Where [v] sits in [name]'s calibration table, 0..1, linear between the nine points. */
    internal fun rank(name: String, v: Float): Float {
        val t = CALIBRATION.getValue(name)
        if (!v.isFinite() || v <= t[0]) return 0f
        if (v >= t[t.size - 1]) return 1f
        for (i in t.size - 2 downTo 0) {
            if (v >= t[i] && v < t[i + 1]) return (i + (v - t[i]) / (t[i + 1] - t[i])) / (t.size - 1)
        }
        return 0f
    }

    // ---------- reading the photo ----------

    /** A read: the patch's knobs, its whole-picture line, and the portrait. */
    class Read(val macros: Map<String, Float>, val table: IntArray, val portrait: SnapPortrait)

    /**
     * The whole door for a new photo pad: [Snap.read] plus the portrait. [capturedAtMillis] is
     * when the shutter fired (the phone clock today; EXIF in round 2). [camera] is false for an
     * image that is not a camera photo, which drops the clipping reading (§8).
     */
    fun read(photo: Photo, voice: SnapVoice, name: String, capturedAtMillis: Long, camera: Boolean = true): SnapPatch {
        val r = readPortrait(photo, voice, capturedAtMillis, camera)
        return SnapPatch(name, voice, r.macros, r.table, portrait = r.portrait)
    }

    fun readPortrait(photo: Photo, voice: SnapVoice, capturedAtMillis: Long, camera: Boolean = true): Read {
        require(voice.readsPhoto) { "a ${voice.name} line is not read off a photo" }
        val pic = cropBorder(photo)
        val table = Snap.table(pic, voice)
        require(!Snap.isFlat(table)) { "the ${voice.name} line through this photo is one flat colour: no waveform in it" }
        val look = Snap.look(pic)
        val w = pic.width
        val h = pic.height
        val lum = FloatArray(w * h) { pic.luminance(it % w, it / w) }

        // strips: column means of each horizontal band, guarded like PhotoField's cells, aligned
        val cycles = ArrayList<FloatArray>(STRIPS)
        for (b in 0 until STRIPS) {
            val y0 = b * h / STRIPS
            val y1 = max(y0 + 1, min((b + 1) * h / STRIPS, h))
            val cols = DoubleArray(w)
            for (x in 0 until w) {
                var s = 0.0
                for (y in y0 until y1) s += lum[y * w + x]
                cols[x] = s / (y1 - y0)
            }
            val line = IntArray(Snap.TABLE_SIZE)
            Snap.resample(cols, line)
            cycles.add(Snap.cycle(PhotoField.cellTable(line).first))
        }
        val aligned = align(cycles)
        val vchange = vchange(aligned)

        // readings
        val light = lightOf(lum, w, h)
        val warmth = warmthOf(pic)
        val symmetry = symmetryOf(lum, w, h)
        val clipped = lum.count { it <= 2f / 255f || it >= 253f / 255f }.toFloat() / lum.size
        val grain = grainOf(lum, w, h)
        val rLum = rank("lum", look.luminance)
        val rDetail = rank("detail", look.detail)

        // spectral contrast, stored with the strips (§6.4, widened in round 0c)
        val amount = 1f + 2f * rDetail
        val slope = -1.7f + 1.1f * rLum
        val strips = aligned.map { toBytes(contrast(it, amount, slope)) }

        val grey = look.saturation < Snap.GREY_SATURATION || look.hueStrength < Snap.WEAK_HUE
        val noteName = if (grey) 0 else Math.round(((look.hue - Snap.HUE_SEAM_DEGREES + 720f) % 360f) / 30f) % 12
        val register = min(floor(rLum * 4f).toInt(), 3) - 1

        val portrait = SnapPortrait(
            version = VERSION,
            strips = strips,
            light = rank("light", light),
            fromBelow = light < 0f,
            symmetry = rank("symmetry", symmetry),
            warmth = rank("warmth", warmth),
            grain = rank("grain", grain),
            clip = if (camera) rank("clipped", clipped) else 0f,
            register = register,
            seed = Dsp.seedFor("SNAP-PORTRAIT", capturedAtMillis / 1000L, photo.argb.contentHashCode()),
        )
        val macros = mapOf(
            "TUNE" to noteName / Snap.TUNE_SEMITONES.toFloat(),
            "BRIGHT" to 0.15f + 0.8f * rLum,
            "DECAY" to 0.2f + 0.7f * rank("sat", look.saturation),
            "GRIT" to 0.8f * rDetail,
            "SCAN" to 0.3f + 0.7f * rank("vchange", vchange),
        )
        return Read(macros, table, portrait)
    }

    /**
     * The picture without a uniform frame: rows and columns at the edges whose brightness spread
     * is under three levels are cut away (the phone's black frame with printed shot data, §8).
     * Refused, keeping the whole photo, if it would leave less than half either way.
     */
    internal fun cropBorder(photo: Photo): Photo {
        val w = photo.width
        val h = photo.height
        fun rowFlat(y: Int): Boolean { var lo = 1f; var hi = 0f; for (x in 0 until w) { val v = photo.luminance(x, y); if (v < lo) lo = v; if (v > hi) hi = v }; return hi - lo < 3f / 255f }
        fun colFlat(x: Int): Boolean { var lo = 1f; var hi = 0f; for (y in 0 until h) { val v = photo.luminance(x, y); if (v < lo) lo = v; if (v > hi) hi = v }; return hi - lo < 3f / 255f }
        var top = 0; while (top < h - 1 && rowFlat(top)) top++
        var bottom = h - 1; while (bottom > top && rowFlat(bottom)) bottom--
        var left = 0; while (left < w - 1 && colFlat(left)) left++
        var right = w - 1; while (right > left && colFlat(right)) right--
        val cw = right - left + 1
        val ch = bottom - top + 1
        if (cw * 2 < w || ch * 2 < h || (cw == w && ch == h)) return photo
        return photo.crop(left, top, cw, ch)
    }

    /** Each cycle circularly shifted to best match the one above it, so a blend never cancels (§2.2). */
    internal fun align(cycles: List<FloatArray>): List<FloatArray> {
        val n = Snap.TABLE_SIZE
        val out = ArrayList<FloatArray>(cycles.size)
        out.add(cycles[0])
        for (k in 1 until cycles.size) {
            val prev = out[k - 1]
            val cur = cycles[k]
            var best = 0
            var bestScore = Double.NEGATIVE_INFINITY
            for (lag in 0 until n) {
                var s = 0.0
                for (i in 0 until n) s += prev[i] * cur[((i - lag) % n + n) % n]
                if (s > bestScore) { bestScore = s; best = lag }
            }
            out.add(FloatArray(n) { i -> cur[((i - best) % n + n) % n] })
        }
        return out
    }

    /** Worst level dip of a 50/50 blend of neighbouring cycles, in dB (0 or negative). */
    internal fun worstBlendDip(cycles: List<FloatArray>): Double {
        var worst = 0.0
        for (k in 1 until cycles.size) {
            val a = cycles[k - 1]
            val b = cycles[k]
            fun rms(x: (Int) -> Double) = sqrt((0 until a.size).sumOf { x(it) * x(it) } / a.size)
            val mid = rms { 0.5 * a[it] + 0.5 * b[it] }
            val ends = 0.5 * (rms { a[it].toDouble() } + rms { b[it].toDouble() })
            if (ends > 0) worst = min(worst, 20 * kotlin.math.log10(mid / ends))
        }
        return worst
    }

    /** Harmonics 0..n/2 of a real cycle, numpy's unnormalized convention. */
    private fun dft(x: FloatArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        val re = DoubleArray(n / 2 + 1)
        val im = DoubleArray(n / 2 + 1)
        for (k in 0..n / 2) {
            var r = 0.0
            var i = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                r += x[t] * cos(a)
                i += x[t] * sin(a)
            }
            re[k] = r
            im[k] = i
        }
        return re to im
    }

    private fun idft(re: DoubleArray, im: DoubleArray, n: Int): FloatArray = FloatArray(n) { t ->
        var s = re[0]
        for (k in 1 until n / 2) {
            val a = 2.0 * PI * k * t / n
            s += 2.0 * (re[k] * cos(a) - im[k] * sin(a))
        }
        s += re[n / 2] * cos(PI * t)
        (s / n).toFloat()
    }

    /** Mean log-spectral distance between neighbouring strips: how much the picture changes top to bottom. */
    internal fun vchange(cycles: List<FloatArray>): Float {
        val mags = cycles.map { c ->
            val (re, im) = dft(c)
            DoubleArray(40) { 20.0 * kotlin.math.log10(hypot(re[it + 1], im[it + 1]) + 1e-4) }
        }
        var sum = 0.0
        for (k in 1 until mags.size) {
            var d = 0.0
            for (i in 0 until 40) { val e = mags[k][i] - mags[k - 1][i]; d += e * e }
            sum += sqrt(d / 40)
        }
        return (sum / (mags.size - 1)).toFloat()
    }

    /**
     * Spectral contrast (§6.4): fit the 1/f slope every natural photo shares, remove it, scale
     * what is left by [amount], lay [slope] back under it, roll the top octave off, keep phases.
     */
    internal fun contrast(cycle: FloatArray, amount: Float, slope: Float): FloatArray {
        val n = cycle.size
        val (re, im) = dft(cycle)
        val hs = 1..n / 2
        val lh = DoubleArray(n / 2 + 1) { if (it == 0) 0.0 else ln(it.toDouble()) }
        val lm = DoubleArray(n / 2 + 1) { if (it == 0) 0.0 else ln(hypot(re[it], im[it]) + 1e-9) }
        // least squares over harmonics 1..64
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (h in 1..64) { sx += lh[h]; sy += lm[h]; sxx += lh[h] * lh[h]; sxy += lh[h] * lm[h] }
        val b = (64 * sxy - sx * sy) / (64 * sxx - sx * sx)
        val a = (sy - b * sx) / 64
        val nr = DoubleArray(n / 2 + 1)
        val ni = DoubleArray(n / 2 + 1)
        for (h in hs) {
            val resid = (lm[h] - (a + b * lh[h])).coerceIn(-3.0, 3.0)
            var newLog = slope * lh[h] + amount * resid
            if (h > 96) newLog -= (h - 96) * 0.15
            val mag = exp(newLog)
            val ph = atan2(im[h], re[h])
            nr[h] = mag * cos(ph)
            ni[h] = mag * sin(ph)
        }
        val out = idft(nr, ni, n)
        var peak = 0f
        for (v in out) peak = max(peak, abs(v))
        if (peak > 0f) for (i in out.indices) out[i] /= peak
        return out
    }

    private fun toBytes(cycle: FloatArray): IntArray =
        IntArray(cycle.size) { Math.round((cycle[it].coerceIn(-1f, 1f) + 1f) * 0.5f * 255f).coerceIn(0, 255) }

    /** Top third's mean brightness minus the bottom third's: positive when the sky is the bright part. */
    internal fun lightOf(lum: FloatArray, w: Int, h: Int): Float {
        val third = max(1, h / 3)
        var top = 0.0; var bottom = 0.0
        for (y in 0 until third) for (x in 0 until w) { top += lum[y * w + x]; bottom += lum[(h - 1 - y) * w + x] }
        return ((top - bottom) / (third * w)).toFloat()
    }

    internal fun warmthOf(photo: Photo): Float {
        var s = 0.0
        for (p in photo.argb) s += (Photo.red(p) - Photo.blue(p)) / 255.0
        return (s / photo.argb.size).toFloat()
    }

    /** Mirror correlation of brightness, left against right: 1 symmetric, about 0 none. */
    internal fun symmetryOf(lum: FloatArray, w: Int, h: Int): Float {
        val mean = lum.average()
        var num = 0.0; var den = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val a = lum[y * w + x] - mean
            num += a * (lum[y * w + (w - 1 - x)] - mean)
            den += a * a
        }
        return if (den < 1e-12) 0f else (num / den).toFloat()
    }

    /** Immerkær's noise estimate: a 3x3 kernel that cancels smooth structure, leaving sensor grain. */
    internal fun grainOf(lum: FloatArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3) return 0f
        var s = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            fun l(dx: Int, dy: Int) = lum[(y + dy) * w + (x + dx)]
            val k = l(-1, -1) - 2 * l(0, -1) + l(1, -1) - 2 * l(-1, 0) + 4 * l(0, 0) - 2 * l(1, 0) + l(-1, 1) - 2 * l(0, 1) + l(1, 1)
            s += abs(k)
        }
        return (sqrt(PI / 2) / (6.0 * (w - 2) * (h - 2)) * s).toFloat()
    }

    // ---------- the sound (TONE family) ----------

    /** The note a portrait pad plays: TUNE's snapped note, moved by the portrait's register, before the fingerprint. */
    fun frequencyFor(tune: Float, portrait: SnapPortrait): Float = Snap.frequencyFor(tune) * 2f.pow(portrait.register)

    /** What one take's fingerprint draws (§7, strength [FINGERPRINT]). Take 0 is the pad as saved. */
    internal class Fingerprint(seed: Int, take: Int) {
        private val rng = Random(Dsp.seedFor("SNAP-TAKE", seed, take))
        private fun draw(lo: Float, hi: Float) = (lo + (hi - lo) * rng.nextFloat()) * FINGERPRINT
        // Drawn in a fixed order so adding a draw at the end never moves the earlier ones.
        val cents = draw(-6f, 6f)
        val lengthScale = 1f + draw(-0.12f, 0.12f)
        val scanStart = abs(draw(0f, 1f))
        val phase = rng.nextFloat()
        val driftPhase = rng.nextFloat() * 2f * PI.toFloat()
    }

    /** One take of a portrait pad at [rate], before decimation. */
    internal fun synthesize(table: IntArray, macros: Map<String, Float>, portrait: SnapPortrait, take: Int, rate: Int): FloatArray {
        val m = Snap.defaults(SnapVoice.HORIZON).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = if (v.isFinite()) v.coerceIn(0f, 1f) else m.getValue(k)
        val fp = Fingerprint(portrait.seed, take)
        val freq = frequencyFor(m.getValue("TUNE"), portrait) * 2f.pow(fp.cents / 1200f)
        val bright = m.getValue("BRIGHT")
        val grit = m.getValue("GRIT")
        val scan = m.getValue("SCAN")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.15f, 2f) * fp.lengthScale
        val seconds = (t60 * Snap.LENGTH_OVER_T60).coerceIn(0.25f, Snap.MAX_SECONDS)
        val attack = Dsp.expMap(1f - portrait.light, 0.003f, 0.35f * seconds)
        val hold = portrait.symmetry * 0.25f * seconds
        val driftCents = (1f - portrait.symmetry) * 8f
        val warm = portrait.warmth - 0.5f
        val grainLevel = portrait.grain * 0.12f
        val gain = (1f + 2f * grit) * (1f + 2f * portrait.clip)
        val tanhGain = tanh(gain)

        val horizon = Snap.cycle(table)
        val strips = portrait.strips.map { Snap.cycle(it) }
        val n = Snap.TABLE_SIZE
        val out = FloatArray((seconds * rate).toInt())
        val travel = scan * (STRIPS - 1)
        val curve = 1f - exp(-3f)
        val cutoffBase = Dsp.expMap(bright, 250f, 12_000f)
        val filter = Dsp.TptSvf(rate)
        val tiltCoef = exp(-2.0 * PI * 2500.0 / rate).toFloat()
        var tiltLp = 0f
        var noiseLp = 0f
        val noise = Dsp.Noise(Dsp.seedFor("SNAP-GRAIN", portrait.seed, take))
        var phase = fp.phase.toDouble() * n
        val baseStep = freq.toDouble() * n / rate
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val env = (t / attack).coerceAtMost(1f).pow(1.5f) *
                (if (t < attack + hold) 1f else Dsp.envAt(t - attack - hold, t60))
            // the scan: fast then settling, over the note's own length
            var pos = (fp.scanStart + travel * (1f - exp(-3f * t / seconds)) / curve).coerceIn(0f, (STRIPS - 1).toFloat())
            if (portrait.fromBelow) pos = (STRIPS - 1) - pos
            val b0 = pos.toInt().coerceIn(0, STRIPS - 1)
            val b1 = min(b0 + 1, STRIPS - 1)
            var bw = pos - b0
            bw = bw * bw * (3f - 2f * bw)
            val idx = phase.toInt()
            val frac = (phase - idx).toFloat()
            val i0 = idx % n
            val i1 = (idx + 1) % n
            fun read(c: FloatArray) = c[i0] + (c[i1] - c[i0]) * frac
            val stripSample = read(strips[b0]) * (1f - bw) + read(strips[b1]) * bw
            var x = (1f - scan) * read(horizon) + scan * stripSample
            // symmetry: a lopsided picture drifts a few cents
            val drift = 2.0.pow(driftCents / 1200.0 * sin(2.0 * PI * 0.7 * t + fp.driftPhase))
            phase += baseStep * drift
            if (phase >= n) phase -= n
            // warmth: warm rounds the top, cool adds air
            tiltLp += (1f - tiltCoef) * (x - tiltLp)
            x = if (warm > 0f) x + warm * 1.2f * (tiltLp - x) else x + (-warm) * 0.8f * (x - tiltLp)
            // grain: a breath of band-limited noise
            noiseLp += (1f - tiltCoef) * (noise.next() - noiseLp)
            x += grainLevel * 4f * noiseLp
            filter.process(x, cutoffBase * (0.45f + 0.55f * env), k = 1.2f)
            out[i] = (tanh(filter.low * gain) / tanhGain) * env
        }
        return out
    }

    /** Take [take] of a portrait pad: rendered at 4x and decimated, as every SNAP pad is. */
    fun render(table: IntArray, macros: Map<String, Float>, portrait: SnapPortrait, take: Int = 0): com.snipsnap.audio.Snip {
        require(take >= 0) { "a take is 0 or more, got $take" }
        val raw = synthesize(table, macros, portrait, take, Dsp.RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(raw, Dsp.RATE)
        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return com.snipsnap.audio.Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
