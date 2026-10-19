package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Four articulations of one struck-string instrument, rather than four physical objects. */
enum class OvenVoice { GRAND, FELT, FORGED, BLOOM }

/**
 * OVEN: velocity-sensitive stiff strings heard through a dark, fixed-frequency body.
 * Independent damped modes and a restrained companion string supply a changing natural tail.
 * The force pulse is a musical contact proxy, not a solved Hunt–Crossley collision; neither the
 * body frequencies nor the losses are measured cast-iron or concert-grand data. HALO is baked
 * per-note harmonic resonance. Every render starts fresh and ends; no passive note is looped.
 */
object Oven {
    const val MODEL_VERSION = 1
    const val ROOT_MIDI = 48
    const val DEFAULT_MIDI = 60
    const val MIDI_MIN = 24
    const val MIDI_MAX = 96
    const val TUNE_SEMITONES = 24
    const val MAX_SECONDS = 8.5f
    private const val RATE = Dsp.RATE
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val TAU = 2.0 * PI
    private const val BANDWIDTH = 17_500.0
    private const val LN_1000 = 6.907755278982137

    fun macrosFor(voice: OvenVoice): List<MacroSpec> {
        val values = when (voice) {
            OvenVoice.GRAND -> floatArrayOf(.55f, .55f, .55f, 0f, .20f)
            OvenVoice.FELT -> floatArrayOf(.12f, .72f, .42f, .04f, .12f)
            OvenVoice.FORGED -> floatArrayOf(.90f, .35f, .42f, 0f, .10f)
            OvenVoice.BLOOM -> floatArrayOf(.35f, .65f, .78f, .72f, .70f)
        }
        return listOf(MacroSpec("TUNE", .5f, .5f), MacroSpec("HAMMER", values[0], .5f),
            MacroSpec("BODY", values[1], .5f), MacroSpec("RING", values[2], .5f),
            MacroSpec("BLOOM", values[3], 0f), MacroSpec("HALO", values[4], 0f))
    }

    fun defaults(voice: OvenVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: OvenVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (key, default) ->
            (macros[key]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f)
        }

