package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.TempoFit
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.KitPad
import java.util.Random

/**
 * Round robin for our own kits (HH1.2) — the PSK corpus trick, aimed the
 * other way. Commercial kits chain N *recorded* takes and let Slice
 * Motion step through them; our pads usually have one take, so the robin
 * renders the missing ones: N seeded, subtly different variants of the
 * pad's sample — micro level, micro pitch, a hair of start jitter, the
 * differences real drummers can't help making — concatenated into one
 * chain WAV. The pad becomes a chain ([ChainInfo]), the MPC 3 cycles a
 * take per hit, the preview does the same, and the MPC 2 plays take one
 * (which is the untouched original, deliberately first).
 *
 * Bin-backed like every treatment: undo pulls the single take back out
 * byte-identical and clears the chain.
 */
object Robin {

    const val DEFAULT_TAKES = 3

    /** The corpus's chains cycle 2..4; 8 matches the layer count ceiling. */
    const val MAX_TAKES = 8

    /**
     * Variation caps — the humanize philosophy at render time: audible
     * side by side, invisible in a groove. Level ±8% (~0.7 dB), pitch
     * ±10 cents, up to 2ms shaved off the front.
     */
    const val LEVEL_SPREAD = 0.08f
    const val PITCH_SPREAD_CENTS = 10f
    const val START_JITTER_SEC = 0.002f

    /**
     * Turn pad [slot]'s single take into a chain of [takes]. Take one is
     * the original verbatim; the rest are seeded variants, so the same
     * (seed, takes) always renders the same chain. Saves are the
     * caller's job, as everywhere in the model.
     */
    fun apply(model: KitBuilderModel, slot: Int, takes: Int = DEFAULT_TAKES, seed: Int = 0): KitPad {
        require(takes in 2..MAX_TAKES) { "takes is 2..$MAX_TAKES, got $takes" }
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.chain == null) { "pad $slot is already a round-robin chain - `robin --undo` first" }

        var boundaries: List<Long> = emptyList()
        val recipe = JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "robin" to JsonValue.Obj(
                    linkedMapOf(
                        "takes" to JsonValue.Num(takes.toDouble()),
                        "seed" to JsonValue.Num(seed.toDouble()),
                    ),
                ),
            ),
        )
        model.replaceAudio(slot, recipe) { original ->
            val rng = Random(seed.toLong() * 31 + slot)
            val rendered = buildList {
                add(original)
                repeat(takes - 1) { add(variant(original, rng)) }
            }
            var at = 0L
            boundaries = rendered.map { t -> at.also { _ -> at += t.frameCount } }
            concat(rendered)
        }
        return model.update(slot) { it.copy(chain = ChainInfo(boundaries, cycle = takes)) }
    }

    /** The single take back out of the bin, byte-identical; the chain cleared. */
    fun undo(model: KitBuilderModel, slot: Int): KitPad {
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.chain != null) { "pad $slot isn't a round-robin chain - nothing to undo" }
        model.restoreFromBin(pad.sampleFile)
            ?: throw IllegalArgumentException(
                "nothing to restore for pad $slot - the bin holds no earlier take of it",
            )
        return model.update(slot) { it.copy(chain = null, recipe = null) }
    }

    private fun variant(original: Snip, rng: Random): Snip {
        // Draw all three before any early return, so the rng stream (and
        // with it every later take) never depends on this take's values.
        val trimFrames = (rng.nextFloat() * START_JITTER_SEC * original.sampleRate).toInt()
            .coerceAtMost(original.frameCount / 4)
        val cents = (rng.nextFloat() * 2f - 1f) * PITCH_SPREAD_CENTS
        val gain = 1f + (rng.nextFloat() * 2f - 1f) * LEVEL_SPREAD

        var s = if (trimFrames > 0) {
            Snip(
                original.samples.copyOfRange(trimFrames * original.channels, original.samples.size),
                original.channels,
                original.sampleRate,
            )
        } else {
            original
        }
        // Micro repitch by resample - the sampler way, pitch and length
        // riding together like a slightly different hit would.
        val speed = Math.pow(2.0, cents / 1200.0).toFloat()
        s = TempoFit.repitch(s, 1000f, 1000f * speed)
        val out = FloatArray(s.samples.size)
        for (i in s.samples.indices) out[i] = (s.samples[i] * gain).coerceIn(-1f, 1f)
        return Snip(out, s.channels, s.sampleRate)
    }

    private fun concat(takes: List<Snip>): Snip {
        val channels = takes.first().channels
        val rate = takes.first().sampleRate
        val total = takes.sumOf { it.samples.size }
        val out = FloatArray(total)
        var at = 0
        for (t in takes) {
            t.samples.copyInto(out, at)
            at += t.samples.size
        }
        return Snip(out, channels, rate)
    }
}
