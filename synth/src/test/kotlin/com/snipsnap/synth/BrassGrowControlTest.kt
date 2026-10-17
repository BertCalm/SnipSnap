package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Focused physical/host contracts; BrassGrowPrototypeGenerator owns the complete 60-render grid. */
class BrassGrowControlTest {
    private fun controls(grow: Float, held: Boolean = false) = mapOf(
        "TUNE" to 0f, "GROW" to grow, "LENGTH" to .5f, "HOLD" to if (held) 1f else 0f,
    )

    private fun rms(samples: FloatArray, rate: Int, from: Double, to: Double): Double {
        val first = (from * rate).toInt().coerceIn(0, samples.size)
        val end = (to * rate).toInt().coerceIn(first, samples.size)
        require(end > first)
        var energy = 0.0
        for (i in first until end) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (end - first))
    }

    private fun rootShare(snip: Snip, wanted: Float, from: Float): Double {
        val n = 32768
        val count = (.25f * snip.sampleRate).toInt()
        val first = (from * snip.sampleRate).toInt()
        require(first >= 0 && first + count <= snip.frameCount)
        val real = FloatArray(n)
        val imaginary = FloatArray(n)
        for (i in 0 until count) {
            val window = .5 - .5 * cos(2 * PI * i / (count - 1))
            real[i] = (snip.samples[first + i] * window).toFloat()
        }
        Fft.forward(real, imaginary)
        var fundamental = 0.0
        var total = 0.0
        for (i in 1 until n / 2) {
            val hz = i.toDouble() * snip.sampleRate / n
            if (hz !in 35.0..16000.0) continue
            val power = real[i].toDouble() * real[i] + imaginary[i].toDouble() * imaginary[i]
            total += power
            if (hz in wanted * .96..wanted * 1.04) fundamental += power
        }
        return fundamental / total.coerceAtLeast(1e-300)
    }

    @Test
    fun `soft low rough brass keeps its requested root through the finite and sustained phrase`() {
        val wanted = Keys.midiHz(48)
        for (held in listOf(false, true)) {
            val rendered = BrassGrow.renderMeasured(controls(0f, held), 48, .3f)
            val sourceEnd = rendered.played.sourceEndSeconds.toDouble()
            val sourceFrom = listOf(.2, .5, .8).map { (.15 + (sourceEnd - .4) * it).toFloat() }
            val nativeEnd = if (held) rendered.raw.durationSeconds.toDouble() else sourceEnd
            val nativeFrom = listOf(.2, .5, .8).map { (.15 + (nativeEnd - .4) * it).toFloat() }
            for ((label, source, windows) in listOf(
                Triple("source", rendered.source, sourceFrom),
                Triple("native", rendered.raw, nativeFrom),
            )) for (from in windows) {
                val pitch = assertNotNull(Pitch.detect(source, from, .25f), "held=$held $label at $from has no root")
                val cents = FineTuning.cents(pitch.hz.toDouble(), wanted.toDouble())
                assertTrue(abs(cents) < 25 && pitch.confidence >= .5f,
                    "held=$held $label at $from entered an upper regime: $cents cents, confidence ${pitch.confidence}")
                assertTrue(rootShare(source, wanted, from) > .01,
                    "held=$held $label at $from keeps the fundamental only as a negligible trace")
            }
        }
    }

    @Test
    fun `the same played note has identical source and output through tune or stored midi`() {
        for (held in listOf(false, true)) {
            val storedC3 = BrassGrow.renderMeasured(controls(0f, held) + ("TUNE" to .5f), 48, .3f)
            val storedC4 = BrassGrow.renderMeasured(controls(0f, held), 60, .3f)
            assertEquals(storedC3.played.hz, storedC4.played.hz)
            assertEquals(storedC3.played.seed, storedC4.played.seed)
            assertContentEquals(storedC3.played.raw, storedC4.played.raw,
                "held=$held a capture guard used the stored MIDI instead of the played note")
            assertContentEquals(storedC3.source.samples, storedC4.source.samples)
            assertContentEquals(storedC3.raw.samples, storedC4.raw.samples,
                "held=$held native output depends on the base/TUNE encoding")
            assertContentEquals(storedC3.snip.samples, storedC4.snip.samples,
                "held=$held normalized output depends on the base/TUNE encoding")
        }
    }

    @Test
    fun `grow changes a single feedback-driven physical source rather than only output gain`() {
        val settings = controls(.75f)
        val linked = BrassGrow.playSource(settings, 60, .7f, totalSeconds = 1.1f, record = true)
        val open = BrassGrow.playSource(settings, 60, .7f, totalSeconds = 1.1f,
            record = true, feedback = false)
        assertContentEquals(linked.trace.player, open.trace.player,
            "feedback ablation must leave the player's pressure score alone")
        val a = rms(linked.raw, linked.rate, .2, .9).coerceAtLeast(1e-12)
        val b = rms(open.raw, open.rate, .2, .9).coerceAtLeast(1e-12)
        val from = (linked.rate * .2).toInt()
        val to = (linked.rate * .9).toInt()
        var difference = 0.0
        for (i in from until to) {
            val d = linked.raw[i] / a - open.raw[i] / b
            difference += d * d
        }
        assertTrue(sqrt(difference / (to - from)) > .04,
            "removing returning pressure did not change the gain-matched source")
        assertTrue(!linked.trace.driver.contentEquals(open.trace.driver),
            "the contact driver never receives its own resonator's returning pressure")
    }

    @Test
    fun `the middle grow anchor has continuous source pressure and a constant note seed`() {
        val values = listOf(.499f, .5f, .501f).map {
            BrassGrow.playSource(controls(it), 60, .7f, totalSeconds = .6f, record = true)
        }
        assertEquals(1, values.map { it.seed }.distinct().size,
            "GROW must not replace the note's noise realization")
        val center = values[1].trace.player
        assertTrue(center.any { it > .1f }, "continuity was checked on a silent score")
        for (neighbor in listOf(values[0], values[2])) {
            assertEquals(center.size, neighbor.trace.player.size)
            val maximumChange = center.indices.maxOf { abs(center[it] - neighbor.trace.player[it]) }
            assertTrue(maximumChange < .01f,
                "GROW crosses a discontinuous pressure/gesture switch at 0.5: $maximumChange")
        }
    }

    @Test
    fun `native velocity changes player work before output leveling`() {
        val quiet = BrassGrow.playSource(controls(.75f), 60, .3f, totalSeconds = 1.7f, record = true)
        val strong = BrassGrow.playSource(controls(.75f), 60, .9f, totalSeconds = 1.7f, record = true)
        assertEquals(quiet.seed, strong.seed)
        assertTrue(strong.trace.player.maxOrNull()!! > quiet.trace.player.maxOrNull()!!,
            "velocity changed only a final playback gain")
        assertTrue(rms(strong.raw, strong.rate, .4, 1.2) > rms(quiet.raw, quiet.rate, .4, 1.2),
            "native source energy does not respond to velocity")
    }

    @Test
    fun `held source work stops and its raw stored energy dissipates across register and grow`() {
        for (grow in listOf(0f, .5f, 1f)) for ((midi, velocity) in listOf(48 to .3f, 72 to .9f)) {
            val p = BrassGrow.playSource(controls(grow, held = true), midi, velocity, held = true,
                sourceStopSeconds = .8f, totalSeconds = 2.4f, record = true)
            val label = "GROW=$grow MIDI=$midi velocity=$velocity"
            assertTrue(p.raw.all { it.isFinite() } && p.maxAbs < 8f && p.maxEnergy < 16, label)
            val stop = (.8f * p.rate / p.trace.hop).toInt() + 1
            assertTrue(p.trace.player.drop(stop).all { it == 0f }, "$label keeps supplying work after release")
            val before = rms(p.raw, p.rate, .6, .8)
            val late = rms(p.raw, p.rate, 2.15, 2.4)
            assertTrue(before > 1e-8, "$label did not speak before the stop")
            assertTrue(late < before * .025, "$label raw passive tail does not dissipate: $before -> $late")
            val firstEnergy = p.trace.energy.drop(stop).take(100).average()
            val lastEnergy = p.trace.energy.takeLast(100).average()
            assertTrue(firstEnergy > 0 && lastEnergy < firstEnergy * .05,
                "$label stored energy does not dissipate: $firstEnergy -> $lastEnergy")
            assertTrue(p.raw.none { it != 0f && abs(it) < java.lang.Float.MIN_NORMAL },
                "$label raw release retains subnormal output")
        }
    }

    @Test
    fun `finite and held outputs reproduce after an unrelated source render`() {
        for (held in listOf(false, true)) {
            val settings = controls(.25f, held)
            val first = BrassGrow.renderMeasured(settings, 60, .6f)
            BrassGrow.playSource(controls(1f), 72, .3f, totalSeconds = .1f)
            val second = BrassGrow.renderMeasured(settings, 60, .6f)
            assertContentEquals(first.played.raw, second.played.raw, "held=$held raw source is not deterministic")
            assertContentEquals(first.raw.samples, second.raw.samples, "held=$held native output is not deterministic")
            assertContentEquals(first.snip.samples, second.snip.samples, "held=$held production output is not deterministic")
            if (held) {
                val join = assertNotNull(first.join)
                assertEquals(join, second.join)
                assertEquals(true, join["qualityPassed"])
                assertTrue((join.getValue("candidateCount") as Number).toInt() <= 17,
                    "capture exceeded its bounded search")
            }
        }
    }

    @Test
    fun `zero velocity supplies exact silence for both render modes`() {
        for (held in listOf(false, true)) {
            val output = BrassGrow.renderMeasured(controls(.75f, held), 60, 0f)
            assertTrue(output.played.raw.all { it == 0f }, "held=$held zero velocity supplies physical work")
            assertTrue(output.raw.samples.isNotEmpty() && output.raw.samples.all { it == 0f })
            assertTrue(output.snip.samples.all { it == 0f })
        }
    }

    @Test
    fun `length endpoints render the complete short and long finite gesture across register`() {
        for ((midi, grow) in listOf(48 to 0f, 72 to 1f)) {
            val short = BrassGrow.renderMeasured(controls(grow) + ("LENGTH" to 0f), midi, .7f)
            val long = BrassGrow.renderMeasured(controls(grow) + ("LENGTH" to 1f), midi, .7f)
            assertTrue(long.played.sourceEndSeconds > short.played.sourceEndSeconds * 2,
                "MIDI=$midi GROW=$grow LENGTH does not extend the actual player's gesture")
            assertTrue(long.snip.frameCount > short.snip.frameCount)
            for (output in listOf(short, long)) {
                assertTrue(output.snip.durationSeconds > output.played.sourceEndSeconds + .5f,
                    "MIDI=$midi GROW=$grow finite LENGTH endpoint truncates its passive release")
                assertTrue(output.snip.samples.all { it.isFinite() && abs(it) <= 1f })
            }
        }
    }

    @Test
    fun `held length endpoints keep captured joins and change the played phrase pace`() {
        for ((midi, grow) in listOf(48 to 0f, 72 to 1f)) {
            val short = BrassGrow.renderMeasured(controls(grow, held = true) + ("LENGTH" to 0f),
                midi, .7f, record = true)
            val long = BrassGrow.renderMeasured(controls(grow, held = true) + ("LENGTH" to 1f),
                midi, .7f, record = true)
            assertTrue(long.played.sourceEndSeconds > short.played.sourceEndSeconds * 1.5f,
                "MIDI=$midi GROW=$grow held LENGTH does not change the breath-phrase pace")
            fun atTwoSeconds(output: BrassGrow.Rendered): Float {
                val frame = 2 * output.played.rate / output.played.trace.hop
                return output.played.trace.player[frame]
            }
            assertTrue(abs(atTwoSeconds(short) - atTwoSeconds(long)) > .01f,
                "MIDI=$midi GROW=$grow held LENGTH changes duration metadata without changing pressure timing")
            for (output in listOf(short, long)) {
                val join = assertNotNull(output.join)
                assertEquals(true, join["qualityPassed"], "MIDI=$midi GROW=$grow LENGTH endpoint cannot close")
                assertTrue((join.getValue("candidateCount") as Number).toInt() <= 17)
                assertTrue(output.snip.samples.all { it.isFinite() && abs(it) <= 1f })
            }
        }
    }

    @Test
    fun `a tiny positive velocity remains bounded without a denormal output tail`() {
        val output = BrassGrow.renderMeasured(controls(.5f), 60, 1e-20f)
        assertTrue(output.raw.samples.all { it.isFinite() })
        assertTrue(output.snip.samples.all { it.isFinite() && abs(it) <= 1f })
        assertTrue(output.snip.samples.none { it != 0f && abs(it) < java.lang.Float.MIN_NORMAL })
    }

    @Test
    fun `capture keeps native level even when the source is extremely quiet`() {
        val source = FloatArray(9 * Dsp.RATE) { i ->
            val t = i.toDouble() / Dsp.RATE
            ((.5 * sin(2 * PI * Keys.midiHz(60) * t) +
                .12 * sin(4 * PI * Keys.midiHz(60) * t)) * (1 + .02 * sin(1.7 * t))).toFloat()
        }
        val ordinary = BrassGrowCapture.close(source)
        val scale = 1e-12f
        val quiet = BrassGrowCapture.close(FloatArray(source.size) { source[it] * scale })
        assertEquals(true, ordinary.metadata["qualityPassed"])
        assertEquals(true, quiet.metadata["qualityPassed"])
        assertEquals(ordinary.metadata["lengthFrames"], quiet.metadata["lengthFrames"])
        assertEquals(ordinary.metadata["sourceCaptureStartFrames"], quiet.metadata["sourceCaptureStartFrames"])
        assertEquals(false, quiet.metadata["normalized"])
        // The quiet-crossing choice may move under float rounding; undo each circular
        // turn before comparing the native waveform at its captured source position.
        val ordinaryJunction = (ordinary.metadata.getValue("junctionFrames") as Number).toInt()
        val quietJunction = (quiet.metadata.getValue("junctionFrames") as Number).toInt()
        val maximum = ordinary.samples.maxOf { abs(it) } * scale
        val error = quiet.samples.indices.maxOf { i ->
            val a = ordinary.samples[(i + ordinaryJunction) % ordinary.samples.size]
            val b = quiet.samples[(i + quietJunction) % quiet.samples.size]
            abs(b - a * scale)
        }
        assertTrue(error < maximum * 1e-5f, "capture secretly leveled or changed the quiet note: $error")
    }

    @Test
    fun `superseded physical renders and capture searches honor interruption`() {
        val actions = listOf<() -> Unit>(
            { BrassGrow.renderMeasured(controls(.25f), 60, .7f) },
            { BrassGrow.renderMeasured(controls(.75f, held = true), 60, .7f) },
            { BrassGrowCapture.close(FloatArray(1)) },
        )
        for (action in actions) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<CancellationException> { action() }
            } finally {
                Thread.interrupted()
            }
        }
        assertFailsWith<CancellationException> {
            BrassGrowCapture.close(FloatArray(1), cancelled = { true })
        }
    }

    @Test
    fun `grow source controls refuse unsupported or nonfinite macro values`() {
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { BrassGrow.configuration(controls(value)) }
        }
        assertFailsWith<IllegalArgumentException> {
            BrassGrow.configuration(mapOf("EFFORT" to .5f))
        }
    }
}
