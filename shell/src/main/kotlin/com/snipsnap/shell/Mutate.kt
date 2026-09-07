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
 *   this kick, crack from that snare");
 * - **room** — the pad played *inside* the parent: the parent's tail as
 *   the impulse response the pad is convolved with ("kick in the
 *   snare's room"), MIX the dry/wet;
 * - **transplant** — the pad's attack wearing the parent's long-term
 *   spectral envelope (a one-knob vocoder, BANDS its resolution): the
 *   pad's time, the parent's tone;
 * - **drift** — one knob: the crate's roulette finds the neighbour and
 *   morph blends toward it ([drift]).
 *
 * Bin-backed through the same door as every treatment; the recipe
 * (mode, parents, split, flips) rides the pad so the sound stays
 * regenerable; provenance stamps the parents so `lineage` shows a hit
 * with two of them. Deterministic: same parents, same recipe, same
 * bytes.
 */
object Mutate {

    enum class Mode { STACK, SPLICE, SPLIT, MORPH, ROOM, TRANSPLANT }

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

    /** Guided roulette spins among this many nearest compatible sounds. */
    const val ROULETTE_WINDOW = 8

    /** What the roulette dealt: the parent's name, its file, how alike it is. */
    data class Pick(val label: String, val file: File, val distance: Float)

    /**
     * The crate picks the partner (LL2): guided by default — [Similar]'s
     * distance ranks every pad under [root], dupes are excluded (a copy
     * isn't a partner), and a seeded spin lands on one of the
     * [ROULETTE_WINDOW] nearest — or `--wild`, a seeded spin across the
     * whole crate. Never the pad itself. Deterministic per (crate, seed).
     */
    fun roulette(
        model: KitBuilderModel,
        slot: Int,
        root: File,
        seed: Int = 0,
        wild: Boolean = false,
    ): Pick {
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        val index = Crate.index(root)
        val self = File(model.kitDir, pad.sampleFile).canonicalPath
        val candidates = index.entries.filter { e ->
            File(root, e.file).canonicalPath != self
        }
        require(candidates.isNotEmpty()) { "the crate under $root has nothing to spin for" }

        val rng = java.util.Random(seed.toLong())
        val chosen = if (wild) {
            candidates[rng.nextInt(candidates.size)] to -1f
        } else {
            val snip = com.snipsnap.audio.WavReader.read(File(model.kitDir, pad.sampleFile))
            val target = com.snipsnap.audio.Similar
                .vector(com.snipsnap.audio.FeatureExtractor.extract(snip)).toList()
            val ranked = candidates.map { it to Crate.distance(target, it.vector) }
                .filter { it.second > Crate.DUPE_DISTANCE }
                .sortedWith(compareBy({ it.second }, { it.first.file }))
            require(ranked.isNotEmpty()) {
                "every sound in the crate is this pad's double - spin --wild instead"
            }
            val window = ranked.take(ROULETTE_WINDOW)
            window[rng.nextInt(window.size)]
        }
        return Pick(
            label = "${chosen.first.kitName}:${chosen.first.label}",
            file = File(root, chosen.first.file),
            distance = chosen.second,
        )
    }

    fun apply(
        model: KitBuilderModel,
        slot: Int,
        sources: List<Source>,
        mode: Mode = Mode.STACK,
        spliceAtMs: Int = DEFAULT_SPLICE_MS,
        crossoverHz: Float = DEFAULT_CROSSOVER_HZ,
        /** MORPH only: 0 = all pad, 1 = all parent. */
        morphAmount: Float = 0.5f,
        /** ROOM only: 0 = dry, 1 = the room alone. */
        roomMix: Float = 0.5f,
        /** TRANSPLANT only: how finely the parent's tone is read. */
        bands: Int = com.snipsnap.audio.Transplant.DEFAULT_BANDS,
        /** Extra recipe fields — how the roulette records its spin. */
        extraRecipe: Map<String, JsonValue> = emptyMap(),
    ): Outcome {
        require(sources.isNotEmpty()) { "mutate wants at least one --with parent" }
        if (mode != Mode.STACK) {
            require(sources.size == 1) { "${mode.name.lowercase()} takes exactly one --with parent" }
        }
        require(spliceAtMs in 5..2000) { "--at wants 5..2000 ms, got $spliceAtMs" }
        require(crossoverHz in 40f..8000f) { "--hz wants 40..8000, got $crossoverHz" }
        require(morphAmount in 0f..1f) { "--amount wants 0..1, got $morphAmount" }
        require(roomMix in 0f..1f) { "--amount wants 0..1, got $roomMix" }
        require(bands in com.snipsnap.audio.Transplant.MIN_BANDS..com.snipsnap.audio.Transplant.MAX_BANDS) {
            "--bands wants ${com.snipsnap.audio.Transplant.MIN_BANDS}..${com.snipsnap.audio.Transplant.MAX_BANDS}, got $bands"
        }
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
            Mode.MORPH -> morph(baseAligned, parents.single().snip, morphAmount, rate)
            Mode.ROOM -> room(baseAligned, parents.single().snip, roomMix, rate)
            Mode.TRANSPLANT -> com.snipsnap.audio.Transplant.apply(baseAligned, parents.single().snip, bands)
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
                        if (mode == Mode.MORPH) r["amount"] = JsonValue.Num(morphAmount.toDouble())
                        if (mode == Mode.ROOM) r["mix"] = JsonValue.Num(roomMix.toDouble())
                        if (mode == Mode.TRANSPLANT) r["bands"] = JsonValue.Num(bands.toDouble())
                        if (flipped.isNotEmpty()) {
                            r["flipped"] = JsonValue.Arr(flipped.map { JsonValue.Str(it) })
                        }
                        r.putAll(extraRecipe)
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

    /** What DRIFT did: the deal the crate made and the morph toward it. */
    data class Drifted(val pick: Pick, val outcome: Outcome)

    /**
     * DRIFT TOWARD THE CRATE (XX1) — one knob: [roulette] finds the
     * neighbour (guided, never wild, never the pad itself), [Mode.MORPH]
     * blends [amount] of the way toward it. Exactly a roulette then a
     * morph, so the recipe is the morph's with the spin recorded beside
     * it and a `drift` flag; deterministic per (crate, seed).
     */
    fun drift(model: KitBuilderModel, slot: Int, root: File, seed: Int = 0, amount: Float = 0.5f): Drifted {
        require(amount in 0f..1f) { "--amount wants 0..1, got $amount" }
        val pick = roulette(model, slot, root, seed = seed, wild = false)
        val outcome = apply(
            model, slot, listOf(Source(pick.label, com.snipsnap.audio.WavReader.read(pick.file))), Mode.MORPH,
            morphAmount = amount,
            extraRecipe = mapOf(
                "roulette" to JsonValue.Obj(
                    linkedMapOf<String, JsonValue>(
                        "seed" to JsonValue.Num(seed.toDouble()),
                        "wild" to JsonValue.Bool(false),
                    ),
                ),
                "drift" to JsonValue.Bool(true),
            ),
        )
        return Drifted(pick, outcome)
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

    /**
     * The Séance's move, done properly on the spectral door: both
     * parents' magnitude spectrograms (transient-aligned by the caller)
     * interpolated bin by bin at [amount], phases re-invented by
     * [com.snipsnap.audio.Pghi] — a sound *between* the parents, not a
     * crossfade of them. Length and level interpolate too.
     */
    private fun morph(base: Snip, parent: Snip, amount: Float, rate: Int): Snip {
        fun monoMags(s: Snip): Pair<List<FloatArray>, Int> {
            val mono = Snip(
                FloatArray(s.frameCount) { f -> (s.samples[f * 2] + s.samples[f * 2 + 1]) / 2f },
                1, s.sampleRate,
            )
            val mags = mutableListOf<FloatArray>()
            com.snipsnap.audio.Spectral.forEachFrame(mono) { _, _, m -> mags.add(m.copyOf()) }
            return mags to mono.frameCount
        }
        val (ma, la) = monoMags(base)
        val (mb, lb) = monoMags(parent)
        val frames = maxOf(ma.size, mb.size)
        val silence = FloatArray(com.snipsnap.audio.Spectral.BINS)
        val mixed = ArrayList<FloatArray>(frames)
        for (f in 0 until frames) {
            val a = ma.getOrElse(f) { silence }
            val b = mb.getOrElse(f) { silence }
            mixed.add(FloatArray(a.size) { i -> a[i] * (1f - amount) + b[i] * amount })
        }
        val outFrames = Math.round(la * (1f - amount) + lb * amount)
        val morphed = com.snipsnap.audio.Pghi.invert(mixed, outFrames, rate)
        val target = (peak(base.samples) * (1f - amount) + peak(parent.samples) * amount).coerceAtMost(0.99f)
        val out = FloatArray(outFrames * 2)
        for (f in 0 until outFrames) {
            out[f * 2] = morphed.samples[f]
            out[f * 2 + 1] = morphed.samples[f]
        }
        normalizeTo(out, target)
        return Snip(out, 2, rate)
    }

    /**
     * ROOM OF ITSELF: the parent as an impulse response. Convolution is a
     * multiplication of spectra, so both go through the classifier's own
     * FFT at a power-of-two length that holds the whole result; the parent
     * is mono-folded and scaled to unit energy so the room's loudness comes
     * from the pad, not the size of the file. The wet signal is brought to
     * the pad's own peak, then MIX crossfades dry to wet. The result runs
     * the pad's length plus the room's tail.
     */
    private fun room(base: Snip, impulse: Snip, mix: Float, rate: Int): Snip {
        val n = base.frameCount + impulse.frameCount - 1
        var size = 1
        while (size < n) size = size shl 1

        // The impulse: mono, unit energy.
        val irRe = FloatArray(size)
        val irIm = FloatArray(size)
        var energy = 0.0
        for (f in 0 until impulse.frameCount) {
            val v = (impulse.samples[f * 2] + impulse.samples[f * 2 + 1]) * 0.5f
            irRe[f] = v
            energy += v * v.toDouble()
        }
        if (energy <= 1e-12) return base
        val k = (1.0 / Math.sqrt(energy)).toFloat()
        for (f in 0 until impulse.frameCount) irRe[f] *= k
        com.snipsnap.audio.Fft.forward(irRe, irIm)

        val wet = FloatArray(n * 2)
        for (ch in 0 until 2) {
            val re = FloatArray(size)
            val im = FloatArray(size)
            for (f in 0 until base.frameCount) re[f] = base.samples[f * 2 + ch]
            com.snipsnap.audio.Fft.forward(re, im)
            for (b in 0 until size) {
                val r = re[b] * irRe[b] - im[b] * irIm[b]
                val i = re[b] * irIm[b] + im[b] * irRe[b]
                re[b] = r
                im[b] = i
            }
            com.snipsnap.audio.Fft.inverse(re, im)
            for (f in 0 until n) wet[f * 2 + ch] = re[f]
        }
        normalizeTo(wet, peak(base.samples))

        val out = FloatArray(n * 2)
        for (i in out.indices) {
            val dry = if (i < base.samples.size) base.samples[i] else 0f
            out[i] = dry * (1f - mix) + wet[i] * mix
        }
        return Snip(out, 2, rate)
    }

    // ---- helpers ----------------------------------------------------------

    /** Before the first onset, this loud (relative to the peak) is not room — the sound was already hot. */
    private const val HOT_OPEN_RATIO = 0.1f

    /**
     * Leading room before the hit is trimmed, so layers meet at the attack.
     *
     * Unless there is no room to trim: the onset detector credits nothing
     * to its first analysis frame, so a sound that starts *on* its hit (a
     * chopped break, a captured room's impulse response with the direct
     * arrival at the top) reports its *second* event as the first onset —
     * and trimming to that would throw the hit away and keep the echo.
     * A head that is already within [HOT_OPEN_RATIO] of the peak before
     * the "first" onset is the hit itself, and stays.
     */
    private fun alignToOnset(snip: Snip): Snip {
        val onset = Transients.detect(snip).firstOrNull()?.frame ?: return snip
        if (onset <= 0) return snip
        var headPeak = 0f
        for (i in 0 until onset * 2) {
            val a = if (snip.samples[i] < 0) -snip.samples[i] else snip.samples[i]
            if (a > headPeak) headPeak = a
        }
        if (headPeak >= HOT_OPEN_RATIO * peak(snip.samples)) return snip
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
