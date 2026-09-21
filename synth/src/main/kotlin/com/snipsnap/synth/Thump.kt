package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * THUMP — the analog-style drum voice engine.
 *
 * Everything renders offline: a voice type plus a macro map (every macro
 * normalized 0..1) becomes a mono [Snip], ready for the same cleanup/kit/
 * export pipeline as captured audio.
 *
 * The playability rules from docs/SYNTH_ROADMAP.md are enforced by
 * construction here: macros are 0..1 and mapped internally onto bounded
 * musical ranges, so the worst any knob position can sound is "not for me" —
 * never broken. SCRAMBLE rolls near a factory preset (docs/SYNTH_UPGRADE.md,
 * U2); a flat uniform roll of the whole space is still reachable at
 * `temperature = 1` (see [scramble]).
 */
enum class ThumpVoice { KICK, SNARE, HAT_CLOSED, HAT_OPEN, CLAP, TOM, COWBELL, RIM }

/**
 * One macro: its name, where it starts, and where it *does nothing*.
 * [neutral] is 0 for a macro that fades to silence and 0.5 for a centered
 * one (EQ's flat, PITCH's native) — `Treatments.chain` fades toward it, so
 * a centered macro is not dragged off centre as AMT falls.
 */
data class MacroSpec(val name: String, val default: Float, val neutral: Float = 0f)

object Thump {

