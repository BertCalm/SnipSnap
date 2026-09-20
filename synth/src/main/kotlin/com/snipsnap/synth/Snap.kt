package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * SNAP — a photo becomes a pad. The "Snap" half of the name, cashed.
 *
 * Two ideas from image sonification, chosen because both land on a
 * one-shot the rest of the pipeline already knows how to trim, place and
 * export, rather than on a minute of texture:
 *
 * 1. **Wavetable.** One line read across the picture *is* one cycle of a
 *    waveform. 256 brightness values, looped at a pitch, are an oscillator
 *    whose timbre is the photo's own structure — a skyline is buzzy, a
 *    gradient is soft, stripes are harmonic. The voice says which line:
 *    [SnapVoice.HORIZON] reads left to right (each column averaged top to
 *    bottom, so it is the picture's silhouette, not one noisy row of
 *    pixels), [SnapVoice.PLUMB] reads top to bottom the same way, and
 *    [SnapVoice.ORBIT] walks a circle around the centre — which closes on
 *    itself, so that cycle wraps with no seam at all.
 *
 * 2. **Feature mapping.** The photo's summary numbers set the knobs:
 *    dominant hue → TUNE (red low, violet high), brightness → BRIGHT (the
 *    filter), colourfulness → DECAY (a vivid photo rings longer), fine
 *    detail → GRIT (a busy photo drives harder). See [macrosFrom]. Those
 *    are starting points to wreck, same as any preset: the sliders stay
 *    live afterwards.
 *
 * What a pad stores is the 256 numbers themselves ([SnapPatch.table]),
 * 0..255 as read, plus the macros — not the photo. The sidecar carries the
 * picture's line in plain digits, and the WAV rebuilds from it bit for bit
 * like every other synth recipe.
 *
 * Nothing about a photo *inherently* sounds like anything; every mapping
 * is a choice. These were chosen so that visible structure becomes
 * audible structure, and so that no photo lands on garbage: the ranges are
 * bounded the way SCRAMBLE's are. The one refusal comes in words — a flat
 * photo ([read]) has no waveform in it.
 */
enum class SnapVoice { HORIZON, PLUMB, ORBIT }

object Snap {

    /** Points in one cycle. Stored per pad, so it stays small. */
    const val TABLE_SIZE = 256

    /** Semitone span of the TUNE macro: A2 at 0, two octaves up at 1. */
    const val TUNE_SEMITONES = 24
    private const val ROOT_HZ = 110f

    /**
     * Samples at the end of the cycle blended toward its start, so a
     * HORIZON or PLUMB line — whose two ends are unrelated pixels — does
     * not click once per period. ORBIT's ends are already neighbours;
     * the same blend changes it by nothing audible.
     */
    private const val SEAM = 16

    /** The longest render: under the classifier's 1.5 s LOOP line, so a pad stays a hit. */
    internal const val MAX_SECONDS = 1.45f

