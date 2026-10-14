package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

enum class OpenSkyVoice { ANTHEM, SOAR, HALO, CRYSTAL }

/** Original source/articulation recipes for the presets. Neutral values
 * retain the first model's dry samples exactly; the four front-panel macros remain.
 * Blends are unit-interval source mixtures, not post-render gain adjustments.
 */
data class OpenSkyTimbre(
    val pulseMix: Float = 0f,
    val pulseWidth: Float = .5f,
    val sineMix: Float = 0f,
    val octaveMix: Float = 0f,
    val centerWeightScale: Float = 1f,
    val detuneScale: Float = 1f,
    val toneScale: Float = 1f,
    val attackScale: Float = 1f,
    val sustainLevel: Float = 1f,
    val decaySeconds: Float = .4f,
    val releaseScale: Float = 1f,
    val bellBrightnessScale: Float = 1f,
    val bellDecayScale: Float = 1f,
) {
    init {
        fun bound(name: String, value: Float, lo: Float, hi: Float) {
            require(value.isFinite() && value in lo..hi) { "OpenSky $name must be finite in $lo..$hi, got $value" }
        }
        bound("pulseMix", pulseMix, 0f, 1f)
        bound("pulseWidth", pulseWidth, .15f, .85f)
        bound("sineMix", sineMix, 0f, 1f)
        bound("octaveMix", octaveMix, 0f, .8f)
        bound("centerWeightScale", centerWeightScale, .3f, 3f)
        bound("detuneScale", detuneScale, .2f, 2f)
        bound("toneScale", toneScale, .3f, 2f)
        bound("attackScale", attackScale, .2f, 5f)
        bound("sustainLevel", sustainLevel, .2f, 1f)
        bound("decaySeconds", decaySeconds, .05f, 2f)
        bound("releaseScale", releaseScale, .2f, 3f)
        bound("bellBrightnessScale", bellBrightnessScale, .25f, 2f)
        bound("bellDecayScale", bellDecayScale, .25f, 3f)
    }
}

/**
 * Offline OPENSKY synthesis shared by saved patches and native key samples.
 * Seven individually phased, band-limited saws feed a stereo room. RISE returns an
 * octave-shifted part of that room to its delay lines, rather than adding another
 * oscillator above the note. CRYSTAL substitutes a harmonic bell for the saw bank.
 *
 * Held audio is synthesized periodically: all oscillator frequencies are integer
 * cycles per loop, the two pitch-shifter heads share that loop's clock, and the
 * complete wet state is run to measured convergence. No seam crossfade or tail fade
 * manufactures the held region. Native keys additionally retain their actual onset.
 */
object OpenSky {
    const val RATE = WavWriter.MPC_SAMPLE_RATE
    // R3 revises the wet hall. The R2 dry source and all timbre recipes are retained.
    const val MODEL_VERSION = 3
    const val DEFAULT_MIDI = 60
    const val MIN_MIDI = 0
    const val MAX_MIDI = 127
    const val DEFAULT_GATE_SECONDS = 1.5f
    private const val OVERSAMPLE = 4
    private const val CHECK_FRAMES = 16_384
    private const val ROOM_RETURN_GAIN = .55f
    private val SPREAD = doubleArrayOf(-1.0, -.66, -.31, 0.0, .31, .66, 1.0)

    fun defaults(voice: OpenSkyVoice): Map<String, Float> = when (voice) {
        OpenSkyVoice.ANTHEM -> mapOf("RISE" to .62f, "WIDTH" to .72f, "GLOW" to .55f, "AIR" to .64f)
        OpenSkyVoice.SOAR -> mapOf("RISE" to .74f, "WIDTH" to .46f, "GLOW" to .32f, "AIR" to .57f)
        OpenSkyVoice.HALO -> mapOf("RISE" to .40f, "WIDTH" to .80f, "GLOW" to .66f, "AIR" to .72f)
        OpenSkyVoice.CRYSTAL -> mapOf("RISE" to .52f, "WIDTH" to .38f, "GLOW" to .76f, "AIR" to .78f)
    }