    fun macrosFor(voice: ThumpVoice): List<MacroSpec> = when (voice) {
        ThumpVoice.KICK -> listOf(
            MacroSpec("TUNE", 0.35f), MacroSpec("SWEEP", 0.5f), MacroSpec("DECAY", 0.45f),
            MacroSpec("CLICK", 0.35f), MacroSpec("DRIVE", 0.25f), MacroSpec("PUNCH", 0.5f),
        )
        ThumpVoice.SNARE -> listOf(
            MacroSpec("TUNE", 0.4f), MacroSpec("SNAP", 0.55f), MacroSpec("DECAY", 0.4f),
            MacroSpec("TONE", 0.55f), MacroSpec("PUNCH", 0.5f),
            // 0.28 is a placeholder - off-centre, where a snare is actually
            // played - and belongs at the next audition (Task 3), not here.
            MacroSpec("STRIKE", 0.28f),
        )
        ThumpVoice.HAT_CLOSED -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.3f), MacroSpec("METAL", 0.5f),
            MacroSpec("PUNCH", 0.5f),
        )
        ThumpVoice.HAT_OPEN -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.55f), MacroSpec("METAL", 0.5f),
            MacroSpec("PUNCH", 0.5f),
        )
        ThumpVoice.CLAP -> listOf(
            MacroSpec("SPREAD", 0.5f), MacroSpec("DECAY", 0.45f), MacroSpec("TONE", 0.5f),
            MacroSpec("PUNCH", 0.5f),
        )
        ThumpVoice.TOM -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("SWEEP", 0.4f), MacroSpec("DECAY", 0.5f),
            MacroSpec("PUNCH", 0.5f),
        )
        ThumpVoice.COWBELL -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.4f), MacroSpec("PUNCH", 0.5f),
        )
        ThumpVoice.RIM -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.3f), MacroSpec("PUNCH", 0.5f),
        )
    }

    /** The factory macro settings for [voice]. */
    fun defaults(voice: ThumpVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /**
     * SCRAMBLE: rolls near a seed instead of flat across the macro box
     * (docs/SYNTH_UPGRADE.md, U2) — [near] picks the seed, defaulting to a
     * random factory preset for [voice]; [temperature] scales the gaussian
     * perturbation, clamped to 0..1. `temperature = 0` returns the seed
     * untouched; `temperature = 1` discards it and rolls every macro
     * flat-uniform, the pre-U2 behaviour, still reachable. Bounded ranges
     * mean any roll is playable; a seeded [random] makes rolls reproducible.
     */
    fun scramble(voice: ThumpVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        // At temperature >= 1, Dsp.scrambleNear ignores the seed's values
        // (only its keys matter) - so skip picking a preset there. Copilot
        // caught this: picking one anyway spent a random draw before the
        // per-macro rolls, shifting a seeded/shared Random's downstream
        // sequence away from the pre-U2 behaviour temperature = 1 promises.
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + ThumpPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** Render [voice] with [macros]; missing macros fall back to defaults. */
    fun render(voice: ThumpVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        // U6 (docs/SYNTH_UPGRADE.md): every voice synthesizes at 4x RATE, so
        // its own oscillators' aliasing folds down above 22.05kHz instead of
        // into the audible band, then Dsp.decimate brings it back to RATE.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = when (voice) {
            ThumpVoice.KICK -> kick(m, renderRate)
            ThumpVoice.SNARE -> snare(m, renderRate)
            ThumpVoice.HAT_CLOSED -> hat(m, open = false, rate = renderRate)
            ThumpVoice.HAT_OPEN -> hat(m, open = true, rate = renderRate)
            ThumpVoice.CLAP -> clap(m, renderRate)
            ThumpVoice.TOM -> tom(m, renderRate)
            ThumpVoice.COWBELL -> cowbell(m, renderRate)
            ThumpVoice.RIM -> rim(m, renderRate)
        }
        val punch = m.getValue("PUNCH")
        // Punch (U3, docs/SYNTH_UPGRADE.md) swaps the peak target below for a
        // perceived-level one, so normalize has to set the reference loudness
        // BEFORE Punch reshapes the hit, not after: normalizing again
        // afterward would silently rescale Punch's own loudness-matched
        // result right back to a fixed peak, undoing the "more PUNCH reshapes
        // the hit, it doesn't just make it louder" guarantee PunchTest
        // proves for Punch.apply in isolation.
        Dsp.normalize(raw)
        // Punch.applyOversampled runs saturate and the boost envelope on
        // `raw` here, still at renderRate, before its own internal
        // Dsp.decimate call - both are nonlinear or fast-changing enough to
        // mint content outside the buffer's own band wherever they run, so
        // applying either after decimation would hand U6's whole
        // anti-aliasing story right back to a raw oscillator's problem.
        // PunchTest exercises this exact function, so that ordering is
        // proven there, not just trusted here.
        val buf = Punch.applyOversampled(raw, punch, RATE)
        // The actual clipping safety net, run last: only steps in if the
        // transient boost pushed a sample past what's safe, same as
        // normalize always did for PUNCH amount 0.
        Dsp.limitPeak(buf)
        Dsp.fadeTail(buf)
        return Snip(buf, channels = 1, sampleRate = RATE)
    }

    // ---------- voices ----------

    private fun frames(seconds: Float, rate: Int) = (seconds * rate).toInt().coerceAtLeast(64)

    private fun kick(m: Map<String, Float>, rate: Int): FloatArray {
        // Ranges stay in kick territory on purpose: the playability contract
        // says a scrambled kick still reads as a kick, and spectral centroid
        // is merciless - a whisper of broadband click outweighs a wall of
        // 46 Hz fundamental. Hence the low-passed thump-click and the fast
        // sweep, both tuned against the classifier.
        val base = Dsp.expMap(m.getValue("TUNE"), 34f, 62f)
        val sweepMult = Dsp.lin(m.getValue("SWEEP"), 1.2f, 4f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.09f, 0.85f)
        val click = m.getValue("CLICK")
        val driveAmt = m.getValue("DRIVE")

        val out = FloatArray(frames(t60 * 1.4f, rate))
        var phase = 0.0
        val noise = Dsp.Noise(7)
        val clickLp = Dsp.OnePole(rate)
        // A 1ms attack ramp (U5, docs/SYNTH_UPGRADE.md) - short enough to
        // leave the punch alone, long enough to declick the instant onset
        // every THUMP voice used to jump straight into.
        val env = Dsp.Env(attackSeconds = 0.001f, decay2T60 = t60)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            // The defining kick shape: frequency falls fast onto the base.
            val f = base * (1f + (sweepMult - 1f) * exp((-90.0 * t)).toFloat())
            phase += f / rate
            var s = env.at(t) * sin(2.0 * PI * phase).toFloat()
            if (t < 0.005f) {
                // Copilot's review of this PR: the click burst had its own
                // 5ms linear fade-out but no fade-in, so it still jumped to
                // full level at t=0 even after the sine body was declicked.
                // Its own attack, independent of the body's decay-bearing
                // env above - stacking t60's decay onto a 5ms burst would
                // also quietly change the click's shape, not just declick it.
                val clickAttack = (t / 0.001f).coerceAtMost(1f)
                s += click * 0.6f * clickLp.lp(noise.next(), 1000f) * (1f - t / 0.005f) * clickAttack * 2f
            }
            out[i] = Dsp.drive(s, driveAmt)
        }
        return out
    }

    /**
     * SNARE — a membrane with wires resting on it.
     *
     * It used to be two detuned sines plus lowpassed noise, crossfaded, and
     * the audition gate's verdict on that was "a very burst-of-static feel."
     * Fair: at the shipped presets' SNAP values the noise ran 80% of the mix,
     * over a body that two sines at ratio 1.83 could never make read as a
     * drum. A real head is a circular membrane — the Bessel ratios in
     * [Modes.Material.MEMBRANE].
     *
     * The wires stay their own layer rather than joining the modal
     * excitation. A prototype that fed them through the head lost exactly
     * what makes a snare a snare: the wires rattle AGAINST the drum, they are
     * not filtered BY it.
     */
    private fun snare(m: Map<String, Float>, rate: Int): FloatArray {
        val tune = snareFundamental(m.getValue("TUNE"))
        val snap = m.getValue("SNAP")
        val damp = Dsp.expMap(m.getValue("DECAY"), 0.05f, 0.34f)
        val air = m.getValue("TONE")
        // Modes.atPosition's mode shape is |sin(n*pi*position)|, which is
        // exactly zero at BOTH literal edges for every mode at once - the
        // real physics of hitting precisely on a fixed boundary, but not a
        // strike position any real hand plays, and not a place this macro's
        // corners should go fully silent. Kept off the exact edges so
        // STRIKE 0..1 stays reachable and audible end to end.
        val strike = m.getValue("STRIKE").coerceIn(0.02f, 0.98f)

        val frames = frames(0.6f, rate)

        // The stick, plus a breath of noise so the head is struck rather than
        // plucked. Short: this excites the membrane, it is not the wires.
        val exc = FloatArray(frames)
        exc[0] = 1f
        val stickNoise = Dsp.Noise(3)
        val stickEnv = Dsp.Env(attackSeconds = 0.0005f, decay2T60 = 0.02f)
        for (i in exc.indices) exc[i] += 0.35f * stickNoise.next() * stickEnv.at(i.toFloat() / rate)

        val head = Modes.atPosition(Modes.tableFor(Modes.Material.MEMBRANE), strike)
            .map { it.copy(t60 = it.t60 * damp) }
        val body = Modes.ring(exc, tune, head, rate)
        // A resonator's impulse-response peak (Modes.ring's own KDoc gives the
        // closed form: g * r^n * sin((n+1)*theta)/sin(theta)) grows as
        // 1/sin(theta) for a low fundamental against a high sample rate -
        // measured at THUMP's oversampled renderRate and SNARE's tuning
        // range, a bare Modes.ring(...) here peaks around 180-490 while the
        // wire layer below peaks around 2. That is theta shrinking, not a
        // louder strike, and it would leave SNAP's crossfade fighting a
        // 250x head start before it ever gets to choose a balance. Peak-
        // normalizing the head here makes bodyGain/wireGain in
        // [snareBodyGain]/[snareWireGain] mean what their numbers say.
        val bodyPeak = body.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9f)
        for (i in body.indices) body[i] /= bodyPeak

        // The wires: broadband, highpassed into sizzle, decaying on their own
        // clock. Not routed through the head - see the KDoc above.
        val out = FloatArray(frames)
        val wireNoise = Dsp.Noise(11)
        val dull = Dsp.OnePole(rate)
        val wireEnv = Dsp.Env(attackSeconds = 0.0008f, decay2T60 = damp * 3f)
        val bodyGain = snareBodyGain(snap)
        val wireGain = snareWireGain(snap)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val raw = wireNoise.next()
            val sizzle = raw - dull.lp(raw, Dsp.expMap(air, 900f, 5000f))
            out[i] = bodyGain * body[i] + wireGain * sizzle * wireEnv.at(t) * 1.8f
        }
        return trimSnareTail(out, rate)
    }

    /**
     * Trims [out]'s trailing near-silence (-60 dB of its own peak, plus a
     * short release margin) rather than returning the full fixed 0.6 s
     * buffer every time.
     *
     * The buffer is allocated at a constant 0.6 s so the slowest DECAY
     * setting has room to ring out - but unlike every other THUMP voice
     * (`frames(t60 * 1.4f, rate)` for kick/hat/tom/etc.), SNARE's own
     * allocation doesn't scale with DECAY at all, so a fast, short-decay
     * snare used to come back exactly as long as a slow one. The
     * pre-rebuild snare didn't have this problem: its buffer WAS
     * `frames(t60 * 1.4f, rate)`, so it was already whatever length its own
     * DECAY implied (0.168-0.7s at that engine's range). Fixed at 0.6s
     * regardless, this snare's [Snip.durationSeconds] no longer tracks how
     * long the hit actually is - measured: `Thump.render(SNARE)` at
     * default DECAY (0.4) is 0.6s here versus 0.297s on the pre-rebuild
     * engine at the same DECAY. Stacked with one FX section's own tail
     * (ECHO's defaults add ~0.97s), that fixed 0.6s pushed the combined
     * length to 1.57s - over Classifier's 1.5s LOOP_MIN_SECONDS - so
     * `FxTest`'s "an echoed kick is still a kick" and "the full default
     * rack..." both misclassified a snare through ECHO as [DrumClass.LOOP]
     * (the old engine's 0.297s + the same tail landed at 1.27s, well
     * under). Trimming the true tail restores that duration-tracks-decay
     * property without touching [Modes.ring]'s own fixed-size math.
     */
    private fun trimSnareTail(
        out: FloatArray,
        rate: Int,
        channels: Int = 1,
        marginSeconds: Float = 0.03f,
    ): FloatArray {
        if (out.isEmpty() || channels < 1) return out
        var peak = 0f
        for (v in out) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
        if (peak <= 1e-9f) return out
        val threshold = peak * 0.001f // -60 dB
        val frames = out.size / channels
        var lastFrame = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                if (kotlin.math.abs(out[f * channels + c]) > threshold) { lastFrame = f; break }
            }
        }
        // Frames throughout: mixing a sample index with a frame count gave a
        // stereo buffer half its margin, and an odd cut transposed L and R.
        val endFrame = (lastFrame + (marginSeconds * rate).toInt() + 1).coerceAtMost(frames)
        val end = endFrame * channels
        return if (end >= out.size) out else out.copyOf(end)
    }

    internal fun trimSnareTailForTest(out: FloatArray, rate: Int, channels: Int = 1): FloatArray =
        trimSnareTail(out, rate, channels)

    /** The head's fundamental: drum size, from piccolo to a deep 14-inch. */
    internal fun snareFundamental(tune: Float): Float = Dsp.expMap(tune, 120f, 330f)

    /**
     * SNAP's two halves. The wires climb past unity (1.5 at SNAP=1) so
     * SNAP=1 is a genuine static burst rather than the body just turned
     * down, and [snare]'s own body-peak normalization is what makes that
     * multiplier meaningful instead of fighting a 250x head start.
     *
     * MEASURED, not the brief's starting quadratic/cubic pair — see
     * `.superpowers/sdd/2026-09-20-synth-depth-phase-1b/task-1-2-report.md`
     * for the swept table. A quadratic body ([1-s]^2) paired with a
     * faster-than-linear wire rise sounds right on paper, but spectral
     * centroid and flatness are both scale-invariant once one layer has
     * swamped the other, so that pairing collapsed almost all of the knob's
     * audible movement into SNAP 0.1-0.5 and left 0.75-1.0 reading as the
     * same static burst repeated four times (measured last-step centroid
     * delta 1.45 Hz, against `SNAP moves at every step of its travel`'s own
     * 1 Hz floor - a live instance of the "narrowed past where the wanted
     * territory started" failure this project has hit before). A LINEAR
     * body fall paired with a QUADRATIC wire rise crosses over near SNAP
     * 0.55 - body still audibly present past the middle of the knob, wire
     * still visibly climbing after it - and keeps every consecutive step's
     * centroid delta at least 13x that 1 Hz floor, all the way to both ends.
     */
    internal fun snareBodyGain(snap: Float): Float {
        val s = snap.coerceIn(0f, 1f)
        return 1f - s
    }

    internal fun snareWireGain(snap: Float): Float {
        val s = snap.coerceIn(0f, 1f)
        return 1.5f * s * s
    }

    /**
     * The classic metallic recipe: a cluster of six inharmonic squares,
     * high-passed hard so only the shimmer survives.
     */
    private fun hat(m: Map<String, Float>, open: Boolean, rate: Int): FloatArray {
        val base = Dsp.expMap(m.getValue("TUNE"), 320f, 620f)
        val t60 = if (open) Dsp.expMap(m.getValue("DECAY"), 0.25f, 1.0f)
        else Dsp.expMap(m.getValue("DECAY"), 0.04f, 0.16f)
        val metal = m.getValue("METAL")
        val ratios = floatArrayOf(1f, 1.342f, 1.681f, 1.940f, 2.318f, 2.703f)
        val spread = Dsp.lin(metal, 0.9f, 1.25f)

        val out = FloatArray(frames(t60 * 1.4f, rate))
        val phases = DoubleArray(6)
        val lp1 = Dsp.OnePole(rate); val lp2 = Dsp.OnePole(rate)
        // Raised from 6800-9200Hz: U6's oversampling (docs/SYNTH_UPGRADE.md)
        // properly band-limits these square waves' harmonics instead of
        // letting them alias, and the aliased version happened to be
        // brighter by the classifier's own measure - its spectral centroid
        // no longer cleared HAT_MIN_CENTROID_HZ once the alias content was
        // gone. Retuned against the classifier the same way the rest of
        // THUMP's ranges already are, not chosen for any other reason.
        val hpHz = Dsp.lin(metal, 11000f, 14000f)
        val env = Dsp.Env(attackSeconds = 0.001f, decay2T60 = t60)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            var s = 0f
            for (k in 0 until 6) {
                phases[k] += base * Math.pow(ratios[k].toDouble(), spread.toDouble()) / rate
                s += Dsp.square(phases[k])
            }
            s /= 6f
            // Two cascaded one-pole high-passes: keep the sizzle, dump the body.
            val hp = s - lp1.lp(s, hpHz)
            val hp2 = hp - lp2.lp(hp, hpHz)
            out[i] = hp2 * 2.2f * env.at(t)
        }
        return out
    }

    private fun clap(m: Map<String, Float>, rate: Int): FloatArray {
        val spreadS = Dsp.lin(m.getValue("SPREAD"), 0.007f, 0.016f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.15f, 0.5f)
        val toneHz = Dsp.expMap(m.getValue("TONE"), 1600f, 5200f)

        val out = FloatArray(frames(t60 * 1.4f + 3 * spreadS, rate))
        val noise = Dsp.Noise(4)
        val lp = Dsp.OnePole(rate)
        val bursts = floatArrayOf(0f, spreadS, 2 * spreadS, 3 * spreadS)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val n = lp.lp(noise.next(), toneHz) * 2.4f
            var env = 0f
            // A hand clap is several impacts a few ms apart, then a tail.
            for (b in bursts) if (t >= b) env = maxOf(env, exp((-320.0 * (t - b))).toFloat())
            val tail = if (t >= bursts.last()) 0.6f * Dsp.envAt(t - bursts.last(), t60) else 0f
            out[i] = n * maxOf(env, tail)
        }
        return out
    }

    private fun tom(m: Map<String, Float>, rate: Int): FloatArray {
        val base = Dsp.expMap(m.getValue("TUNE"), 82f, 240f)
        val sweep = Dsp.lin(m.getValue("SWEEP"), 1.05f, 1.6f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.18f, 0.7f)

        val out = FloatArray(frames(t60 * 1.4f, rate))
        var phase = 0.0
        val env = Dsp.Env(attackSeconds = 0.001f, decay2T60 = t60)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val f = base * (1f + (sweep - 1f) * exp((-30.0 * t)).toFloat())
            phase += f / rate
            out[i] = env.at(t) * sin(2.0 * PI * phase).toFloat()
        }
        return out
    }

    private fun cowbell(m: Map<String, Float>, rate: Int): FloatArray {
        val base = Dsp.expMap(m.getValue("TUNE"), 420f, 700f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.09f, 0.45f)

        val out = FloatArray(frames(t60 * 1.4f, rate))
        var p1 = 0.0; var p2 = 0.0
        val svf = Dsp.Svf(rate)
        val env = Dsp.Env(attackSeconds = 0.001f, decay2T60 = t60)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            p1 += base / rate
            p2 += base * 1.48 / rate
            val s = (Dsp.square(p1) + Dsp.square(p2)) * 0.5f
            svf.process(s, base * 1.2f, 0.6f)
            out[i] = svf.band * 1.6f * env.at(t)
        }
        return out
    }

    private fun rim(m: Map<String, Float>, rate: Int): FloatArray {
        val freq = Dsp.expMap(m.getValue("TUNE"), 1200f, 2400f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.03f, 0.12f)

        val out = FloatArray(frames(maxOf(t60 * 1.6f, 0.05f), rate))
        val svf = Dsp.Svf(rate)
        val noise = Dsp.Noise(9)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            // A damped resonator struck by a 1 ms excitation: the tick.
            val excite = if (t < 0.001f) noise.next() + 1.5f else 0f
            svf.process(excite, freq, 0.12f)
            out[i] = svf.band * Dsp.envAt(t, t60)
        }
        return out
    }
}