    /** The macro set is the same for every voice: the voice picks the line, the knobs shape the sound. */
    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f),
        MacroSpec("BRIGHT", 0.6f),
        MacroSpec("DECAY", 0.5f),
        MacroSpec("GRIT", 0.15f),
    )

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: SnapVoice): List<MacroSpec> = MACROS

    fun defaults(voice: SnapVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** SCRAMBLE near the current sound; see [Thump.scramble] (docs/SYNTH_UPGRADE.md, U2). */
    fun scramble(voice: SnapVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** The snapped note frequency the TUNE macro lands on. */
    fun frequencyFor(tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return ROOT_HZ * 2f.pow(semis / 12f)
    }

    // ---------- looking at the photo ----------

    /**
     * What a photo looks like to SNAP: a few cheap, interpretable numbers,
     * the same bias `Features` has for audio — when a pad comes out dark or
     * dirty the user should be able to see why on the LCD.
     */
    data class Reading(
        /** Mean brightness, 0..1. */
        val luminance: Float,
        /** Spread of brightness (standard deviation), 0..1. Zero is one flat colour. */
        val contrast: Float,
        /** Mean colourfulness, 0..1. Zero is a grey photo. */
        val saturation: Float,
        /**
         * Dominant hue in degrees, 0..360 — the circular mean weighted by
         * saturation, so grey pixels do not vote. Meaningless when
         * [saturation] is near zero or [hueStrength] is low; [macrosFrom]
         * treats it so.
         */
        val hue: Float,
        /**
         * How much the photo agrees on that hue, 0..1: the length of the
         * mean hue vector. One colour scores 1; a photo half red and half
         * cyan cancels to 0 and has no dominant hue at all, whatever its
         * saturation says.
         */
        val hueStrength: Float,
        /**
         * Mean brightness step between points one grid cell apart, 0..1,
         * measured across and down on a [DETAIL_GRID]-cell grid — so the
         * number is about the picture, not about how many pixels the
         * camera app happened to hand back, and a horizontal stripe scores
         * the same as a vertical one.
         */
        val detail: Float,
    )

    /** Below this mean saturation a photo counts as grey and its hue is not trusted. */
    internal const val GREY_SATURATION = 0.08f

    /** Below this [Reading.hueStrength] the hue is a cancellation, not a colour, and is not trusted either. */
    internal const val WEAK_HUE = 0.2f

    /** Cells across the long side that [Reading.detail] is measured on. */
    internal const val DETAIL_GRID = 128

    /**
     * Where the hue circle is cut to lay it on the TUNE knob. Any cut
     * puts two near-identical hues at opposite ends; this one sits at
     * rose, between magenta and red, so every red a photo is likely to
     * hold (a sunset at 10°, a skin tone at 20°, a red door at 355°) is
     * on the same low end of the knob rather than straddling the seam
     * at 0°.
     */
    internal const val HUE_SEAM_DEGREES = 330f

    fun look(photo: Photo): Reading {
        val w = photo.width
        val h = photo.height
        val n = w * h
        var lumSum = 0.0
        var lumSq = 0.0
        var satSum = 0.0
        var hx = 0.0
        var hy = 0.0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = photo.pixel(x, y)
                val r = Photo.red(p) / 255f
                val g = Photo.green(p) / 255f
                val b = Photo.blue(p) / 255f
                val lum = Photo.luminance(p)
                lumSum += lum
                lumSq += lum.toDouble() * lum
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val chroma = mx - mn
                val sat = if (mx <= 1e-6f) 0f else chroma / mx
                satSum += sat
                if (chroma > 1e-6f) {
                    // HSV hue, in degrees, as a unit vector so the mean wraps
                    // correctly at red (0° and 360° are the same colour).
                    val hue = when (mx) {
                        r -> 60f * (((g - b) / chroma) % 6f)
                        g -> 60f * ((b - r) / chroma + 2f)
                        else -> 60f * ((r - g) / chroma + 4f)
                    }
                    val rad = hue * PI / 180.0
                    hx += cos(rad) * sat
                    hy += sin(rad) * sat
                }
            }
        }
        // Detail on a fixed grid, both ways: one step per cell across and
        // one down, so a 160x120 thumbnail and a 1024x768 one of the same
        // scene agree, and a horizontal stripe counts as much as a
        // vertical one.
        val stride = max(1, Math.round(max(w, h).toFloat() / DETAIL_GRID))
        var stepSum = 0.0
        var steps = 0
        for (y in 0 until h step stride) {
            for (x in 0 until w step stride) {
                val here = photo.luminance(x, y)
                if (x + stride < w) { stepSum += abs(photo.luminance(x + stride, y) - here); steps++ }
                if (y + stride < h) { stepSum += abs(photo.luminance(x, y + stride) - here); steps++ }
            }
        }
        val mean = (lumSum / n).toFloat()
        val variance = (lumSq / n - mean.toDouble() * mean).coerceAtLeast(0.0)
        var hueDeg = (atan2(hy, hx) * 180.0 / PI).toFloat()
        if (hueDeg < 0f) hueDeg += 360f
        val strength = if (satSum <= 1e-9) 0f else (sqrt(hx * hx + hy * hy) / satSum).toFloat().coerceIn(0f, 1f)
        return Reading(
            luminance = mean,
            contrast = sqrt(variance).toFloat(),
            saturation = (satSum / n).toFloat(),
            hue = hueDeg,
            hueStrength = strength,
            detail = if (steps == 0) 0f else (stepSum / steps).toFloat(),
        )
    }

    /**
     * The photo's numbers as knob positions. Every output is bounded to a
     * range that sounds like something, so no photo lands on silence or
     * a mistuning; the mapping is documented on the object.
     */
    fun macrosFrom(reading: Reading): Map<String, Float> {
        // Hue walks the spectrum as TUNE walks two octaves: red at the
        // bottom, violet at the top, the circle cut at [HUE_SEAM_DEGREES]
        // so the reds stay together. A grey photo has no hue to speak of,
        // and a photo whose colours cancel (half red, half cyan) has no
        // dominant one; both land on the root an octave up — the centre
        // detent — rather than on wherever float noise points.
        val tune = if (reading.saturation < GREY_SATURATION || reading.hueStrength < WEAK_HUE) {
            0.5f
        } else {
            (((reading.hue - HUE_SEAM_DEGREES + 720f) % 360f) / 360f).coerceIn(0f, 1f)
        }
        // Brightness opens the filter. Floored so a night shot is dark,
        // not inaudible.
        val bright = Dsp.lin(reading.luminance, 0.15f, 0.95f)
        // Colourfulness rings. Phone photos rarely average past 0.5
        // saturation, so the useful half of the scale is stretched to
        // the whole knob.
        val decay = Dsp.lin((reading.saturation * 2f).coerceIn(0f, 1f), 0.2f, 0.9f)
        // Texture drives. A neighbouring-pixel step of 0.1 is already a
        // very busy picture; 0.8 keeps the top of the knob for the slider.
        val grit = (reading.detail * 8f).coerceIn(0f, 1f) * 0.8f
        return mapOf("TUNE" to tune, "BRIGHT" to bright, "DECAY" to decay, "GRIT" to grit)
    }

    // ---------- the line through the photo ----------

    /**
     * One cycle read off the photo, [TABLE_SIZE] brightness values 0..255,
     * raw — no conditioning, so the numbers on the sidecar are the
     * picture's own. The voice picks the line; see the class doc.
     */
    fun table(photo: Photo, voice: SnapVoice): IntArray {
        val w = photo.width
        val h = photo.height
        val out = IntArray(TABLE_SIZE)
        when (voice) {
            SnapVoice.HORIZON -> for (i in 0 until TABLE_SIZE) {
                // Bin i covers a band of columns, at least one wide, so a
                // photo wider than the table is averaged, not aliased.
                val x0 = i * w / TABLE_SIZE
                val x1 = max(x0 + 1, (i + 1) * w / TABLE_SIZE)
                var sum = 0.0
                var count = 0
                for (x in x0 until min(x1, w)) for (y in 0 until h) { sum += photo.luminance(x, y); count++ }
                out[i] = toByte(sum / count)
            }
            SnapVoice.PLUMB -> for (i in 0 until TABLE_SIZE) {
                val y0 = i * h / TABLE_SIZE
                val y1 = max(y0 + 1, (i + 1) * h / TABLE_SIZE)
                var sum = 0.0
                var count = 0
                for (y in y0 until min(y1, h)) for (x in 0 until w) { sum += photo.luminance(x, y); count++ }
                out[i] = toByte(sum / count)
            }
            SnapVoice.ORBIT -> {
                // A ring at a third of the short side, three radii deep so
                // one stray pixel does not own a sample. Clockwise from
                // three o'clock, as angles go.
                val cx = (w - 1) / 2.0
                val cy = (h - 1) / 2.0
                val radius = min(w, h) * 0.32
                val radii = doubleArrayOf(radius * 0.9, radius, radius * 1.1)
                for (i in 0 until TABLE_SIZE) {
                    val angle = 2.0 * PI * i / TABLE_SIZE
                    var sum = 0.0
                    for (r in radii) {
                        val x = Math.round(cx + r * cos(angle)).toInt().coerceIn(0, w - 1)
                        val y = Math.round(cy + r * sin(angle)).toInt().coerceIn(0, h - 1)
                        sum += photo.luminance(x, y)
                    }
                    out[i] = toByte(sum / radii.size)
                }
            }
        }
        return out
    }

    private fun toByte(lum: Double): Int = Math.round(lum.coerceIn(0.0, 1.0) * 255).toInt()

    /** A table with no swing in it — one flat colour along the line — has no waveform to play. */
    fun isFlat(table: IntArray): Boolean {
        var lo = 255
        var hi = 0
        for (v in table) { if (v < lo) lo = v; if (v > hi) hi = v }
        return hi - lo < FLAT_SWING
    }

    /** Less than this many brightness steps between the line's darkest and lightest point is flat. */
    internal const val FLAT_SWING = 3

    /**
     * The whole door: look at the photo, read the line the voice names,
     * set the knobs from what was seen. Refuses a line with no swing in
     * it in words — the app toasts that, it never lands a silent pad.
     */
    fun read(photo: Photo, voice: SnapVoice, name: String): SnapPatch {
        val table = table(photo, voice)
        require(!isFlat(table)) { "the ${voice.name} line through this photo is one flat colour: no waveform in it" }
        return SnapPatch(name, voice, macrosFrom(look(photo)), table)
    }

    // ---------- the sound ----------

    /**
     * The stored bytes as a playable cycle: zero-mean (a DC offset would
     * be a thump on every note-on), the seam blended (see [SEAM]), peak
     * at ±1.
     */
    internal fun cycle(table: IntArray): FloatArray {
        val n = table.size
        val out = FloatArray(n) { table[it] / 255f }
        // Seam first, then the mean: the blend moves a few points, and a
        // mean taken before it would leave that shift behind as DC.
        val seam = min(SEAM, n / 2)
        val head = out[0]
        for (k in 0 until seam) {
            val i = n - seam + k
            val t = (k + 1).toFloat() / (seam + 1)
            out[i] = out[i] * (1f - t) + head * t
        }
        var mean = 0f
        for (v in out) mean += v
        mean /= n
        for (i in out.indices) out[i] -= mean
        Dsp.normalize(out, 1f)
        return out
    }

    /**
     * The raw synth loop, at whatever [rate] the caller wants — split out of
     * [render] so U6's oversampled dispatch (docs/SYNTH_UPGRADE.md) can be
     * tested against a native-rate render, like the other engines.
     */
    internal fun synthesize(table: IntArray, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(SnapVoice.HORIZON).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val freq = frequencyFor(m.getValue("TUNE"))
        val bright = m.getValue("BRIGHT")
        val decay = m.getValue("DECAY")
        val grit = m.getValue("GRIT")

        val wave = cycle(table)
        val n = wave.size
        // A one-shot, like PLUCK: the classifier files anything past
        // 1.5 s as a LOOP, and a pad is a hit. The top of DECAY rings
        // long enough to be heard as a note (TONAL) and is cut, faded,
        // where the envelope is already 40 dB down.
        val t60 = Dsp.expMap(decay, 0.15f, 2f)
        val seconds = (t60 * 1.1f).coerceIn(0.25f, MAX_SECONDS)
        val cutoff = Dsp.expMap(bright, 250f, 12_000f)
        val env = Dsp.Env(attackSeconds = 0.003f, decay2T60 = t60)
        val filter = Dsp.TptSvf(rate)
        val gain = Dsp.lin(grit, 1f, 3f)

        val out = FloatArray((seconds * rate).toInt())
        var phase = 0.0
        val step = freq.toDouble() * n / rate
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val e = env.at(t)
            // Linear interpolation into the cycle; the table is small, the
            // oversampled render (U6) is what keeps the top clean.
            val idx = phase.toInt()
            val frac = (phase - idx).toFloat()
            val a = wave[idx % n]
            val b = wave[(idx + 1) % n]
            val s = a + (b - a) * frac
            phase += step
            if (phase >= n) phase -= n
            // The filter follows the envelope a little, the way a struck
            // thing gets darker as it fades.
            filter.process(s, cutoff * Dsp.lin(e, 0.45f, 1f), k = 1.2f)
            out[i] = Dsp.drive(filter.low * gain, grit) * e
        }
        return out
    }

    fun render(table: IntArray, macros: Map<String, Float> = emptyMap()): Snip {
        require(table.size == TABLE_SIZE) { "a SNAP table has $TABLE_SIZE points, got ${table.size}" }
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE and decimate. This
        // engine has two reasons to: the GRIT drive makes fresh harmonics,
        // and a 256-point table read at 440 Hz has plenty of its own above
        // the audible band.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(table, macros, renderRate)
        val out = Dsp.decimate(raw, RATE)
        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
