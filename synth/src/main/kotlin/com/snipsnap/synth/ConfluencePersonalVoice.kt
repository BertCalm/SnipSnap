package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A deliberately small personal source route through the existing Confluence performer renderer.
 * It accepts a finite, steady vowel, not arbitrary speech, a whole song, or an indefinite HOLD.
 * Qualification inspects untreated amplitude and a wider pitch range than the PSOLA analyzer to
 * reject detectable out-of-range octave aliases. A confident correlation
 * is evidence of periodicity, not proof that its earliest peak is the vocal fundamental.
 *
 * All thresholds below are conservative first-version product bounds, not universal rules for
 * singers. Original playback remains an exact PCM copy; qualification never levels or repairs it.
 */
object ConfluencePersonalVoice {
    const val VERSION = 1
    const val ANALYSIS_VERSION = Confluence.ANALYSIS_VERSION
    const val CLIP_LEVEL = .999f
    const val MIN_SELECTION_SECONDS = .6f
    const val MAX_SELECTION_SECONDS = 8f
    const val MAX_SOURCE_SECONDS = 30
    const val MIN_REFERENCE_HZ = 80f
    const val MAX_REFERENCE_HZ = 500f
    private const val MIN_RMS = .003f
    private const val DIVISOR = 4
    private const val WINDOW = 1024
    private const val HOP = 256
    private const val MIN_CORRELATION = .85
    private const val MAX_SPREAD_CENTS = 85f
    private const val MIN_STABLE_SECONDS = .4f

    enum class Problem {
        FORMAT, NONFINITE, INVALID_SELECTION, TOO_SHORT, TOO_LONG, SILENT, TOO_QUIET,
        CLIPPED, UNCERTAIN_PITCH, UNSTABLE_PITCH, AMBIGUOUS_PITCH, UNSUPPORTED_PITCH,
    }

    data class Issue(val code: Problem, val message: String)

    data class Assessment(
        val issues: List<Issue>,
        val sourceHash: String?,
        val startFrame: Int,
        val endFrame: Int,
        val suggestedStartFrame: Int?,
        val suggestedEndFrame: Int?,
        val referenceMidi: Float?,
        val rootMidi: Int?,
        val referenceHz: Float?,
        val confidence: Float,
        val stableFraction: Float,
        val pitchSpreadCents: Float,
        val peak: Float,
        val rms: Float,
        val clippedSamples: Int,
        val analysisVersion: Int = ANALYSIS_VERSION,
    ) {
        val usable: Boolean get() = issues.isEmpty()
        val message: String get() = issues.firstOrNull()?.message ?: "Ready for a finite personal voice."

        fun toJsonValue(): JsonValue.Obj {
            fun n(value: Number?) = value?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null
            return JsonValue.Obj(linkedMapOf(
                "version" to n(VERSION), "analysisVersion" to n(analysisVersion),
                "usable" to JsonValue.Bool(usable), "message" to JsonValue.Str(message),
                "sourceHash" to (sourceHash?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                "startFrame" to n(startFrame), "endFrame" to n(endFrame),
                "suggestedStartFrame" to n(suggestedStartFrame), "suggestedEndFrame" to n(suggestedEndFrame),
                "referenceMidi" to n(referenceMidi), "rootMidi" to n(rootMidi), "referenceHz" to n(referenceHz),
                "confidence" to n(confidence), "stableFraction" to n(stableFraction),
                "pitchSpreadCents" to n(pitchSpreadCents), "peak" to n(peak), "rms" to n(rms),
                "clippedSamples" to n(clippedSamples),
                "issues" to JsonValue.Arr(issues.map { JsonValue.Obj(mapOf(
                    "code" to JsonValue.Str(it.code.name), "message" to JsonValue.Str(it.message),
                )) }),
            ))
        }
    }

    class PreparedVoice internal constructor(
        val assessment: Assessment,
        internal val source: ConfluenceSource,
        internal val analysis: ConfluenceVoice.Analysis,
    ) {
        val sourceHash: String get() = source.hash
        val startFrame: Int get() = assessment.startFrame
        val endFrame: Int get() = assessment.endFrame
        val referenceMidi: Float get() = checkNotNull(assessment.referenceMidi)
        val rootMidi: Int get() = checkNotNull(assessment.rootMidi)
        val referenceHz: Float get() = checkNotNull(assessment.referenceHz)
        val analysisVersion: Int get() = assessment.analysisVersion
        internal val key get() = ConfluenceAnalysisKey(sourceHash, startFrame, endFrame, referenceMidi, analysisVersion)
        val analysisKey: String get() = "$sourceHash:$startFrame:$endFrame:${referenceMidi.toRawBits()}:$analysisVersion"
    }

    /** Count raw rail contacts in every channel before downmixing or quantization can conceal them. */
    fun clippedSampleCount(snip: Snip): Int = snip.samples.count { abs(it) >= CLIP_LEVEL }

    fun assess(snip: Snip, startFrame: Int = 0, endFrame: Int = snip.frameCount): Assessment {
        // A capture/player may own and reuse its buffer. Freeze valid bounded input before the
        // evidence and content hash are computed; malformed/oversized inputs refuse without a copy.
        val snapshot = if (snip.channels == 1 && snip.sampleRate == Confluence.RATE &&
            snip.frameCount <= MAX_SOURCE_SECONDS * Confluence.RATE)
            Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate) else snip
        return inspect(snapshot, startFrame, endFrame).assessment
    }

