package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.random.Random

/**
 * SKIN — the acoustic-style drum voice engine.
 *
 * Where THUMP is built from oscillators (swept sines, square-wave clusters)
 * shaped by envelopes, SKIN is modal: KICK/SNARE/TOM/STICK sum decaying
 * sine partials at inharmonic ratios (the textbook "physically-modeled
 * hit" recipe — a struck membrane rings at several modes that decay
 * independently, faster at higher partials; STICK is just one partial,
 * the shortest and highest-pitched of the four). HAT/RIDE run continuous
 * noise through a bank of resonant [Dsp.TptSvf] filters instead (see
 * [hat]'s own doc comment for why continuous, not impulse-excited).
 * SHAKER also runs continuous noise through one [Dsp.TptSvf], but tuned
 * wide and non-resonant (`k=1.2`, nowhere near ringing) rather than as a
 * bank of tuned modes — a broad band, not a tonal partial.
 *
 * An impulse-excited [Dsp.TptSvf] mode (the same shape [Thump.rim] uses)
 * was tried first for STICK and was wrong: even at the filter's own
 * loosest legal damping, its natural ring at STICK's frequency range
 * finishes in a few ms regardless of what DECAY asks for — see [stick]'s
 * own doc comment for the numbers.
 *
 * Same contract as every other engine: macros are 0..1 mapped onto bounded
 * musical ranges so SCRAMBLE can't land on garbage, renders happen at
 * `RATE * Dsp.OVERSAMPLE` and decimate (U6, docs/SYNTH_UPGRADE.md), and
 * PUNCH runs on every voice (U3) exactly as THUMP's does.
 *
 * Voices are split across two PRs: KICK/SNARE/HAT_CLOSED/HAT_OPEN landed
 * first; TOM/RIDE/SHAKER/STICK finish the 8-voice set here.
 */
enum class SkinVoice { KICK, SNARE, HAT_CLOSED, HAT_OPEN, TOM, RIDE, SHAKER, STICK }

object Skin {

