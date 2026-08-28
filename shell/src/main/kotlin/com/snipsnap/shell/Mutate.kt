package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Transients
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import java.io.File

/**
 * Mutate (LL1) — sound design by **recombination**: one hit from many
 * parents. Where `treat` and `era` transform a single sound, mutate
 * breeds a new one:
 *
 * - **stack** — parents layered, transient-aligned (no flams), with an
 *   honest polarity check: a layer that measurably cancels against the
 *   base is flipped, and the result says so;
 * - **splice** — the classic mash: the pad's own transient crossfaded
 *   into a parent's body at the split;
 * - **split** — the pad below a crossover, the parent above ("sub from
 *   this kick, crack from that snare").
 *
 * Bin-backed through the same door as every treatment; the recipe
 * (mode, parents, split, flips) rides the pad so the sound stays
 * regenerable; provenance stamps the parents so `lineage` shows a hit
 * with two of them. Deterministic: same parents, same recipe, same
 * bytes.
 */
object Mutate {

    enum class Mode { STACK, SPLICE, SPLIT }

    /** A parent sound: where it came from (for the recipe) and its audio. */
    data class Source(val label: String, val snip: Snip)

    data class Outcome(val pad: KitPad, val flipped: List<String>)

    /** Splice: where the transient hands over, past the pad's own attack. */
    const val DEFAULT_SPLICE_MS = 40

    /** Splice crossfade — a handover, never a click. */
    const val FADE_MS = 10

    /** Split: the default crossover between the pad's lows and the parent's highs. */
    const val DEFAULT_CROSSOVER_HZ = 200f

    /** Stack: correlation below this reads as phase cancellation. */
    const val CANCEL_CORRELATION = -0.2f

