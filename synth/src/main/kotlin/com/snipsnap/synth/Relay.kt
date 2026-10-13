package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class RelayVoice { KEY, BASS, PLUCK, ORGAN, CHIME, FLOW }

/**
 * RELAY: one requested note passes between four original generated source roles. ORDER morphs
 * complete source-weight schedules on one phrase clock; PULSE reads a separate finite accent
 * roster. BLEND changes the width of the continuous handoffs. No sample or factory program data.
 *
 * All source phases run throughout the render. Additive, band-limited source tables run at 4x,
 * followed by two bounded nonresonant filter poles and the shared decimator/melodic level.
 * HOLD's top step exports only a repeating phrase. Its clock is fitted to whole periods of the
 * requested root and whole output frames, with less than a fractional-frame pitch adjustment;
 * the root is never rounded to an integer number of cycles in a fixed-duration loop. The exact
 * clock and tuning adjustment are exposed by [inspect]. A real preceding cycle supplies the
 * project-standard smoothstep wrap after bounded preroll.
 */
object Relay {
    const val TUNE_SEMITONES = 24
    const val LOOP_THRESHOLD = .99f
    const val SCRAMBLE_HOLD_CEILING = .95f
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CONTROL_BLOCK = 64
    private const val TABLE_SIZE = 4096
    private const val OUTPUT_GAIN = .42

    private val starting = arrayOf(
        floatArrayOf(0f, .20f, .55f, .60f, .55f),
        floatArrayOf(0f, .42f, .50f, .45f, .40f),
        floatArrayOf(0f, .46f, .42f, .68f, .35f),
        floatArrayOf(0f, .10f, .70f, .60f, .80f),
        floatArrayOf(0f, .18f, .55f, .72f, .70f),
        floatArrayOf(0f, .08f, .90f, .42f, .95f),
    )

    fun macrosFor(voice: RelayVoice): List<MacroSpec> {
        val d = starting[voice.ordinal]
        return listOf(MacroSpec("TUNE", .5f, neutral = .5f),
            MacroSpec("ORDER", d[0], neutral = .30f), MacroSpec("PULSE", d[1], neutral = .30f),
            MacroSpec("BLEND", d[2], neutral = .50f), MacroSpec("COLOR", d[3], neutral = .50f),
            MacroSpec("LENGTH", d[4], neutral = .50f), MacroSpec("HOLD", 0f, neutral = 0f))
    }

