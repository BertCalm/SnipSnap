package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

enum class LibrationVoice { GATHER, TWINS, DEPARTURE }

/** Finite instrument. Trajectories control voicing; they never receive audio feedback. */
object Libration {
    const val MODEL_VERSION = 3
    const val MIN_MIDI = 36
    const val MAX_MIDI = 84
    const val CONTROL_RATE = 500
    const val FAMILY_VOICES = 16
    const val PITCH_CEILING_CENTS = 12.0
    const val MIN_SOURCE_SECONDS = .2
    const val MAX_SOURCE_SECONDS = 8.0
    private const val TABLE_SIZE = 32768
    private val sine = DoubleArray(TABLE_SIZE + 1) { sin(2 * PI * it / TABLE_SIZE) }

    fun macrosFor(voice: LibrationVoice): List<MacroSpec> {
        val values = when (voice) {
            LibrationVoice.GATHER -> floatArrayOf(0f, .48f, .12f, .55f, .40f)
            LibrationVoice.TWINS -> floatArrayOf(1f, .40f, .14f, .95f, .53f)
            LibrationVoice.DEPARTURE -> floatArrayOf(.5f, .52f, .72f, .78f, .66f)
        }
        return listOf("REGION", "WANDER", "NUDGE", "SWARM", "RESONANCE").mapIndexed { i, name ->
            MacroSpec(name, values[i], neutral = .5f)
        } + MacroSpec("HOLD", 0f, neutral = 0f)
    }
    fun defaults(voice: LibrationVoice) = macrosFor(voice).associate { it.name to it.default }
    fun settled(voice: LibrationVoice, macros: Map<String, Float>): Map<String, Float> {
        val values = defaults(voice).toMutableMap()
        for ((name, value) in macros) {
            require(name in values) { "unknown LIBRATION macro $name" }
            require(value.isFinite()) { "non-finite LIBRATION macro $name" }
            values[name] = value.coerceIn(0f, 1f)
        }
        require(values.getValue("HOLD") == 0f) { "LIBRATION is finite; sustained HOLD is not implemented" }
        return values
    }
    fun scramble(voice: LibrationVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(defaults(voice) + near?.macros.orEmpty(), temperature, random) + ("HOLD" to 0f)
    fun drumClassFor(voice: LibrationVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        settled(voice, macros)
        return DrumClass.TONAL
    }

    internal enum class Ablation { FULL, FROZEN, PITCH_ONLY, NO_MEMORY, NO_PITCH, ROOT_ONLY }
    internal data class Options(val seconds: Double = 6.0, val rate: Int = CONTROL_RATE,
        val ablation: Ablation = Ablation.FULL)
    internal data class Particle(val family: LibrationTrajectory.Family, val id: Int, val glass: Boolean,
        val weight: Double, val path: LibrationTrajectory.Path, val descriptors: Array<DoubleArray>)
    internal data class PopulationKey(val model: Int, val seconds: Double, val rate: Int, val physics: List<Float>)
    internal data class Population(val particles: List<Particle>, val rate: Int, val seconds: Double, val key: PopulationKey)
    internal data class Diagnostics(val rawPeak: Double, val rawRms: Double, val maxJacobiDrift: Double,
        val terminatedParticles: Int, val activeVoices: Int, val totalWeight: Double,
        val maxPitchDeviation: Double, val renderMilliseconds: Double, val solverWork: Int)
    internal data class Probe(val raw: Snip, val samples: Snip, val population: Population, val diagnostics: Diagnostics)

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    private fun wave(p: Double): Double {
        val index = (p - floor(p)) * TABLE_SIZE
        val i = index.toInt()
        return sine[i] + (sine[i + 1] - sine[i]) * (index - i)
    }
    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Libration render cancelled")
    }

    internal fun populationKey(voice: LibrationVoice, macros: Map<String, Float>, options: Options,
        model: Int = MODEL_VERSION): PopulationKey {
        val m = settled(voice, macros)
        return PopulationKey(model, options.seconds, options.rate,
            listOf("REGION", "WANDER", "NUDGE", "SWARM").map { m.getValue(it) })
    }