    /** The production host exposes the same four accepted authoring controls. */
    fun macrosFor(voice: OpenSkyVoice): List<MacroSpec> = defaults(voice).map { (name, value) ->
        MacroSpec(name, value, neutral = value)
    }

    fun scramble(voice: OpenSkyVoice, random: Random = Random.Default): Map<String, Float> =
        Dsp.scrambleNear(defaults(voice), .35f, random)

    /** Long wet tails remain pitched notes in the pad and IN KEY pipelines. */
    fun drumClassFor(
        @Suppress("UNUSED_PARAMETER") voice: OpenSkyVoice,
        @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap(),
    ): DrumClass = DrumClass.TONAL

    private fun settled(voice: OpenSkyVoice, macros: Map<String, Float>) = defaults(voice).mapValues { (k, v) ->
        (macros[k]?.takeIf { it.isFinite() } ?: v).coerceIn(0f, 1f)
    }

    fun render(
        voice: OpenSkyVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        gateSeconds: Float = DEFAULT_GATE_SECONDS,
        wet: Boolean = true,
        cancelled: () -> Boolean = { false },
        timbre: OpenSkyTimbre = OpenSkyTimbre(),
    ): Snip {
        val probe = probe(voice, macros, midi, velocity, gateSeconds, wet, cancelled = cancelled, timbre = timbre)
        val out = probe.snip.samples
        // The audition keeps one raw gain for dry/wet pairs and macro sweeps.
        // Per-clip matching would hide what adding a room actually does.
        Dsp.fadeTail(out, 20f, RATE, channels = 2)
        return probe.snip
    }

    fun renderHeld(
        voice: OpenSkyVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        loopSeconds: Float = 3f,
        wet: Boolean = true,
        cancelled: () -> Boolean = { false },
        timbre: OpenSkyTimbre = OpenSkyTimbre(),
    ): Snip {
        val probe = heldProbe(voice, macros, midi, loopSeconds, wet, cancelled = cancelled, timbre = timbre)
        return probe.snip
    }

    /** Raw output and state peaks: normalisation cannot conceal a runaway wet path. */
    internal data class Probe(
        val snip: Snip,
        val rawPeak: Float,
        val roomPeak: Float,
        val shimmerPeak: Float,
        val loopError: Double = 0.0,
        val settleCycles: Int = 0,
        val previousCycle: FloatArray = FloatArray(0),
        val sourceFrames: Int = 0,
        val wetSamples: FloatArray = FloatArray(0),
    )

    internal fun probe(
        voice: OpenSkyVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = 60,
        velocity: Float = 1f,
        gateSeconds: Float = 1.5f,
        wet: Boolean = true,
        shimmer: Boolean = true,
        cancelled: () -> Boolean = { false },
        timbre: OpenSkyTimbre = OpenSkyTimbre(),
    ): Probe {
        require(midi in 0..127) { "OpenSky MIDI must be 0..127" }
        require(gateSeconds.isFinite() && gateSeconds in .05f..8f) { "OpenSky gate must be .05..8 seconds" }
        val m = settled(voice, macros)
        val release = (if (voice == OpenSkyVoice.HALO) 1.6f else .65f) * timbre.releaseScale
        val sourceSeconds = gateSeconds + release
        val source = source(voice, m, midi, (sourceSeconds * RATE).roundToInt(), gateSeconds, cleanVelocity(velocity), null, cancelled, timbre)
        val tail = if (wet) (RATE * max(1.2, roomDecaySeconds(m.getValue("GLOW")) * 1.6)).roundToInt() else 0
        val out = FloatArray(source.size + tail * 2)
        val wetOut = FloatArray(out.size)
        val room = if (wet) Room(m, shimmer = shimmer) else null
        val mix = ROOM_RETURN_GAIN
        for (f in 0 until out.size / 2) {
            if (f % CHECK_FRAMES == 0) stopIfCancelled(cancelled)
            val l = if (f * 2 < source.size) source[f * 2] else 0f
            val r = if (f * 2 < source.size) source[f * 2 + 1] else 0f
            if (room == null) {
                out[f * 2] = l; out[f * 2 + 1] = r
            } else {
                room.step(l, r)
                wetOut[f * 2] = mix * room.left
                wetOut[f * 2 + 1] = mix * room.right
                out[f * 2] = l + wetOut[f * 2]
                out[f * 2 + 1] = r + wetOut[f * 2 + 1]
            }
        }
        return checkedProbe(out, room).copy(sourceFrames = source.size / 2, wetSamples = wetOut)
    }

