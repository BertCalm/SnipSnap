package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Independent measurements used to establish Skitter's prototype acceptance margins. */
internal object SkitterTestMeasure {
    val timbral = listOf("BUZZ", "SKITTER", "AGITATE", "VENT", "STATIC")

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var energy = 0.0
        for (i in from until to) energy += x[i].toDouble() * x[i]
        return sqrt(energy / (to - from).coerceAtLeast(1))
    }

    /** Least-squares gain matching removes a scalar volume change from the comparison. */
    fun distance(a: FloatArray, b: FloatArray, from: Int = 0, to: Int = minOf(a.size, b.size)): Double {
        var aa = 0.0
        var bb = 0.0
        var ab = 0.0
        for (i in from until to) {
            aa += a[i].toDouble() * a[i]
            bb += b[i].toDouble() * b[i]
            ab += a[i].toDouble() * b[i]
        }
        if (aa <= 1e-20 || bb <= 1e-20) return if (aa == bb) 0.0 else 1.0
        val gain = (ab / bb).coerceAtLeast(0.0)
        var diff = 0.0
        for (i in from until to) {
            val d = a[i] - gain * b[i]
            diff += d * d
        }
        return sqrt(diff / aa)
    }

    fun cents(measured: Double, expected: Double): Double = 1200 * ln(measured / expected) / ln(2.0)

    /** Four-corner timbral interaction after removing each corner's RMS gain. */
    fun interaction(lowLow: FloatArray, highLow: FloatArray, lowHigh: FloatArray, highHigh: FloatArray): Double {
        val corners = listOf(lowLow, highLow, lowHigh, highHigh)
        val n = corners.minOf { it.size }
        val levels = corners.map { rms(it, to = n).coerceAtLeast(1e-12) }
        var energy = 0.0
        for (i in 0 until n) {
            val residual = lowLow[i] / levels[0] - highLow[i] / levels[1] - lowHigh[i] / levels[2] + highHigh[i] / levels[3]
            energy += residual * residual
        }
        return sqrt(energy / n)
    }

    fun moderate(voice: SkitterVoice): Map<String, Float> = Skitter.defaults(voice).mapValues { (name, value) ->
        when (name) { "SKITTER" -> minOf(value, .35f); "AGITATE" -> minOf(value, .55f); "STATIC" -> minOf(value, .2f); else -> value }
    }

    /** Onset/body noise and pre-release sustain, independent of the classifier's peak-time heuristic. */
    fun bodyFacts(snip: Snip): DoubleArray {
        val rate = snip.sampleRate
        val early = rms(snip.samples, (.1 * rate).toInt(), (.4 * rate).toInt())
        val late = rms(snip.samples, (.95 * rate).toInt(), (1.2 * rate).toInt())
        val body = Snip(snip.samples.copyOfRange((.55 * rate).toInt(), (.95 * rate).toInt()), 1, rate)
        return doubleArrayOf(late / early, FeatureExtractor.extract(snip).flatness.toDouble(), FeatureExtractor.extract(body).flatness.toDouble())
    }

    fun motion(trace: List<SkitterTrace>, from: Double, to: Double): Double =
        trace.filter { it.seconds.toDouble() in from..to }.maxOfOrNull { abs(it.agitation.toDouble()) } ?: 0.0

    fun pitchDeparture(trace: List<SkitterTrace>, from: Double, to: Double): Double =
        trace.filter { it.seconds.toDouble() in from..to }.maxOfOrNull {
            maxOf(abs(it.pitchOffsetA.toDouble()), abs(it.pitchOffsetB.toDouble()))
        } ?: 0.0
}

/** Run outside JUnit to print the evidence before choosing new numerical margins. */
object SkitterMeasure {
    @JvmStatic
    fun main(args: Array<String>) {
        val modes = args.toSet().ifEmpty { setOf("pitch", "macros", "identity", "held", "stability") }
        if ("pitch" in modes) pitch()
        if ("macros" in modes) macros()
        if ("identity" in modes) identity()
        if ("held" in modes) held()
        if ("stability" in modes) stability()
        if ("features" in modes) features()
    }

