package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tanh
import kotlin.random.Random

enum class RivetVoice { KICK, SNARE }

/**
 * Industrial drums: contact -> membrane and metal -> drive -> the building answers.
 * RIVET owns the macro mapping and reuses Modes and ROOM without changing either.
 * Modal ratios, mix weights and contact times are designed shapes, not measured materials.
 * The room is a one-way branch. A fixed close path keeps its mic move off the attack.
 * Model 1 is finite and explicitly refuses HOLD; it is a listening prototype.
 */
object Rivet {
    const val MODEL_VERSION = 1
    const val RATE = Dsp.RATE
    private const val IR = RATE * Dsp.OVERSAMPLE
    internal const val CLOSE_WEIGHT = .60f
    internal const val ROOM_WEIGHT = 1f - CLOSE_WEIGHT
    private const val KICK_ROOM_CUTOFF_HZ = 150f

    fun macrosFor(voice: RivetVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", if (voice == RivetVoice.KICK) .35f else .45f),
        MacroSpec("STRIKE", .60f), MacroSpec("METAL", .40f), MacroSpec("DRIVE", .40f),
        MacroSpec("SIZE", .30f), MacroSpec("WALLS", .70f), MacroSpec("HOLD", 0f),
    )

    fun defaults(voice: RivetVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun settled(voice: RivetVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (key, default) -> macros[key]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: default }

    internal fun requireModelVersion(@Suppress("UNUSED_PARAMETER") voice: RivetVoice, version: Int) {
        require(version == MODEL_VERSION) { "unsupported RIVET model version: $version" }
    }

    internal fun requireFinite(macros: Map<String, Float>) {
        require(macros.getValue("HOLD") == 0f) { "RIVET model 1 is a finite drum; HOLD is unsupported" }
    }

    fun drumClassFor(voice: RivetVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        requireFinite(settled(voice, macros))
        return if (voice == RivetVoice.KICK) DrumClass.KICK else DrumClass.SNARE
    }

    fun frequencyFor(voice: RivetVoice, macros: Map<String, Float> = emptyMap()): Float {
        val tune = settled(voice, macros).getValue("TUNE")
        return if (voice == RivetVoice.KICK) Dsp.expMap(tune, 42f, 78f) else Dsp.expMap(tune, 140f, 260f)
    }

    fun scramble(voice: RivetVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = settled(voice, near?.macros ?: emptyMap())
        val t = if (temperature.isFinite()) temperature.coerceIn(0f, 1f) else .35f
        return Dsp.scrambleNear(base, t, random) + ("HOLD" to 0f)
    }

    /** Room geometry is independent of the drum's note, strike and distortion. */
    internal fun roomMacros(m: Map<String, Float>) = mapOf(
        "SIZE" to m.getValue("SIZE"), "WALLS" to m.getValue("WALLS"), "DISTANCE" to .75f,
    )

    internal fun saturateSample(x: Float, gain: Float): Float = tanh(x * gain) / tanh(gain)

    private fun roomBand(snip: Snip, voice: RivetVoice): Snip {
        if (voice != RivetVoice.KICK) return snip
        // Two high-pass stages leave the kick's sub on the close path. Otherwise
        // the warehouse's low standing-wave peaks can pull the apparent bass root.
        val out = snip.samples.copyOf()
        repeat(2) {
            val lp = Dsp.OnePole(RATE)
            for (i in out.indices) out[i] -= lp.lp(out[i], KICK_ROOM_CUTOFF_HZ)
        }
        return Snip(out, 1, RATE)
    }

    /** One primary contact. Its integral stays one as a softer strike widens it. */
    private fun contact(frames: Int, strike: Float, effort: Float): FloatArray {
        val seconds = Dsp.lin(strike * StrikeVelocity.cornerFactor(effort), .0035f, .00025f)
        val width = (seconds * IR).toInt().coerceAtLeast(3)
        val x = FloatArray(frames)
        var sum = 0f
        for (i in 0 until width) {
            x[i] = (.5 - .5 * cos(2.0 * PI * i / (width - 1))).toFloat()
            sum += x[i]
        }
        for (i in 0 until width) x[i] /= sum
        return x
    }

    /** Double-precision energy coordinates retain low roots at 4x without a two-pole
     * coefficient subtraction near one. Fixed radiating gains need no per-mode normalization. */
    private fun body(pulse: FloatArray, hz: Float, ratios: FloatArray, weights: FloatArray,
        decay: Float, effort: Float): FloatArray {
        val bank = Modes.Bank(ratios.size, IR)
        val omega = DoubleArray(ratios.size) { 2.0 * PI * hz * ratios[it] }
        val participation = DoubleArray(ratios.size) { weights[it].toDouble() * StrikeVelocity.modeFactor(ratios[it], effort) }
        for (i in ratios.indices) bank.tune(i, hz.toDouble() * ratios[i], decay / (1.0 + .18 * i))
        return FloatArray(pulse.size) { frame ->
            bank.step()
            if (pulse[frame] != 0f) bank.drive(participation, pulse[frame].toDouble() * IR)
            var out = 0.0
            for (i in ratios.indices) out += bank.displacement(i) * omega[i]
            out.toFloat()
        }
    }

    internal data class Probe(val snip: Snip, val close: Snip, val room: Snip, val rawPeak: Float)

    fun render(voice: RivetVoice, macros: Map<String, Float> = emptyMap(),
        modelVersion: Int = MODEL_VERSION, velocity: Float = 1f): Snip =
        inspect(voice, macros, modelVersion, velocity).snip

    internal fun inspect(voice: RivetVoice, macros: Map<String, Float> = emptyMap(),
        modelVersion: Int = MODEL_VERSION, velocity: Float = 1f): Probe {
        requireModelVersion(voice, modelVersion)
        val m = settled(voice, macros)
        requireFinite(m)
        val effort = StrikeVelocity.settled(velocity)
        val kick = voice == RivetVoice.KICK
        // Source + ROOM's maximum one second remains below the classifier's 1.5 s loop boundary.
        val seconds = if (kick) .45f else .36f
        val pulse = contact((seconds * IR).toInt(), m.getValue("STRIKE"), effort)
        val hz = frequencyFor(voice, m)
        val metal = m.getValue("METAL")
        val membrane = body(pulse, hz, floatArrayOf(1f, 1.59f, 2.14f, 2.65f),
            if (kick) floatArrayOf(.80f, .12f, .06f, .025f) else floatArrayOf(.42f, .22f, .14f, .08f),
            if (kick) .24f else .13f, effort)
        // Designed metal ratios stretch while the membrane fundamental stays anchored to TUNE.
        val ratios = floatArrayOf(2.76f, 4.82f, 7.13f, 9.61f)
        for (i in ratios.indices) ratios[i] *= 1f + metal * .12f * (i + 1)
        val plate = body(pulse, hz, ratios, floatArrayOf(.42f, .26f, .18f, .10f),
            Dsp.lin(metal, .08f, .30f), effort)
        val noise = Dsp.Noise(Dsp.seedFor("RIVET", MODEL_VERSION, voice.name))
        val lp = Dsp.OnePole(IR)
        val shaped = Dsp.OnePole(IR)
        val source = FloatArray(pulse.size)
        val strike = m.getValue("STRIKE")
        val gain = Dsp.expMap(m.getValue("DRIVE"), 1f, 12f) * effort
        var rawPeak = 0f
        for (i in source.indices) {
            val t = i.toFloat() / IR
            val n = noise.next()
            val edge = shaped.lp(n - lp.lp(n, if (kick) 1200f else 1800f),
                Dsp.lin(strike, 3500f, 14000f) * StrikeVelocity.cornerFactor(effort))
            val noiseLife = if (kick) .008f else Dsp.lin(metal, .065f, .16f)
            val wire = edge * Dsp.envAt(t, noiseLife) * StrikeVelocity.gritFactor(effort) *
                (if (kick) .12f * strike else .9f + .6f * metal)
            val x = membrane[i] + plate[i] * metal * (if (kick) .32f else .65f) + wire
            rawPeak = maxOf(rawPeak, kotlin.math.abs(x))
            source[i] = saturateSample(x, gain)
        }
        Tide.bandLimit(source, IR)
        val closeSamples = Dsp.decimate(source, RATE)
        dcAndFade(closeSamples)
        val close = Snip(closeSamples, 1, RATE)
        // The existing room includes a distance-coloured direct path. This is a parallel room
        // blend, not an isolated wet return, so no subtraction pretends to recover hidden stems.
        val room = roomBand(Room.process(roomBand(close, voice), roomMacros(m)), voice)
        val out = FloatArray(room.frameCount) { i ->
            CLOSE_WEIGHT * closeSamples.getOrElse(i) { 0f } + ROOM_WEIGHT * room.samples[i]
        }
        dcAndFade(out)
        Dsp.normalize(out, .95f) // Drum peak convention; sampler velocity owns playback level.
        return Probe(Snip(out, 1, RATE), close, room, rawPeak)
    }

    private fun dcAndFade(out: FloatArray) {
        val mean = out.average().toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - mean
            out[i] = x - hp.lp(x, 20f)
        }
        Dsp.fadeTail(out)
    }
}