    internal fun heldProbe(
        voice: OpenSkyVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = 60,
        loopSeconds: Float = 3f,
        wet: Boolean = true,
        shimmer: Boolean = true,
        cancelled: () -> Boolean = { false },
        timbre: OpenSkyTimbre = OpenSkyTimbre(),
    ): Probe {
        require(midi in 0..127) { "OpenSky MIDI must be 0..127" }
        require(loopSeconds.isFinite() && loopSeconds in 1f..8f) { "OpenSky loop must be 1..8 seconds" }
        val m = settled(voice, macros)
        val hz = midiHz(midi)
        // Fit whole root cycles to whole frames, moving pitch by less than half a
        // frame over the entire loop. Detuned oscillators get their own cycle counts.
        val cycles = (hz * loopSeconds).roundToInt().coerceAtLeast(1)
        val frames = (cycles * RATE / hz).roundToInt().coerceAtLeast(256)
        val source = source(voice, m, midi, frames, null, 1f, frames, cancelled, timbre)
        if (!wet) return checkedProbe(source, null).copy(previousCycle = source.copyOf(), settleCycles = 1,
            sourceFrames = frames, wetSamples = FloatArray(source.size))
        val room = Room(m, frames, shimmer)
        val mix = ROOM_RETURN_GAIN
        var previous = FloatArray(source.size)
        var current = FloatArray(source.size)
        var error = Double.POSITIVE_INFINITY
        // At least 12 seconds of full-state settling, not just oscillator settling.
        val minimum = max(4, (12.0 * RATE / frames).roundToInt())
        for (cycle in 1..40) {
            for (f in 0 until frames) {
                if (f % CHECK_FRAMES == 0) stopIfCancelled(cancelled)
                val l = source[f * 2]; val r = source[f * 2 + 1]
                room.step(l, r)
                current[f * 2] = l + mix * room.left
                current[f * 2 + 1] = r + mix * room.right
            }
            error = relativeError(previous, current)
            if (cycle >= minimum && error < 1e-10) {
                return checkedProbe(current, room).copy(loopError = error, settleCycles = cycle, previousCycle = previous,
                    sourceFrames = frames, wetSamples = FloatArray(current.size) { current[it] - source[it] })
            }
            val swap = previous; previous = current; current = swap
        }
        throw IllegalStateException("OpenSky wet loop did not converge: $voice MIDI $midi, error $error")
    }

    private fun checkedProbe(out: FloatArray, room: Room?): Probe {
        var peak = 0f
        for (v in out) {
            check(v.isFinite()) { "OpenSky produced a non-finite raw sample" }
            peak = max(peak, abs(v))
        }
        check(peak < 4f) { "OpenSky raw output exceeded its headroom: $peak" }
        return Probe(Snip(out, channels = 2, sampleRate = RATE), peak, room?.statePeak ?: 0f, room?.shimmerPeak ?: 0f)
    }

    private fun relativeError(a: FloatArray, b: FloatArray): Double {
        var difference = 0.0; var energy = 0.0
        for (i in a.indices) {
            val delta = a[i] - b[i].toDouble()
            difference += delta * delta; energy += b[i].toDouble() * b[i]
        }
        return difference / max(energy, 1e-20)
    }