    internal fun population(voice: LibrationVoice, macros: Map<String, Float>, options: Options,
        model: Int = MODEL_VERSION): Population {
        require(model in 1..MODEL_VERSION)
        val m = settled(voice, macros)
        require(options.seconds.isFinite() && options.seconds in MIN_SOURCE_SECONDS..MAX_SOURCE_SECONDS)
        require(options.rate in 100..1000)
        val region = m.getValue("REGION").toDouble() * 4
        val lower = min(4, floor(region).toInt())
        val mix = region - lower
        val banks = if (lower == 4 || mix < 1e-12) listOf(lower to 1.0)
            else listOf(lower to cos(mix * PI / 2), lower + 1 to sin(mix * PI / 2))
        val desired = 1 + (FAMILY_VOICES - 1) * m.getValue("SWARM")
        val particles = mutableListOf<Particle>()
        for ((index, bankWeight) in banks) for (id in 0 until FAMILY_VOICES) {
            val activation = (desired - id).coerceIn(0f, 1f).toDouble()
            if (activation <= 0 || bankWeight <= 1e-12) continue
            var family = LibrationTrajectory.Family.entries[index]
            val opposite = (family == LibrationTrajectory.Family.L4 || family == LibrationTrajectory.Family.L5) && id % 4 == 3
            if (opposite) family = if (family == LibrationTrajectory.Family.L4) LibrationTrajectory.Family.L5 else LibrationTrajectory.Family.L4
            val glass = family == LibrationTrajectory.Family.L5 ||
                (family !in listOf(LibrationTrajectory.Family.L4, LibrationTrajectory.Family.L5) && id % 3 == 2)
            val initial = LibrationTrajectory.initial(family, id, m.getValue("WANDER").toDouble(), m.getValue("NUDGE").toDouble(), model)
            val path = LibrationTrajectory.integrate(family, id, initial, options.seconds, options.rate)
            val weight = activation * bankWeight * if (opposite) .58 else 1.0
            particles += Particle(family, id, glass, weight, path, descriptors(path))
        }
        return Population(particles, options.rate, options.seconds, populationKey(voice, m, options, model))
    }

    /** cents, signed spectral balance, brightness, excitation rate, excitation gate, local turning. */
    private fun descriptors(path: LibrationTrajectory.Path): Array<DoubleArray> {
        val e = LibrationTrajectory.equilibrium(path.family)
        val triangle = path.family == LibrationTrajectory.Family.L4 || path.family == LibrationTrajectory.Family.L5
        val neighborhood = LibrationTrajectory.templates.getValue(path.family).neighborhood
        val scale = if (triangle) .0045 else neighborhood * .35
        val velocityScale = if (triangle) .003 else if (path.family == LibrationTrajectory.Family.L3) .015 else .08
        var maximumDistance = 0.0
        return Array(path.states.size) { i ->
            val s = path.states[i]
            val d = LibrationTrajectory.distance(s, e)
            maximumDistance = max(maximumDistance, d)
            val x = tanh((s.x - e.x) / scale); val y = tanh((s.y - e.y) / scale)
            val speed = hypot(s.vx, s.vy)
            val acceleration = LibrationTrajectory.acceleration(s)
            val turn = tanh(.16 * (s.vx * acceleration.second - s.vy * acceleration.first) / (speed * speed + 1e-12))
            val bright = (.38 + .38 * tanh(speed / velocityScale) + .24 * y).coerceIn(0.0, 1.0)
            val leaving = if (triangle) 1.0 / (1 + (d / neighborhood).pow(6))
                else 1.0 / (1 + (maximumDistance / neighborhood).pow(4))
            val termination = if (i < path.report.validSamples) 1.0 else
                1 - smooth((i - path.report.validSamples + 1).toDouble() / (path.rate * .10))
            doubleArrayOf(PITCH_CEILING_CENTS * (.7 * x + .3 * y), x, bright,
                1.1 + 4.5 * tanh(speed / velocityScale) + 1.4 * abs(turn), leaving * termination, turn)
        }
    }

