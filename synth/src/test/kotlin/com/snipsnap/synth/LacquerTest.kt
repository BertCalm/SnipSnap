package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Dry causal and numerical gates; the published audition supplies the listening gate. */
class LacquerTest {
    @Test
    fun `fresh render state and reordered controls reproduce samples and arrivals`() {
        for (voice in LacquerVoice.entries) {
            val macros = Lacquer.defaults(voice) + mapOf("RELIEF" to .7f, "GLUE" to .6f)
            val first = Lacquer.renderProbe(voice, macros, midi = 57, velocity = .7f, seconds = .7f)
            Lacquer.renderProbe(voice, mapOf("SHEEN" to 1f, "BODY" to 0f), midi = 36, seconds = .1f)
            val repeated = Lacquer.renderProbe(voice, macros.entries.reversed().associate { it.key to it.value },
                midi = 57, velocity = .7f, seconds = .7f)
            assertContentEquals(first.samples, repeated.samples, "$voice independent output")
            assertContentEquals(first.raw, repeated.raw, "$voice independent oversampled state")
            assertEquals(first.onsetSeconds, repeated.onsetSeconds)
            assertEquals(first.layers.keys, repeated.layers.keys)
            for (role in first.layers.keys) assertContentEquals(first.layers.getValue(role), repeated.layers.getValue(role))
        }
    }

    @Test
    fun `the root remains identifiable at low middle and high notes and soft velocity`() {
        var worstCents = 0.0
        var smallestRoot = 1.0
        val notes = listOf(24 to .25f, 48 to .7f, 84 to 1f)
        for (voice in LacquerVoice.entries) for ((midi, velocity) in notes) {
            val p = Lacquer.renderProbe(voice, midi = midi, velocity = velocity, seconds = 1f)
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(p.samples, Dsp.RATE, wanted, .2f, .6f)
            val cents = abs(FineTuning.cents(measured, wanted.toDouble()))
            val share = rootShare(p.samples, wanted.toDouble(), .2f, .6f)
            worstCents = max(worstCents, cents)
            smallestRoot = minOf(smallestRoot, share)
            assertTrue(cents < 5.0, "$voice MIDI $midi velocity $velocity: root $cents cents off")
            assertTrue(share > .04, "$voice MIDI $midi velocity $velocity: fundamental share $share")
        }
        println("LACQUER requested notes: worst $worstCents cents; smallest fundamental share $smallestRoot")
    }

    @Test
    fun `relief schedules smooth independent layer onsets and its low end is active`() {
        var smallestLowOffset = Double.POSITIVE_INFINITY
        for (voice in LacquerVoice.entries) {
            val low = Lacquer.renderProbe(voice, mapOf("RELIEF" to 0f), seconds = .5f)
            val slight = Lacquer.renderProbe(voice, mapOf("RELIEF" to .1f), seconds = .5f)
            val high = Lacquer.renderProbe(voice, mapOf("RELIEF" to 1f), seconds = .6f)
            assertTrue(high.layers.keys.containsAll(listOf(LacquerRole.BODY, LacquerRole.EDGE, LacquerRole.TRAIL)))
            assertTrue(high.onsetSeconds.values.min() < .005, "$voice lost its earliest pitched arrival")
            val lowMovement = slight.onsetSeconds.keys.maxOf { slight.onsetSeconds.getValue(it) - low.onsetSeconds.getValue(it) }
            smallestLowOffset = minOf(smallestLowOffset, lowMovement)
            assertTrue(lowMovement > .001, "$voice RELIEF .1 has no meaningful onset separation")
            assertTrue(high.onsetSeconds.values.max() <= .150001, "$voice onset escaped the bounded profile")
            assertTrue(high.onsetSeconds.values.max() - high.onsetSeconds.values.min() > .02,
                "$voice full RELIEF has no distinct independent arrivals")
            assertTrue(shapeDistance(low.samples, slight.samples) > .005, "$voice RELIEF's low end did not change contact")
            for ((role, samples) in high.layers) {
                val onset = high.onsetSeconds.getValue(role)
                val before = ((onset - .004).coerceAtLeast(0.0) * Dsp.RATE).toInt()
                assertTrue(samples.take(before).all { abs(it) < 1e-5f }, "$voice $role sounded before its onset $onset")
                val first = samples.indexOfFirst { abs(it) > 1e-5f }
                assertTrue(first >= 0 && first.toDouble() / Dsp.RATE < onset + .04,
                    "$voice $role did not develop from its actual local onset")
                val peak = samples.maxOf { abs(it) }
                assertTrue(peak > .001f, "$voice $role has no diagnostic role")
            }
            val body = high.layers.getValue(LacquerRole.BODY)
            val edge = high.layers.getValue(LacquerRole.EDGE)
            assertTrue(shapeDistance(body, edge) > .1, "$voice body and edge are duplicate complete tones")
        }
        println("LACQUER RELIEF .1: smallest scheduled separation change $smallestLowOffset seconds")
    }