    internal data class KeyProbe(val note: KeyNote, val loopError: Double, val settleCycles: Int, val rawPeak: Float)

    /** A real onset followed by independently validated sustain. Every stored
     * cycle is actually rendered; no repeated copy or crossfade creates the seam.
     * The program's release envelope must fade the whole baked wet sample.
     */
    internal fun keyProbe(
        voice: OpenSkyVoice,
        macros: Map<String, Float>,
        midi: Int,
        velocity: Float,
        timbre: OpenSkyTimbre,
        wet: Boolean,
        loopSeconds: Float,
        cancelled: () -> Boolean,
    ): KeyProbe {
        require(voice == OpenSkyVoice.HALO) { "OPENSKY key sustain currently supports HALO only" }
        require(midi in OpenSkyKeys.MIN_ZONE_MIDI..OpenSkyKeys.MAX_ZONE_MIDI) { "OPENSKY key MIDI must be ${OpenSkyKeys.MIN_ZONE_MIDI}..${OpenSkyKeys.MAX_ZONE_MIDI}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OPENSKY key velocity must be finite in 0..1" }
        require(loopSeconds.isFinite() && loopSeconds in 1f..OpenSkyKeys.MAX_LOOP_SECONDS) { "OPENSKY key loop must be 1..${OpenSkyKeys.MAX_LOOP_SECONDS} seconds" }
        stopIfCancelled(cancelled)
        val m = settled(voice, macros)
        val hz = midiHz(midi)
        val cycles = (hz * loopSeconds).roundToInt().coerceAtLeast(1)
        val frames = (cycles * RATE / hz).roundToInt().coerceAtLeast(256)
        val room = if (wet) Room(m, frames) else null
        val rendered = ArrayList<FloatArray>()
        var previous: FloatArray? = null
        var error = Double.POSITIVE_INFINITY
        val minimum = if (wet) max(4, (12.0 * RATE / frames).roundToInt()) else 2
        val maximum = minOf(40, (OpenSkyKeys.MAX_SAMPLE_SECONDS * RATE / frames).toInt())
        for (cycle in 0 until maximum) {
            val dry = source(voice, m, midi, frames, null, velocity, frames, cancelled, timbre, keyCycle = cycle)
            val current = FloatArray(dry.size)
            for (f in 0 until frames) {
                if (f % CHECK_FRAMES == 0) stopIfCancelled(cancelled)
                val l = dry[f * 2]; val r = dry[f * 2 + 1]
                if (room == null) {
                    current[f * 2] = l; current[f * 2 + 1] = r
                } else {
                    room.step(l, r)
                    current[f * 2] = l + ROOM_RETURN_GAIN * room.left
                    current[f * 2 + 1] = r + ROOM_RETURN_GAIN * room.right
                }
            }
            rendered.add(current)
            previous?.let { error = relativeError(it, current) }
            if (cycle + 1 >= minimum && error < 1e-10) {
                val all = FloatArray(rendered.size * current.size)
                for (i in rendered.indices) rendered[i].copyInto(all, i * current.size)
                val checked = checkedProbe(all, room)
                return KeyProbe(KeyNote(checked.snip, loopStartFrame = cycle.toLong() * frames), error, cycle + 1, checked.rawPeak)
            }
            previous = current
        }
        throw IllegalStateException("OPENSKY key wet/source state did not converge within ${OpenSkyKeys.MAX_SAMPLE_SECONDS}s: MIDI $midi, error $error")
    }

