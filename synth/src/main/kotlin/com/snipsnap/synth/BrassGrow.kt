package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * The opt-in model-3 BRASS performance. GROW interpolates source parameters and a
 * mouth-pressure score before ONE lip junction, waveguide and body are rendered.
 * It never renders or mixes separate age-group takes.
 *
 * Finite notes retain the audition's disclosed playback articulation: roughness in
 * slow pitch/breath motion, or delayed expressive vibrato and a source-derived shelf.
 * Sustains use the approved played-source method instead: finite contact calibration,
 * an aperiodic seeded flow and a long mouth-pressure phrase, captured at native pitch.
 * They receive none of that finite playback treatment. The host still renders notes
 * offline; changing GROW designs a new performance rather than mutating a live voice.
 */
internal object BrassGrow {
    val MACROS = listOf(MacroSpec("TUNE", .5f, neutral = .5f),
        MacroSpec("GROW", .5f, neutral = .5f), MacroSpec("LENGTH", .5f, neutral = .5f),
        MacroSpec("HOLD", 0f, neutral = 0f))
    private val voice = EmbouchureVoice.BRASS
    private const val RELEASE_SECONDS = .85f

    private data class Profile(val effort: Float, val reed: Float, val shape: Float,
                               val grain: Float, val gesture: EmbouchureGesture,
                               val sustainPressure: List<Float>)
    private val profiles = listOf(
        Profile(.35f, .25f, .25f, .75f, EmbouchureGesture.OVERPRESSURE,
            listOf(0f, 1.08f, .92f, 1.02f, .88f, .99f, .90f, .97f, .72f, 0f)),
        Profile(.55f, .45f, .45f, .15f, EmbouchureGesture.PRESSURE_RELEASE,
            listOf(0f, .98f, .99f, 1f, .92f, 1.02f, .96f, 1f, .78f, 0f)),
        Profile(.82f, .65f, .18f, .06f, EmbouchureGesture.LEANING_SWELL,
            listOf(0f, .85f, .92f, 1.03f, .90f, 1.05f, .93f, 1.02f, .82f, 0f)),
    )

    internal data class Configuration(val controls: Map<String, Float>,
                                      val score: Embouchure.PressureScore,
                                      val durationSeconds: Float,
                                      val grow: Float, val held: Boolean,
                                      val playedMidi: Int, val grainReduction: Float)
    internal data class Rendered(val snip: Snip, val raw: Snip, val source: Snip,
                                 val played: Embouchure.Played, val join: Map<String, Any?>?)

    private fun settled(macros: Map<String, Float>): Map<String, Float> {
        val names = MACROS.map { it.name }.toSet()
        require(macros.all { (name, value) -> name in names && value.isFinite() && value in 0f..1f }) {
            "BRASS GROW accepts finite TUNE/GROW/LENGTH/HOLD values in 0..1"
        }
        return MACROS.associate { it.name to (macros[it.name] ?: it.default) }
    }
    private fun weights(grow: Float) = listOf(maxOf(0f, 1f - 2f * grow),
        1f - abs(2f * grow - 1f), maxOf(0f, 2f * grow - 1f))