    @Test
    fun `latest trail arrives and rings before the finite render ends`() {
        for (voice in listOf(LacquerVoice.KEY, LacquerVoice.PLUCK, LacquerVoice.SILK)) {
            val profile = Lacquer.profileFor(voice)
            val extended = profile.copy(layers = profile.layers.map { layer ->
                if (layer.role == LacquerRole.TRAIL) layer.copy(
                    timing = layer.timing.copy(onsetSeconds = .05f, reliefSeconds = .1f),
                    envelope = layer.envelope.copy(releaseSeconds = 1.1f),
                ) else layer
            })
            val p = Lacquer.renderProbe(voice, mapOf("RELIEF" to 1f, "CONTOUR" to 0f), profile = extended)
            val trail = p.layers.getValue(LacquerRole.TRAIL)
            val onset = p.onsetSeconds.getValue(LacquerRole.TRAIL)
            assertTrue(p.durationSeconds > onset + .3, "$voice render ended before its scheduled trail developed")
            assertTrue(p.durationSeconds >= p.layerEndSeconds.values.max(), "$voice latest active tail was truncated")
            assertTrue(p.layerEndSeconds.getValue(LacquerRole.TRAIL) > onset + .3,
                "$voice trail envelope started its lifetime before the scheduled onset")
            assertEquals(p.samples.size.toDouble() / Dsp.RATE, p.durationSeconds, 1.0 / Dsp.RATE)
            val afterOnset = ((onset + .025) * Dsp.RATE).toInt()
            val afterTail = minOf(trail.size, ((onset + .25) * Dsp.RATE).toInt())
            assertTrue(rms(trail, afterOnset, afterTail) > .001, "$voice late independent trail was trimmed away")
            assertTrue(p.samples.size in Dsp.RATE..(8 * Dsp.RATE), "$voice duration escaped host budget")
            assertTrue(rms(p.samples, p.samples.size - 256, p.samples.size) < rms(p.samples) * .02,
                "$voice finite render truncates an active tail")
        }
    }

    @Test
    fun `long serialized envelopes compress the complete schedule into the host budget`() {
        val base = Lacquer.profileFor(LacquerVoice.SILK)
        val extended = base.copy(layers = base.layers.map { layer -> layer.copy(
            envelope = LacquerEnvelope(1f, 4f, .8f, 3f),
            timing = layer.timing.copy(onsetSeconds = 0f, reliefSeconds = if (layer.role == LacquerRole.TRAIL) .15f else layer.timing.reliefSeconds),
        ) })
        val p = Lacquer.renderProbe(LacquerVoice.SILK, mapOf("RELIEF" to 1f, "CONTOUR" to 1f), profile = extended)
        assertTrue(p.durationSeconds <= Lacquer.MAX_FINITE_SECONDS + 1.0 / Dsp.RATE)
        assertTrue(p.durationSeconds > Lacquer.MAX_FINITE_SECONDS - .05, "boundary case did not exercise schedule compression")
        assertTrue(p.layerEndSeconds.values.all { it <= p.durationSeconds })
        assertTrue(p.onsetSeconds.getValue(LacquerRole.TRAIL) > .02, "compression erased the latest scheduled arrival")
        assertTrue(rms(p.layers.getValue(LacquerRole.TRAIL), Dsp.RATE, 2 * Dsp.RATE) > .001)
        assertTrue(rms(p.samples, p.samples.size - 256, p.samples.size) < rms(p.samples) * .02,
            "host duration limit cuts through an active tail")
    }