    fun scramble(voice: OvenVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(settled(voice, if (near?.engine == "OVEN") near.macros else emptyMap()), temperature, random)

    fun midiFor(@Suppress("UNUSED_PARAMETER") voice: OvenVoice, tune: Float, midi: Int? = null): Int =
        (midi ?: DEFAULT_MIDI) + (((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) - .5f) * TUNE_SEMITONES).roundToInt()

    fun frequencyFor(voice: OvenVoice, tune: Float, midi: Int? = null): Float = Keys.midiHz(midiFor(voice, tune, midi))
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: OvenVoice,
        @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.TONAL
    fun isLoop(@Suppress("UNUSED_PARAMETER") hold: Float): Boolean = false

    /** The first mode stays exactly on the requested note as stiffness changes. */
    internal fun ratioFor(partial: Int, inharmonicity: Double): Double {
        require(partial >= 1 && inharmonicity >= 0.0)
        return partial * sqrt((1.0 + inharmonicity * partial * partial) / (1.0 + inharmonicity))
    }

    internal data class Diagnostics(val rootHz: Double, val inharmonicity: Double, val partials: Int,
        val contactSeconds: Double, val rootT60: Double, val rawPeak: Double,
        val bodyPeak: Double, val haloPeak: Double, val seconds: Double, val haloT60: Double,
        val internalRate: Int = INTERNAL_RATE)
    internal data class Probe(val samples: FloatArray, val diagnostics: Diagnostics) {
        val rootHz get() = diagnostics.rootHz
        val partials get() = diagnostics.partials
        val rawPeak get() = diagnostics.rawPeak
        val contactSeconds get() = diagnostics.contactSeconds
        val inharmonicity get() = diagnostics.inharmonicity
    }

    /** Exact damped rotation in (omega*q, -velocity), with a finite force through its port. */
    private class Mode(hz: Double, t60: Double, val weight: Double, val port: Double = 1.0) {
        private val radius = exp(-LN_1000 / (t60 * INTERNAL_RATE))
        private val cr = radius * cos(TAU * hz / INTERNAL_RATE)
        private val sr = radius * sin(TAU * hz / INTERNAL_RATE)
        val continuousPort = 2.0 * (1.0 - radius)
        private var re = 0.0
        private var im = 0.0
        fun tick(force: Double): Double {
            val next = cr * re - sr * im
            im = sr * re + cr * im - force * port
            re = next
            return re * weight
        }
    }

    /** Raw 44.1 kHz output before loudness conditioning; ablations retain the same string input. */
    internal fun probe(voice: OvenVoice, macros: Map<String, Float> = emptyMap(), midi: Int? = null,
        velocity: Float = 1f, bodyEnabled: Boolean = true, haloEnabled: Boolean = true,
        bloomOverride: Float? = null, seconds: Float? = null): Probe {
        stopIfUnwanted()
        require(midi == null || midi in MIDI_MIN..MIDI_MAX) { "OVEN MIDI root must be $MIDI_MIN..$MIDI_MAX" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OVEN velocity must be 0..1" }
        require(seconds == null || seconds.isFinite() && seconds in .05f..MAX_SECONDS) { "OVEN probe duration must be .05..$MAX_SECONDS" }
        require(bloomOverride == null || bloomOverride.isFinite() && bloomOverride in 0f..1f)
        val m = settled(voice, macros)
        val note = midiFor(voice, m.getValue("TUNE"), midi)
        val hz = Keys.midiHz(note).toDouble()
        val hammer = m.getValue("HAMMER").toDouble()
        val body = m.getValue("BODY").toDouble()
        val ring = m.getValue("RING").toDouble()
        val bloom = (bloomOverride ?: m.getValue("BLOOM")).toDouble()
        val halo = m.getValue("HALO").toDouble()
        val hardness = (.06 + .64 * hammer + .30 * sqrt(velocity.toDouble())).coerceIn(.06, 1.0)
        // Contact remains a fraction of the root period, so a long soft pulse cannot cancel it.
        val contact = min(.48 / hz, (.00042 + .0042 * (1 - hardness).pow(1.7)) * (261.6256 / hz).pow(.2))
            .coerceAtLeast(.00016)
        val contactFrames = (contact * INTERNAL_RATE).roundToInt().coerceAtLeast(8)
        val stiffness = (.00016 * (hz / 261.6256).pow(.75)).coerceIn(.000035, .00065)
        val rootT60 = ((.85 + 6.6 * ring.pow(1.35)) * (261.6256 / hz).pow(.19)).coerceIn(.65, 7.8)
        val haloT60 = if (haloEnabled && halo > 0.0) 1.4 + 3.8 * halo + rootT60 * .20 else 0.0
        val bodyT60 = if (bodyEnabled && body > 0.0) .62 + .16 * body else 0.0
        // The halo has its own stored tail. Budgeting only from the struck string would sever
        // that tail at low RING/high HALO. Explicit diagnostic cuts remain deliberately finite.
        val naturalT60 = max(rootT60, max(haloT60, bodyT60))
        val duration = seconds?.toDouble() ?: (naturalT60 + .30 + .10 * bloom).coerceIn(1.0, MAX_SECONDS.toDouble())
        val frames = (duration * RATE).roundToInt()
        val random = Random(Dsp.seedFor("OVEN", MODEL_VERSION, voice, note, "strings"))
        val companionCents = 1.6 + random.nextDouble() * 1.5
        val stringBuilder = ArrayList<Mode>()
        var count = 0
        // 24–48 candidate partials, pruned where their actual stretched frequency exceeds bandwidth.
        val candidates = (BANDWIDTH / hz).toInt().coerceIn(24, 48)
        for (n in 1..candidates) {
            val f = hz * ratioFor(n, stiffness)
            if (f >= BANDWIDTH) break
            count++
            val formant = 1.0 + .24 * exp(-.5 * ((n - 4.0) / 1.45).pow(2))
            // A hard touch transfers proportionally more work to bending harmonics, rather
            // than merely scaling the whole bank. This remains separate from HAMMER's saved
            // contact character. BODY's spectral loading acts on those modes before output,
            // where a low-pass alone cannot darken a fundamental-heavy soft strike audibly.
            val upperTouch = if (n == 1) 1.0 else (.20 + .80 * hardness) *
                (.20 + 1.35 * velocity.toDouble().pow(.75)) * n.toDouble().pow(-.50 * body) *
                exp(-n * (.028 + .060 * (1 - hardness)))
            val gain = formant * upperTouch / n.toDouble().pow(.92) *
                (1.0 - (f / BANDWIDTH).pow(6)).coerceAtLeast(0.0)
            val t60 = rootT60 / n.toDouble().pow(.36 + .10 * body)
            val variation = if (n == 1) 1.0 else .975 + .05 * random.nextDouble()
            stringBuilder.add(Mode(f, t60 * variation, gain * if (n <= 16) .76 else 1.0))
            if (n <= 16) {
                val cents = companionCents * (.84 + .32 * random.nextDouble())
                stringBuilder.add(Mode(f * 2.0.pow(cents / 1200.0), t60 * (.92 + .05 * random.nextDouble()), gain * .24))
            }
        }
        val strings = stringBuilder.toTypedArray()
        val bodyModes = listOf(142.0, 287.0, 463.0, 731.0, 1169.0).mapIndexed { i, f ->
            Mode(f, .22 + .10 * i + .16 * body, .38 / (1.0 + .35 * i))
        }.toTypedArray()
        val haloModes = listOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0).filter { hz * it < BANDWIDTH }.map { ratio ->
            Mode(hz * ratio, (1.4 + 3.8 * halo + rootT60 * .20) / ratio.pow(.22), .38 / ratio.pow(.65))
        }.toTypedArray()
        val excitation = DoubleArray(contactFrames) { i ->
            (1.0 - cos(TAU * i / (contactFrames - 1))) / contactFrames
        }
        val noise = Dsp.Noise(Dsp.seedFor("OVEN", MODEL_VERSION, voice, note, "hammer"))
        val raw = FloatArray(frames * Dsp.OVERSAMPLE)
        val impulse = velocity.toDouble().pow(.72) * .18
        var low = 0.0
        var noiseLow = 0.0
        var bodyPeak = 0.0
        var haloPeak = 0.0
        val bodyCutoff = (14_000.0 * (1 - body) + 2200.0 * body).coerceAtLeast(hz * 1.8).coerceAtMost(16_000.0)
        val lowCoefficient = 1.0 - exp(-TAU * bodyCutoff / INTERNAL_RATE)
        val noiseCoefficient = 1.0 - exp(-TAU * (2200 + 8500 * hardness) / INTERNAL_RATE)
        val bloomSeconds = .0002 + .38 * bloom * bloom
        for (i in raw.indices) {
            if (i % 8192 == 0) stopIfUnwanted()
            val t = i.toDouble() / INTERNAL_RATE
            val force = if (i < contactFrames) excitation[i] * impulse else 0.0
            var string = 0.0
            for (mode in strings) string += mode.tick(force)
            noiseLow += noiseCoefficient * (noise.next() - noiseLow)
            val hammerAir = if (t < .055) noiseLow * impulse * .030 * hardness * hardness * exp(-t / .006) else 0.0
            var iron = 0.0
            if (bodyEnabled) {
                for (mode in bodyModes) iron += mode.tick(string * mode.continuousPort)
                low += lowCoefficient * (string - low)
            } else low = string
            var sympathy = 0.0
            if (haloEnabled && halo > 0.0) for (mode in haloModes) sympathy += mode.tick(string * mode.continuousPort)
            iron *= if (bodyEnabled) .65 * body else 0.0
            sympathy *= if (haloEnabled) .65 * halo else 0.0
            bodyPeak = max(bodyPeak, abs(iron))
            haloPeak = max(haloPeak, abs(sympathy))
            val arrival = 1.0 - exp(-t / bloomSeconds)
            // The bounded end fade is disclosed truncation protection, never an infinite-sustain loop.
            val remaining = duration - t
            val end = if (remaining >= .10) 1.0 else (remaining / .10).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
            raw[i] = ((low + iron + sympathy + hammerAir) * arrival * end).toFloat()
        }
        stopIfUnwanted()
        val out = Dsp.decimate(raw, RATE)
        stopIfUnwanted()
        val peak = out.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
        check(out.all { it.isFinite() } && peak < 2.0) { "OVEN produced invalid or excessive raw state: $peak" }
        return Probe(out, Diagnostics(hz, stiffness, count, contactFrames.toDouble() / INTERNAL_RATE,
            rootT60, peak, bodyPeak, haloPeak, duration, haloT60))
    }

    fun render(voice: OvenVoice, macros: Map<String, Float> = emptyMap(), midi: Int? = null,
        velocity: Float = 1f): Snip {
        val out = probe(voice, macros, midi, velocity).samples
        if (velocity > 0f) Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.35f))
        Dsp.fadeTail(out)
        stopIfUnwanted()
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("OVEN render no longer wanted")
    }
}
