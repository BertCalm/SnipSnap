package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.test.*

/** Measure the rendered note, independently of the contact's modulation law. */
internal object CairnSingMotionMeasure {
    private fun amplitude(a: FloatArray, hz: Double, from: Int, count: Int, hann: Boolean): Double {
        val angle = 2 * PI * hz / Cairn.RATE
        val rc = cos(angle); val rs = sin(angle)
        var c = cos(angle * from); var s = sin(angle * from)
        var real = 0.0; var imaginary = 0.0; var weight = 0.0
        for (i in 0 until count) {
            val w = if (hann) .5 - .5 * cos(2 * PI * i / (count - 1)) else 1.0
            real += a[from + i] * w * c; imaginary += a[from + i] * w * s; weight += w
            val old = c; c = rc * old - rs * s; s = rs * old + rc * s
        }
        return 2 * hypot(real, imaginary) / weight
    }
    private fun depth(values: List<Double>): Double {
        val sorted = values.sorted()
        val lo = sorted[(sorted.size * .05).toInt()]
        val hi = sorted[(sorted.size * .95).toInt()]
        return (hi - lo) / (hi + lo).coerceAtLeast(1e-30)
    }
    fun measure(samples: FloatArray, midi: Int): Map<String, Double> {
        val hz = (Cairn.frequencyFor(midi) * 2).roundToInt() / 2.0
        val window = (.12 * Cairn.RATE).roundToInt()
        val root = mutableListOf<Double>(); val colour = mutableListOf<Double>()
        repeat(64) { i ->
            val first = ((samples.size - window).toLong() * i / 63).toInt()
            val fundamental = amplitude(samples, hz, first, window, true)
            root += fundamental
            colour += amplitude(samples, 2 * hz, first, window, true) / fundamental.coerceAtLeast(1e-30)
        }
        val carrier = amplitude(samples, hz, 0, samples.size, false)
        return mapOf("rootEnvelopeDepth" to depth(root), "harmonicBalanceDepth" to depth(colour),
            "lowerSidebandRatio" to amplitude(samples, hz - .5, 0, samples.size, false) / carrier,
            "upperSidebandRatio" to amplitude(samples, hz + .5, 0, samples.size, false) / carrier)
    }
}

class CairnSingMotionTest {
    private val voice = CairnVoice.SING
    private val held get() = Cairn.defaults(voice) + ("HOLD" to 1f)
    private fun relative(a: FloatArray, b: FloatArray) = sqrt(a.indices.sumOf {
        (a[it] - b[it]).toDouble().pow(2)
    } / b.sumOf { it.toDouble().pow(2) })

    @Test fun `held song gains slow beating and changing harmonic balance at all three registers`() {
        for (note in listOf(48, 60, 72)) {
            val old = CairnSingMotionMeasure.measure(Cairn.render(voice, held, note, model = 4).samples, note)
            val new = CairnSingMotionMeasure.measure(Cairn.render(voice, held, note, model = 5).samples, note)
            assertTrue(new.getValue("rootEnvelopeDepth") in .07.. .30, "MIDI$note motion $new")
            assertTrue(new.getValue("rootEnvelopeDepth") > 3 * old.getValue("rootEnvelopeDepth"))
            // A master tremolo cannot change this within-note partial/root ratio.
            assertTrue(new.getValue("harmonicBalanceDepth") > .10, "MIDI$note only gain moved: $new")
            assertTrue(new.getValue("lowerSidebandRatio") > .025 && new.getValue("upperSidebandRatio") > .025)
            println("SING motion MIDI$note before=$old after=$new")
        }
    }

    @Test fun `moving contact keeps its root during the whole held cycle`() {
        var maxCents = 0.0; var minConfidence = 1f
        for (note in listOf(48, 60, 72)) for (groove in listOf(0f, .5f, 1f)) {
            val a = Cairn.render(voice, held + ("GROOVE" to groove), note)
            for (from in listOf(.15f, .8f, 1.4f)) {
                val p = assertNotNull(Pitch.detect(a, fromSec = from, windowSec = .3f))
                val cents = abs(1200.0 * log2(p.hz / Cairn.frequencyFor(note)))
                assertTrue(cents < 8 && p.confidence > .8f, "MIDI$note GROOVE$groove t$from $cents cents / ${p.confidence}")
                maxCents = max(maxCents, cents); minConfidence = min(minConfidence, p.confidence)
            }
        }
        println("SING moving root maximum=$maxCents cents minimum confidence=$minConfidence")
    }