    @Test
    fun `all five timbral controls change every voice after matching their levels`() {
        val controls = listOf("RELIEF", "SHEEN", "BODY", "CONTOUR", "GLUE")
        var smallest = Double.POSITIVE_INFINITY
        for (voice in LacquerVoice.entries) {
            val changes = controls.map { control ->
                val a = Lacquer.renderProbe(voice, mapOf(control to 0f), seconds = 1.2f).samples
                val b = Lacquer.renderProbe(voice, mapOf(control to 1f), seconds = 1.2f).samples
                val distance = shapeDistance(a, b)
                smallest = minOf(smallest, distance)
                assertTrue(distance > .008, "$voice $control changes only final gain: matched distance $distance")
                "$control=$distance"
            }
            println("LACQUER $voice matched macro distances: ${changes.joinToString()}")
        }
        println("LACQUER smallest matched timbral distance: $smallest")
    }

    @Test
    fun `velocity changes source energy and color within the saved voice`() {
        for (voice in LacquerVoice.entries) {
            val soft = Lacquer.renderProbe(voice, velocity = .25f, seconds = .8f)
            val hard = Lacquer.renderProbe(voice, velocity = 1f, seconds = .8f)
            assertTrue(rms(hard.raw) > rms(soft.raw) * 1.1, "$voice hard playing did not increase raw source energy")
            assertTrue(shapeDistance(soft.samples, hard.samples) > .008, "$voice velocity changed only level")
            assertEquals(soft.onsetSeconds, hard.onsetSeconds, "$voice velocity shuffled layer arrival order")
        }
    }

    @Test
    fun `coordinated extremes remain finite dc balanced and bounded before shared leveling`() {
        val timbral = listOf("RELIEF", "SHEEN", "BODY", "CONTOUR", "GLUE")
        val cases = listOf(
            timbral.associateWith { 0f },
            timbral.associateWith { 1f },
            mapOf("RELIEF" to 1f, "CONTOUR" to 0f, "SHEEN" to 1f, "BODY" to 1f, "GLUE" to 0f),
        )
        var rawPeak = 0f
        var dc = 0.0
        for (voice in LacquerVoice.entries) for ((index, macros) in cases.withIndex()) {
            val p = Lacquer.renderProbe(voice, macros, midi = if (index == 1) 84 else 36, seconds = 1.4f)
            assertTrue(p.raw.isNotEmpty() && p.raw.all { it.isFinite() }, "$voice case $index raw state failed")
            assertTrue(p.samples.all { it.isFinite() }, "$voice case $index final state failed")
            val peak = p.raw.maxOf { abs(it) }
            val mean = abs(p.samples.sumOf { it.toDouble() } / p.samples.size)
            rawPeak = maxOf(rawPeak, peak)
            dc = maxOf(dc, mean)
            assertTrue(peak <= 1f, "$voice case $index raw peak $peak relies on shared leveling for safety")
            assertTrue(mean < .005, "$voice case $index DC $mean")
            assertTrue(rms(p.samples) > 1e-4, "$voice case $index collapsed to silence")
        }
        println("LACQUER coordinated extremes: maximum raw peak $rawPeak; maximum output DC $dc")
    }