    /** Fixed control/score data for one performance; these are not audio-layer weights. */
    internal fun configuration(macros: Map<String, Float>, held: Boolean = false,
                               midi: Int = Embouchure.rootMidi(voice), velocity: Float = 1f): Configuration {
        val m = settled(macros)
        require(midi in Embouchure.midiRange(voice)) { "BRASS GROW MIDI base must be ${Embouchure.midiRange(voice)}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "BRASS GROW velocity must be 0..1" }
        val playedMidi = midi + Math.round(m.getValue("TUNE") * Embouchure.TUNE_SEMITONES)
        require(playedMidi in Embouchure.midiRange(voice)) { "BRASS GROW played MIDI must be ${Embouchure.midiRange(voice)}" }
        val grow = m.getValue("GROW")
        val w = weights(grow)
        fun blend(select: (Profile) -> Float): Float = profiles.indices.sumOf {
            w[it].toDouble() * select(profiles[it])
        }.toFloat()
        // Loose, noisy contact can pump the upper regime in a low, gentle note.
        // Reduce only its source-linked GRAIN there: a still-rough .45 at the corner,
        // smoothly returning to the audition's .75 by C4 or velocity .75. Pressure,
        // body and contact calibration stay intact; no output pitch correction is used.
        val grainReduction = (.30 * smooth((60 - playedMidi) / 3.0) *
            smooth((.75 - velocity) / .15) * w[0]).toFloat()
        val grain = blend { it.grain }
        val controls = Embouchure.defaults(voice) + mapOf("TUNE" to m.getValue("TUNE"),
            "EFFORT" to blend { it.effort }, "REED" to blend { it.reed },
            "SHAPE" to blend { it.shape }, "GRAIN" to if (grainReduction > 0f) grain - grainReduction else grain,
            "LENGTH" to m.getValue("LENGTH"), "HOLD" to 0f)
        // LENGTH changes a finite note's gate or the pace of the held player's breath phrase.
        // At the default, the sustain source is the approved twelve-second R3 score.
        val duration = if (held) 9f + 6f * m.getValue("LENGTH") else Embouchure.gestureSeconds(voice, controls)
        val finiteScores = profiles.map { Embouchure.finitePressureScore(voice, controls, it.gesture, duration) }
        val times = if (held) listOf(0f, finiteScores.first().times[1], .12f, .25f,
            .38f, .51f, .64f, .77f, .89f, 1f) else finiteScores.first().times
        val values = times.indices.map { knot -> profiles.indices.sumOf { profile ->
            val value = if (held) profiles[profile].sustainPressure[knot] else finiteScores[profile].values[knot]
            w[profile].toDouble() * value
        }.toFloat() }
        return Configuration(controls, Embouchure.PressureScore(times, values), duration, grow, held,
            playedMidi, grainReduction)
    }

    /** The authoritative physical source path, also used for release/feedback diagnostics. */
    internal fun playSource(macros: Map<String, Float> = emptyMap(), midi: Int = Embouchure.rootMidi(voice),
                            velocity: Float = 1f, held: Boolean = false,
                            sourceStopSeconds: Float? = null, totalSeconds: Float? = null,
                            record: Boolean = false, feedback: Boolean = true): Embouchure.Played {
        require(midi in Embouchure.midiRange(voice)) { "BRASS GROW MIDI base must be ${Embouchure.midiRange(voice)}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "BRASS GROW velocity must be 0..1" }
        val config = configuration(macros, held, midi, velocity)
        return Embouchure.play(voice, config.controls, Embouchure.Probe(midi = midi, velocity = velocity,
            // All gesture identity is in the single interpolated pressure score.
            gesture = EmbouchureGesture.OVERPRESSURE, model = Embouchure.MODEL_VERSION,
            gestureDurationSeconds = config.durationSeconds, pressureScore = config.score,
            totalSeconds = totalSeconds ?: (config.durationSeconds + RELEASE_SECONDS),
            sourceStopSeconds = sourceStopSeconds, record = record, feedback = feedback))
    }

    fun render(macros: Map<String, Float> = emptyMap(), midi: Int = Embouchure.rootMidi(voice),
               velocity: Float = 1f): Snip {
        val rendered = renderMeasured(macros, midi, velocity)
        check(rendered.join==null || rendered.join["qualityPassed"]==true) {
            "BRASS GROW sustain join did not pass: ${rendered.join}"
        }
        return rendered.snip
    }

    /** All measurements refer to this production render, not an alternate probe renderer. */
    internal fun renderMeasured(macros: Map<String, Float> = emptyMap(), midi: Int = Embouchure.rootMidi(voice),
                                velocity: Float = 1f, record: Boolean = false): Rendered {
        val m = settled(macros)
        val held = Embouchure.isLoop(m.getValue("HOLD"))
        val config = configuration(m, held, midi, velocity)
        val played = playSource(m, midi, velocity, held = held, record = record)
        checkCancelled()
        val source = Snip(Embouchure.finish(played.raw, normalize = false), 1, Dsp.RATE)
        val raw: Snip
        val join: Map<String, Any?>?
        if (held) {
            if (velocity == 0f) {
                raw = Snip(FloatArray((3.6f * Dsp.RATE).toInt()), 1, Dsp.RATE)
                join = mapOf("silent" to true, "qualityPassed" to true,
                    "method" to "Exact zero input supplies no source work; silent sustain needs no boundary search")
            } else {
                // A captured sustain must still be driven by the player, not selected from passive release.
                val activeFrames = minOf(source.frameCount, (played.sourceEndSeconds * Dsp.RATE).toInt())
                val capture = BrassGrowCapture.close(source.samples.copyOf(activeFrames)) { Thread.currentThread().isInterrupted }
                raw = Snip(capture.samples, 1, Dsp.RATE)
                join = capture.metadata
            }
        } else {
            raw = finitePerformance(source, config)
            join = null
        }
        checkCancelled()
        val out = raw.samples.copyOf()
        Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        val gain = velocity.pow(.60f)
        for (i in out.indices) {
            if (i % 8192 == 0) checkCancelled()
            val sample = out[i] * gain
            out[i] = if (abs(sample) < 1e-30f) 0f else sample
        }
        return Rendered(Snip(out, 1, Dsp.RATE), raw, source, played, join)
    }

    /** R2-style finite articulation of the one source; never a crossfade of completed takes. */
    private fun finitePerformance(source: Snip, config: Configuration): Snip {
        val w = weights(config.grow)
        val rough = w[0].toDouble()
        val expressive = w[2].toDouble()
        if (rough == 0.0 && expressive == 0.0) return source
        val length = config.controls.getValue("LENGTH")
        val slowOmega = 2 * PI / ((1.65 + .55 * length) * Dsp.RATE)
        val vibratoOmega = 2 * PI * 5.1 / Dsp.RATE
        fun offsetAmplitude(cents: Double, omega: Double) = (2.0.pow(cents / 1200) - 1) / omega
        val slowOffset = offsetAmplitude(3.0, slowOmega)
        val roughOffset3 = offsetAmplitude(1.0, slowOmega * 3)
        val vibratoOffset = offsetAmplitude(9.0, vibratoOmega)
        val low = FloatArray(source.frameCount)
        val a = 1 - exp(-2 * PI * 650 / Dsp.RATE)
        var filter = 0.0
        for (i in low.indices) {
            if (i % 8192 == 0) checkCancelled()
            filter += a * (source.samples[i] - filter)
            low[i] = filter.toFloat()
        }
        val out = FloatArray(source.frameCount)
        for (i in out.indices) {
            if (i % 8192 == 0) checkCancelled()
            val t = i.toDouble() / Dsp.RATE
            val phase = i * slowOmega
            val life = smooth((t - .3) / .5) * (1 - smooth((t - config.durationSeconds + .1) / .45))
            val offset = rough * (slowOffset * sin(phase) + roughOffset3 * sin(3 * phase)) +
                expressive * vibratoOffset * life * sin(i * vibratoOmega)
            val u = (t / config.durationSeconds).coerceIn(0.0, 1.0)
            val breath = sin(PI * u).pow(2)
            val gain = 1 + rough * (.035 * sin(phase) + .015 * sin(3 * phase)) +
                expressive * (-.09 + .18 * breath)
            val shelf = 10.0.pow(expressive * (1 + 3 * breath) / 20)
            val body = read(source.samples, i + offset)
            val lowBody = read(low, i + offset)
            out[i] = (gain * (body + (shelf - 1) * (body - lowBody))).toFloat()
        }
        Dsp.fadeTail(out)
        return Snip(out, 1, Dsp.RATE)
    }
    private fun read(source: FloatArray, position: Double): Double {
        if (position < 0 || position > source.lastIndex) return 0.0
        val i = floor(position).toInt()
        val fraction = position - i
        return source[i] * (1 - fraction) + source[minOf(i + 1, source.lastIndex)] * fraction
    }
    private fun smooth(value: Double) = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("BRASS GROW render no longer wanted")
    }
}
