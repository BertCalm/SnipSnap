package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The listening regression: six nearly pure fundamentals must not pass just because
 * their phases, levels or tail lengths differ. These gates inspect fixed C4 frames. */
class CorollaTimbreTest {
    private val windows = listOf(Triple("early", .02, .22), Triple("body", .20, .60))

    private fun spectralFrames(voice: CorollaVoice, macros: Map<String, Float> = emptyMap()) =
        Corolla.finish(Corolla.play(voice, macros + ("TUNE" to .5f), seconds = 1.05f).raw,
            normalize = false, fade = false).let { samples ->
            windows.associate { (label, from, until) -> label to CorollaTimbreMetrics.frames(
                samples, Corolla.frequencyFor(voice, .5f).toDouble(), from, until) }
        }

    @Test
    fun `voice defaults differ in partial balance at the same pitch and playing time`() {
        val frames = CorollaVoice.entries.associateWith { spectralFrames(it) }
        for ((i, a) in CorollaVoice.entries.withIndex()) for (b in CorollaVoice.entries.drop(i + 1)) {
            val distance = windows.map { (label, _, _) -> CorollaTimbreMetrics.distance(
                frames.getValue(a).getValue(label), frames.getValue(b).getValue(label)) }.average()
            // Rejected audition: minimum .0013, maximum .0238. This is a generous
            // separation floor, well below the revised source's heard partial changes.
            assertTrue(distance > .07, "$a and $b remain the same spectral envelope: $distance")
        }
        for (voice in CorollaVoice.entries) {
            val body = frames.getValue(voice).getValue("body")
            assertTrue(CorollaTimbreMetrics.rootShare(body) > .10,
                "$voice loses its note while making its upper modes audible")
        }
    }

    @Test
    fun `named timbre controls change spectral envelopes beyond level and decay`() {
        val base = mapOf("PULL" to .6f, "BLOOM" to .4f, "FIELD" to .45f,
            "CONTACT" to .5f, "CHAMBER" to .5f)
        val cases = listOf(
            Triple(CorollaVoice.TONGUE, "PULL", .10),
            Triple(CorollaVoice.BLOSSOM, "BLOOM", .05),
            Triple(CorollaVoice.ORBIT, "FIELD", .08),
            Triple(CorollaVoice.CHATTER, "CONTACT", .08),
            Triple(CorollaVoice.HUSK, "CHAMBER", .10),
        )
        for ((voice, macro, floor) in cases) {
            val low = spectralFrames(voice, base + (macro to .1f)).getValue("body")
            val high = spectralFrames(voice, base + (macro to .9f)).getValue("body")
            val distance = CorollaTimbreMetrics.distance(low, high)
            assertTrue(distance > floor, "$voice $macro has insufficient audible partial change: $distance")
        }
    }

    @Test
    fun `chatter contacts and husk chamber have audible source roles after the attack`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val from = (.2 * rate).toInt()
        val until = (.6 * rate).toInt()
        fun rms(x: FloatArray) = sqrt((from until until).sumOf { x[it].toDouble() * x[it] } / (until - from))

        val chatter = requireNotNull(Corolla.play(CorollaVoice.CHATTER,
            probe = Corolla.Probe(record = true), seconds = .65f).taps)
        val activity = (from until until).count { chatter.contact[it] > 0 }.toDouble() / (until - from)
        assertTrue(activity > .01, "CHATTER stops contacting after its attack: activity $activity")

        val husk = requireNotNull(Corolla.play(CorollaVoice.HUSK,
            probe = Corolla.Probe(record = true), seconds = .65f).taps)
        val bodyShare = rms(husk.chamber) / rms(husk.direct)
        assertTrue(bodyShare > .20, "HUSK's chamber is too quiet to define the object: ratio $bodyShare")
    }

    /** Fit the expected orbit plus a slow quadratic trend. The amplitude of the
     * periodic term cannot pass just because a sustained note is still settling. */
    private fun orbitAmplitude(series: DoubleArray, hz: Double): Double {
        val normal = Array(5) { DoubleArray(6) }
        for (i in series.indices) {
            val t = i.toDouble() / (series.size - 1) - .5
            val phase = 2 * PI * hz * i * 2048 / Dsp.RATE
            val basis = doubleArrayOf(1.0, t, t * t, sin(phase), cos(phase))
            for (r in 0 until 5) {
                for (c in 0 until 5) normal[r][c] += basis[r] * basis[c]
                normal[r][5] += basis[r] * series[i]
            }
        }
        for (p in 0 until 5) {
            val pivot = (p until 5).maxBy { abs(normal[it][p]) }
            val old = normal[p]
            normal[p] = normal[pivot]
            normal[pivot] = old
            val diagonal = normal[p][p]
            require(abs(diagonal) > 1e-9)
            for (c in p..5) normal[p][c] /= diagonal
            for (r in 0 until 5) if (r != p) {
                val factor = normal[r][p]
                for (c in p..5) normal[r][c] -= factor * normal[p][c]
            }
        }
        return sqrt(normal[3][5] * normal[3][5] + normal[4][5] * normal[4][5])
    }

    @Test
    fun `orbit moves partial balance at the core rate rather than only the volume`() {
        val voice = CorollaVoice.ORBIT
        val field = Corolla.defaults(voice).getValue("FIELD")
        val played = Corolla.play(voice, mapOf("FIELD" to field, "HOLD" to 1f), seconds = 7f)
        val samples = Corolla.finish(played.raw, normalize = false, fade = false)
        val frames = CorollaTimbreMetrics.frames(samples,
            Corolla.frequencyFor(voice, .5f).toDouble(), 1.0, 6.8)
        val rate = Corolla.coreHz(field, voice)
        val centroid = orbitAmplitude(frames.map { it.centroidHz }.toDoubleArray(), rate)
        val balance = orbitAmplitude(frames.map { it.rootShare }.toDoubleArray(), rate)
        // Capture several slow turns so a transient cannot masquerade as circulation.
        // Frame normalization removes an overall gain modulation.
        assertTrue(centroid > 10.0, "ORBIT's core barely changes the timbre: centroid swing $centroid Hz")
        assertTrue(balance > .01, "ORBIT's core barely redistributes partial power: swing $balance")
    }
}
