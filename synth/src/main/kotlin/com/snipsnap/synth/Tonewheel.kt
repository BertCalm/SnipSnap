package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * TONEWHEEL — additive synthesis made playable.
 *
 * Pure additive (draw 64 partials) is synthesis as data entry; additive with
 * the handle humans have loved for 90 years is drawbars. Eight harmonic bars
 * (BAR1..BAR8), a PERC click register, WARBLE for the wobble, DIRT for the
 * drive — and every voice is a registration, so the panel starts on a sound,
 * not on silence.
 *
 * Renders a gated stab (the organ chord you'd sample), ~0.7 s: long enough
 * to read as tonal, short enough to be a pad hit. TUNE snaps to semitones
 * like PLUCK, so stab kits are in a key.
 */
enum class TonewheelVoice { FULL, SOUL, STAB }

object Tonewheel {

    /** Harmonic ratios of the eight bars — the classic footages, minus one. */
    val BAR_RATIOS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f, 4f, 6f, 8f)

    const val TUNE_SEMITONES = 24
    internal const val GATE_SECONDS = 0.55f
    private const val RELEASE_SECONDS = 0.1f

    // ---- Key click -------------------------------------------------------
    // A real generator's key contacts bounce, and the broadband tick that
    // makes is half of why a Hammond reads as a Hammond on the attack. It is
    // not a macro because it is not a choice: it is what the mechanism does.
    private const val CLICK_T60_SECONDS = 0.004f
    private const val CLICK_LEVEL = 0.30f

    /**
     * How hard the percussion register rings against the drawbars, set by
     * ear at a full PERC: 0.8 was a shade polite there once the register
     * moved onto the third harmonic, where it sings rather than pings.
     * [render] normalizes the whole voice afterwards, so this is a balance
     * against the bars rather than a level — turning it up makes the ping
     * louder relative to them, not the pad louder.
     */
    private const val PERC_GAIN = 0.88f

    // ---- Cabinet ---------------------------------------------------------
    // The drive's intermodulation products otherwise run to Nyquist, which
    // is buzz rather than grit. Overdrive is only ever heard through a
    // speaker, so the speaker is part of the drive, not a separate effect.
    private const val CABINET_HZ = 3_800f

    // ---- Scanner chorus --------------------------------------------------
    // The C-3/B-3 scanner sweeps a delay line and mixes it against the dry
    // signal. Always on: an organ with drawbars out is never perfectly still.
    private const val SCANNER_HZ = 6.8f
    private const val SCANNER_DEPTH_SECONDS = 0.0009f

    private const val SCANNER_MIX = 0.5f

    // ---- Rotary (Leslie) -------------------------------------------------
    // Horn and bass rotor split at the crossover, the horn getting doppler
    // (a modulated delay) plus amplitude modulation in quadrature with it -
    // a source on a circle is loudest pointing at you and fastest toward you
    // a quarter turn later. Tip speed over the speed of sound sets the
    // doppler depth at roughly half a millisecond of path length.
    private const val ROTARY_CROSSOVER_HZ = 800f
    private const val ROTARY_HORN_AM = 0.40f
    private const val ROTARY_BASS_AM = 0.25f
    private const val ROTARY_BASS_RATE_RATIO = 0.82f
    private const val ROTARY_DOPPLER_SECONDS = 0.00052f
    private const val ROTARY_BASE_DELAY_SECONDS = 0.0015f
    private const val ROTARY_SLOW_HZ = 0.8f
    private const val ROTARY_FAST_HZ = 6.9f

    /** Below this WARBLE the rotor is braked rather than merely slow. */
    private const val ROTARY_BRAKE_BELOW = 0.15f

    /**
     * One interpolated tap off a circular delay line.
     *
     * The integer index is wrapped rather than the float read position: a
     * position a hair below zero plus the buffer length rounds to exactly
     * the length in float, which indexes one past the end.
     */
    private fun tap(line: FloatArray, writePos: Int, delay: Float): Float {
        var rp = writePos - delay
        while (rp < 0f) rp += line.size
        val whole = rp.toInt()
        val frac = rp - whole
        val i0 = whole % line.size
        val i1 = (i0 + 1) % line.size
        return line[i0] * (1f - frac) + line[i1] * frac
    }

    /**
     * Per-voice nudge on top of [Dsp.MELODIC_LOUDNESS_TARGET], zeroed out
     * awaiting a listening pass (task-4-report.md) - a table edit here, not
     * a refactor of [render].
     */
    private val LOUDNESS_OFFSET: Map<TonewheelVoice, Float> = TonewheelVoice.entries.associateWith { 0f }

    private fun registration(voice: TonewheelVoice): FloatArray = when (voice) {
        // All bars out: the everything drawbar handful.
        TonewheelVoice.FULL -> floatArrayOf(0.9f, 1f, 0.8f, 0.75f, 0.6f, 0.55f, 0.4f, 0.5f)
        // Sub plus fundamental plus a whisper of quint: the ballad bed. The
        // fundamental leads clearly — three near-equal low bars beat hard
        // enough that the envelope dips read as clap bursts downstream.
        TonewheelVoice.SOUL -> floatArrayOf(0.7f, 1f, 0.4f, 0.3f, 0.15f, 0f, 0f, 0f)
        // Bright top-heavy bars that cut: the one you stab with.
        TonewheelVoice.STAB -> floatArrayOf(0.3f, 0.9f, 0.2f, 0.85f, 0.55f, 0.8f, 0.3f, 0.7f)
    }

    fun macrosFor(voice: TonewheelVoice): List<MacroSpec> {
        val bars = registration(voice)
        return listOf(
            MacroSpec("TUNE", 0.5f),
            MacroSpec("PERC", if (voice == TonewheelVoice.STAB) 0.6f else 0.25f),
            MacroSpec("WARBLE", 0.35f),
            MacroSpec("DIRT", if (voice == TonewheelVoice.FULL) 0.3f else 0.1f),
        ) + bars.mapIndexed { i, level -> MacroSpec("BAR${i + 1}", level) }
    }

    fun defaults(voice: TonewheelVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** SCRAMBLE near a preset; see [Thump.scramble] (docs/SYNTH_UPGRADE.md, U2). */
    fun scramble(voice: TonewheelVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + TonewheelPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    fun frequencyFor(tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return 110f * 2f.pow(semis / 12f)
    }

    /**
     * The raw synth loop, at whatever [rate] the caller wants - split out of
     * [render] so U6's oversampled dispatch (docs/SYNTH_UPGRADE.md) can be
     * tested directly against a native-rate render, rather than trusting
     * that reading [render]'s own source matches what it actually does.
     */
    internal fun synthesize(
        voice: TonewheelVoice,
        macros: Map<String, Float>,
        gateSeconds: Float,
        rate: Int,
        /**
         * Scanner and rotor, the two time-varying stages. Off makes the
         * steady region exactly periodic again, which is what [Keys.organ]
         * needs to cut a seamless sustain loop - see [render].
         */
        motion: Boolean = true,
    ): FloatArray {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val base = frequencyFor(m.getValue("TUNE"))
        val perc = m.getValue("PERC")
        val warble = m.getValue("WARBLE")
        val dirt = m.getValue("DIRT")
        // Organ taper: drawbar throw is roughly logarithmic in level.
        val amps = FloatArray(8) { m.getValue("BAR${it + 1}").pow(1.6f) }

        val total = gateSeconds + RELEASE_SECONDS
        val out = FloatArray((total * rate).toInt())
        // Seeded per voice so the eight wheels no longer start coherent —
        // a real generator has no moment where every partial peaks together.
        val phases = Dsp.phases(8, Dsp.seedFor("TONEWHEEL", voice.name))
        var percPhase = 0.0

        // Seeded so the click is noise but the render stays byte-identical.
        val clickNoise = Random(Dsp.seedFor("TONEWHEEL_CLICK", voice.name))

        val cabinet = Dsp.OnePole(rate)
        val crossover = Dsp.OnePole(rate)

        val scanLine = FloatArray((SCANNER_DEPTH_SECONDS * rate).toInt() + 16)
        var scanPos = 0
        val scanDepth = SCANNER_DEPTH_SECONDS * rate

        val lineLen = ((ROTARY_BASE_DELAY_SECONDS + ROTARY_DOPPLER_SECONDS) * rate).toInt() + 16
        val hornLine = FloatArray(lineLen)
        val bassLine = FloatArray(lineLen)
        var hornPos = 0
        val hornBaseDelay = ROTARY_BASE_DELAY_SECONDS * rate
        val hornDoppler = ROTARY_DOPPLER_SECONDS * rate

        // WARBLE is the rotor: chorale at the bottom of the throw, tremolo at
        // the top, braked below ROTARY_BRAKE_BELOW so there is still a way to
        // ask for a still cabinet.
        val rotorHz = Dsp.lin(warble, ROTARY_SLOW_HZ, ROTARY_FAST_HZ)
        val rotorDepth = (warble / ROTARY_BRAKE_BELOW).coerceIn(0f, 1f)
        val bassHz = rotorHz * ROTARY_BASS_RATE_RATIO

        for (i in out.indices) {
            val t = i.toFloat() / rate
            var s = 0f
            for (k in 0 until 8) {
                phases[k] += base * BAR_RATIOS[k] / rate
                s += amps[k] * sin(2.0 * PI * phases[k]).toFloat()
            }
            s /= 4f

            val gate = when {
                t < 0.004f -> t / 0.004f
                t < gateSeconds -> 1f
                else -> (1f - (t - gateSeconds) / RELEASE_SECONDS).coerceAtLeast(0f)
            }
            var v = s * gate

            // Scanner sits ahead of the amp, the way the preamp's vibrato
            // line does on the real instrument.
            if (motion) {
                scanLine[scanPos] = v
                val d = scanDepth * (0.5f + 0.5f * sin(2.0 * PI * SCANNER_HZ * t).toFloat())
                val wet = tap(scanLine, scanPos, d + 1f)
                scanPos = (scanPos + 1) % scanLine.size
                v = (1f - SCANNER_MIX) * v + SCANNER_MIX * wet
            }

            // The drawbars swim; the percussion register and the key click do
            // not. Both are tapped ahead of the vibrato line on the real
            // instrument, which is why a Hammond's percussion stays dry and
            // present over a chorused registration. Running them through the
            // scanner instead puts a comb null on the percussion and eats it.
            //
            // The percussion is a 2 2/3' ping that decays while the bars
            // sustain: a real Hammond taps it off the second or third
            // harmonic, not the 2' wheel, and the third is the one that sings
            // rather than pings.
            percPhase += base * 3f / rate
            var dry = perc * PERC_GAIN * Dsp.envAt(t, 0.2f) * sin(2.0 * PI * percPhase).toFloat()
            // Key click: the contact bounce, broadband and gone in a breath.
            if (t < CLICK_T60_SECONDS * 4f) {
                dry += CLICK_LEVEL * Dsp.envAt(t, CLICK_T60_SECONDS) *
                    (clickNoise.nextFloat() * 2f - 1f)
            }
            v += dry * gate

            // DIRT pushes *into* the drive, but on a cubic taper: the low end
            // of the throw stays near unity so a full registration no longer
            // arrives at the tanh already flat-topped, while DIRT=1 is left
            // exactly where it was. Then the cabinet, because overdrive with
            // nothing above it is buzz rather than grit.
            v = Dsp.drive(v * Dsp.lin(dirt * dirt * dirt, 1f, 2.4f), dirt)
            v = cabinet.lp(v, CABINET_HZ)

            if (motion) {
                val low = crossover.lp(v, ROTARY_CROSSOVER_HZ)
                val high = v - low
                // Both halves ride the same line so they carry the same base
                // latency: delaying only the horn would leave a fixed offset
                // between the two paths, and a fixed offset between two
                // halves of one signal is a comb filter notching the
                // crossover region. Only the *modulation* should differ.
                hornLine[hornPos] = high
                bassLine[hornPos] = low
                val angle = 2.0 * PI * rotorHz * t
                val delayedHigh = tap(
                    hornLine,
                    hornPos,
                    hornBaseDelay + rotorDepth * hornDoppler * sin(angle).toFloat(),
                )
                val delayedLow = tap(bassLine, hornPos, hornBaseDelay)
                hornPos = (hornPos + 1) % hornLine.size
                val hornAm = 1f + rotorDepth * ROTARY_HORN_AM * sin(angle + PI / 2).toFloat()
                val bassAm = 1f + rotorDepth * ROTARY_BASS_AM * sin(2.0 * PI * bassHz * t).toFloat()
                v = delayedHigh * hornAm + delayedLow * bassAm
            }
            out[i] = v
        }
        return out
    }

    fun render(
        voice: TonewheelVoice,
        macros: Map<String, Float> = emptyMap(),
        /**
         * How long the key is held. The default is the one-shot stab the
         * drum kits use; the key-patch instruments hold it long enough to
         * cut a sustain loop from the steady region.
         */
        gateSeconds: Float = GATE_SECONDS,
        /**
         * Scanner and rotor. On for every pad stab, which is every shipped
         * call site but one; [Keys.organ] passes false because those two
         * stages are the only time-*varying* ones here, and a time-varying
         * stage destroys the exact periodicity its loop cut depends on.
         *
         * Everything else on the path is safe for that loop: the drive is
         * memoryless, the cabinet is LTI, and the key click is a transient
         * long gone by the 1.2s the loop starts at.
         */
        motion: Boolean = true,
    ): Snip {
        require(gateSeconds in 0.1f..8f) { "gateSeconds out of range: $gateSeconds" }
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE so DIRT's Dsp.drive
        // saturation - the one nonlinearity in this otherwise-additive
        // engine - folds its harmonics down above 22.05kHz instead of into
        // the audible band, then Dsp.decimate brings it back to RATE. The
        // additive partials themselves top out at 8x TUNE's max (3520Hz),
        // nowhere near Nyquist, so DIRT is the only reason this engine
        // needs the detour - but it needs it exactly the way THUMP's Punch
        // does. Keys.organ() relies on this render staying exactly periodic
        // by the time its loop starts (1.2s in, long after PERC, the key
        // click and the attack ramp have settled) so it can cut a seamless
        // loop - decimation and the cabinet are linear, time-invariant
        // filters, so a steady sum of sinusoids stays exactly periodic
        // through them, just phase-shifted. That argument is why `motion`
        // exists: scanner and rotor are time-varying and would break it.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, gateSeconds, renderRate, motion)
        val out = Dsp.decimate(raw, RATE)
        // Loudness, not peak: a sine-heavy voice at equal peak reads quieter
        // (Dsp.MELODIC_LOUDNESS_TARGET's doc comment has the measurement).
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET + LOUDNESS_OFFSET.getValue(voice))
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