    @Test
    fun `held contours preserve relief and close difficult loops without coarse detuning`() {
        var worstSeam = 0.0
        var smallestArticulation = Double.POSITIVE_INFINITY
        val weakMixes = mutableListOf<String>()
        for ((index, voice) in LacquerVoice.entries.withIndex()) {
            val midi = if (index % 2 == 0) 36 else 72
            val base = mapOf("HOLD" to 1f, "CONTOUR" to 1f, "SHEEN" to 1f, "BODY" to 1f, "GLUE" to 1f)
            val aligned = Lacquer.renderProbe(voice, base + ("RELIEF" to 0f), midi = midi)
            val separated = Lacquer.renderProbe(voice, base + ("RELIEF" to 1f), midi = midi)
            for (p in listOf(aligned, separated)) {
                assertTrue(p.previousCycle.size >= 256, "$voice omitted stable prior loop state")
                val joined = p.previousCycle + p.samples
                val seam = Keys.seamError(joined, p.previousCycle.size)
                worstSeam = max(worstSeam, seam)
                assertTrue(seam < Keys.MAX_SEAM_ERROR, "$voice MIDI $midi seam $seam")
                assertTrue(p.unwrappedSeamError < Keys.MAX_SEAM_ERROR,
                    "$voice MIDI $midi loop only closes after corrective wrapping: ${p.unwrappedSeamError}")
                assertTrue(p.raw.all { it.isFinite() } && p.samples.all { it.isFinite() })
                assertTrue(p.prerollCycles in 1..12, "$voice loop preroll is unbounded")
                assertTrue(abs(FineTuning.cents(p.renderedHz, p.requestedHz)) < .5, "$voice loop coarsely detunes its root")
            }
            val articulation = aligned.layers.keys.maxOf { role ->
                trajectoryDistance(aligned.layers.getValue(role), separated.layers.getValue(role))
            }
            smallestArticulation = minOf(smallestArticulation, articulation)
            val mixDistance = shapeDistance(aligned.samples, separated.samples)
            println("LACQUER $voice difficult HOLD: matched mix distance $mixDistance; layer articulation distance $articulation; " +
                "unwrapped seam ${maxOf(aligned.unwrappedSeamError, separated.unwrappedSeamError)}")
            assertTrue(articulation > .015, "$voice HOLD erased RELIEF's independent periodic development: $articulation")
            if (mixDistance <= .02) weakMixes += "$voice held RELIEF matched mix distance $mixDistance (minimum .02)"
        }
        println("LACQUER difficult HOLD: worst seam $worstSeam; smallest layer articulation distance $smallestArticulation")
        assertTrue(weakMixes.isEmpty(), weakMixes.joinToString("; "))
    }

