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
    const val MIDI_MIN = 24
    const val MIDI_MAX = 72
    const val LOOP_THRESHOLD = .99f
    const val SCRAMBLE_HOLD_CEILING = .95f
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CONTROL_BLOCK = 64
    private const val TABLE_SIZE = 4096
    private const val OUTPUT_GAIN = .42

    private val starting = arrayOf(
        floatArrayOf(.25f, .30f, .45f, .50f, .50f),
        floatArrayOf(.20f, .45f, .40f, .35f, .45f),
        floatArrayOf(.40f, .60f, .25f, .60f, .35f),
        floatArrayOf(.35f, .30f, .65f, .45f, .65f),
        floatArrayOf(.55f, .40f, .50f, .65f, .60f),
        floatArrayOf(.45f, .15f, .80f, .50f, .80f),
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
            RelayVoice.KEY -> floatArrayOf(1f, .90f, .62f, .65f)
            RelayVoice.BASS -> floatArrayOf(.95f, 1f, .40f, .85f)
            RelayVoice.PLUCK -> floatArrayOf(1f, .62f, .75f, .58f)
            RelayVoice.ORGAN -> floatArrayOf(.65f, 1f, .40f, .85f)
            RelayVoice.CHIME -> floatArrayOf(.75f, .70f, 1f, .58f)
            RelayVoice.FLOW -> floatArrayOf(.65f, .85f, .65f, 1f)
        }
        val decays = when (voice) {
            RelayVoice.PLUCK -> floatArrayOf(.13f, .65f, .40f, .85f)
            RelayVoice.BASS -> floatArrayOf(.22f, .90f, .30f, .90f)
            RelayVoice.CHIME -> floatArrayOf(.28f, 1.00f, 1.15f, 1.20f)
            RelayVoice.FLOW -> floatArrayOf(.55f, 1.20f, .95f, 1.50f)
            else -> floatArrayOf(.30f, 1.00f, .70f, 1.10f)
        }
        val brightness = floatArrayOf(.60f, .45f, .85f, .25f)
        return RelayPhrase(RelayRole.entries.mapIndexed { i, role ->
            RelaySource(role, gains[i], brightness[i], decays[i])
        }, listOf(RelaySlot(0, 1f, .20f), RelaySlot(1, 1f, .25f),
            RelaySlot(3, 1f, .30f), RelaySlot(2, 1f, .25f)),
            listOf(listOf(0, 1, 3, 2), listOf(0, 2, 1, 3), listOf(3, 1, 2, 0), listOf(2, 3, 0, 1)),
            listOf(RelayAccent(0f, 1f), RelayAccent(.75f, .50f), RelayAccent(1.5f, .72f),
                RelayAccent(2.5f, .85f), RelayAccent(3.25f, .55f)))
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

    /** Derived before synthesis; source order and envelope are blended at corresponding times. */
    private class Schedule(val phrase: RelayPhrase, order: Float, blend: Float,
                           val seconds: Double, val periodic: Boolean) {
        private val starts = DoubleArray(phrase.slots.size)
        private val scale = seconds / phrase.phraseBeats
        private val width = .06 + 5.4 * blend.toDouble().pow(1.35)
        private val position = order.toDouble() * (phrase.orderRoster.size - 1)
        private val lo = position.toInt().coerceAtMost(phrase.orderRoster.lastIndex)
        private val hi = (lo + 1).coerceAtMost(phrase.orderRoster.lastIndex)
        private val morph = smooth(position - lo)
        // Valid short phrases can have broad windows longer than one phrase. Include every
        // overlapping copy, so both gains and source envelope histories close at the modulo
        // boundary. The serialized dwell/transition budgets bound this radius to 13 cycles.
        private val cycleRadius = if (periodic) ceil(phrase.slots.maxOf {
            (it.beats + it.transitionBeats * width) * scale / seconds
        }).toInt() + 1 else 0

        init {
            var time = 0.0
            for (i in starts.indices) {
                starts[i] = time
                time += phrase.slots[i].beats * scale
            }
        }

        /** weights sum to one; excitation histories remain attached to their original source. */
        fun at(timeSeconds: Double, weights: DoubleArray, envelopes: DoubleArray) {
            weights.fill(0.0); envelopes.fill(0.0)
            val t = if (periodic) ((timeSeconds % seconds) + seconds) % seconds
                else timeSeconds.coerceIn(0.0, seconds - 1e-9)
            addOrder(t, lo, 1.0 - morph, weights, envelopes)
            if (hi != lo && morph > 0.0) addOrder(t, hi, morph, weights, envelopes)
        }

        private fun addOrder(t: Double, roster: Int, mix: Double, weights: DoubleArray, envelopes: DoubleArray) {
            val contributions = DoubleArray(starts.size)
            val ages = DoubleArray(starts.size)
            for (slot in starts.indices) {
                val start = starts[slot]
                val dwell = phrase.slots[slot].beats * scale
                val transition = max(.008, phrase.slots[slot].transitionBeats * scale * width)
                // Broad windows may overlap three or four roles. Unity-sum normalization below
                // also handles correlated roots; equal-power weights would make those swell.
                for (cycle in -cycleRadius..cycleRadius) {
                    val age = t - start - cycle * seconds
                    val a = smooth((age + transition) / transition)
                    val b = smooth((dwell + transition - age) / transition)
                    val contribution = a * b
                    contributions[slot] += contribution
                    ages[slot] += contribution * max(0.0, age)
                }
            }
            val total = contributions.sum().coerceAtLeast(1e-12)
            for (slot in starts.indices) {
                val source = phrase.orderRoster[roster][slot]
                val w = mix * contributions[slot] / total
                val age = if (contributions[slot] > 1e-12) ages[slot] / contributions[slot] else 0.0
                val definition = phrase.sources[source]
                val transient = exp(-age / definition.decaySeconds)
                val envelope = when (definition.role) {
                    RelayRole.PLUCK -> .25 + .75 * transient
                    RelayRole.BELL -> .48 + .52 * transient
                    RelayRole.ORGAN -> .91 + .09 * transient
                    RelayRole.SOFT -> .96 - .16 * transient
                }
                weights[source] += w
                envelopes[source] += w * envelope
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

    private fun weightFrames(schedule: Schedule): List<WeightFrame> {
        val w = DoubleArray(schedule.phrase.sources.size)
        val e = DoubleArray(w.size)
        val count = ceil(schedule.seconds / .01).toInt()
        return (0..count).map { index ->
            val t = min(schedule.seconds, index * .01)
            schedule.at(t, w, e)
            WeightFrame(t, w.toList())
        }
    }

    /** Original finite harmonic spectra, with fundamental dominance at every COLOR. */
    private fun table(source: RelaySource, color: Double, hz: Double, seed: Int): FloatArray {
        val c = (.18 + .70 * color + .25 * (source.brightness - .5)).coerceIn(0.0, 1.0)
        val amplitudes = when (source.role) {
            RelayRole.PLUCK -> doubleArrayOf(0.0, 1.0, .10 + .35 * c, .035 + .245 * c,
                .01 + .14 * c, .005 + .085 * c, .035 * c)
            RelayRole.ORGAN -> doubleArrayOf(0.0, 1.0, .22 + .16 * c, .13 + .20 * c,
                .04 + .065 * c, 0.0, .02 + .065 * c, 0.0, .035 * c)
            RelayRole.BELL -> doubleArrayOf(0.0, 1.0, .03 + .13 * c, .06 + .37 * c,
                0.0, .025 + .245 * c, 0.0, .015 + .145 * c, 0.0, .085 * c)
            RelayRole.SOFT -> doubleArrayOf(0.0, 1.0, .025, -.085 - .16 * c,
                0.0, .012 + .055 * c, 0.0, -.02 * c)
        }
        for (harmonic in amplitudes.indices) {
            val frequency = harmonic * hz
            // Fade before output Nyquist; source generation contains no unrestricted FM/noise.
            amplitudes[harmonic] *= (1.0 - smooth((frequency - 12_000.0) / 6_000.0))
        }
        val rms = sqrt(amplitudes.sumOf { it * it } / 2.0).coerceAtLeast(.001)
        val phases = Dsp.phases(amplitudes.size, seed)
        return FloatArray(TABLE_SIZE + 1) { sample ->
            val angle = 2.0 * PI * (sample % TABLE_SIZE) / TABLE_SIZE
            var value = 0.0
            for (harmonic in 1 until amplitudes.size) {
                // Shared root phase prevents silent cancellation in broad blends. Upper partial
                // phases are seeded per generated source, stable across all macro settings.
                val offset = if (harmonic == 1) 0.0 else .10 * sin(phases[harmonic])
                value += amplitudes[harmonic] * sin(harmonic * angle + offset)
            }
            (.45 * value / rms).toFloat()
        }
    }

    private fun read(table: FloatArray, phase: Double): Double {
        val position = phase * TABLE_SIZE
        val index = position.toInt()
        val fraction = position - index
        return table[index] + fraction * (table[index + 1] - table[index])
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
        val color = m.getValue("COLOR").toDouble()
        val length = m.getValue("LENGTH").toDouble()
        val schedule = Schedule(recipe, m.getValue("ORDER"), m.getValue("BLEND"), p.phraseSeconds, p.held)
        val accentEvents = recipe.accents.sortedBy { it.beat }.map {
            AccentEvent(it.beat.toDouble(), it.beat / recipe.phraseBeats * p.phraseSeconds,
                it.strength * (.15 + .85 * pulse) * velocity)
        // The serialized roster is retained, but a finite gate admits only its earlier
        // excitations. Existing accent envelopes finish naturally through the release;
        // starting a fresh late accent would turn a terminal tail into another attack.
        }.filter { p.held || it.timeSeconds < p.gateSeconds }
        val lowTables = recipe.sources.mapIndexed { index, source ->
            table(source, max(0.0, color - .30), p.actualHz,
                Dsp.seedFor("RELAY", voice.name, midiFor(voice, m.getValue("TUNE")), index))
        }
        val highTables = recipe.sources.mapIndexed { index, source ->
            table(source, min(1.0, color + .12 + .15 * velocity), p.actualHz,
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
        val cutoff = max(2.4 * p.actualHz, 650.0 + 11_000.0 * color.pow(1.5))
        val filterA = 1.0 - exp(-2.0 * PI * min(cutoff, 16_000.0) / INTERNAL_RATE)
        val phaseStep = p.actualHz / INTERNAL_RATE
        var phase = 0.0
        var pole1 = 0.0
        var pole2 = 0.0
        var global = 0.0
        var nextGlobal = 0.0
        var accent = 0.0
        var nextAccent = 0.0
        var rawPeak = 0.0

        fun contours(frame: Int, weights: DoubleArray, envelopes: DoubleArray): Pair<Double, Double> {
            val seconds = frame.toDouble() / INTERNAL_RATE
            val local = if (p.held) seconds % p.phraseSeconds else seconds
            schedule.at(local, weights, envelopes)
            var emphasis = 0.0
            for (event in accentEvents) {
                var elapsed = local - event.timeSeconds
                if (p.held && elapsed < 0) elapsed += p.phraseSeconds
                if (elapsed >= 0) {
                    val attack = .008 + .008 * (1.0 - velocity)
                    val decay = .045 + .070 * length
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
            val rhythmic = (1.0 - .60 * pulse) + .95 * pulse * emphasis
            val excitation = velocity * (.45 + .55 * velocity)
            val envelope = if (p.held) {
                // LENGTH changes articulation within each repeating dwell, never phrase tempo.
                val cell = local / p.phraseSeconds * recipe.slots.size
                val articulation = (.64 + .36 * length) + (.36 - .36 * length) *
                    (.5 + .5 * cos(2.0 * PI * cell))
                articulation
            } else {
                val attack = smooth(local / (.006 + .023 * (1.0 - velocity)))
                val release = if (local <= p.gateSeconds) 1.0 else {
                    val tailPosition = ((local - p.gateSeconds) / p.tailSeconds).coerceIn(0.0, 1.0)
                    .5 + .5 * cos(PI * tailPosition)
                }
                attack * release
            }
            return Pair(OUTPUT_GAIN * excitation * rhythmic * envelope, emphasis)
        }

        val first = contours(0, currentWeights, currentEnvelopes)
        global = first.first; accent = first.second
        for (frame in 0 until warm + frames) {
            if (frame % CONTROL_BLOCK == 0) {
                if (frame != 0) {
                    nextWeights.copyInto(currentWeights); nextEnvelopes.copyInto(currentEnvelopes)
                    global = nextGlobal; accent = nextAccent
                }
                val next = contours(frame + CONTROL_BLOCK, nextWeights, nextEnvelopes)
                nextGlobal = next.first; nextAccent = next.second
            }
            val fraction = (frame % CONTROL_BLOCK).toDouble() / CONTROL_BLOCK
            val accentNow = accent + fraction * (nextAccent - accent)
            var mix = 0.0
            for (source in recipe.sources.indices) {
                val w = currentWeights[source] + fraction * (nextWeights[source] - currentWeights[source])
                val e = currentEnvelopes[source] + fraction * (nextEnvelopes[source] - currentEnvelopes[source])
                val transient = if (w > 1e-8) (e / w).coerceIn(0.0, 1.0) else 0.0
                val brightness = (.25 + .70 * transient + .30 * pulse * accentNow).coerceIn(0.0, 1.0)
                val low = read(lowTables[source], phase)
                val high = read(highTables[source], phase)
                mix += recipe.sources[source].gain * e * (low + brightness * (high - low))
            }
            mix *= global + fraction * (nextGlobal - global)
            pole1 += filterA * (mix - pole1)
            pole2 += filterA * (pole1 - pole2)
            if (frame >= warm) {
                val value = pole2.toFloat()
                check(value.isFinite() && abs(value) < 1.0f) { "RELAY bounded source stage failed" }
                raw[frame - warm] = value
                rawPeak = max(rawPeak, abs(value.toDouble()))
            }
            phase += phaseStep
            if (phase >= 1.0) phase -= 1.0
        }

        val conditioned = raw.copyOf()
        Tide.bandLimit(conditioned, INTERNAL_RATE)
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
            Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            val gain = if (peakBeforeLevel > 1e-12f) (samples.maxOfOrNull { abs(it) } ?: 0f) / peakBeforeLevel else 1f
            for (index in previous.indices) previous[index] *= gain
        }
        if (!p.held) Dsp.fadeTail(samples)
        val seam = if (p.held && samples.any { it != 0f }) Keys.seamError(previous + samples, previous.size) else 0.0
        check(!p.held || seam < Keys.MAX_SEAM_ERROR) { "RELAY phrase does not close: $seam" }
        return Report(Snip(samples, channels = 1, sampleRate = Dsp.RATE), raw, previous, seam,
            p.held, p.requestedHz, p.actualHz, p.phraseSeconds, p.gateSeconds, rawPeak,
            accentEvents, weightFrames(schedule))
    }
}
