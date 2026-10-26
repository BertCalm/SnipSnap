package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Pitch
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OhmV3CoreTest {
    private fun capture() = OhmCapture.fromSnip(Snip(FloatArray(11_025) { i ->
        (.35 * sin(2 * PI * 330 * i / 44_100) * (1 - i / 11_025.0)).toFloat()
    }, 1, 44_100), "test contact")

    @Test
    fun `new finite sources are deterministic bounded and retain playing silence`() {
        val sources = listOf(OhmOptions(exciter = OhmExciter.BOW),
            OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture()),
            OhmOptions(elder = .8f, grains = .65f, freeze = .5f))
        for ((i, source) in sources.withIndex()) {
            val m = mapOf("LENGTH" to .15f, "TOUCH" to if (i == 0) .85f else .25f,
                "BODY" to .7f, "MEDDLING" to .8f, "COMMUNE" to .7f)
            val first = OhmV3.renderRaw(m, 48 + 12 * i, .8f, 731, OhmGesture.GATHER, source)
            val second = OhmV3.renderRaw(m, 48 + 12 * i, .8f, 731, OhmGesture.GATHER, source)
            assertContentEquals(first.samples, second.samples)
            assertTrue(first.samples.all { it.isFinite() })
            val peak = first.samples.maxOf { abs(it) }
            assertTrue(peak in 1e-5f..2f, "${source.exciter}: raw peak $peak")
            assertTrue(abs(first.samples.map { it.toDouble() }.average()) < .01, "DC bound")
            assertTrue(OhmV3.renderRaw(m, 60, 0f, 731, options = source).samples.all { it == 0f })
        }
    }

    @Test
    fun `held callback cancellation stops simulation before a whole note finishes`() {
        var calls = 0
        assertFailsWith<CancellationException> {
            OhmV3.renderHeld(options = OhmOptions(exciter = OhmExciter.BOW), cancelled = { ++calls >= 4 })
        }
        assertTrue(calls in 4..5)
    }

    @Test
    fun `captured selection refuses an absent capture rather than quietly changing sources`() {
        assertFailsWith<IllegalArgumentException> { OhmV3.renderRaw(options = OhmOptions(exciter = OhmExciter.CAPTURED)) }
    }

    @Test
    fun `optional elder reaches the actual foreground only through commune including the bow junction`() {
        val base = mapOf("LENGTH" to .15f, "MEDDLING" to .9f, "TOUCH" to .5f, "BODY" to .5f)
        for (exciter in OhmExciter.entries) {
            val off = OhmOptions(exciter = exciter, capture = if (exciter == OhmExciter.CAPTURED) capture() else null)
            for (commune in listOf(0f, 1f)) {
                val m = base + ("COMMUNE" to commune)
                val quiet = OhmV3.foregroundProbe(m, options = off)
                val elder = OhmV3.foregroundProbe(m, options = off.copy(elder = 1f))
                if (commune == 0f) assertContentEquals(quiet.foreground, elder.foreground,
                    "$exciter: an uncoupled extra source changed the primary state")
                else {
                    val from = (quiet.sampleRate * .3).toInt()
                    val until = (quiet.sampleRate * 1.8).toInt()
                    var energy = 0.0; var difference = 0.0
                    for (i in from until until) {
                        val reference = quiet.foreground[i].toDouble()
                        val delta = reference - elder.foreground[i]
                        energy += reference * reference; difference += delta * delta
                    }
                    val changed = difference / energy
                    assertTrue(energy > 1e-12 && changed > 1e-12,
                        "$exciter: reciprocal excitation never reached the primary state ($changed)")
                    println("OHM V3 $exciter causal primary difference energy with COMMUNE1: $changed; COMMUNE0 exact")
                }
            }
        }
    }

    @Test
    fun `bow and captured contacts retain the played register in active early and late bodies`() {
        for (exciter in listOf(OhmExciter.BOW, OhmExciter.CAPTURED)) for (midi in listOf(48, 60, 72)) {
            val options = OhmOptions(exciter = exciter, capture = if (exciter == OhmExciter.CAPTURED) capture() else null)
            val note = OhmV3.renderRaw(mapOf("LENGTH" to .65f, "MEDDLING" to .65f, "COMMUNE" to .7f),
                midi, .8f, 731, options = options)
            for (from in listOf(.5f, 1.25f, 2.5f)) {
                val pitch = Pitch.detect(note, from, .4f)
                assertTrue(pitch != null, "$exciter MIDI$midi at $from: no measurable root")
                val cents = 1200 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
                assertTrue(abs(cents) < 10 && pitch.confidence >= .75f,
                    "$exciter MIDI$midi at $from: $cents cents, confidence ${pitch.confidence}")
                println("OHM V3 $exciter MIDI$midi at $from: $cents cents, confidence ${pitch.confidence}")
            }
        }
    }
}
