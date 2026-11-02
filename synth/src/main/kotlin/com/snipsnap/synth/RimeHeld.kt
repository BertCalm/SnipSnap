package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.*

/** Periodic excitation and independently settled modal cycles, with an evolving key-on. */
internal object RimeHeld {
    data class Render(val note: KeyNote, val previousCycle: FloatArray, val cycle: FloatArray,
        val seamError: Double, val actualHz: Double, val internalPeak: Double, val maxBodyEnergy: Double)
    private val cache = object : LinkedHashMap<RimePatch, Render>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RimePatch, Render>?) = size > 8
    }
    private fun Render.owned() = copy(note = note.copy(snip = note.snip.copy(samples = note.snip.samples.copyOf())),
        previousCycle = previousCycle.copyOf(), cycle = cycle.copyOf())

    fun render(patch: RimePatch): Render {
        require(patch.model >= 5) { "RIME HOLD requires model 5" }
        val key = patch.copy(name = "Held Rime", macros = patch.macros + ("HOLD" to 1f))
        synchronized(cache) { cache[key]?.let { return it.owned() } }
        val result = build(key)
        synchronized(cache) {
            cache[key] = result
            // Moving loops are longer. Bound retained PCM as well as entry count.
            fun bytes() = cache.values.sumOf { 4L * (it.note.snip.samples.size + it.previousCycle.size + it.cycle.size) }
            while (cache.size > 1 && bytes() > 32L * 1024 * 1024) cache.remove(cache.keys.first())
        }
        return result.owned()
    }

    private fun heldBandGain(t: Double, band: Int, bands: Int, unfold: Double, gate: Double): Double {
        if (band == 0 || unfold == 0.0) return 1.0
        val reveal = min(1.5, gate * .72) * unfold.pow(1.6)
        val delay = reveal * (.12 + .70 * band / max(1, bands - 1))
        return RimeContour.smooth((t - delay) / (.008 + reveal * .25))
    }

    private fun build(patch: RimePatch): Render {
        val voice = patch.voice; val velocity = patch.velocity
        val gateSeconds = patch.gateSeconds; val midi = patch.midi
        val m = Rime.settled(voice, patch.macros, patch.model)
        val requestedHz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val moving = Rime.movingHold(m, patch.model)
        val targetSeconds = if (moving) .6 + 1.4 * m.getValue("UNFOLD") else .5
        val periods = (requestedHz * targetSeconds).roundToInt().coerceAtLeast(1)
        val cycleFrames = (periods * Dsp.RATE / requestedHz).roundToInt()
        val hz = periods * Dsp.RATE.toDouble() / cycleFrames
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val cycleSamples = cycleFrames * Dsp.OVERSAMPLE
        val edge = m.getValue("EDGE").toDouble(); val body = m.getValue("BODY").toDouble()
        val unfold = m.getValue("UNFOLD").toDouble()
        val angle = (m.getValue("ANGLE").toDouble() % 1) * 2 * PI
        val ca = cos(angle); val sa = sin(angle); val cr = cos(PI / 6); val sr = sin(PI / 6)
        val source = RimeContour.prepare(hz, m.getValue("DETAIL").toDouble(), edge, apertureAngle = angle, apertureLaw = 4,
            variant = RimeContour.variantFor(voice))
        val resonator = RimeBody(voice, hz, body, rate)
        val reveal = min(1.5, gateSeconds * .72) * unfold.pow(1.6)
        val warmCycles = ceil((reveal + 3 * resonator.t60 + .4) * Dsp.RATE / cycleFrames).toInt().coerceAtLeast(2)
        // The final guard cycle absorbs the decimator's end padding. Both measured
        // cycles have their own independently simulated modal and filter histories.
        val raw = FloatArray((warmCycles + 3) * cycleSamples)
        val noise = Dsp.Noise(Dsp.seedFor("RIME", 5, patch.seed, midi, "periodic-friction"))
        val white = DoubleArray(cycleSamples) { noise.next().toDouble() }
        val roughCycle = DoubleArray(cycleSamples)
        val noiseAlpha = 1 - exp(-2 * PI * 12000 / rate)
        var rough = 0.0
        repeat(2) { for (i in white.indices) { rough += noiseAlpha * (white[i] - rough); roughCycle[i] = rough } }
        val contactGain = (.035 + 1.05 * edge.pow(2)) * (.35 + .65 * velocity) * RimeVoicing.contact(voice)
        var phase = 0.0; var peak = 0.0; var maxEnergy = 0.0
        val gains = DoubleArray(source.bands.size)
        val increments = DoubleArray(gains.size)
        val block = (rate / 1000).coerceAtLeast(1)
        val attack = RimeVoicing.attack(voice) +
            (if (source.bands.size > 1) unfold * .008 else 0.0)
        // One phase-aligned periodic scale envelope per band. Table lookup makes
        // its control interpolation repeat exactly even for a fractional ms cycle.
        val motion = if (moving) source.bands.indices.map { b -> FloatArray(1024) { j ->
            val scale = b.toDouble() / max(1, source.bands.lastIndex)
            if (b == 0) 1f else (.16 + .84 * (.5 - .5 * cos(2 * PI * (j / 1024.0 - .22 * scale)))).toFloat()
        } } else emptyList()
        for (i in raw.indices) {
            phase = ((i % cycleSamples).toDouble() * periods / cycleSamples) % 1.0
            if (i % 4096 == 0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("RIME render cancelled")
            val t = i.toDouble() / rate
            if (i % block == 0) for (b in gains.indices) {
                gains[b] = heldBandGain(t, b, gains.size, unfold, gateSeconds.toDouble())
                increments[b] = (heldBandGain((i + block).toDouble() / rate, b, gains.size, unfold, gateSeconds.toDouble()) - gains[b]) / block
            }
            var x = 0.0; var y = 0.0; var dx = 0.0; var dy = 0.0
            var rx = 0.0; var ry = 0.0; var rdx = 0.0; var rdy = 0.0
            // A versioned second-port path length, expressed in root periods.
            // ANGLE rotates the projection and, in model 3, its finite contact aperture.
            val referencePhase = phase - (.035 + .075 * body)
            for (b in source.bands.indices) {
                val band = source.bands[b]
                val gain = gains[b] * if (moving) band.read(motion[b], (i % cycleSamples).toDouble() / cycleSamples) else 1.0
                x += gain * band.read(band.x, phase); y += gain * band.read(band.y, phase)
                dx += gain * band.read(band.dx, phase); dy += gain * band.read(band.dy, phase)
                run {
                    rx += gain * band.read(band.x, referencePhase); ry += gain * band.read(band.y, referencePhase)
                    rdx += gain * band.read(band.dx, referencePhase); rdy += gain * band.read(band.dy, referencePhase)
                }
                gains[b] += increments[b]
            }
            val p = ca * x + sa * y; val reference = cr * rx + sr * ry
            val slopeA = ca * dx + sa * dy; val slopeB = cr * rdx + sr * rdy
            // Bounded smooth pressure, normalized from the admitted derivative energy.
            val pressureA = slopeA / sqrt(1 + .3 * slopeA * slopeA)
            val pressureB = slopeB / sqrt(1 + .3 * slopeB * slopeB)
            rough = roughCycle[i % cycleSamples]
            val friction = RimeVoicing.friction(voice) * rough
            val contactA = contactGain * (pressureA + friction * abs(pressureA))
            val contactB = contactGain * (pressureB + friction * abs(pressureB))
            val envelope = RimeContour.smooth(t / attack)
            val a = velocity * envelope * (p + .50 * contactA)
            val b = .36 * velocity * envelope * (reference + .50 * contactB)
            // The body needs contour-derived upper-mode force. Feeding it mostly the
            // same sine as the direct path made BODY a level change erased by finishing.
            val bodyContact = .45 + .90 * edge
            val driveA = velocity * envelope * (.18 * p + bodyContact * pressureA)
            val driveB = .36 * velocity * envelope * (.18 * reference + bodyContact * pressureB)
            val resonated = resonator.next(driveA, driveB)
            val value = (1 - .45 * body) * a + .18 * b + (3.8 * body) * resonated
            check(value.isFinite() && abs(value) < 16) { "RIME unbounded internal output at $i: $value" }
            raw[i] = value.toFloat(); peak = max(peak, abs(value))
            if (i % (rate / 50) == 0) {
                val energy = resonator.energy()
                check(energy.isFinite() && energy < 1e8) { "RIME modal energy unbounded: $energy" }
                maxEnergy = max(maxEnergy, energy)
            }
        }
        Tide.bandLimit(raw, rate)
        val down = Dsp.decimate(raw, Dsp.RATE)
        val start = warmCycles * cycleFrames
        val end = start + 2 * cycleFrames
        val mean = (start until end).sumOf { down[it].toDouble() } / (2 * cycleFrames)
        val highpass = Dsp.OnePole(Dsp.RATE)
        for (i in down.indices) { val v = (down[i] - mean).toFloat(); down[i] = v - highpass.lp(v, 8f) }
        val steady = down.copyOfRange(start, end)
        val measured = Loudness.of(steady, 1, Dsp.RATE)
        val target = min(Dsp.MELODIC_LOUDNESS_TARGET * velocity.toDouble().pow(.65), measured * 12.0)
        val gain = if (measured > 1e-9 && velocity > 0) min(target / measured, .94 / down.maxOf { abs(it) }.coerceAtLeast(1e-20f)) else 0.0
        for (i in down.indices) down[i] = (down[i] * gain).toFloat()
        val previous = down.copyOfRange(start, start + cycleFrames)
        val cycle = down.copyOfRange(start + cycleFrames, end)
        val seam = if (velocity == 0f) 0.0 else Keys.seamError(previous + cycle, cycleFrames)
        check(seam < Keys.MAX_SEAM_ERROR) { "RIME independently rendered HOLD cycles do not close: $seam" }
        // Retain the real opening. A phase-aligned one-cycle handoff follows it;
        // two unmodified steady cycles then establish the exported loop marker.
        val prefixCycles = ceil((reveal + .12) * Dsp.RATE / cycleFrames).toInt().coerceAtLeast(1)
        val prefix = min(prefixCycles, warmCycles - 1) * cycleFrames
        val samples = FloatArray(prefix + (if (moving) 2 else 3) * cycleFrames)
        down.copyInto(samples, 0, 0, prefix + cycleFrames)
        for (i in 0 until cycleFrames) {
            val mix = RimeContour.smooth(i.toDouble() / (cycleFrames - 1))
            samples[prefix + i] = ((1 - mix) * samples[prefix + i] + mix * previous[i]).toFloat()
        }
        if (moving) {
            cycle.copyInto(samples, prefix + cycleFrames)
        } else {
            previous.copyInto(samples, prefix + cycleFrames)
            cycle.copyInto(samples, prefix + 2 * cycleFrames)
        }
        val note = KeyNote(Snip(samples, 1, Dsp.RATE), (prefix + (if (moving) 1 else 2) * cycleFrames).toLong())
        return Render(note, previous, cycle, seam, hz, peak, maxEnergy)
    }
}
