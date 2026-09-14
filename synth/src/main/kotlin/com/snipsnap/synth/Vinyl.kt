package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * VINYL — the record, as TAPE is the cassette. A sampling app's source
 * medium is vinyl, and vinyl is never silent: a low rumble off the
 * platter, a hiss in the groove, and the crackle of every play before
 * this one.
 *
 * Sits after DUB and before TAPE, deliberately. TAPE then processes the
 * crackle along with everything else, because the chain being modelled is
 * a record dubbed to tape, not a record with tape painted beside it.
 *
 * The only section here that **adds** signal, so it carries two rules the
 * others get for free. All-zeros returns the input bit for bit. And the
 * beds are scaled against the *input's own peak* before they are summed,
 * so a quiet hit never ends up under a loud record; the sum is then peak
 * matched like everything else. Seeded, so a pad crackles the same way
 * forever. Ceilings follow `TapeWear`'s: earned noise is capped, and the
 * cap is the feature.
 */
object Vinyl {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("CRACKLE", 0.4f),  // how busy the surface is
        MacroSpec("RUMBLE", 0.25f),  // the platter under everything
        MacroSpec("HISS", 0.3f),     // the groove's own floor
    )

    /** One seed for every record: the same hit wears the same way. */
    const val SEED = 19

    /** The ceilings, against the input's peak. Honest maxima, as TapeWear's are. */
    const val CRACKLE_CEILING = 0.35f
    const val RUMBLE_CEILING = 0.05f
    const val HISS_CEILING = 0.004f   // about -48 dBFS against a full-scale hit

    const val RUMBLE_HZ = 60f
    const val CRACKLE_HZ = 2_200f
    const val CRACKLE_Q = 0.8f

    /** Clicks per second at CRACKLE 1. */
    const val MAX_CLICKS_PER_SEC = 90f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val crackle = m.getValue("CRACKLE")
        val rumble = m.getValue("RUMBLE")
        val hiss = m.getValue("HISS")
        if (crackle <= 0f && rumble <= 0f && hiss <= 0f) {
            return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        }

        // Nothing to scale the beds against: silence in, silence out. Not a
        // special case below - every bed is scaled by inPeak, so a zero
        // inPeak already zeroes every draw, and the final peak-match (whose
        // own outPeak then comes out exactly zero too) never touches it.
        val inPeak = snip.peak()

        val out = snip.samples.copyOf()
        val noise = Dsp.Noise(SEED)
        val rumbleFilter = Dsp.OnePole(snip.sampleRate)
        val crackleFilter = Dsp.Biquad().apply { bandpass(CRACKLE_HZ, CRACKLE_Q, snip.sampleRate) }
        val clickChance = crackle * MAX_CLICKS_PER_SEC / snip.sampleRate

        for (f in 0 until snip.frameCount) {
            // Every draw happens every frame, gated only by multiplying with
            // its own macro (zero when off) rather than by skipping the
            // call. RUMBLE and HISS being off must not shift the noise
            // stream CRACKLE reads from - a macro's level is what a knob
            // should move, never the crackle pattern underneath it.
            var bed = 0f
            bed += rumbleFilter.lp(noise.next(), RUMBLE_HZ) * rumble * RUMBLE_CEILING * inPeak
            bed += noise.next() * hiss * HISS_CEILING * inPeak
            // A click is a single impulse through a bandpass: a pop, not a tone.
            val impulse = if (noise.next() * 0.5f + 0.5f < clickChance) noise.next() else 0f
            bed += crackleFilter.process(impulse) * crackle * CRACKLE_CEILING * inPeak
            // One bed across the frame, so a stereo pair shares one record.
            for (ch in 0 until snip.channels) out[f * snip.channels + ch] += bed
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        if (outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