    fun apply(
        model: KitBuilderModel,
        slot: Int,
        sources: List<Source>,
        mode: Mode = Mode.STACK,
        spliceAtMs: Int = DEFAULT_SPLICE_MS,
        crossoverHz: Float = DEFAULT_CROSSOVER_HZ,
    ): Outcome {
        require(sources.isNotEmpty()) { "mutate wants at least one --with parent" }
        if (mode != Mode.STACK) {
            require(sources.size == 1) { "${mode.name.lowercase()} takes exactly one --with parent" }
        }
        require(spliceAtMs in 5..2000) { "--at wants 5..2000 ms, got $spliceAtMs" }
        require(crossoverHz in 40f..8000f) { "--hz wants 40..8000, got $crossoverHz" }
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")

        val base = com.snipsnap.audio.WavReader.read(File(model.kitDir, pad.sampleFile))
        val rate = base.sampleRate
        val baseAligned = alignToOnset(toStereo(base))
        val parents = sources.map { it.copy(snip = alignToOnset(resampled(toStereo(it.snip), rate))) }

        val flipped = mutableListOf<String>()
        val result = when (mode) {
            Mode.STACK -> stack(baseAligned, parents, flipped)
            Mode.SPLICE -> splice(baseAligned, parents.single().snip, spliceAtMs, rate)
            Mode.SPLIT -> split(baseAligned, parents.single().snip, crossoverHz, rate)
        }

        val recipe = JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "mutate" to JsonValue.Obj(
                    linkedMapOf<String, JsonValue>(
                        "mode" to JsonValue.Str(mode.name.lowercase()),
                        "with" to JsonValue.Arr(sources.map { JsonValue.Str(it.label) }),
                    ).also { r ->
                        if (mode == Mode.SPLICE) r["at"] = JsonValue.Num(spliceAtMs.toDouble())
                        if (mode == Mode.SPLIT) r["hz"] = JsonValue.Num(crossoverHz.toDouble())
                        if (flipped.isNotEmpty()) {
                            r["flipped"] = JsonValue.Arr(flipped.map { JsonValue.Str(it) })
                        }
                    },
                ),
            ),
        )
        model.replaceAudio(slot, recipe) { result }
        val mutated = model.update(slot) {
            it.copy(source = it.source + mapOf("mutatedWith" to sources.joinToString(", ") { s -> s.label }))
        }
        return Outcome(mutated, flipped)
    }

    /** The parents back out of the bin; recipe and parent stamp cleared. */
    fun undo(model: KitBuilderModel, slot: Int): KitPad {
        model.untreatPad(slot)
        return model.update(slot) { it.copy(source = it.source - "mutatedWith") }
    }

    // ---- the three moves --------------------------------------------------

    private fun stack(base: Snip, parents: List<Source>, flippedOut: MutableList<String>): Snip {
        val layers = mutableListOf(base)
        for (p in parents) {
            val layer = if (correlation(base, p.snip) < CANCEL_CORRELATION) {
                flippedOut += p.label
                Snip(FloatArray(p.snip.samples.size) { -p.snip.samples[it] }, 2, p.snip.sampleRate)
            } else {
                p.snip
            }
            layers += layer
        }
        val frames = layers.maxOf { it.frameCount }
        val out = FloatArray(frames * 2)
        for (l in layers) {
            for (i in l.samples.indices) out[i] += l.samples[i]
        }
        // The stack sits at the loudest parent's own level - layering adds
        // weight, not clipping.
        val target = layers.maxOf { peak(it.samples) }.coerceAtMost(0.99f)
        normalizeTo(out, target)
        return Snip(out, 2, base.sampleRate)
    }

    private fun splice(base: Snip, body: Snip, atMs: Int, rate: Int): Snip {
        val split = (atMs * rate / 1000).coerceAtMost(maxOf(1, base.frameCount - 1))
        val fade = (FADE_MS * rate / 1000).coerceAtLeast(8)
        val frames = maxOf(split + fade, body.frameCount)
        val out = FloatArray(frames * 2)
        // The pad's transient up to the split, fading through the handover...
        for (f in 0 until minOf(split + fade, base.frameCount)) {
            val g = if (f < split) 1f else 1f - (f - split).toFloat() / fade
            out[f * 2] += base.samples[f * 2] * g
            out[f * 2 + 1] += base.samples[f * 2 + 1] * g
        }
        // ...into the parent's body from the same time position, so its
        // decay continues naturally - as if both had been hit together.
        for (f in split until body.frameCount) {
            val g = if (f < split + fade) (f - split).toFloat() / fade else 1f
            out[f * 2] += body.samples[f * 2] * g
            out[f * 2 + 1] += body.samples[f * 2 + 1] * g
        }
        return Snip(out, 2, rate)
    }

    private fun split(base: Snip, top: Snip, hz: Float, rate: Int): Snip {
        val lows = lowpass(base, hz)
        val topLows = lowpass(top, hz)
        val frames = maxOf(base.frameCount, top.frameCount)
        val out = FloatArray(frames * 2)
        for (i in lows.samples.indices) out[i] += lows.samples[i]
        for (i in top.samples.indices) out[i] += top.samples[i] - topLows.samples[i]
        return Snip(out, 2, rate)
    }

    // ---- helpers ----------------------------------------------------------

    /** Leading room before the hit is trimmed, so layers meet at the attack. */
    private fun alignToOnset(snip: Snip): Snip {
        val onset = Transients.detect(snip).firstOrNull()?.frame ?: return snip
        if (onset <= 0) return snip
        return Snip(snip.samples.copyOfRange(onset * 2, snip.samples.size), 2, snip.sampleRate)
    }

    /** Correlation of the first ~46ms, mono-folded — the cancellation test. */
    private fun correlation(a: Snip, b: Snip): Float {
        val n = minOf(a.frameCount, b.frameCount, 2048)
        if (n < 32) return 0f
        var dot = 0.0
        var ea = 0.0
        var eb = 0.0
        for (f in 0 until n) {
            val x = (a.samples[f * 2] + a.samples[f * 2 + 1]) * 0.5
            val y = (b.samples[f * 2] + b.samples[f * 2 + 1]) * 0.5
            dot += x * y
            ea += x * x
            eb += y * y
        }
        if (ea < 1e-9 || eb < 1e-9) return 0f
        return (dot / Math.sqrt(ea * eb)).toFloat()
    }

    private fun toStereo(snip: Snip): Snip = when (snip.channels) {
        2 -> snip
        else -> {
            val out = FloatArray(snip.frameCount * 2)
            for (f in 0 until snip.frameCount) {
                var sum = 0f
                for (ch in 0 until snip.channels) sum += snip.samples[f * snip.channels + ch]
                val v = sum / snip.channels
                out[f * 2] = v
                out[f * 2 + 1] = v
            }
            Snip(out, 2, snip.sampleRate)
        }
    }

    private fun resampled(snip: Snip, rate: Int): Snip =
        if (snip.sampleRate == rate) snip else com.snipsnap.audio.Resampler.resample(snip, rate)

    private fun lowpass(snip: Snip, hz: Float): Snip {
        val a = (1.0 - Math.exp(-2.0 * Math.PI * hz / snip.sampleRate)).toFloat()
        val out = FloatArray(snip.samples.size)
        for (ch in 0 until 2) {
            var y = 0f
            var i = ch
            while (i < snip.samples.size) {
                y += a * (snip.samples[i] - y)
                out[i] = y
                i += 2
            }
        }
        return Snip(out, 2, snip.sampleRate)
    }

    private fun peak(samples: FloatArray): Float {
        var p = 0f
        for (v in samples) {
            val x = if (v < 0) -v else v
            if (x > p) p = x
        }
        return p
    }

    private fun normalizeTo(samples: FloatArray, target: Float) {
        val p = peak(samples)
        if (p < 1e-9f || target <= 0f) return
        val k = target / p
        for (i in samples.indices) samples[i] *= k
    }
}