    /**
     * Requalify a snapshot of exactly the requested region, without applying suggested trims.
     * A persisted reference is a consistency check within five cents, never an escape from refusal.
     */
    fun prepare(
        snip: Snip,
        startFrame: Int = 0,
        endFrame: Int = snip.frameCount,
        referenceMidi: Float? = null,
    ): PreparedVoice {
        require(snip.channels == 1 && snip.sampleRate == Confluence.RATE) {
            "Use untreated mono 44.1 kHz audio for personal voice assessment."
        }
        require(snip.frameCount <= MAX_SOURCE_SECONDS * Confluence.RATE) { "Choose a recording no longer than thirty seconds." }
        val snapshot = Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        val inspected = inspect(snapshot, startFrame, endFrame)
        require(inspected.assessment.usable) { inspected.assessment.message }
        val measured = checkNotNull(inspected.assessment.referenceMidi)
        require(referenceMidi == null || referenceMidi.isFinite() && abs(referenceMidi - measured) <= .05f &&
            referenceMidi.roundToInt() == measured.roundToInt()) {
            "The saved reference pitch disagrees with this recording. Assess the voice again."
        }
        val root = referenceMidi ?: measured
        val assessment = inspected.assessment.copy(referenceMidi = root, rootMidi = measured.roundToInt())
        val source = checkNotNull(inspected.source)
        val analysis = if (root == measured) checkNotNull(inspected.analysis)
            else ConfluenceVoice.analyze(source.pcm.copyOfRange(startFrame, endFrame), root)
        return PreparedVoice(assessment, source, analysis)
    }

    /** No normalization, resampling, pitch correction, fade or performer treatment. */
    fun original(prepared: PreparedVoice): Snip =
        Snip(prepared.source.pcm.copyOfRange(prepared.startFrame, prepared.endFrame), 1, Confluence.RATE)

    /**
     * One complete finite vowel gesture per performer, with room-release margin. Nearby notes
     * target the nearest equal-tempered root plus the requested semitones; original() retains the
     * exact recorded tuning. Individuality deliberately uses only the lower third of the existing
     * identity range. Even at zero it is processed audio, not the original reference.
     */
    fun render(
        prepared: PreparedVoice,
        performers: Int = 1,
        semitones: Int = 0,
        individuality: Float = .2f,
        room: Float = 0f,
    ): ConfluenceReport {
        require(performers in setOf(1, 2, 4)) { "Choose one, two or four personal performers." }
        require(semitones in -2..2) { "Personal voice previews are limited to two semitones either side." }
        require(individuality.isFinite() && individuality in 0f..1f) { "Individuality must be between zero and one." }
        require(room.isFinite() && room in 0f..1f) { "Room must be between zero and one." }
        val region = ConfluenceRegion(prepared.sourceHash, prepared.startFrame, prepared.endFrame,
            referenceMidi = prepared.referenceMidi)
        val duration = (prepared.endFrame - prepared.startFrame).toFloat() / Confluence.RATE
        val recipe = ConfluenceRecipe(
            "MY VOICE", ConfluenceProfile.UNISON, listOf(region),
            macros = mapOf("CROWD" to Confluence.crowdForCount(performers), "REGISTER" to 0f,
                "DIVERGENCE" to individuality * .3f, "RESPONSE" to 0f, "SPACE" to room),
            seed = Dsp.seedFor("confluence-personal", prepared.analysisKey),
            midi = prepared.rootMidi + semitones,
            seconds = max(2f, duration * 1.06f + 1.7f),
        )
        return Confluence.renderWithAnalysis(recipe, mapOf(prepared.sourceHash to prepared.source),
            ConfluenceOptions(), mapOf(prepared.key to prepared.analysis))
    }