    private fun source(
        voice: OpenSkyVoice, m: Map<String, Float>, midi: Int, frames: Int,
        gate: Float?, velocity: Float, periodFrames: Int?, cancelled: () -> Boolean,
        timbre: OpenSkyTimbre,
        keyCycle: Int? = null,
    ): FloatArray {
        val rate = RATE * OVERSAMPLE
        // Held synthesis carries three actual periodic cycles through the sinc
        // decimator; its middle cycle never sees a file-edge boundary.
        val repeats = if (periodFrames == null) 1 else 3
        val raw = FloatArray(frames * OVERSAMPLE * repeats * 2)
        val hz = midiHz(midi)
        val width = m.getValue("WIDTH").toDouble()
        val rise = m.getValue("RISE").toDouble()
        val air = m.getValue("AIR").toDouble()
        val maxCents = when (voice) {
            OpenSkyVoice.ANTHEM -> 38.0
            OpenSkyVoice.SOAR -> 23.0
            OpenSkyVoice.HALO -> 31.0
            OpenSkyVoice.CRYSTAL -> 8.0
        }
        val phases = Dsp.phases(7, Dsp.seedFor("OPENSKY", voice.name, midi))
        val baseCycles = periodFrames?.let { (hz * it / RATE).roundToInt().coerceAtLeast(1) }
        val exactHz = if (baseCycles == null) hz else baseCycles.toDouble() * RATE / periodFrames!!
        val steps = DoubleArray(7) { i ->
            val requested = exactHz * 2.0.pow(SPREAD[i] * maxCents * width * timbre.detuneScale / 1200.0)
            if (periodFrames == null) requested / rate else
                (requested * periodFrames / RATE).roundToInt().coerceAtLeast(1).toDouble() / (periodFrames * OVERSAMPLE)
        }
        val pans = DoubleArray(7) { SPREAD[it] * width }
        val weights = DoubleArray(7) {
            if (it == 3) { (if (voice == OpenSkyVoice.SOAR) 2.1 else 1.1) * timbre.centerWeightScale }
            else if (voice == OpenSkyVoice.SOAR) .40 else .58
        }
        val gainSum = weights.sum()
        val gainL = DoubleArray(7) { weights[it] * sqrt((1.0 - pans[it]) * .5) / gainSum }
        val gainR = DoubleArray(7) { weights[it] * sqrt((1.0 + pans[it]) * .5) / gainSum }
        val lpL = Dsp.TptSvf(rate); val lpR = Dsp.TptSvf(rate)
        val attack = when (voice) {
            OpenSkyVoice.ANTHEM -> .010 + .14 * (1.0 - rise)
            OpenSkyVoice.SOAR -> .004 + .04 * (1.0 - rise)
            OpenSkyVoice.HALO -> .10 + .95 * (1.0 - rise)
            OpenSkyVoice.CRYSTAL -> .003 + .016 * (1.0 - rise)
        } * timbre.attackScale
        val release = (if (voice == OpenSkyVoice.HALO) 1.6 else .65) * timbre.releaseScale
        // The held filter is constant. Finite RISE opens it over the onset.
        val voiceTone = if (voice == OpenSkyVoice.HALO) .78 else if (voice == OpenSkyVoice.SOAR) 1.12 else 1.0
        val brightHz = (2_400.0 * voiceTone * 2.0.pow(2.7 * air + .65 * rise) * timbre.toneScale).coerceAtMost(18_000.0)
        for (f in 0 until raw.size / 2) {
            if (f % (CHECK_FRAMES * OVERSAMPLE) == 0) stopIfCancelled(cancelled)
            val t = if (keyCycle == null) f.toDouble() / rate else
                ((keyCycle - 1).toDouble() * frames * OVERSAMPLE + f) / rate
            var l = 0.0; var r = 0.0
            for (i in 0..6) {
                phases[i] += steps[i]; phases[i] -= floor(phases[i])
                var x = if (voice == OpenSkyVoice.CRYSTAL) {
                    // Harmonic bell: root is protected, upper harmonics lose energy
                    // faster than its body; held material retains their settled balance.
                    val harmonic = i + 1
                    val bellLoss = if (gate == null) 1.0 else exp(-t * (.35 + harmonic * .28) / timbre.bellDecayScale)
                    if (steps[i] * harmonic < .45) sin(2.0 * PI * phases[i] * harmonic) * bellLoss / harmonic.toDouble().pow(.8 / timbre.bellBrightnessScale) else 0.0
                } else {
                    if (steps[i] < .45) {
                        val saw = 2.0 * phases[i] - 1.0 - polyBlep(phases[i], steps[i])
                        val shaped = if (timbre.pulseMix == 0f) saw else
                            (1.0 - timbre.pulseMix) * saw + timbre.pulseMix * pulse(phases[i], steps[i], timbre.pulseWidth.toDouble())
                        if (voice == OpenSkyVoice.HALO) .70 * shaped + .30 * sin(2.0 * PI * phases[i]) else shaped
                    } else 0.0
                }
                if (timbre.sineMix > 0f) {
                    // The ascending saw's fundamental is -sin. Aligned support
                    // restores a warm root rather than cancelling it; the original
                    // HALO balance is retained exactly when this override is zero.
                    x = (1.0 - timbre.sineMix) * x - timbre.sineMix * sin(2.0 * PI * phases[i])
                }
                if (timbre.octaveMix > 0f && steps[i] * 2.0 * rate < RATE * .45) {
                    val phase = (phases[i] * 2.0) % 1.0
                    val upper = 2.0 * phase - 1.0 - polyBlep(phase, steps[i] * 2.0)
                    x = (1.0 - timbre.octaveMix) * x + timbre.octaveMix * upper
                }
                // Constant-power pan; centre remains in both channels even at WIDTH 1.
                l += gainL[i] * x
                r += gainR[i] * x
            }
            val envelope = if (keyCycle != null) {
                val onset = (t / attack).coerceIn(0.0, 1.0)
                val smooth = onset * onset * (3.0 - 2.0 * onset)
                val decay = if (timbre.sustainLevel == 1f) 1.0 else timbre.sustainLevel +
                    (1.0 - timbre.sustainLevel) * exp(-max(0.0, t - attack) / timbre.decaySeconds)
                smooth * decay
            } else if (gate == null) timbre.sustainLevel.toDouble() else {
                val onset = (t / attack).coerceIn(0.0, 1.0)
                val smooth = onset * onset * (3.0 - 2.0 * onset)
                val decay = if (timbre.sustainLevel == 1f) 1.0 else timbre.sustainLevel +
                    (1.0 - timbre.sustainLevel) * exp(-max(0.0, t - attack) / timbre.decaySeconds)
                smooth * decay * if (t <= gate) 1.0 else exp(-6.907755 * (t - gate) / release)
            }
            val brassSettle = if (voice == OpenSkyVoice.ANTHEM) {
                if (gate == null) .78 else .78 + .22 * exp(-max(0.0, t - attack * 2.0) / .25)
            } else 1.0
            val cutoff = brassSettle * if (gate == null && keyCycle == null) brightHz else
                brightHz * (0.28 + .72 * (t / (attack * 2.0)).coerceIn(0.0, 1.0))
            lpL.process(if (keyCycle != null && t < 0.0) 0f else l.toFloat(), cutoff.toFloat(), 1.3f)
            lpR.process(if (keyCycle != null && t < 0.0) 0f else r.toFloat(), cutoff.toFloat(), 1.3f)
            raw[f * 2] = lpL.low * envelope.toFloat() * velocity
            raw[f * 2 + 1] = lpR.low * envelope.toFloat() * velocity
        }
        val decimated = Dsp.decimate(raw, RATE, channels = 2)
        return if (periodFrames == null) decimated else decimated.copyOfRange(frames * 2, frames * 4)
    }

