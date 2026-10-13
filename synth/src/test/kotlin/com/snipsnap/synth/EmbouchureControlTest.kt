package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** These response goals are separate from owner listening and the engine's numerical safety gates. */
class EmbouchureControlTest {
    private data class Input(val exposure: Double, val halfArrivalSeconds: Double)

    private fun input(voice: EmbouchureVoice, macros: Map<String, Float>): Input {
        val trace = Embouchure.play(voice, macros, Embouchure.Probe(model = 2)).trace
        val peak = trace.player.maxOrNull() ?: 0f
        val first = trace.player.indexOfFirst { it >= peak * .5f }
        return Input(trace.player.sumOf { it.toDouble() * it } / trace.rate,
            first.toDouble() / trace.rate)
    }

    private fun harmonicChanges(a: EmbouchureControlMetrics.Reading, b: EmbouchureControlMetrics.Reading): Boolean =
        (1 until a.bodyHarmonicsDb.size).any { harmonic ->
            // A large dB movement in a vanishingly quiet partial cannot substitute for a control response.
            maxOf(a.bodyHarmonicShares[harmonic], b.bodyHarmonicShares[harmonic]) >= .003 &&
                abs(a.bodyHarmonicsDb[harmonic] - b.bodyHarmonicsDb[harmonic]) >= 1.0
        }

    @Test
    fun `five point controls change contact color roughness articulation and phrase span in their intended roles`() {
        val failures = mutableListOf<String>()
        val positions = listOf(0f, .25f, .5f, .75f, 1f)
        for (voice in EmbouchureVoice.entries) for (control in listOf("EFFORT", "REED", "SHAPE", "GRAIN", "LENGTH")) {
            val inputs = mutableListOf<Input>()
            val readings = positions.map { position ->
                val macros = Embouchure.defaults(voice) + (control to position)
                val snip = Embouchure.render(voice, macros, model = 2)
                val gesture = Embouchure.gestureSeconds(voice, macros).toDouble()
                val wanted = Embouchure.frequencyFor(voice, macros.getValue("TUNE"))
                // Read after the initial capture even on a very gentle, late-speaking pressure gesture.
                val pitch = Pitch.detect(snip, (.55 * gesture).toFloat(), minOf(.4, .25 * gesture).toFloat())
                val cents = pitch?.let { FineTuning.cents(it.hz.toDouble(), wanted.toDouble()) }
                if (cents == null || abs(cents) >= 25 || (pitch?.confidence ?: 0f) < .4f)
                    failures += "$voice $control=$position loses the late tonal anchor: cents=$cents, confidence=${pitch?.confidence}"
                if (control == "EFFORT") inputs += input(voice, macros)
                EmbouchureControlMetrics.measure(snip, gesture, pitch?.hz?.toDouble() ?: wanted.toDouble())
            }
            val body = readings.zipWithNext { a, b -> EmbouchureControlMetrics.weightedDb(a.body, b.body) }
            val onset = readings.zipWithNext { a, b -> EmbouchureControlMetrics.weightedDb(a.onset, b.onset) }
            val endpoint = EmbouchureControlMetrics.weightedDb(readings.first().body, readings.last().body)
            println("EMBOUCHURE RESPONSE $voice $control: bodyEnd=$endpoint, bodyNeighbors=$body, onsetNeighbors=$onset, " +
                "attack20=${readings.map { it.attack20Seconds }}, rootShare=${readings.map { it.bodyRootShare }}, " +
                "noiseShare=${readings.map { it.bodyNonharmonicShare }}")
            when (control) {
                "REED", "SHAPE" -> {
                    if (endpoint < 2.0) failures += "$voice $control body range is compressed: $endpoint dB"
                    val active = body.indices.count { i ->
                        body[i] >= .5 || harmonicChanges(readings[i], readings[i + 1])
                    }
                    if (active < 3) failures += "$voice $control has only $active active neighboring regions"
                }
                "GRAIN" -> {
                    if (endpoint < 3.0) failures += "$voice GRAIN has negligible settled texture: $endpoint dB"
                    // Quiet defaults occupy the lower region. The upper half must keep opening,
                    // rather than making an onset shift or a single final-step jump do all the work.
                    for (i in 2..3) if (body[i] < .5)
                        failures += "$voice GRAIN ${positions[i]}→${positions[i + 1]} upper texture plateaus: ${body[i]} dB"
                    val clear = readings.first()
                    val rough = readings.last()
                    if (rough.bodyNonharmonicShare < clear.bodyNonharmonicShare + .01)
                        failures += "$voice GRAIN changes only harmonic color, without continuing breath/contact texture"
                    if (readings.any { it.bodyRootShare < .10 } || rough.bodyNonharmonicShare > .20)
                        failures += "$voice GRAIN buries the pitched driver in noise"
                }
                "EFFORT" -> {
                    // Integral of actual pressure/speed squared is an input-exposure proxy, not
                    // a claim of mechanical joules. It is measured before output loudness matching.
                    println("EMBOUCHURE EFFORT INPUT $voice: exposure=${inputs.map { it.exposure }}, " +
                        "halfArrival=${inputs.map { it.halfArrivalSeconds }}")
                    for (i in 0..3) {
                        if (inputs[i + 1].exposure < inputs[i].exposure * 1.10)
                            failures += "$voice EFFORT ${positions[i]}→${positions[i + 1]} does not increase applied source exposure"
                        if (inputs[i + 1].halfArrivalSeconds > inputs[i].halfArrivalSeconds * .80)
                            failures += "$voice EFFORT ${positions[i]}→${positions[i + 1]} leaves the source rise unchanged"
                    }
                    if (inputs.last().exposure < inputs.first().exposure * 2)
                        failures += "$voice EFFORT compresses the applied source range"
                    val gentle = readings.first().attack20Seconds
                    val firm = readings.last().attack20Seconds
                    if (firm > gentle * .5 || gentle - firm < .1)
                        failures += "$voice EFFORT loses its audible entry difference after gain matching: $gentle→$firm s"
                }
                "LENGTH" -> {
                    val spans = readings.map { it.energy90SpanSeconds }
                    println("EMBOUCHURE LENGTH $voice: energy90Spans=$spans")
                    for (i in 0..3) if (spans[i + 1] - spans[i] < .2)
                        failures += "$voice LENGTH ${positions[i]}→${positions[i + 1]} does not extend the heard phrase: $spans"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