    private fun pitch() {
        println("PITCH,voice,midi,velocity,kind,hz,confidence,cents,class,bodyClass,rawPeak,rawRms,rawDc,sustainRatio,onsetFlatness,bodyFlatness")
        for (voice in SkitterVoice.entries) for (midi in listOf(36, 48, 60, 72)) for (velocity in listOf(.25f, .6f, 1f)) for (moderate in listOf(false, true)) {
            val result = Skitter.renderProbe(voice, if (moderate) SkitterTestMeasure.moderate(voice) else Skitter.defaults(voice), midi = midi, velocity = velocity)
            val found = Pitch.detect(result.snip, .55f, .4f)
            val cents = found?.let { SkitterTestMeasure.cents(it.hz.toDouble(), Keys.midiHz(midi).toDouble()) }
            val d = result.diagnostics
            val body = Snip(result.snip.samples.copyOf(minOf(result.snip.samples.size, (1.4 * Dsp.RATE).toInt())), 1, Dsp.RATE)
            println("PITCH,$voice,$midi,$velocity,${if (moderate) "moderate" else "default"},${found?.hz},${found?.confidence},$cents,${Classifier.classify(result.snip).drumClass},${Classifier.classify(body).drumClass},${d.rawPeak},${d.rawRms},${d.rawDc},${SkitterTestMeasure.bodyFacts(result.snip).joinToString(",")}")
        }
    }

    private fun macros() {
        println("MACRO,voice,macro,low02,step025,step050,step075,step100,whole")
        for (voice in SkitterVoice.entries) for (name in SkitterTestMeasure.timbral) {
            val base = Skitter.defaults(voice)
            val steps = listOf(0f, .02f, .25f, .5f, .75f, 1f).map {
                Skitter.renderProbe(voice, base + (name to it), seconds = 1.2f).raw
            }
            val ds = steps.zipWithNext().map { (a, b) -> SkitterTestMeasure.distance(a, b) }
            val whole = SkitterTestMeasure.distance(steps.first(), steps.last())
            println("MACRO,$voice,$name,${ds.joinToString(",")},$whole")
        }
    }

    private fun identity() {
        println("IDENTITY,voice,probe,distance,agitationDifference,cutoffDifference,gestureCount")
        for (voice in SkitterVoice.entries) {
            val macros = Skitter.defaults(voice) + mapOf("SKITTER" to .55f, "AGITATE" to .7f, "STATIC" to .55f)
            val full = Skitter.renderProbe(voice, macros, seconds = 1.2f)
            val probes = listOf(
                "memory" to SkitterProbe(memoryFrozen = true),
                "agitation" to SkitterProbe(agitationDisabled = true),
                "equalRecovery" to SkitterProbe(equalRecovery = true),
                "noiseForcing" to SkitterProbe(noiseForcingDisabled = true),
            )
            for ((name, probe) in probes) {
                val ablated = Skitter.renderProbe(voice, macros, seconds = 1.2f, probe = probe)
                val paired = full.trace.zip(ablated.trace)
                val agitation = paired.maxOf { (a, b) -> abs(a.agitation.toDouble() - b.agitation.toDouble()) }
                val cutoff = paired.maxOf { (a, b) -> abs(a.cutoffHz.toDouble() - b.cutoffHz.toDouble()) }
                println("IDENTITY,$voice,$name,${SkitterTestMeasure.distance(full.raw, ablated.raw)},$agitation,$cutoff,${full.diagnostics.gestureCount}")
            }
            val finite = Skitter.renderProbe(voice, Skitter.defaults(voice) + mapOf("SKITTER" to .18f, "AGITATE" to .4f, "STATIC" to 0f), seconds = 7f)
            val early = SkitterTestMeasure.motion(finite.trace, .0, .4)
            val late = SkitterTestMeasure.motion(finite.trace, 6.0, 7.0)
            val pEarly = SkitterTestMeasure.pitchDeparture(finite.trace, .0, .1)
            val pLate = SkitterTestMeasure.pitchDeparture(finite.trace, 6.0, 7.0)
            println("SETTLE,$voice,$early,$late,$pEarly,$pLate,${SkitterTestMeasure.rms(finite.raw, finite.raw.size - Dsp.RATE / 2)},${finite.diagnostics.gestureCount}")
            for (name in listOf("BUZZ", "SKITTER", "STATIC", "VENT")) {
                val corners = listOf(0f to 0f, .75f to 0f, 0f to .75f, .75f to .75f).map { (other, agitate) ->
                    Skitter.renderProbe(voice, Skitter.defaults(voice) + mapOf(name to other, "AGITATE" to agitate), seconds = 1.2f).raw
                }
                println("INTERACTION,$voice,$name,${SkitterTestMeasure.interaction(corners[0], corners[1], corners[2], corners[3])}")
            }
        }
    }

