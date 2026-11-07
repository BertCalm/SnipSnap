package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Standalone proof for the listening study, deliberately outside production and the factory bank.
 *
 * Timing and decay assertions use delivered PCM16 port envelopes. A changed sample array alone
 * would also accept phase changes, so it cannot establish that REPLY spaces the answers or that
 * RING retains a longer tail. Foreground pitch is measured independently of the octave/third
 * companions; mixed-note estimates are also printed so that distinction remains visible.
 */
object OhmReplyRingStudyCheck {
    private const val RATE = 44_100
    private val base = mapOf("TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f, "MEDDLING" to .9f)

    private data class Case(val midi: Int, val commune: Float, val reply: Float, val ring: Float)
    private val cache = mutableMapOf<Case, OhmReplyRingStudy.Probe>()
    private var renders = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val started = System.nanoTime()
        cache.clear()
        renders = 0
        determinismAndBypasses()
        cornerHeadroomAndPitch()
        replyEnvelopeEvidence()
        ringEnvelopeEvidence()
        val seconds = (System.nanoTime() - started) / 1e9
        println("OHM REPLY/RING study checks PASS: $renders renders, ${"%.3f".format(seconds)} host seconds")
        println("Measured: exactness, PCM16 port pitch, companion timing/decay and raw headroom. Human listening remains pending.")
    }

    private fun rendered(case: Case): OhmReplyRingStudy.Probe = cache.getOrPut(case) {
        renders++
        OhmReplyRingStudy.probe(base + ("COMMUNE" to case.commune), case.midi,
            reply = case.reply, ring = case.ring)
    }

    private fun determinismAndBypasses() {
        val reference = rendered(Case(60, 0f, 0f, 0f))
        renders++
        val again = OhmReplyRingStudy.probe(base + ("COMMUNE" to 0f), reply = 0f, ring = 0f)
        check(reference.snip.samples.contentEquals(again.snip.samples)) { "Study render is not deterministic" }

        // A disconnected bridge permits changing the side damping and their entry clocks without
        // changing one sample of the actual primary port. Coupled equality would be a false claim.
        for (reply in listOf(0f, .5f, 1f)) for (ring in listOf(0f, .5f, 1f)) {
            if (reply == .5f && ring != .5f || ring == .5f && reply != .5f) continue
            val moved = rendered(Case(60, 0f, reply, ring))
            check(reference.foreground.contentEquals(moved.foreground)) {
                "COMMUNE0 changed the foreground when REPLY=$reply RING=$ring"
            }
        }

        val solo = base + mapOf("MEDDLING" to 0f, "COMMUNE" to 0f)
        renders += 2
        val soloA = OhmReplyRingStudy.probe(solo, reply = 0f, ring = 0f)
        val soloB = OhmReplyRingStudy.probe(solo + ("COMMUNE" to 1f), reply = 1f, ring = 1f)
        check(soloA.snip.samples.contentEquals(soloB.snip.samples)) {
            "MEDDLING0 is no longer an exact solo across the study controls and COMMUNE"
        }
        check(soloA.pressurePeak == 0f && soloB.pressurePeak == 0f) { "Solo generated companion pressure" }
        renders++
        val acceptedSolo = OhmV2.renderRaw(solo, midi = 60, velocity = .8f, seed = 731,
            gesture = OhmGesture.STEADY)
        // The study has a longer buffer. Compare before model2's own endpoint fade, rather than
        // accepting an altered primary just because every study setting altered it the same way.
        val unchangedFrames = (4.2 * RATE).toInt()
        check((0 until unchangedFrames).all { soloA.snip.samples[it].toBits() == acceptedSolo.samples[it].toBits() }) {
            "The study changed the accepted model2 solo before its endpoint fade"
        }

        // Muting the companion sources must affect the coupled primary, rather than only the mix.
        val free = rendered(Case(60, 0f, .5f, .5f))
        val coupled = rendered(Case(60, 1f, .5f, .5f))
        renders += 2
        val freeMuted = OhmReplyRingStudy.probe(base + ("COMMUNE" to 0f), companionExcitation = false)
        val coupledMuted = OhmReplyRingStudy.probe(base + ("COMMUNE" to 1f), companionExcitation = false)
        check(free.foreground.contentEquals(freeMuted.foreground)) { "Disconnected companions affected the primary" }
        val influence = relativeDistance(coupled.foreground, coupledMuted.foreground, .25, 2.8)
        check(influence > .005) { "Study lost reciprocal influence into the primary: $influence" }
        println("Exact deterministic/solo/disconnected-primary checks PASS; coupled companion influence=$influence")
    }