    private data class Inspected(val assessment: Assessment, val source: ConfluenceSource?, val analysis: ConfluenceVoice.Analysis?)
    private data class Observation(val center: Int, val hz: Float, val correlation: Float, val rms: Float,
        val ambiguous: Boolean, val unsupported: Boolean = false)

    private fun inspect(snip: Snip, start: Int, end: Int): Inspected {
        val issues = ArrayList<Issue>()
        fun issue(code: Problem, text: String) { if (issues.none { it.code == code }) issues += Issue(code, text) }
        var hash: String? = null
        var peak = 0f; var rms = 0f; var clipped = 0
        var midi: Float? = null; var hz: Float? = null
        var confidence = 0f; var fraction = 0f; var spread = 0f
        var suggestedStart: Int? = null; var suggestedEnd: Int? = null
        fun result(source: ConfluenceSource? = null, analysis: ConfluenceVoice.Analysis? = null) = Inspected(
            Assessment(issues.toList(), hash, start, end, suggestedStart, suggestedEnd, midi, midi?.roundToInt(), hz,
                confidence, fraction, spread, peak, rms, clipped), source, analysis)
        if (snip.channels != 1 || snip.sampleRate != Confluence.RATE) {
            issue(Problem.FORMAT, "Use untreated mono 44.1 kHz audio for personal voice assessment.")
            return result()
        }
        if (snip.samples.any { !it.isFinite() }) {
            issue(Problem.NONFINITE, "The recording contains invalid audio samples.")
            return result()
        }
        if (snip.frameCount > MAX_SOURCE_SECONDS * Confluence.RATE) {
            issue(Problem.TOO_LONG, "Choose a recording no longer than thirty seconds.")
            return result()
        }
        if (start < 0 || end > snip.frameCount || end <= start) {
            issue(Problem.INVALID_SELECTION, "Choose a start before the end, inside the recording.")
            return result()
        }
        val length = end - start
        val seconds = length.toFloat() / Confluence.RATE
        if (seconds < MIN_SELECTION_SECONDS) issue(Problem.TOO_SHORT, "Hold one vowel for at least 0.6 seconds.")
        if (seconds > MAX_SELECTION_SECONDS) issue(Problem.TOO_LONG, "Choose at most eight seconds of one held vowel.")
        // Assessment is scoped to the selected material. Capture/library callers separately check
        // every selected raw channel before downmix; clipping in an excluded region is not hidden.
        for (i in start until end) if (abs(snip.samples[i]) >= CLIP_LEVEL) clipped++
        if (clipped > 0) issue(Problem.CLIPPED, "The recording reaches clipping level. Record again with more microphone headroom.")
        var power = 0.0
        for (i in start until end) { val sample = snip.samples[i]; peak = max(peak, abs(sample)); power += sample.toDouble() * sample }
        rms = sqrt(power / length).toFloat()
        if (peak < 1e-5f || rms < 1e-6f) issue(Problem.SILENT, "No usable voice was recorded.")
        else if (rms < MIN_RMS) issue(Problem.TOO_QUIET, "The voice is too quiet. Move a little closer and record a relaxed held vowel.")
        if (snip.frameCount >= 256) hash = ConfluenceSource.fromSnip(snip).hash
        if (seconds < MIN_SELECTION_SECONDS || peak < 1e-5f || rms < MIN_RMS) return result()
        val selected = snip.samples.copyOfRange(start, end)
        val observations = observe(selected)
        val active = observations.filter { it.rms >= max(MIN_RMS, rms * .15f) }
        val confident = active.filter { it.correlation >= MIN_CORRELATION }
        fraction = if (active.isEmpty()) 0f else confident.size.toFloat() / active.size
        if (confident.isEmpty() || fraction < .75f) {
            issue(Problem.UNCERTAIN_PITCH, "A steady pitch could not be established. Try a clear, comfortable aah or oo.")
            return result()
        }
        val estimatedHz = percentile(confident.map { it.hz }, .5f)
        hz = estimatedHz
        midi = (69 + 12 * log2(estimatedHz / 440f))
        confidence = percentile(confident.map { it.correlation }, .5f)
        spread = cents(percentile(confident.map { it.hz }, .9f) / percentile(confident.map { it.hz }, .1f))
        var stableRun = 0
        var longestStableRun = 0
        for (observation in observations) {
            if (observation.rms >= max(MIN_RMS, rms * .15f) && observation.correlation >= MIN_CORRELATION &&
                !observation.ambiguous && abs(cents(observation.hz / estimatedHz)) <= 60f) {
                stableRun += HOP * DIVISOR
                longestStableRun = max(longestStableRun, stableRun)
            } else stableRun = 0
        }
        // Quiet padding cannot turn a brief syllable into a held vowel. Require one continuous
        // confidently tracked body as well as a sufficiently long selected buffer.
        if (estimatedHz !in MIN_REFERENCE_HZ..MAX_REFERENCE_HZ ||
            confident.count { it.unsupported } > max(1, confident.size / 12)) {
            issue(Problem.UNSUPPORTED_PITCH, "This pitch is outside the current personal-voice range. Try a comfortable lower or higher note.")
        }
        if (spread > MAX_SPREAD_CENTS) issue(Problem.UNSTABLE_PITCH, "The pitch changes too much for one held voice. Keep one comfortable note.")
        if (confident.count { it.ambiguous } > max(1, confident.size / 12)) {
            issue(Problem.AMBIGUOUS_PITCH, "The recording has competing pitch candidates. Try a clearer vowel rather than forcing an octave.")
        }
        if (longestStableRun < (MIN_STABLE_SECONDS * Confluence.RATE).roundToInt() &&
            issues.none { it.code in setOf(Problem.UNSUPPORTED_PITCH, Problem.UNSTABLE_PITCH, Problem.AMBIGUOUS_PITCH) }) {
            issue(Problem.UNCERTAIN_PITCH, "Hold one steady vowel for longer; at least 0.4 seconds of stable voice is needed.")
        }
        // Suggestions are bounded source-envelope trims, not an automatic replacement for selection.
        val first = active.firstOrNull()?.center
        val last = active.lastOrNull()?.center
        if (first != null && last != null) {
            suggestedStart = max(start, start + first - (Confluence.RATE * .12f).roundToInt())
            suggestedEnd = min(min(end, start + last + (Confluence.RATE * .18f).roundToInt()),
                suggestedStart!! + (MAX_SELECTION_SECONDS * Confluence.RATE).roundToInt())
            if (suggestedEnd!! - suggestedStart!! < (MIN_SELECTION_SECONDS * Confluence.RATE).roundToInt()) {
                suggestedStart = null; suggestedEnd = null
            }
        }
        if (issues.isNotEmpty()) return result()
        val source = ConfluenceSource.fromSnip(snip)
        val analysis = ConfluenceVoice.analyze(selected, midi)
        val differences = confident.filter { !it.ambiguous }.map {
            abs(cents((Confluence.RATE / analysis.periodAt(it.center.toDouble())).toFloat() / it.hz))
        }
        if (differences.isEmpty() || percentile(differences, .9f) > 65f) {
            issue(Problem.AMBIGUOUS_PITCH, "The voice processor cannot track this recording consistently. Try a clearer, comfortable vowel.")
        }
        return result(source, analysis)
    }