    private fun held() {
        println("HELD,voice,midi,kind,seam,stateError,periods,converged,agitation,offset,peak")
        for (voice in SkitterVoice.entries) for (midi in listOf(24, 48, 84)) for (extreme in listOf(false, true)) {
            val macros = if (extreme) Skitter.defaults(voice).mapValues { 1f } else Skitter.defaults(voice) + ("HOLD" to 1f)
            val result = Skitter.renderProbe(voice, macros, midi = midi, seed = 917)
            val d = result.diagnostics
            println("HELD,$voice,$midi,${if (extreme) "high" else "default"},${Keys.seamError(result.snip.samples, result.loopStart)},${d.loopStateError},${d.loopPrerollPeriods},${d.loopConverged},${d.agitationPeak},${d.pitchOffsetPeakCents},${d.rawPeak}")
        }
    }

    private fun stability() {
        println("STABILITY,voice,midi,velocity,kind,peak,rms,dcRatio,filter,history,signedHistory,agitation,agitationVelocity,pitch,bounds")
        for (voice in SkitterVoice.entries) for (midi in 24..84 step 12) for (velocity in listOf(.2f, .6f, 1f)) for (extreme in listOf(false, true)) {
            val macros = if (extreme) Skitter.defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else 1f } else Skitter.defaults(voice)
            val d = Skitter.renderProbe(voice, macros, midi = midi, velocity = velocity, seconds = 1.2f).diagnostics
            println("STABILITY,$voice,$midi,$velocity,${if (extreme) "high" else "default"},${d.rawPeak},${d.rawRms},${abs(d.rawDc) / d.rawRms},${d.filterStatePeak},${d.historyPeak},${d.signedHistoryPeak},${d.agitationPeak},${d.agitationVelocityPeak},${d.pitchOffsetPeakCents},${d.boundHits}")
        }
    }

    private fun features() {
        println("FEATURES,voice,midi,velocity,kind,duration,class,pitch,confidence,decayMs,centroid,low,mid,high,flatness,nearPeakSeconds,earlyRms,lateRms,end300Rms,peak300Rms,endPeakRatio")
        val cases = listOf(Triple(SkitterVoice.LEAD, 72, 1f), Triple(SkitterVoice.HOLLOW, 36, .6f), Triple(SkitterVoice.HOLLOW, 36, 1f),
            Triple(SkitterVoice.CHIRP, 48, .25f), Triple(SkitterVoice.FRAY, 72, 1f))
        for ((voice, midi, velocity) in cases) {
            val full = Skitter.render(voice, SkitterTestMeasure.moderate(voice), midi, velocity)
            val variants = listOf("full" to full) + listOf(1.4, 1.5).map { seconds ->
                "crop$seconds" to Snip(full.samples.copyOf((seconds * Dsp.RATE).toInt()), 1, Dsp.RATE)
            }
            for ((kind, snip) in variants) {
                val f = FeatureExtractor.extract(snip)
                val pitch = Pitch.detect(snip, .55f, .4f)
                val hop = 256
                // FeatureExtractor uses overlapping 1024-frame (23 ms) peak windows with a 256-frame hop.
                val envelopes = (0 until snip.samples.size / hop).map { i ->
                    val from = i * hop
                    snip.samples.sliceArray(from until minOf(from + 1024, snip.samples.size)).maxOf { abs(it.toDouble()) }
                }
                val nearPeak = envelopes.indexOfFirst { it >= envelopes.max() * .99 } * hop.toDouble() / Dsp.RATE
                val early = SkitterTestMeasure.rms(snip.samples, (.1 * Dsp.RATE).toInt(), (.4 * Dsp.RATE).toInt())
                val late = SkitterTestMeasure.rms(snip.samples, (1.0 * Dsp.RATE).toInt(), (1.3 * Dsp.RATE).toInt())
                val window = (.3 * Dsp.RATE).toInt()
                val end = SkitterTestMeasure.rms(snip.samples, snip.samples.size - window)
                val peak = (0..snip.samples.size - window step 441).maxOf { from -> SkitterTestMeasure.rms(snip.samples, from, from + window) }
                println("FEATURES,$voice,$midi,$velocity,$kind,${snip.durationSeconds},${Classifier.classify(snip).drumClass},${pitch?.hz},${pitch?.confidence},${f.decayMs},${f.centroidHz},${f.lowRatio},${f.midRatio},${f.highRatio},${f.flatness},$nearPeak,$early,$late,$end,$peak,${end / peak}")
            }
        }
    }
}