    private fun cornerHeadroomAndPitch() {
        println("midi,commune,reply,ring,rawPeak,maxState,pressurePeak,earlyForegroundCents,lateForegroundCents,mixedLateCents,preFadeTailRatio")
        for (midi in listOf(48, 60, 72)) for (commune in listOf(0f, 1f)) {
            for (reply in listOf(0f, 1f)) for (ring in listOf(0f, 1f)) {
                val case = Case(midi, commune, reply, ring)
                val probe = rendered(case)
                val x = probe.snip.samples
                check(probe.snip.channels == 1 && probe.snip.sampleRate == RATE && x.size == 7 * RATE) {
                    "$case did not produce the fixed seven-second mono44.1k study buffer"
                }
                check(x.all { it.isFinite() } && probe.maxState.isFinite() && probe.maxState < 8f) {
                    "$case lost numerical headroom: ${probe.maxState}"
                }
                val peak = x.maxOf { abs(it) }
                check(peak in .001f.. .95f) { "$case raw peak $peak cannot be exported honestly without clipping" }
                check(probe.pressurePeak.isFinite() && probe.pressurePeak in 0f..1.000001f) {
                    "$case allowed overlapping replies to multiply whistle pressure: ${probe.pressurePeak}"
                }
                check(probe.onsets.size == 5 && probe.onsets.asList().zipWithNext().all { (a, b) -> b - a >= .099999 }) {
                    "$case collapsed or reordered companion entries: ${probe.onsets.joinToString()}"
                }
                check(probe.sourceEndSeconds >= probe.onsets.last() + .16 + .4 * .65 - 1e-5) {
                    "$case truncated the final whistle reply"
                }
                val foreground = pcm16(Snip(probe.foreground, 1, RATE))
                val early = nativePitch(foreground, midi, .08f, "$case early foreground")
                val late = nativePitch(foreground, midi, .75f, "$case late foreground")
                val mixed = pcm16(probe.snip)
                val mixedLate = Pitch.detect(mixed, .75f, .35f)?.let {
                    1200 * ln(it.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
                }
                val tailRatio = rms(x, 6.55, 6.8) / rms(x, .03, .25).coerceAtLeast(1e-12)
                check(tailRatio < .03) { "$case endpoint fade hides an unfinished loud tail: $tailRatio" }
                println("$midi,$commune,$reply,$ring,$peak,${probe.maxState},${probe.pressurePeak},$early,$late,$mixedLate,$tailRatio")
            }
        }
    }

    private fun replyEnvelopeEvidence() {
        val steps = listOf(0f, .5f, 1f)
        val centroids = steps.map { reply ->
            val probe = rendered(Case(60, 0f, reply, .5f))
            val side = companionPcm(probe)
            val centroid = energyCentroid(side, .03, 5.5)
            println("REPLY $reply PCM16 companion energy centroid=$centroid seconds, onsets=${probe.onsets.joinToString()}")
            centroid
        }
        for (i in 0 until centroids.lastIndex) {
            check(centroids[i + 1] - centroids[i] > .15) {
                "REPLY ${steps[i]}..${steps[i + 1]} did not meaningfully spread the real companion envelope: $centroids"
            }
        }
    }

    private fun ringEnvelopeEvidence() {
        val steps = listOf(0f, .5f, 1f)
        val retentionDb = steps.map { ring ->
            val probe = rendered(Case(60, 0f, .5f, ring))
            // Use the physical glass port after its final brief stroke, avoiding the still-driven
            // whistle and the unchanged primary. This ratio is invariant to whole-clip level matching.
            val glass = pcm16(Snip(probe.glass, 1, RATE)).samples
            val lastStroke = probe.onsets.last() + .07
            val early = rms(glass, lastStroke + .035, lastStroke + .075)
            val late = rms(glass, lastStroke + .43, lastStroke + .53)
            check(early > 1e-5) { "RING $ring has no measurable glass response" }
            val retained = 20 * log10(late.coerceAtLeast(1e-8) / early)
            println("RING $ring PCM16 glass post-stroke retention=$retained dB; T60 whistle=${probe.whistleT60}, glass=${probe.glassT60}")
            retained
        }
        for (i in 0 until retentionDb.lastIndex) {
            check(retentionDb[i + 1] - retentionDb[i] > 3.0) {
                "RING ${steps[i]}..${steps[i + 1]} did not retain a meaningfully longer real decay: $retentionDb"
            }
        }
    }

    private fun nativePitch(snip: Snip, midi: Int, from: Float, label: String): Double {
        val pitch = checkNotNull(Pitch.detect(snip, from, .35f)) { "$label lost its periodic root" }
        val cents = 1200 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
        check(abs(cents) < 20 && pitch.confidence >= .65f) {
            "$label replaced the requested root: $cents cents, confidence ${pitch.confidence}"
        }
        return cents
    }

    private fun pcm16(snip: Snip): Snip {
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_16) }.toByteArray()
        return WavReader.read(bytes).also {
            check(it.channels == 1 && it.sampleRate == RATE && it.samples.size == snip.samples.size)
        }
    }

    private fun companionPcm(probe: OhmReplyRingStudy.Probe): FloatArray = pcm16(Snip(
        FloatArray(probe.whistle.size) { .55f * probe.whistle[it] + .68f * probe.glass[it] }, 1, RATE,
    )).samples

    private fun rms(x: FloatArray, from: Double, until: Double): Double {
        val first = (from * RATE).toInt().coerceIn(0, x.size)
        val end = (until * RATE).toInt().coerceIn(first, x.size)
        var energy = 0.0
        for (i in first until end) energy += x[i].toDouble() * x[i]
        return sqrt(energy / (end - first).coerceAtLeast(1))
    }

    private fun energyCentroid(x: FloatArray, from: Double, until: Double): Double {
        val first = (from * RATE).toInt().coerceIn(0, x.size)
        val end = (until * RATE).toInt().coerceIn(first, x.size)
        var energy = 0.0
        var timeEnergy = 0.0
        for (i in first until end) {
            val e = x[i].toDouble() * x[i]
            energy += e
            timeEnergy += e * i / RATE
        }
        check(energy > 1e-9) { "No companion audio to measure" }
        return timeEnergy / energy
    }

    private fun relativeDistance(a: FloatArray, b: FloatArray, from: Double, until: Double): Double {
        val first = (from * RATE).toInt()
        val end = minOf(a.size, b.size, (until * RATE).toInt())
        var change = 0.0
        var energy = 0.0
        for (i in first until end) {
            val difference = a[i].toDouble() - b[i]
            change += difference * difference
            energy += b[i].toDouble() * b[i]
        }
        return sqrt(change / energy.coerceAtLeast(1e-20))
    }
}