    /** Independent wide-range qualification. Unlike the performer tracker, this includes 20–1200 Hz. */
    private fun observe(samples: FloatArray): List<Observation> {
        val small = FloatArray((samples.size + DIVISOR - 1) / DIVISOR) { index ->
            var sum = 0f; var count = 0
            for (i in index * DIVISOR until min(samples.size, (index + 1) * DIVISOR)) { sum += samples[i]; count++ }
            sum / count
        }
        val rate = Confluence.RATE.toFloat() / DIVISOR
        val minLag = (rate / 1200).toInt().coerceAtLeast(2)
        val maxLag = ceil(rate / 20).toInt()
        val observations = ArrayList<Observation>()
        val x = DoubleArray(WINDOW)
        val correlations = DoubleArray(maxLag + 2)
        for (center in 0 until small.size step HOP) {
            var mean = 0.0
            for (i in x.indices) { x[i] = small.getOrElse(center + i - WINDOW / 2) { 0f }.toDouble(); mean += x[i] }
            mean /= WINDOW
            var power = 0.0
            for (i in x.indices) { x[i] -= mean; power += x[i] * x[i] }
            val rms = sqrt(power / WINDOW).toFloat()
            if (rms < MIN_RMS * .5f) { observations += Observation(center * DIVISOR, 0f, 0f, rms, false); continue }
            for (lag in minLag - 1..maxLag + 1) {
                var xy = 0.0; var xx = 0.0; var yy = 0.0
                for (i in 0 until WINDOW - lag) { val a = x[i]; val b = x[i + lag]; xy += a * b; xx += a * a; yy += b * b }
                correlations[lag] = xy / sqrt(xx * yy + 1e-24)
            }
            val peaks = (minLag..maxLag).filter { correlations[it] >= correlations[it - 1] && correlations[it] >= correlations[it + 1] }
            val strongest = peaks.maxOfOrNull { correlations[it] } ?: 0.0
            val lag = peaks.firstOrNull { correlations[it] >= strongest - .018 } ?: minLag
            val left = correlations[lag - 1]; val middle = correlations[lag]; val right = correlations[lag + 1]
            val denominator = left - 2 * middle + right
            val delta = if (abs(denominator) > 1e-10) (.5 * (left - right) / denominator).coerceIn(-.5, .5) else 0.0
            val hz = (rate / (lag + delta)).toFloat()
            // A longer period alone is not proof of a lower fundamental: every pure tone repeats
            // after two periods. Require independent spectral support below the selected candidate.
            val spectrum = FloatArray(WINDOW * 2)
            val imaginary = FloatArray(spectrum.size)
            for (i in x.indices) spectrum[i] = (x[i] * (.5 - .5 * cos(2 * PI * i / (WINDOW - 1)))).toFloat()
            Fft.forward(spectrum, imaginary)
            fun bandPower(frequency: Float): Double {
                val from = max(1, ((frequency * .94f - 3f) * spectrum.size / rate).toInt())
                val to = min(spectrum.size / 2 - 1, ceil((frequency * 1.06f + 3f) * spectrum.size / rate).toInt())
                var peak = 0.0
                for (i in from..to) peak = max(peak, spectrum[i].toDouble() * spectrum[i] + imaginary[i].toDouble() * imaginary[i])
                return peak
            }
            val candidatePower = bandPower(hz)
            var strongestBin = 1
            var strongestPower = 0.0
            for (i in 1 until spectrum.size / 2) {
                val powerAtBin = spectrum[i].toDouble() * spectrum[i] + imaginary[i].toDouble() * imaginary[i]
                if (powerAtBin > strongestPower) { strongestPower = powerAtBin; strongestBin = i }
            }
            // An above-band pure tone can still correlate after two or three cycles. Refuse a
            // candidate with no spectral support at its claimed root rather than accepting that
            // subharmonic as a safe voice; genuine missing-fundamental material is deferred too.
            val unsupportedRoot = candidatePower < strongestPower * .0005 &&
                strongestBin * rate / spectrum.size > MAX_REFERENCE_HZ
            // Real recordings contain weak low-frequency room/breath energy. That energy alone
            // cannot establish a lower root: require the corresponding longer period to fit
            // better too. Search a small lag neighborhood to allow fractional-period rounding.
            // The wider 20 Hz floor lets this test corroborate low roots such as 30 Hz instead
            // of treating any low spectral leakage as an unsupported fundamental.
            val lowerCandidates = (2..3).filter { divisor ->
                val longer = lag * divisor
                if (longer > maxLag) false else {
                    val longerCorrelation = (max(minLag, longer - 2)..min(maxLag, longer + 2))
                        .maxOf { correlations[it] }
                    longerCorrelation > middle + .001 &&
                        bandPower(hz / divisor) > max(1e-12, candidatePower * .0004)
                }
            }
            val lowerOutOfBand = lowerCandidates.any { hz / it < MIN_REFERENCE_HZ }
            val ambiguous = candidatePower < strongestPower * .0005 || lowerCandidates.isNotEmpty()
            observations += Observation(center * DIVISOR, hz, middle.toFloat(), rms, ambiguous, unsupportedRoot || lowerOutOfBand)
        }
        return observations
    }

    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[((sorted.lastIndex * fraction).roundToInt()).coerceIn(0, sorted.lastIndex)]
    }

    private fun cents(ratio: Float): Float = (1200 * ln(ratio.toDouble()) / ln(2.0)).toFloat()
}