    private fun polyBlep(phase: Double, step: Double): Double = when {
        phase < step -> { val t = phase / step; t + t - t * t - 1.0 }
        phase > 1.0 - step -> { val t = (phase - 1.0) / step; t * t + t + t + 1.0 }
        else -> 0.0
    }

    private fun pulse(phase: Double, step: Double, width: Double): Double {
        // Centre the positive lobe at phase .75: its fundamental is -sin,
        // aligned with the ascending saw for every duty cycle.
        val shifted = (phase + .25 + width * .5) % 1.0
        val square = (if (shifted < width) 1.0 else -1.0) + polyBlep(shifted, step) -
            polyBlep((shifted - width + 1.0) % 1.0, step)
        // Remove the PWM duty-cycle DC; keep the asymmetric pulse's peak at unity.
        return (square - (2.0 * width - 1.0)) / (2.0 * max(width, 1.0 - width))
    }

    /** Two complementary Hann read heads, each advancing twice as fast as time.
     * The delay reset happens beneath its zero-weight window. In held mode the
     * grain clock has an integer number of turns over the complete loop.
     */
    internal class OctaveShift(private val periodFrames: Int = 2048, private val grains: Int = 1) {
        private val grainFrames = periodFrames.toDouble() / grains
        private val line = FloatArray(kotlin.math.ceil(grainFrames).toInt() + 32)
        private var write = 0
        private var clock = 0
        fun next(input: Float): Float {
            line[write] = input
            val phase = ((clock.toDouble() * grains / periodFrames) % 1.0)
            val other = (phase + .5) % 1.0
            val a = .5 - .5 * cos(2.0 * PI * phase)
            val b = 1.0 - a
            val out = a * tap(8.0 + grainFrames * (1.0 - phase)) + b * tap(8.0 + grainFrames * (1.0 - other))
            if (++write == line.size) write = 0
            if (++clock == periodFrames) clock = 0
            return out.toFloat()
        }
        private fun tap(delay: Double): Float {
            var position = write - delay
            while (position < 0.0) position += line.size
            // Adding a tiny negative residue to an integer size can round up to
            // exactly size. Wrap both sides before selecting an array element.
            while (position >= line.size) position -= line.size
            val index = position.toInt()
            val next = if (index + 1 == line.size) 0 else index + 1
            return line[index] + (line[next] - line[index]) * (position - index).toFloat()
        }
    }