    internal fun probe(voice: LibrationVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 60,
        velocity: Float = .8f, options: Options = Options(), cached: Population? = null,
        model: Int = MODEL_VERSION): Probe {
        val began = System.nanoTime()
        cancelled()
        require(midi in MIN_MIDI..MAX_MIDI)
        require(velocity.isFinite() && velocity in 0f..1f)
        require(model in 1..MODEL_VERSION)
        val m = settled(voice, macros)
        val population = cached ?: population(voice, m, options, model)
        require(population.key == populationKey(voice, m, options, model)) { "stale LIBRATION trajectory cache" }
        val particles = population.particles
        val hz = Keys.midiHz(midi).toDouble()
        val resonance = m.getValue("RESONANCE").toDouble()
        val tail = 1.2 + 4.2 * resonance
        val frames = ((options.seconds + tail) * Dsp.RATE).roundToInt()
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = FloatArray(frames * Dsp.OVERSAMPLE)
        val phases = DoubleArray(particles.size) { j ->
            Random(Dsp.seedFor("LIBRATION_PHASE", particles[j].family.name, particles[j].id)).nextDouble()
        }
        val pulsePhase = DoubleArray(particles.size) { j ->
            Random(Dsp.seedFor("LIBRATION_EXCITE", particles[j].family.name, particles[j].id)).nextDouble()
        }
        val envelope = DoubleArray(particles.size)
        val harmonics = IntArray(particles.size) { j -> if (particles[j].glass) 3 + particles[j].id % 4 else 1 + particles[j].id % 3 }
        val modes = (1..14).filter { it * hz < 17_000 }
        val bank = Modes.Bank(modes.size, rate)
        val t60 = if (model == 1) .20 * 30.0.pow(resonance) else .25 + 5.75 * resonance
        val participation = DoubleArray(modes.size) { j ->
            val h = modes[j].toDouble()
            val damping = 6.907755278982137 / (t60 / h.pow(.22))
            // Compensate the resonant gain for decay, so length is not an implicit gain knob.
            (if (model == 1) 1.0 else 2 * damping) / sqrt(h)
        }
        val modalOutput = DoubleArray(modes.size) { j -> (if (model == 1) .38 else .90) / modes[j].toDouble().pow(.7) }
        for (j in modes.indices) bank.tune(j, modes[j] * hz, t60 / modes[j].toDouble().pow(.22))
        val memorySend = if (model == 1) 7.0 * resonance else 1.6 * sqrt(resonance)
        // Touch pivots at the accepted .8 reference. The kick and root tuning do
        // not change. Models 1/2 retain their exact coefficients and gate law.
        val touch = if (model >= 3) (velocity - .8f).toDouble() else 0.0
        val touchSpectrum = 1 + .60 * touch
        val touchAttack = 1 - .55 * touch
        val touchRoot = 1 + .40 * touch
        val release = if (model >= 3) min(.65, options.seconds * .325) else .65
        val energyScale = .23 / sqrt(particles.sumOf { it.weight * it.weight }.coerceAtLeast(1e-12))
        val blockFrames = max(1, (rate.toDouble() / options.rate).roundToInt())
        val d = Array(particles.size) { DoubleArray(6) }
        val slope = Array(particles.size) { DoubleArray(6) }
        val frequency = DoubleArray(particles.size)
        val frequencyRatio = DoubleArray(particles.size)
        val partials = Array(particles.size) { DoubleArray(4) }
        val ampCoefficient = DoubleArray(particles.size)
        var rootPhase = 0.0; var peak = 0.0; var squares = 0.0; var maxCents = 0.0
        for (block in 0 until raw.size step blockFrames) {
            cancelled()
            val count = min(blockFrames, raw.size - block)
            val time = block.toDouble() / rate
            val position = time * options.rate
            val segment = floor(position).toInt().coerceAtMost(particles.first().descriptors.size - 2)
            val fraction = (position - segment).coerceIn(0.0, 1.0)
            for (j in particles.indices) {
                val p = particles[j]
                val a = p.descriptors[segment]; val b = p.descriptors[segment + 1]; val initial = p.descriptors[0]
                for (q in 0..5) {
                    val frozen = options.ablation == Ablation.FROZEN || options.ablation == Ablation.PITCH_ONLY && q != 0
                    d[j][q] = if (frozen) initial[q] else a[q] + fraction * (b[q] - a[q])
                    slope[j][q] = if (frozen || time >= options.seconds) 0.0 else (b[q] - a[q]) * options.rate / rate
                }
                if (options.ablation == Ablation.NO_PITCH) { d[j][0] = 0.0; slope[j][0] = 0.0 }
                maxCents = max(maxCents, abs(d[j][0]))
                frequency[j] = hz * harmonics[j] * 2.0.pow(d[j][0] / 1200)
                frequencyRatio[j] = 2.0.pow(slope[j][0] / 1200)
                val u = d[j][1]; val brightness = d[j][2]
                for (k in 0..3) {
                    val base = if (p.glass) doubleArrayOf(.70, .40, .20, .09)[k] else doubleArrayOf(1.0, .31, .10, .035)[k]
                    val redistribution = if (k < 2) 1 - .55 * u else 1 + .85 * u
                    val spectral = if (k == 0) 1.0 else (.30 + 1.4 * brightness) * touchSpectrum
                    val maxHz = hz * harmonics[j] * (k + 1) * 2.0.pow(PITCH_CEILING_CENTS / 1200)
                    partials[j][k] = base * redistribution * spectral * (1 - smooth((maxHz - 14_000) / 4_000))
                }
                val sum = partials[j].sum().coerceAtLeast(1e-12)
                for (k in 0..3) partials[j][k] /= sum
                val attack = if (p.glass) .0025 + .0035 * (1 - brightness) else .012 + .016 * (1 - brightness)
                ampCoefficient[j] = 1 - exp(-1 / (rate * attack * touchAttack))
            }
            for (offset in 0 until count) {
                val t = (block + offset).toDouble() / rate
                val opening = smooth(t / (if (voice == LibrationVoice.DEPARTURE) .018 else .035))
                val closing = 1 - smooth((t - options.seconds + release) / release)
                val supply = opening * closing
                val root = .19 * (1 - .12 * resonance) * velocity * supply *
                    (wave(rootPhase) + .15 * touchRoot * wave(2 * rootPhase) + .055 * touchRoot * wave(3 * rootPhase))
                var cloud = 0.0
                if (options.ablation != Ablation.ROOT_ONLY) for (j in particles.indices) {
                    val pulse = .5 + .5 * wave(pulsePhase[j] + .25)
                    val shape = if (particles[j].glass) pulse * pulse * pulse * pulse else pulse * pulse
                    val target = supply * d[j][4] * (.12 + .88 * shape)
                    envelope[j] += ampCoefficient[j] * (target - envelope[j])
                    var tone = 0.0
                    for (k in 0..3) tone += partials[j][k] * wave((k + 1) * phases[j])
                    cloud += tone * envelope[j] * particles[j].weight
                    phases[j] += frequency[j] / rate
                    if (phases[j] >= 1) phases[j] -= floor(phases[j])
                    pulsePhase[j] += d[j][3] / rate
                    if (pulsePhase[j] >= 1) pulsePhase[j] -= 1.0
                    frequency[j] *= frequencyRatio[j]
                    for (q in 1..5) d[j][q] += slope[j][q]
                }
                cloud *= energyScale * velocity * (1 - (if (model == 1) .10 else .35) * resonance)
                var memory = 0.0
                if (options.ablation != Ablation.NO_MEMORY && options.ablation != Ablation.ROOT_ONLY && resonance > 0) {
                    bank.step()
                    val rootSend = if (model == 1) .18 + .82 * exp(-t / .24) else .55 + .45 * exp(-t / .24)
                    val excitation = cloud + root * rootSend
                    bank.drive(participation, excitation * memorySend)
                    for (j in modes.indices) memory += modalOutput[j] * bank.velocity(j)
                }
                val sample = root + cloud + memory
                check(sample.isFinite()) { "LIBRATION non-finite audio" }
                raw[block + offset] = sample.toFloat()
                peak = max(peak, abs(sample)); squares += sample * sample
                rootPhase += hz / rate
                if (rootPhase >= 1) rootPhase -= 1.0
            }
        }
        cancelled()
        val output = Dsp.decimate(raw, Dsp.RATE)
        Dsp.fadeTail(output)
        val normalized = output.copyOf()
        Dsp.levelTo(normalized, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.toDouble().pow(.7).toFloat(), .94f)
        val diagnostics = Diagnostics(peak, sqrt(squares / raw.size),
            particles.maxOf { it.path.report.jacobiDrift }, particles.count { it.path.report.termination != "horizon" },
            particles.size, particles.sumOf { it.weight }, maxCents, (System.nanoTime() - began) / 1e6,
            particles.sumOf { it.path.report.work })
        return Probe(Snip(output, 1, Dsp.RATE), Snip(normalized, 1, Dsp.RATE), population, diagnostics)
    }

    fun render(voice: LibrationVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 60,
        velocity: Float = .8f, model: Int = MODEL_VERSION, sourceSeconds: Double = 6.0): Snip =
        probe(voice, macros, midi, velocity, Options(seconds = sourceSeconds), model = model).samples
}