    fun macrosFor(voice: SkinVoice): List<MacroSpec> = when (voice) {
        SkinVoice.KICK -> listOf(
            MacroSpec("TUNE", 0.35f), MacroSpec("DECAY", 0.45f), MacroSpec("TONE", 0.5f),
            MacroSpec("PUNCH", 0.5f),
        )
        SkinVoice.SNARE -> listOf(
            MacroSpec("TUNE", 0.4f), MacroSpec("SNAP", 0.55f), MacroSpec("DECAY", 0.4f),
            MacroSpec("PUNCH", 0.5f),
        )
        SkinVoice.HAT_CLOSED -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.3f), MacroSpec("TONE", 0.5f),
            MacroSpec("PUNCH", 0.5f),
        )
        SkinVoice.HAT_OPEN -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.55f), MacroSpec("TONE", 0.5f),
            MacroSpec("PUNCH", 0.5f),
        )
        SkinVoice.TOM -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.5f), MacroSpec("TONE", 0.5f),
            MacroSpec("PUNCH", 0.5f),
        )
        SkinVoice.RIDE -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.5f), MacroSpec("TONE", 0.5f),
            MacroSpec("PUNCH", 0.5f),
        )
        SkinVoice.SHAKER -> listOf(
            MacroSpec("TONE", 0.5f), MacroSpec("DECAY", 0.4f), MacroSpec("PUNCH", 0.5f),
        )
        SkinVoice.STICK -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.4f), MacroSpec("PUNCH", 0.5f),
        )
    }

    /** The factory macro settings for [voice]. */
    fun defaults(voice: SkinVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /**
     * SCRAMBLE near the factory default. SKIN has no preset library yet
     * (a follow-up, same as every other engine's own presets shipped after
     * its engine did) so unlike THUMP/FATHOM's `scramble` there's no preset
     * to seed near — this perturbs the plain default instead, which is
     * still honest: `temperature = 1` still discards the seed entirely and
     * rolls every macro flat-uniform (`Dsp.scrambleNear`'s own contract),
     * so the pre-U2 behaviour stays reachable. Once `SkinPresets` exists,
     * this should pick a random preset the way `Thump.scramble` does.
     */
    fun scramble(voice: SkinVoice, random: Random, temperature: Float = 0.35f): Map<String, Float> =
        Dsp.scrambleNear(defaults(voice), temperature, random)

    /** Render [voice] with [macros]; missing macros fall back to defaults. */
    fun render(voice: SkinVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        // U6 (docs/SYNTH_UPGRADE.md): render oversampled, same contract as
        // every other engine — see Dsp.OVERSAMPLE's own doc comment.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = when (voice) {
            SkinVoice.KICK -> kick(m, renderRate)
            SkinVoice.SNARE -> snare(m, renderRate)
            SkinVoice.HAT_CLOSED -> hat(m, open = false, rate = renderRate)
            SkinVoice.HAT_OPEN -> hat(m, open = true, rate = renderRate)
            SkinVoice.TOM -> tom(m, renderRate)
            SkinVoice.RIDE -> ride(m, renderRate)
            SkinVoice.SHAKER -> shaker(m, renderRate)
            SkinVoice.STICK -> stick(m, renderRate)
        }
        val punch = m.getValue("PUNCH")
        // Punch (U3) needs its loudness reference set before it reshapes
        // the hit — see Thump.render's own comment on this same ordering.
        Dsp.normalize(raw)
        val buf = Punch.applyOversampled(raw, punch, RATE)
        Dsp.limitPeak(buf)
        Dsp.fadeTail(buf)
        return Snip(buf, channels = 1, sampleRate = RATE)
    }

    // ---------- modal resonant body ----------

    private fun frames(seconds: Float, rate: Int) = (seconds * rate).toInt().coerceAtLeast(64)

    /** One decaying sine partial: its own frequency, level, and T60. */
    private class Partial(val freqHz: Float, val level: Float, val t60: Float)

    /**
     * A struck resonant body, modal-synthesis style: [partials] are pure
     * sines at their own frequency, level, and T60, all triggered at t=0
     * and summed — the textbook "sum of decaying sinusoids" recipe for a
     * physically-modeled hit. Higher partials decaying faster than the
     * fundamental is what reads as a struck object rather than a synth pad.
     *
     * Deliberately sine partials rather than a noise-excited resonant
     * filter: [Dsp.TptSvf]'s own damping floor (`k.coerceAtLeast(0.1f)`)
     * caps how long a resonant filter can ring on its own to a couple
     * hundred ms at these frequencies (its natural decay time scales with
     * `1/(k * freqHz)`), well short of DECAY's ~900ms top end — a sine
     * partial's decay is set directly by its own [Dsp.envAt] and isn't
     * bound by that floor. [hat] below still uses [Dsp.TptSvf], but feeds
     * it noise continuously rather than relying on its own impulse decay,
     * sidestepping the same limit.
     */
    private fun modalBody(partials: List<Partial>, seconds: Float, rate: Int): FloatArray {
        val out = FloatArray(frames(seconds, rate))
        val phases = DoubleArray(partials.size)
        // A 1ms attack ramp per partial (U5, docs/SYNTH_UPGRADE.md) — the
        // same convention every other engine's onset follows. Beyond just
        // declicking, an instant t=0 jump to full level is also the exact
        // shape that maximizes PUNCH's boostEnvelope's own crest-factor
        // impact (it multiplies an already-peaked first sample instead of
        // a ramping one), which is what PunchTest-style loudness-match
        // tests are sensitive to.
        val envs = partials.map { Dsp.Env(attackSeconds = 0.001f, decay2T60 = it.t60) }
        for (i in out.indices) {
            val t = i.toFloat() / rate
            var s = 0f
            for (p in partials.indices) {
                val partial = partials[p]
                phases[p] += partial.freqHz / rate
                s += kotlin.math.sin(2.0 * Math.PI * phases[p]).toFloat() * partial.level * envs[p].at(t)
            }
            out[i] = s
        }
        return out
    }

    // ---------- voices ----------

    private fun kick(m: Map<String, Float>, rate: Int): FloatArray {
        val fundamental = Dsp.expMap(m.getValue("TUNE"), 45f, 75f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 0.9f)
        val tone = m.getValue("TONE")
        // TONE balances the shell's fundamental against two inharmonic
        // overtones (1.59x/2.14x, circular-membrane ratios) — up, and the
        // hit reads as a smaller, rattlier shell rather than a bigger one.
        // Loud enough to be audible as a ring, not just measurable as a
        // centroid nudge: at the old 0.1-0.6 range the overtone was there
        // in the numbers but inaudible next to the fundamental, which is
        // most of why a factory KICK read as close kin to THUMP's own.
        val body = modalBody(
            listOf(
                Partial(fundamental, 1f, t60),
                Partial(fundamental * 1.59f, 0.25f + 0.65f * tone, t60 * 0.35f),
                Partial(fundamental * 2.14f, 0.08f + 0.22f * tone, t60 * 0.18f),
            ),
            seconds = t60 * 1.4f,
            rate = rate,
        )
        // The mallet strike itself: a brief low-passed noise click, same
        // role as Thump.kick's own CLICK burst, layered under the body.
        val out = body.copyOf()
        val noise = Dsp.Noise(11)
        val clickLp = Dsp.OnePole(rate)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            if (t < 0.004f) {
                // Its own fade-in as well as its fade-out (Thump.kick's own
                // CLICK burst needed the identical fix): without it, the
                // click still jumps to full level at t=0 even though the
                // modal body above is properly ramped.
                val clickAttack = (t / 0.001f).coerceAtMost(1f)
                out[i] += 0.5f * clickLp.lp(noise.next(), 1400f) * (1f - t / 0.004f) * clickAttack
            }
        }
        return out
    }

    private fun snare(m: Map<String, Float>, rate: Int): FloatArray {
        val fundamental = Dsp.expMap(m.getValue("TUNE"), 170f, 260f)
        val bodyT60 = Dsp.expMap(m.getValue("DECAY"), 0.09f, 0.3f)
        val snap = m.getValue("SNAP")
        val body = modalBody(
            listOf(
                Partial(fundamental, 1f, bodyT60),
                Partial(fundamental * 1.8f, 0.35f, bodyT60 * 0.5f),
            ),
            seconds = bodyT60 * 1.4f,
            rate = rate,
        )
        // The wires: a separately-enveloped high-passed noise layer, mixed
        // against the body by SNAP — same (1-snap)*body + snap*rattle shape
        // Thump.snare() uses.
        val wireT60 = Dsp.expMap(m.getValue("DECAY"), 0.07f, 0.28f)
        val wireEnv = Dsp.Env(attackSeconds = 0.001f, decay2T60 = wireT60)
        val out = FloatArray(maxOf(body.size, frames(wireT60 * 1.4f, rate)))
        val wireNoise = Dsp.Noise(17)
        val hp = Dsp.OnePole(rate)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val bodyS = if (i < body.size) body[i] else 0f
            val n = wireNoise.next()
            val wire = (n - hp.lp(n, 3200f)) * wireEnv.at(t)
            out[i] = (1f - snap) * bodyS + snap * wire * 1.4f
        }
        return out
    }

    private val HAT_RATIOS = floatArrayOf(1f, 1.342f, 1.681f, 1.940f, 2.318f, 2.703f)

    private fun hat(m: Map<String, Float>, open: Boolean, rate: Int): FloatArray {
        val base = Dsp.expMap(m.getValue("TUNE"), 5200f, 8200f)
        val t60 = if (open) Dsp.expMap(m.getValue("DECAY"), 0.25f, 1.0f)
        else Dsp.expMap(m.getValue("DECAY"), 0.04f, 0.16f)
        val tone = m.getValue("TONE")
        val spread = Dsp.lin(tone, 0.9f, 1.25f)
        // Continuous noise through a dense inharmonic bandpass bank, not a
        // single noise burst: a cymbal is driven the whole time it's
        // ringing, and feeding the filters continuously means the audible
        // decay is set entirely by the explicit envelope below, not bound
        // by Dsp.TptSvf's own damping floor the way a single-impulse
        // excitation would be (see modalBody's doc comment).
        val noise = Dsp.Noise(if (open) 19 else 23)
        val filters = HAT_RATIOS.map { Dsp.TptSvf(rate) }
        val freqs = HAT_RATIOS.map { ratio -> (base * Math.pow(ratio.toDouble(), spread.toDouble())).toFloat() }
        val env = Dsp.Env(attackSeconds = 0.001f, decay2T60 = t60)
        val out = FloatArray(frames(t60 * 1.4f, rate))
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val n = noise.next()
            var s = 0f
            for (idx in filters.indices) {
                filters[idx].process(n, freqs[idx], 0.15f)
                s += filters[idx].band
            }
            out[i] = (s / filters.size) * env.at(t)
        }
        return out
    }

    private fun tom(m: Map<String, Float>, rate: Int): FloatArray {
        val fundamental = Dsp.expMap(m.getValue("TUNE"), 90f, 220f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.15f, 0.7f)
        val tone = m.getValue("TONE")
        // Same shell-plus-overtone shape as KICK, just pitched into tom
        // territory and with no mallet click — a tom reads as the shell
        // resonance alone. Same louder-overtone rationale as KICK's own
        // comment: the ring needs to be audible, not just measurable.
        return modalBody(
            listOf(
                Partial(fundamental, 1f, t60),
                Partial(fundamental * 1.63f, 0.25f + 0.55f * tone, t60 * 0.4f),
            ),
            seconds = t60 * 1.4f,
            rate = rate,
        )
    }

    private val RIDE_RATIOS = floatArrayOf(1f, 1.19f, 1.57f, 2.14f, 2.63f, 3.08f, 3.55f, 4.11f)

    private fun ride(m: Map<String, Float>, rate: Int): FloatArray {
        val base = Dsp.expMap(m.getValue("TUNE"), 3200f, 5200f)
        // Capped well under Classifier.LOOP_MIN_SECONDS (1.5s): at t60=1.0
        // this renders 1.4s (`seconds = t60 * 1.4f` below), same margin
        // Thump.hat's own HAT_OPEN keeps (t60 tops out at 1.0 there too) -
        // a longer ceiling here classified RIDE as a LOOP, not a drum hit.
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.35f, 1.0f)
        val tone = m.getValue("TONE")
        val spread = Dsp.lin(tone, 0.9f, 1.3f)
        // Same continuous-noise-through-a-bank recipe as hat(), but denser
        // (8 modes, not 6) and tuned for a long bell-like sustain rather
        // than a short shimmer — the wash a ride is defined by.
        val noise = Dsp.Noise(29)
        val filters = RIDE_RATIOS.map { Dsp.TptSvf(rate) }
        val freqs = RIDE_RATIOS.map { ratio -> (base * Math.pow(ratio.toDouble(), spread.toDouble())).toFloat() }
        val env = Dsp.Env(attackSeconds = 0.001f, decay2T60 = t60)
        val out = FloatArray(frames(t60 * 1.4f, rate))
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val n = noise.next()
            var s = 0f
            for (idx in filters.indices) {
                filters[idx].process(n, freqs[idx], 0.1f)
                s += filters[idx].band
            }
            out[i] = (s / filters.size) * env.at(t)
        }
        return out
    }

    private fun shaker(m: Map<String, Float>, rate: Int): FloatArray {
        val bandHz = Dsp.expMap(m.getValue("TONE"), 3500f, 9000f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.08f, 0.5f)
        // Broadband noise, not a resonant bank: a shaker has no tonal
        // partials to speak of, just beads rattling in a broad high band.
        // k=1.2 is well clear of resonance (2 = none), wide enough to
        // pass a band rather than ring a peak.
        val noise = Dsp.Noise(31)
        val svf = Dsp.TptSvf(rate)
        val env = Dsp.Env(attackSeconds = 0.001f, decay2T60 = t60)
        val out = FloatArray(frames(t60 * 1.4f, rate))
        for (i in out.indices) {
            val t = i.toFloat() / rate
            svf.process(noise.next(), bandHz, 1.2f)
            out[i] = svf.band * env.at(t) * 2f
        }
        return out
    }

    private fun stick(m: Map<String, Float>, rate: Int): FloatArray {
        // The rimshot/stick click: one tuned sine partial, decay set
        // directly by Dsp.Env. An impulse-excited Dsp.TptSvf mode (Thump.
        // rim()'s own shape, but with the newer filter) was tried first
        // and was wrong: even at TptSvf's own loosest legal damping
        // (k=0.1, the floor modalBody's doc comment already describes),
        // the filter's OWN natural ring at STICK's 1.8-3.4kHz range caps
        // out around 3-6ms - nowhere near the 20-90ms DECAY asks for, so
        // the macro had no real effect once the filter's own decay had
        // already finished. modalBody's explicit envelope isn't bound by
        // that ceiling at all.
        val freq = Dsp.expMap(m.getValue("TUNE"), 1800f, 3400f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.02f, 0.09f)
        return modalBody(listOf(Partial(freq, 1f, t60)), seconds = maxOf(t60 * 1.6f, 0.05f), rate = rate)
    }
}