    private class Allpass(length: Int) {
        private val line = FloatArray(length)
        private var head = 0
        fun next(x: Float): Float {
            val delayed = line[head]
            val written = x + .57f * delayed
            line[head] = written
            if (++head == line.size) head = 0
            return delayed - .57f * written
        }
    }

    private fun roomDecaySeconds(glow: Float): Double = .6 * (7.5 / .6).pow(glow.toDouble())

    /** Eight-line orthogonal feedback hall. The octave loop is projected at half
     * the output gain, so an audible bloom can coexist with a dissipative tank.
     */
    private class Room(m: Map<String, Float>, period: Int = 2048, shimmer: Boolean = true) {
        private val lengths = intArrayOf(2671, 3167, 3767, 4451, 5233, 6121, 7151, 8293)
        private val lines = Array(8) { FloatArray(lengths[it]) }
        private val heads = IntArray(8)
        private val damp = FloatArray(8)
        private val scattered = FloatArray(8)
        private val pre = Array(2) { FloatArray(4) }
        private val shifts = Array(2) { OctaveShift(period, max(1, (period / 2048.0).roundToInt())) }
        private val inputDiffuser = arrayOf(arrayOf(Allpass(149), Allpass(421)), arrayOf(Allpass(211), Allpass(547)))
        private val diffuser = arrayOf(arrayOf(Allpass(613), Allpass(173)), arrayOf(Allpass(773), Allpass(229)))
        private val predelay = Array(2) { FloatArray(991) }
        private var preHead = 0
        private val signs = floatArrayOf(1f, 1f, -1f, 1f, 1f, -1f, -1f, -1f)
        private val air = m.getValue("AIR")
        private val rt60 = roomDecaySeconds(m.getValue("GLOW"))
        private val feedback = FloatArray(8) { exp(-6.907755 * lengths[it] / (rt60 * RATE)).coerceAtMost(.90).toFloat() }
        // Hadamard scattering has norm 1. A stereo quarter-sum has norm .5;
        // the two-head octave read has energy norm at most sqrt(2); distributing
        // each channel to four lines at gain .5 has norm 1. Thus the complete
        // return is bounded by .90 + .10*.5*sqrt(2) = .970711 before the soft
        // state bound. The larger audible octave output is outside that loop.
        private val send = if (shimmer) .10f * m.getValue("RISE") else 0f
        private val octaveReturn = if (shimmer) 1.80f * m.getValue("RISE") else 0f
        private val damping = (1.0 - exp(-2.0 * PI * (4200.0 + 8500.0 * air) / RATE)).toFloat()
        // Four fixed poles before octave-up reads suppress content above the new
        // Nyquist. Repeated octave passes therefore get lighter, not brighter forever.
        private val pitchLowpass = (1.0 - exp(-2.0 * PI * (3200.0 + 4800.0 * air) / RATE)).toFloat()
        var left = 0f; private set
        var right = 0f; private set
        var statePeak = 0f; private set
        var shimmerPeak = 0f; private set
        private fun shift(channel: Int, input: Float): Float {
            var low = input
            for (pole in 0..3) {
                pre[channel][pole] += pitchLowpass * (low - pre[channel][pole])
                low = pre[channel][pole]
            }
            val shifted = shifts[channel].next(low)
            shimmerPeak = max(shimmerPeak, abs(send * shifted))
            return shifted
        }
        fun step(l: Float, r: Float) {
            var sumL = 0f; var sumR = 0f
            for (i in 0..7) {
                val old = lines[i][heads[i]]
                damp[i] += damping * (old - damp[i])
                scattered[i] = damp[i]
                if (i % 2 == 0) sumL += signs[i] * old * .5f else sumR += signs[i] * old * .5f
            }
            // Three butterfly stages implement an orthogonal 8x8 Hadamard mix:
            // every return circulates through all eight distinct delay lengths.
            var span = 1
            while (span < 8) {
                var group = 0
                while (group < 8) {
                    for (j in 0 until span) {
                        val a = scattered[group + j]
                        val b = scattered[group + j + span]
                        scattered[group + j] = a + b
                        scattered[group + j + span] = a - b
                    }
                    group += span * 2
                }
                span *= 2
            }
            val shiftedL = shift(0, sumL * .5f)
            val shiftedR = shift(1, sumR * .5f)
            val dryL = inputDiffuser[0][1].next(inputDiffuser[0][0].next(predelay[0][preHead]))
            val dryR = inputDiffuser[1][1].next(inputDiffuser[1][0].next(predelay[1][preHead]))
            predelay[0][preHead] = l
            predelay[1][preHead] = r
            if (++preHead == predelay[0].size) preHead = 0
            for (i in 0..7) {
                val even = i % 2 == 0
                val excitation = .5f * signs[i] * (if (even) dryL else dryR)
                val octave = .5f * signs[i] * (if (even) shiftedR else shiftedL)
                val value = excitation + feedback[i] * scattered[i] * .3535533905932738f + send * octave
                // Deliberate smooth state-energy bound, not a NaN reset or output
                // normaliser. Ordinary room levels remain in its nearly linear region.
                val bounded = (2.0 * tanh(value * .5)).toFloat()
                lines[i][heads[i]] = bounded
                statePeak = max(statePeak, abs(bounded))
                if (++heads[i] == lines[i].size) heads[i] = 0
            }
            left = diffuser[0][1].next(diffuser[0][0].next(sumL + octaveReturn * shiftedR))
            right = diffuser[1][1].next(diffuser[1][0].next(sumR + octaveReturn * shiftedL))
        }
    }

    private fun midiHz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun cleanVelocity(velocity: Float) = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
    private fun stopIfCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("OpenSky render no longer wanted")
    }
}