    @Test
    fun `bright high notes keep held spectral energy near the legal harmonic neighborhoods`() {
        for (voice in listOf(LacquerVoice.BRASS, LacquerVoice.GLASS)) {
            val p = Lacquer.renderProbe(voice, mapOf("HOLD" to 1f, "RELIEF" to 1f, "SHEEN" to 1f,
                "BODY" to 1f, "CONTOUR" to 1f, "GLUE" to 1f), midi = 96)
            val size = 32768
            val from = (p.samples.size - size) / 2
            val real = FloatArray(size) { i ->
                p.samples[from + i] * (.5 - .5 * cos(2 * PI * i / (size - 1))).toFloat()
            }
            val imaginary = FloatArray(size)
            Fft.forward(real, imaginary)
            val binHz = Dsp.RATE.toDouble() / size
            var total = 0.0
            var residual = 0.0
            for (bin in 1 until size / 2) {
                val hz = bin * binHz
                if (hz < 20) continue
                val energy = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                val harmonic = (hz / p.renderedHz).roundToInt().coerceAtLeast(1)
                total += energy
                if (abs(hz - harmonic * p.renderedHz) > 8 * binHz) residual += energy
            }
            val fraction = residual / total
            // This exposes aliases that fold away from the harmonic grid, together
            // with window leakage and genuine contour sidebands. It does not prove
            // that every possible alias is absent, including one on the grid.
            println("LACQUER $voice MIDI96 all-high held spectrum: off-neighborhood fraction $fraction; neighborhood +/-${8 * binHz} Hz")
            assertTrue(fraction < 1e-4, "$voice bright held spectrum has excessive off-neighborhood energy: $fraction")
        }
    }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        require(from >= 0 && to <= samples.size && to > from)
        var energy = 0.0
        for (i in from until to) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (to - from))
    }

    /** Relative RMS distance after giving both buffers the same RMS; gain alone scores zero. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val ar = rms(a, 0, n)
        val br = rms(b, 0, n)
        require(ar > 1e-10 && br > 1e-10)
        var difference = 0.0
        for (i in 0 until n) {
            val delta = a[i] / ar - b[i] / br
            difference += delta * delta
        }
        return sqrt(difference / n)
    }

    /** Slow layer energy contour, normalized per layer so a final gain change cannot pass. */
    private fun trajectoryDistance(a: FloatArray, b: FloatArray): Double {
        val blocks = 24
        fun trajectory(samples: FloatArray): DoubleArray {
            val values = DoubleArray(blocks) { i -> rms(samples, i * samples.size / blocks, (i + 1) * samples.size / blocks) }
            val mean = values.average()
            return DoubleArray(blocks) { values[it] / mean }
        }
        val x = trajectory(a)
        val y = trajectory(b)
        return sqrt(x.indices.sumOf { (x[it] - y[it]) * (x[it] - y[it]) } / blocks)
    }

    private fun rootShare(samples: FloatArray, hz: Double, fromSeconds: Float, seconds: Float): Double {
        val from = (fromSeconds * Dsp.RATE).toInt()
        val length = minOf((seconds * Dsp.RATE).toInt(), samples.size - from)
        val energies = DoubleArray(minOf(8, (Dsp.RATE * .45 / hz).toInt())) { harmonic ->
            var real = 0.0
            var imaginary = 0.0
            for (i in 0 until length) {
                val window = .5 - .5 * cos(2 * PI * i / (length - 1))
                val angle = 2 * PI * hz * (harmonic + 1) * i / Dsp.RATE
                val sample = samples[from + i] * window
                real += sample * cos(angle)
                imaginary += sample * sin(angle)
            }
            real * real + imaginary * imaginary
        }
        return energies[0] / energies.sum().coerceAtLeast(1e-30)
    }

    @Test
    fun `an interrupted finite or held render stops instead of finishing beside its replacement`() {
        // SynthScreen previews run in runInterruptible: a newer request interrupts the worker, and the
        // tick loop, the band limit, the decimation, the seam and the level must stop on that flag.
        for (render in listOf<() -> Unit>(
            { Lacquer.render(LacquerVoice.KEY) },
            { Lacquer.render(LacquerVoice.KEY, mapOf("HOLD" to 1f)) },
        )) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<java.util.concurrent.CancellationException> { render() }
            } finally {
                Thread.interrupted()
            }
        }
        assertTrue(Lacquer.render(LacquerVoice.KEY).samples.any { it != 0f }, "an uninterrupted render still sounds")
    }

    @Test
    fun `the conditioning pass looks again after its band limit when the render is no longer wanted`() {
        // The pass looks on entry, so a flag set before it is caught there and says nothing about the look
        // between its band limit and its decimation; an interrupt that lands after that first look must still stop it.
        val late = LateInterrupt(clearLooks = 1)
        assertFailsWith<java.util.concurrent.CancellationException> {
            late.run { Lacquer.finish(FloatArray(Dsp.OVERSAMPLE * 4096)) }
        }
        assertEquals(2, late.looks, "the pass stopped at its look after the band limit")
        assertEquals(4096, Lacquer.finish(FloatArray(Dsp.OVERSAMPLE * 4096)).size, "an uninterrupted pass still conditions")
    }
}