    fun defaults(voice: RelayVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    fun rootMidi(voice: RelayVoice): Int = if (voice == RelayVoice.BASS) 24 else 48
    fun midiFor(voice: RelayVoice, tune: Float): Int = rootMidi(voice) +
        (tune.coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(voice: RelayVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD
    fun drumClassFor(voice: RelayVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    fun scramble(voice: RelayVoice, random: Random = Random.Default, temperature: Float = .35f,
                 near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        return Dsp.scrambleNear(base + (near?.macros?.filterKeys { it in base } ?: emptyMap()),
            temperature, random).toMutableMap().apply {
            this["HOLD"] = getValue("HOLD").coerceAtMost(SCRAMBLE_HOLD_CEILING)
        }
    }

    internal fun settled(voice: RelayVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).toMutableMap().apply {
            for ((key, value) in macros) {
                require(key in this) { "unknown RELAY macro: $key" }
                require(value.isFinite() && value in 0f..1f) { "RELAY $key must be finite and in 0..1" }
                this[key] = value
            }
        }

    /** Six dry identities are proposals for listening; preset expansion follows sonic approval. */
    fun phraseFor(voice: RelayVoice): RelayPhrase {
        val gains = when (voice) {
            RelayVoice.KEY -> floatArrayOf(1f, .90f, .72f, .60f)
            RelayVoice.BASS -> floatArrayOf(1f, .90f, .32f, .60f)
            RelayVoice.PLUCK -> floatArrayOf(1f, .40f, .85f, .38f)
            RelayVoice.ORGAN -> floatArrayOf(.50f, 1f, .45f, .70f)
            RelayVoice.CHIME -> floatArrayOf(.55f, .50f, 1f, .50f)
            RelayVoice.FLOW -> floatArrayOf(.40f, .60f, .50f, 1f)
        }
        val decays = when (voice) {
            RelayVoice.KEY -> floatArrayOf(.16f, 1.00f, .50f, .65f)
            // A bass string rings through its picked answer; the pluck voice remains short.
            RelayVoice.BASS -> floatArrayOf(1.00f, 1.20f, .25f, .55f)
            RelayVoice.PLUCK -> floatArrayOf(.07f, .55f, .22f, .42f)
            RelayVoice.ORGAN -> floatArrayOf(.30f, 2.40f, .45f, 1.30f)
            RelayVoice.CHIME -> floatArrayOf(.16f, .80f, 2.40f, .70f)
            RelayVoice.FLOW -> floatArrayOf(.70f, 1.80f, 1.40f, 2.20f)
        }
        val brightness = when (voice) {
            RelayVoice.KEY -> floatArrayOf(.78f, .68f, .72f, .24f)
            RelayVoice.BASS -> floatArrayOf(.70f, .46f, .45f, .18f)
            RelayVoice.PLUCK -> floatArrayOf(.96f, .60f, .82f, .30f)
            RelayVoice.ORGAN -> floatArrayOf(.58f, .94f, .62f, .40f)
            RelayVoice.CHIME -> floatArrayOf(.78f, .50f, 1f, .26f)
            RelayVoice.FLOW -> floatArrayOf(.48f, .60f, .76f, .52f)
        }
        val order = when (voice) {
            RelayVoice.KEY -> listOf(0, 1, 3, 2)
            RelayVoice.BASS -> listOf(1, 0, 3, 2)
            RelayVoice.PLUCK -> listOf(0, 2, 3, 1)
            RelayVoice.ORGAN -> listOf(1, 3, 0, 2)
            RelayVoice.CHIME -> listOf(2, 3, 0, 1)
            RelayVoice.FLOW -> listOf(3, 1, 2, 0)
        }
        val dwells = when (voice) {
            RelayVoice.KEY -> floatArrayOf(.65f, 1.40f, .65f, 1.30f)
            RelayVoice.BASS -> floatArrayOf(1.15f, .60f, 1.40f, .85f)
            RelayVoice.PLUCK -> floatArrayOf(1.35f, .55f, .75f, 1.35f)
            RelayVoice.ORGAN -> floatArrayOf(2.00f, .50f, .55f, .95f)
            RelayVoice.CHIME -> floatArrayOf(2.00f, .75f, .50f, .75f)
            RelayVoice.FLOW -> floatArrayOf(1.25f, .75f, .75f, 1.25f)
        }
        val transition = when (voice) {
            RelayVoice.KEY -> .18f
            RelayVoice.BASS -> .12f
            RelayVoice.PLUCK -> .10f
            RelayVoice.ORGAN -> .28f
            RelayVoice.CHIME -> .14f
            RelayVoice.FLOW -> .44f
        }
        val alternates = when (voice) {
            RelayVoice.KEY -> listOf(listOf(0, 2, 3, 1), listOf(3, 1, 0, 2), listOf(2, 0, 1, 3))
            RelayVoice.BASS -> listOf(listOf(0, 3, 2, 1), listOf(3, 1, 2, 0), listOf(2, 0, 1, 3))
            RelayVoice.PLUCK -> listOf(listOf(2, 0, 1, 3), listOf(3, 0, 2, 1), listOf(1, 3, 0, 2))
            RelayVoice.ORGAN -> listOf(listOf(1, 2, 3, 0), listOf(0, 3, 2, 1), listOf(2, 0, 1, 3))
            RelayVoice.CHIME -> listOf(listOf(0, 2, 3, 1), listOf(2, 1, 0, 3), listOf(1, 3, 2, 0))
            RelayVoice.FLOW -> listOf(listOf(1, 3, 0, 2), listOf(2, 0, 3, 1), listOf(0, 2, 1, 3))
        }
        val accents = when (voice) {
            RelayVoice.KEY -> listOf(0f to 1f, .5f to .25f, 1.75f to .62f, 2.75f to .40f)
            RelayVoice.BASS -> listOf(0f to 1f, .5f to .40f, 1f to .70f, 1.75f to .30f,
                2f to .90f, 2.75f to .65f, 3.5f to .72f)
            RelayVoice.PLUCK -> listOf(0f to 1f, .75f to .75f, 1.25f to .30f,
                2f to .85f, 2.5f to .40f, 3.25f to .60f)
            RelayVoice.ORGAN -> listOf(0f to .70f, 2f to .50f, 3.1f to .25f)
            RelayVoice.CHIME -> listOf(0f to .40f, 1.2f to .30f, 2f to .95f,
                2.75f to .65f, 3.5f to .40f)
            RelayVoice.FLOW -> listOf(0f to .50f, 1.8f to .30f, 3.2f to .20f)
        }
        val tempo = when (voice) {
            RelayVoice.KEY -> 112f
            RelayVoice.BASS -> 126f
            RelayVoice.PLUCK -> 145f
            RelayVoice.ORGAN -> 88f
            RelayVoice.CHIME -> 104f
            RelayVoice.FLOW -> 72f
        }
        return RelayPhrase(RelayRole.entries.mapIndexed { i, role ->
            RelaySource(role, gains[i], brightness[i], decays[i])
        }, order.mapIndexed { i, source -> RelaySlot(source, dwells[i], transition) },
            listOf(order) + alternates, accents.map { RelayAccent(it.first, it.second) }, tempo)
    }

    internal data class AccentEvent(val beat: Double, val timeSeconds: Double, val strength: Double)
    internal data class WeightFrame(val timeSeconds: Double, val weights: List<Double>)
    internal data class Report(
        val snip: Snip,
        /** Oversampled generated mix before conditioning or normalization. */
        val raw: FloatArray,
        /** Real preceding finished cycle at the same gain, for Keys.seamError. */
        val previousCycle: FloatArray,
        val seamError: Double,
        val loop: Boolean,
        val requestedHz: Double,
        val actualHz: Double,
        val phraseSeconds: Double,
        val gateSeconds: Double,
        val rawPeak: Double,
        val accents: List<AccentEvent>,
        /** Actual output trajectory: finite handoffs stop at the gate; HOLD shows one cycle. */
        val weights: List<WeightFrame>,
    )

    private data class Plan(val held: Boolean, val frames: Int, val phraseFrames: Int,
                            val phraseSeconds: Double, val gateSeconds: Double, val tailSeconds: Double,
                            val requestedHz: Double, val actualHz: Double)

    private fun plan(voice: RelayVoice, m: Map<String, Float>, phrase: RelayPhrase): Plan {
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val length = m.getValue("LENGTH").toDouble()
        if (isLoop(m.getValue("HOLD"))) {
            // Fit the phrase to the note first, then to output frames: sub-frame root error,
            // rather than up to half a root period of detuning at the lowest bass notes.
            val cycles = (phrase.phraseSeconds * hz).roundToInt().coerceAtLeast(4)
            val frames = (cycles * Dsp.RATE / hz).roundToInt()
            val seconds = frames.toDouble() / Dsp.RATE
            return Plan(true, frames, frames, seconds, seconds * (.30 + .70 * length), 0.0,
                hz, cycles * Dsp.RATE.toDouble() / frames)
        }
        val gate = phrase.phraseSeconds * (.30 + .70 * length)
        // A low note must retain a pitched release through compact custom clocks. The
        // classifier's bass guard needs more than 500 ms of audible decay; a 300 ms tail
        // made the supported short C1 phrase a kick and its C3 chime handoff a tom.
        // This is an actual raised-cosine tonal release, never silence added for length.
        val tail = 1.05 + .35 * length
        return Plan(false, ((gate + tail) * Dsp.RATE).roundToInt(),
            (phrase.phraseSeconds * Dsp.RATE).roundToInt(), phrase.phraseSeconds,
            gate, tail, hz, hz)
    }

    fun renderFrames(macros: Map<String, Float>, voice: RelayVoice,
                     phrase: RelayPhrase = phraseFor(voice)): Int = plan(voice, settled(voice, macros), phrase).frames

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    /** Derived before synthesis; source order morphs while excitation keeps one note history. */
    private class Schedule(val phrase: RelayPhrase, order: Float, blend: Float,
                           val seconds: Double, val periodic: Boolean,
                           private val historyLength: Double = .5) {
        private val starts = DoubleArray(phrase.slots.size)
        private val scale = seconds / phrase.phraseBeats
        private val width = 1.0 + 4.0 * blend.toDouble().pow(1.35)
        private val dwellOverlap = .18 + .90 * smooth(blend.toDouble())
        private val position = order.toDouble() * (phrase.orderRoster.size - 1)
        private val lo = position.toInt().coerceAtMost(phrase.orderRoster.lastIndex)
        private val hi = (lo + 1).coerceAtMost(phrase.orderRoster.lastIndex)
        private val morph = smooth(position - lo)
        // Valid short phrases can have broad windows longer than one phrase. Include every
        // overlapping copy, so both gains and source envelope histories close at the modulo
        // boundary. The serialized dwell/transition budgets bound this radius to 12 cycles.
        private val cycleRadius = if (periodic) ceil(phrase.slots.indices.maxOf {
            (phrase.slots[it].beats * scale + transitionFor(it)) / seconds
        }).toInt() + 1 else 0

        init {
            var time = 0.0
            for (i in starts.indices) {
                starts[i] = time
                time += phrase.slots[i].beats * scale
            }
        }

        private fun transitionFor(slot: Int): Double {
            val slots = phrase.slots
            val dwell = slots[slot].beats * scale
            val before = slots[(slot + slots.size - 1) % slots.size].beats * scale
            val after = slots[(slot + 1) % slots.size].beats * scale
            // The serialized transition still sets the character, but normal clocks cannot
            // turn a new role on in a few milliseconds. BLEND widens both authored and
            // dwell-relative overlap; several distinct colors can remain in the same note.
            return max(.008, max(slots[slot].transitionBeats * scale * width,
                min(dwell, min(before, after)) * dwellOverlap))
        }

        /** weights sum to one; excitation histories remain attached to their original source. */
        fun at(timeSeconds: Double, weights: DoubleArray, envelopes: DoubleArray,
               releaseAge: Double = 0.0) {
            weights.fill(0.0); envelopes.fill(0.0)
            val t = if (periodic) ((timeSeconds % seconds) + seconds) % seconds
                else timeSeconds.coerceIn(0.0, seconds - 1e-9)
            addOrder(t, lo, 1.0 - morph, weights)
            if (hi != lo && morph > 0.0) addOrder(t, hi, morph, weights)
            // Source changes reveal continuously running contours; a slot is never a new
            // excitation. The finite gesture ages from note-on through its terminal release.
            // A held gesture opens and closes smoothly once per phrase, with zero slope at
            // the wrap. LENGTH adjusts its damping, not its tempo or number of articulations.
            val age = if (periodic) .5 * seconds * (1.0 - cos(2.0 * PI * t / seconds)) /
                (.55 + .90 * historyLength) else max(0.0, timeSeconds) + releaseAge
            for (source in phrase.sources.indices) {
                val definition = phrase.sources[source]
                val transient = exp(-age / definition.decaySeconds)
                val envelope = when (definition.role) {
                    RelayRole.PLUCK -> .30 + .70 * transient
                    RelayRole.BELL -> .38 + .62 * transient
                    RelayRole.ORGAN -> .86 + .14 * smooth(age / .035)
                    RelayRole.SOFT -> .16 + .84 * smooth(age / (.12 + .32 * definition.decaySeconds))
                }
                envelopes[source] = weights[source] * envelope
            }
        }

        private fun addOrder(t: Double, roster: Int, mix: Double, weights: DoubleArray) {
            val contributions = DoubleArray(starts.size)
            for (slot in starts.indices) {
                val start = starts[slot]
                val dwell = phrase.slots[slot].beats * scale
                val transition = transitionFor(slot)
                // Broad windows may overlap three or four roles. Unity-sum normalization below
                // also handles correlated roots; equal-power weights would make those swell.
                for (cycle in -cycleRadius..cycleRadius) {
                    val age = t - start - cycle * seconds
                    val a = smooth((age + transition) / transition)
                    val b = smooth((dwell + transition - age) / transition)
                    val contribution = a * b
                    contributions[slot] += contribution
                }
            }
            val total = contributions.sum().coerceAtLeast(1e-12)
            for (slot in starts.indices) {
                val source = phrase.orderRoster[roster][slot]
                val w = mix * contributions[slot] / total
                weights[source] += w
            }
        }
    }

    /** Economical diagnostic surface for schedule continuity and clock independence. */
    internal fun weightSchedule(phrase: RelayPhrase, order: Float, blend: Float,
                                periodic: Boolean = false): List<WeightFrame> {
        require(order.isFinite() && order in 0f..1f && blend.isFinite() && blend in 0f..1f)
        val schedule = Schedule(phrase.snapshot(), order, blend, phrase.phraseSeconds, periodic)
        return weightFrames(schedule)
    }

    private fun weightFrames(schedule: Schedule, durationSeconds: Double = schedule.seconds,
                             gateSeconds: Double = durationSeconds): List<WeightFrame> {
        val w = DoubleArray(schedule.phrase.sources.size)
        val e = DoubleArray(w.size)
        val count = ceil(durationSeconds / .01).toInt()
        return (0..count).map { index ->
            val t = min(durationSeconds, index * .01)
            schedule.at(min(t, gateSeconds), w, e)
            WeightFrame(t, w.toList())
        }
    }

    /**
     * Four deliberately different original spectra. Each has a present, largest single root
     * partial, but the upper registers carry enough energy to be heard as source handoffs.
     * The first audition's 86–93% root-energy mixtures sounded like one sine oscillator.
     */
    private fun table(source: RelaySource, color: Double, hz: Double, seed: Int): FloatArray {
        val c = (color + .42 * (source.brightness - .5)).coerceIn(0.0, 1.0)
        val amplitudes = when (source.role) {
            // Muted string: a picked, dense harmonic burst that can lose its upper register
            // without losing the root. The high and low banks have the same continuous phase.
            RelayRole.PLUCK -> DoubleArray(25) { harmonic ->
                when (harmonic) {
                    0 -> 0.0
                    1 -> 1.0
                    else -> harmonic.toDouble().pow(-1.35 + .60 * c) * (.75 + .25 * c) *
                        (.78 + .22 * cos(harmonic * .83)) + when (harmonic) {
                            // The string's low overtones support the picked note after the
                            // dense upper register damps, rather than collapsing to a sine.
                            2 -> .12 + .14 * c
                            3 -> .08 + .12 * c
                            else -> 0.0
                        }
                }
            }
            // Drawbars: strong octave registers distinguish this sustained body from both
            // the string's decaying saw-like burst and the bell's predominantly odd detail.
            RelayRole.ORGAN -> doubleArrayOf(0.0, 1.0, .55 + .40 * c, .18 + .15 * c,
                .20 + .55 * c, 0.0, .08 + .32 * c, 0.0, .04 + .20 * c, 0.0,
                .025 + .12 * c, 0.0, .015 + .08 * c)
            // Integer-ratio FM-like bell. Bright odd overtones ring above a supported root,
            // then damp independently through the scheduled source-excitation contour.
            RelayRole.BELL -> doubleArrayOf(0.0, 1.0, .06, .48 + .40 * c,
                0.0, .18 + .40 * c, 0.0, .07 + .23 * c, 0.0, .025 + .125 * c,
                0.0, .01 + .07 * c)
            // Slow rounded triangle: sparse, hollow and soft; the source's onset swells.
            RelayRole.SOFT -> doubleArrayOf(0.0, 1.0, 0.0, -.16 - .20 * c,
                0.0, .035 + .105 * c, 0.0, -.01 - .055 * c, 0.0, .025 * c)
        }
        for (harmonic in amplitudes.indices) {
            val frequency = harmonic * hz
            // Fade before output Nyquist; source generation contains no unrestricted FM/noise.
            amplitudes[harmonic] *= (1.0 - smooth((frequency - 10_000.0) / 7_000.0))
            // The densely populated string spectrum darkens toward the top of the keyboard.
            // Keep its pitched low overtones instead of letting dozens of high harmonics
            // dominate a short high note. Sparse drawbars and bell registers keep their shape.
            if (source.role == RelayRole.PLUCK) {
                amplitudes[harmonic] *= (1.0 - smooth((frequency - 2_000.0) / 5_000.0))
            }
        }
        val rms = sqrt(amplitudes.sumOf { it * it } / 2.0).coerceAtLeast(.001)
        val phases = Dsp.phases(amplitudes.size, seed)
        val output = FloatArray(TABLE_SIZE + 1) { sample ->
            val angle = 2.0 * PI * (sample % TABLE_SIZE) / TABLE_SIZE
            var value = 0.0
            for (harmonic in 1 until amplitudes.size) {
                // Shared root phase prevents silent cancellation in broad blends. Upper partial
                // phases are seeded per generated source, stable across all macro settings.
                val offset = if (harmonic == 1) 0.0 else when (source.role) {
                    // A balanced string waveform has crests on both half-cycles, avoiding
                    // a narrow, one-sided harmonic spike at the lowest supported notes.
                    RelayRole.PLUCK -> .35 * sin(phases[harmonic])
                    RelayRole.ORGAN -> .08 * sin(phases[harmonic])
                    RelayRole.BELL -> PI / 3.0 + .30 * sin(phases[harmonic])
                    RelayRole.SOFT -> 0.0
                }
                value += amplitudes[harmonic] * sin(harmonic * angle + offset)
            }
            (.45 * value / rms).toFloat()
        }
        // Static bank calibration, before synthesis. Finite coefficient sums have no feedback
        // or nonlinear stage; peak trimming only bounds unusually coherent dense tables.
        val peak = output.maxOf { abs(it) }
        if (peak > 1.5f) for (index in output.indices) output[index] *= 1.5f / peak
        return output
    }

    private fun sourceExcitation(role: RelayRole, envelope: Double): Double = when (role) {
        RelayRole.PLUCK -> ((envelope - .30) / .70).coerceIn(0.0, 1.0)
        RelayRole.BELL -> ((envelope - .38) / .62).coerceIn(0.0, 1.0)
        RelayRole.ORGAN -> .88
        RelayRole.SOFT -> ((envelope - .16) / .84).coerceIn(0.0, 1.0) * .55
    }

    private fun read(table: FloatArray, phase: Double): Double {
        val position = phase * TABLE_SIZE
        val index = position.toInt()
        val fraction = position - index
        return table[index] + fraction * (table[index + 1] - table[index])
    }

    // SynthScreen previews run in runInterruptible: a newer request interrupts this thread,
    // and the frame loop and the passes poll it so a stale render stops instead of finishing unwanted.
    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("RELAY render no longer wanted")
    }

    fun render(voice: RelayVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
               normalize: Boolean = true, phrase: RelayPhrase = phraseFor(voice)): Snip =
        inspect(voice, macros, velocity, normalize, phrase).snip

    internal fun inspect(voice: RelayVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
                         normalize: Boolean = true, phrase: RelayPhrase = phraseFor(voice)): Report {
        require(velocity.isFinite() && velocity in 0f..1f) { "RELAY velocity must be finite and in 0..1" }
        val recipe = phrase.snapshot()
        val m = settled(voice, macros)
        val p = plan(voice, m, recipe)
        val pulse = m.getValue("PULSE").toDouble()
        // Most of the knob is phrasing rather than gating. The upper end deliberately
        // reveals the serialized rhythmic figure without making defaults separate notes.
        val pulseContrast = pulse.pow(2.4)
        val color = m.getValue("COLOR").toDouble()
        val length = m.getValue("LENGTH").toDouble()
        val schedule = Schedule(recipe, m.getValue("ORDER"), m.getValue("BLEND"), p.phraseSeconds, p.held, length)
        val accentEvents = recipe.accents.sortedBy { it.beat }.map {
            AccentEvent(it.beat.toDouble(), it.beat / recipe.phraseBeats * p.phraseSeconds,
                it.strength * (.15 + .85 * pulse) * velocity)
        // The serialized roster is retained, but a finite gate admits only its earlier
        // excitations. Existing accent envelopes finish naturally through the release;
        // starting a fresh late accent would turn a terminal tail into another attack.
        }.filter { p.held || it.timeSeconds < p.gateSeconds }
        stopIfUnwanted()
        val lowTables = recipe.sources.mapIndexed { index, source ->
            table(source, max(0.0, color - .55), p.actualHz,
                Dsp.seedFor("RELAY", voice.name, midiFor(voice, m.getValue("TUNE")), index))
        }
        val highTables = recipe.sources.mapIndexed { index, source ->
            table(source, min(1.0, color + .17 + .18 * velocity), p.actualHz,
                Dsp.seedFor("RELAY", voice.name, midiFor(voice, m.getValue("TUNE")), index))
        }
        val cycle = p.phraseFrames * Dsp.OVERSAMPLE
        val warm = if (p.held) recipe.loop.prerollCycles * cycle else 0
        val frames = if (p.held) cycle * 2 else p.frames * Dsp.OVERSAMPLE
        val raw = FloatArray(frames)
        val currentWeights = DoubleArray(recipe.sources.size)
        val currentEnvelopes = DoubleArray(recipe.sources.size)
        val nextWeights = DoubleArray(recipe.sources.size)
        val nextEnvelopes = DoubleArray(recipe.sources.size)
        val cutoff = max(3.0 * p.actualHz, 400.0 + 14_000.0 * color.pow(2.0))
        val phaseStep = p.actualHz / INTERNAL_RATE
        var phase = 0.0
        var pole1 = 0.0
        var pole2 = 0.0
        var global = 0.0
        var nextGlobal = 0.0
        var accent = 0.0
        var nextAccent = 0.0
        var filterA = 0.0
        var nextFilterA = 0.0
        var rawPeak = 0.0

        fun contours(frame: Int, weights: DoubleArray, envelopes: DoubleArray): Triple<Double, Double, Double> {
            val seconds = frame.toDouble() / INTERNAL_RATE
            val local = if (p.held) seconds % p.phraseSeconds else seconds
            // Once the finite note is released, the current source mixture rings out. Its
            // original excitation histories continue to age, but no new handoff starts in
            // the tail. HOLD retains the complete periodically repeating source schedule.
            val scheduleTime = if (p.held) local else min(local, p.gateSeconds)
            val releaseAge = if (p.held) 0.0 else max(0.0, local - p.gateSeconds)
            schedule.at(scheduleTime, weights, envelopes, releaseAge)
            var emphasis = 0.0
            for (event in accentEvents) {
                var elapsed = local - event.timeSeconds
                if (p.held && elapsed < 0) elapsed += p.phraseSeconds
                if (elapsed >= 0) {
                    val softness = (1.0 - pulse).pow(2.0)
                    val attack = .008 + .008 * (1.0 - velocity) + .055 * softness
                    val decay = .045 + .070 * length + .180 * softness
                    val energy = smooth(elapsed / attack) * exp(-elapsed / decay) + if (p.held) {
                        // Include the decaying history of earlier cycles. A finite attack kernel
                        // reset by modulo alone would lose its quiet residue at the phrase seam.
                        val previous = exp(-p.phraseSeconds / decay)
                        exp(-elapsed / decay) * previous / (1.0 - previous)
                    } else 0.0
                    emphasis += energy * event.strength
                }
            }
            emphasis = emphasis.coerceIn(0.0, 1.0)
            val rhythmic = (1.0 - .60 * pulseContrast) + .95 * pulseContrast * emphasis
            val excitation = velocity * (.45 + .55 * velocity)
            val envelope = if (p.held) {
                // A held note has one continuous body. Its source damping and fixed accent
                // widths carry LENGTH; slots must not introduce an implicit amplitude gate.
                1.0
            } else {
                val attackSeconds = when (voice) {
                    RelayVoice.KEY -> .006
                    RelayVoice.BASS -> .004
                    RelayVoice.PLUCK -> .003
                    RelayVoice.ORGAN -> .045
                    RelayVoice.CHIME -> .008
                    RelayVoice.FLOW -> .28
                }
                val attack = smooth(local / (attackSeconds * (1.0 + 2.5 * (1.0 - velocity))))
                val release = if (local <= p.gateSeconds) 1.0 else {
                    val tailPosition = ((local - p.gateSeconds) / p.tailSeconds).coerceIn(0.0, 1.0)
                    .5 + .5 * cos(PI * tailPosition)
                }
                attack * release
            }
            var opening = 0.0
            for (source in recipe.sources.indices) {
                val w = weights[source]
                if (w > 1e-8) opening += w * sourceExcitation(recipe.sources[source].role, envelopes[source] / w)
            }
            // COLOR now spans a genuine closed-to-open filter, while source transients and
            // independent accents open it within a note. Two nonresonant poles remain bounded.
            val frequency = (cutoff * (.65 + .70 * opening + .45 * pulseContrast * emphasis))
                .coerceIn(2.4 * p.actualHz, 16_000.0)
            val coefficient = 1.0 - exp(-2.0 * PI * frequency / INTERNAL_RATE)
            return Triple(OUTPUT_GAIN * excitation * rhythmic * envelope, emphasis, coefficient)
        }

        val first = contours(0, currentWeights, currentEnvelopes)
        global = first.first; accent = first.second; filterA = first.third
        for (frame in 0 until warm + frames) {
            if (frame % 8192 == 0) stopIfUnwanted()
            if (frame % CONTROL_BLOCK == 0) {
                if (frame != 0) {
                    nextWeights.copyInto(currentWeights); nextEnvelopes.copyInto(currentEnvelopes)
                    global = nextGlobal; accent = nextAccent; filterA = nextFilterA
                }
                val next = contours(frame + CONTROL_BLOCK, nextWeights, nextEnvelopes)
                nextGlobal = next.first; nextAccent = next.second; nextFilterA = next.third
            }
            val fraction = (frame % CONTROL_BLOCK).toDouble() / CONTROL_BLOCK
            val accentNow = accent + fraction * (nextAccent - accent)
            var mix = 0.0
            for (source in recipe.sources.indices) {
                val w = currentWeights[source] + fraction * (nextWeights[source] - currentWeights[source])
                val e = currentEnvelopes[source] + fraction * (nextEnvelopes[source] - currentEnvelopes[source])
                val excitation = if (w > 1e-8) sourceExcitation(recipe.sources[source].role, e / w) else 0.0
                val brightness = (.05 + .95 * excitation + .65 * pulseContrast * accentNow).coerceIn(0.0, 1.0)
                val low = read(lowTables[source], phase)
                val high = read(highTables[source], phase)
                mix += recipe.sources[source].gain * e * (low + brightness * (high - low))
            }
            mix *= global + fraction * (nextGlobal - global)
            val coefficient = filterA + fraction * (nextFilterA - filterA)
            pole1 += coefficient * (mix - pole1)
            pole2 += coefficient * (pole1 - pole2)
            if (frame >= warm) {
                val value = pole2.toFloat()
                check(value.isFinite() && abs(value) < 1.0f) { "RELAY bounded source stage failed" }
                raw[frame - warm] = value
                rawPeak = max(rawPeak, abs(value.toDouble()))
            }
            phase += phaseStep
            if (phase >= 1.0) phase -= 1.0
        }

        stopIfUnwanted()
        val conditioned = raw.copyOf()
        Tide.bandLimit(conditioned, INTERNAL_RATE)
        stopIfUnwanted()
        val finished = Dsp.decimate(conditioned, Dsp.RATE)
        // Nonresonant output DC rejection; preserve the lowest C1 anchor.
        val dc = Dsp.OnePole(Dsp.RATE)
        for (index in finished.indices) finished[index] -= dc.lp(finished[index], 8f)
        var previous = FloatArray(0)
        val samples = if (p.held) {
            previous = finished.copyOfRange(0, p.phraseFrames)
            val candidate = finished.copyOfRange(p.phraseFrames, 2 * p.phraseFrames)
            val fade = recipe.loop.wrapFrames.coerceAtMost(candidate.size - 256)
            for (index in 0 until fade + 256) {
                val w = if (index >= fade) 1.0 else smooth(index.toDouble() / fade)
                val destination = candidate.size - fade - 256 + index
                candidate[destination] = ((1.0 - w) * candidate[destination] + w * previous[destination]).toFloat()
            }
            candidate
        } else finished
        val peakBeforeLevel = samples.maxOfOrNull { abs(it) } ?: 0f
        if (normalize) {
            stopIfUnwanted()
            Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            val gain = if (peakBeforeLevel > 1e-12f) (samples.maxOfOrNull { abs(it) } ?: 0f) / peakBeforeLevel else 1f
            for (index in previous.indices) previous[index] *= gain
        }
        if (!p.held) Dsp.fadeTail(samples)
        stopIfUnwanted()
        val seam = if (p.held && samples.any { it != 0f }) Keys.seamError(previous + samples, previous.size) else 0.0
        check(!p.held || seam < Keys.MAX_SEAM_ERROR) { "RELAY phrase does not close: $seam" }
        return Report(Snip(samples, channels = 1, sampleRate = Dsp.RATE), raw, previous, seam,
            p.held, p.requestedHz, p.actualHz, p.phraseSeconds, p.gateSeconds, rawPeak,
            accentEvents, if (p.held) weightFrames(schedule)
                else weightFrames(schedule, p.frames.toDouble() / Dsp.RATE, p.gateSeconds))
    }
}