    @Test fun `real moving continuation closes and matches twelve independent forced cycles`() {
        for (note in listOf(48, 60, 72)) for (groove in listOf(0f, 1f)) {
            val m = held + ("GROOVE" to groove)
            val a = Cairn.inspect(voice, m, note)
            val reference = Cairn.inspect(voice, m, note, options = Cairn.Options(fullLoopPreroll = true))
            assertEquals(2 * Cairn.RATE, a.samples.size)
            assertContentEquals(a.full.copyOfRange(a.diagnostics.loopStartFrame, a.full.size), a.samples)
            assertTrue(a.diagnostics.seamError!! < Keys.MAX_SEAM_ERROR)
            assertTrue(relative(a.samples, reference.samples) < 1e-5)
            assertTrue(a.diagnostics.motion.events.isEmpty())
            println("SING moving MIDI$note GROOVE$groove real seam=${a.diagnostics.seamError} warm=${relative(a.samples, reference.samples)}")
        }
    }

    @Test fun `held control corners remain bounded tonal deterministic and silent at zero velocity`() {
        val controls = listOf("WEIGHT", "SLOPE", "GROOVE", "FRICTION", "BODY")
        for (bits in 0 until 32) {
            val m = held + controls.mapIndexed { i, name -> name to if (bits and (1 shl i) == 0) 0f else 1f }
            val a = Cairn.inspect(voice, m, 48)
            assertTrue(a.raw.all { it.isFinite() } && a.samples.all { it.isFinite() })
            assertTrue(a.diagnostics.rawPeak in .0001..2.0)
            assertTrue(a.samples.maxOf { abs(it) } in .01f.. .991f && abs(a.samples.average()) < .002)
            assertEquals(DrumClass.LOOP, Classifier.classify(Snip(a.samples, 1, Cairn.RATE)).drumClass)
            assertTrue(a.diagnostics.seamError!! < Keys.MAX_SEAM_ERROR)
        }
        val a = Cairn.inspect(voice, held, velocity = .35f)
        Cairn.clearMotionCache()
        assertContentEquals(a.samples, Cairn.inspect(voice, held, velocity = .35f).samples)
        assertTrue(Cairn.inspect(voice, held, velocity = 0f).raw.all { it == 0f })
        val low = CairnSingMotionMeasure.measure(Cairn.render(voice, held + ("GROOVE" to 0f)).samples, 60)
        val high = CairnSingMotionMeasure.measure(Cairn.render(voice, held + ("GROOVE" to 1f)).samples, 60)
        assertTrue(high.getValue("rootEnvelopeDepth") > 1.4 * low.getValue("rootEnvelopeDepth"))
    }

    @Test fun `new motion is versioned and finite songs and earlier contact voices keep their audio`() {
        for (v in CairnVoice.entries) for (note in listOf(48, 60, 72)) {
            for (hold in if (v == voice) listOf(0f, .5f, .95f) else listOf(0f, 1f)) {
                val m = Cairn.defaults(v) + ("HOLD" to hold)
                assertContentEquals(Cairn.render(v, m, note, model = 4).samples,
                    Cairn.render(v, m, note, model = 5).samples, "$v MIDI$note HOLD$hold changed")
            }
        }
        val old = CairnPatch("Before the moving rim", voice, held, model = 4)
        val new = old.copy(model = 5)
        assertEquals(old, CairnPatch.fromJsonText(old.toJsonText()))
        assertEquals(new, CairnPatch.fromJsonText(new.toJsonText()))
        assertTrue(relative(old.render().samples, new.render().samples) > .05)
        assertEquals(5, CairnPatch("New song", voice, held).model)
    }
}
