package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Regenerable evidence for the opt-in, single-source model-3 BRASS GROW prototype.
 * Generates every pitch/velocity/GROW/finite-or-held cell, including failed cells.
 * WAVs and JSON belong in ignored testkit output; no earlier audition is read or replaced.
 */
object BrassGrowPrototypeGenerator {
    private const val MODEL = Embouchure.BRASS_GROW_MODEL
    private const val RAW_WAV_GAIN = .5f
    private const val MATCHED_LEVEL = .03f
    private const val PITCH_LIMIT_CENTS = 25.0
    private const val PITCH_WINDOW_SECONDS = .25f
    private const val DC_LIMIT = .02
    private const val BLEND_BUMP_LIMIT_DB = .5
    private const val RENDER_SOFT_LIMIT_SECONDS = 30.0
    private const val EXPECTED_CELLS = 60
    private val notes = listOf(48, 60, 72)
    private val velocities = listOf(.3f, .9f)
    private val grows = listOf(0f, .25f, .5f, .75f, 1f)

    private data class Cell(val midi: Int, val velocity: Float, val grow: Float, val held: Boolean) {
        val id get() = "m${midi}_v${(velocity * 100).toInt()}_g${(grow * 100).toInt().toString().padStart(3, '0')}_${if (held) "held" else "finite"}"
        val macros get() = linkedMapOf("TUNE" to 0f, "GROW" to grow, "LENGTH" to .5f, "HOLD" to if (held) 1f else 0f)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) {
            "Usage: BrassGrowPrototypeGenerator [ignored-output-directory]"
        }
        val repository = repository()
        val output = File(args.firstOrNull() ?: File(repository, "testkit/brass-grow-prototype").path).canonicalFile
        require(output.toPath().startsWith(File(repository, "testkit").canonicalFile.toPath())) {
            "Generated BRASS GROW evidence must stay under the repository's testkit directory"
        }
        val ignored = ProcessBuilder("git", "check-ignore", "-q", output.path + File.separator)
            .directory(repository).start().waitFor() == 0
        require(ignored) { "Output must be ignored by git before rendering: $output" }
        output.mkdirs()

        val cells = notes.flatMap { midi -> velocities.flatMap { velocity -> grows.flatMap { grow ->
            listOf(Cell(midi, velocity, grow, false), Cell(midi, velocity, grow, true))
        } } }
        check(cells.size == EXPECTED_CELLS && cells.map { it.id }.distinct().size == EXPECTED_CELLS)
        val rows = mutableListOf<Map<String, Any?>>()
        val began = System.nanoTime()
        val provenance = provenance(repository)
        for (cell in cells) {
            cancelled()
            // A failed regeneration must never leave its previous successful WAV beside the new report.
            for (path in listOf("audio/${cell.id}-raw.wav", "audio/${cell.id}-matched.wav", "recipes/${cell.id}.json")) {
                val previous = File(output, path)
                check(!previous.exists() || previous.delete()) { "Cannot replace generated evidence $previous" }
            }
            val started = System.nanoTime()
            val row = try {
                renderCell(cell, output)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                linkedMapOf<String, Any?>(
                    "id" to cell.id, "midi" to cell.midi, "velocity" to cell.velocity,
                    "grow" to cell.grow, "kind" to if (cell.held) "held" else "finite",
                    "model" to MODEL, "macros" to cell.macros, "passed" to false,
                    "failures" to listOf("${failure.javaClass.simpleName}: ${failure.message}"),
                    "timing" to mapOf("failedAfterSeconds" to elapsed(started)),
                )
            }
            rows += row
            writeEvidence(output, rows, provenance, elapsed(began), complete = rows.size == EXPECTED_CELLS)
            println("BRASS GROW ${rows.size}/$EXPECTED_CELLS ${cell.id}: ${if (row["passed"] == true) "pass" else "FAIL"} (${elapsed(started)} s)")
        }
        val failed = rows.filter { it["passed"] != true }
        check(failed.isEmpty()) {
            "${failed.size}/$EXPECTED_CELLS BRASS GROW cells failed; full evidence retained in ${File(output, "matrix.json")}: " +
                failed.joinToString { "${it["id"]}: ${it["failures"]}" }
        }
        println("Wrote all $EXPECTED_CELLS native/matched pairs and matrix evidence to $output")
    }

    private fun renderCell(cell: Cell, output: File): Map<String, Any?> {
        val failures = mutableListOf<String>()
        fun demand(condition: Boolean, failure: String) { if (!condition) failures += failure }
        val patch = EmbouchurePatch("BRASS GROW ${cell.id}", EmbouchureVoice.BRASS, cell.macros,
            midi = cell.midi, velocity = cell.velocity, model = MODEL)
        val recipe = PadRecipe(patch = patch)
        val recipeText = recipe.toJsonText()
        val restored = PadRecipe.fromJsonText(recipeText)
        demand(restored == recipe && restored.toJsonText() == recipeText, "saved recipe does not round-trip exactly")
        val sourceConfiguration = BrassGrow.configuration(cell.macros, cell.held, cell.midi, cell.velocity)

        val renderStarted = System.nanoTime()
        // This one measured call uses exactly the production source, output treatment and held capture.
        val rendered = BrassGrow.renderMeasured(cell.macros, cell.midi, cell.velocity, record = true)
        val renderSeconds = elapsed(renderStarted)
        val analysisStarted = System.nanoTime()
        val native = rendered.raw
        val played = rendered.played
        requireAudio(native)
        requireAudio(rendered.source)
        requireAudio(rendered.snip)
        check(played.raw.isNotEmpty() && played.raw.all { it.isFinite() }) { "nonfinite or empty physical source" }
        demand(played.maxAbs < 8f && played.maxEnergy < 16.0, "physical source exceeded model stability bounds")
        demand(native.peak() > 1e-6f, "native captured output is silent")
        demand(rendered.snip.peak() < 1f, "production-normalized output would clip")
        demand(native.peak() * RAW_WAV_GAIN < 1f, "fixed raw WAV gain would clip; no native WAV written")
        demand(abs(mean(rendered.source.samples)) < DC_LIMIT, "conditioned source DC exceeds $DC_LIMIT")
        demand(abs(mean(native.samples)) < DC_LIMIT, "native captured output DC exceeds $DC_LIMIT")

        val matched = AuditionLevel.level(native)
        requireAudio(matched)
        demand(matched.peak() <= .99001f, "matched peak guard failed")
        demand(abs(Loudness.of(matched) - MATCHED_LEVEL) < 1e-5f, "matched loudness misses 0.03 (peak guard may be active)")
        val sourceBodyEnd = minOf(rendered.source.durationSeconds.toDouble(), played.sourceEndSeconds.toDouble())
        val outputBodyEnd = if (cell.held) native.durationSeconds.toDouble()
            else minOf(native.durationSeconds.toDouble(), played.sourceEndSeconds.toDouble())
        val sourcePitch = pitchWindows(rendered.source, played.hz, sourceBodyEnd)
        val outputPitch = pitchWindows(native, played.hz, outputBodyEnd)
        for ((label, readings) in listOf("source" to sourcePitch, "native" to outputPitch)) {
            demand(readings.all { it["captured"] == true }, "$label pitch exceeds $PITCH_LIMIT_CENTS cents or loses root capture")
        }

        val release = if (cell.held) null else release(played)
        if (release != null) {
            demand(release["sourceWorkStops"] == true, "physical source continues player work after source-off")
            demand(release["tailDecays"] == true, "unfaded raw finite tail does not dissipate")
            demand(release["noSubnormalTail"] == true, "raw passive tail contains subnormal values")
        }
        val join = rendered.join
        val independentJoin = if (cell.held && join != null) heldEvidence(rendered) else null
        if (cell.held) {
            demand(join != null, "held capture omitted its source-continuation evidence")
            if (join != null) {
                val independent = requireNotNull(independentJoin)
                demand(join["qualityPassed"] == true, "authoritative held capture failed its quality gate")
                demand(number(join, "correlation")?.let { it >= .8 } == true, "held real-continuation correlation below 0.80")
                demand(number(join, "blendDipDb")?.let { it >= -1.0 } == true, "held blend dip below -1 dB")
                demand(number(join, "candidateCount")?.let { it in 1.0..17.0 } == true,
                    "held capture candidate count is outside 1..17")
                for (key in listOf("junctionStepToP99Ratio", "wrapStepToP99Ratio", "blendExitStepToP99Ratio"))
                    demand(number(join, key)?.let { it <= 2.0 } == true, "held $key exceeds 2")
                demand(independent["metadataMatchesSamples"] == true,
                    "held join metadata differs from actual source/output samples")
                demand(independent["outsideBlendMatchesSource"] == true,
                    "held output outside the blend differs from its one captured source")
                demand(number(independent, "maximumStepToInteriorP99Ratio")?.let { it <= 2.0 } == true,
                    "independently measured held boundary step exceeds 2 times interior P99")
                demand(number(independent, "maxOutputAboveLouderInputDb")?.let { it <= BLEND_BUMP_LIMIT_DB } == true,
                    "held blend exceeds the louder matching source input by $BLEND_BUMP_LIMIT_DB dB")
            }
        } else demand(join == null, "finite render unexpectedly reported a held capture")
        val analysisSeconds = elapsed(analysisStarted)

        val writeStarted = System.nanoTime()
        val files = linkedMapOf<String, String>("recipe" to "recipes/${cell.id}.json")
        File(output, files.getValue("recipe")).also { it.parentFile.mkdirs() }.writeText(recipeText + "\n")
        // One common scalar preserves the relative amplitudes of all 60 native files.
        // WavWriter clips/scrubs by design, so refuse unsafe audio before calling it.
        if (native.peak() * RAW_WAV_GAIN < 1f) {
            files["raw"] = "audio/${cell.id}-raw.wav"
            WavWriter.write(File(output, files.getValue("raw")), scale(native, RAW_WAV_GAIN), WavWriter.BitDepth.PCM_24)
        }
        files["matched"] = "audio/${cell.id}-matched.wav"
        WavWriter.write(File(output, files.getValue("matched")), matched, WavWriter.BitDepth.PCM_24)
        val writeSeconds = elapsed(writeStarted)

        return linkedMapOf(
            "id" to cell.id, "model" to MODEL, "engine" to "EMBOUCHURE", "voice" to "BRASS",
            "sourceConfiguration" to mapOf("playedMidi" to sourceConfiguration.playedMidi,
                "grainReduction" to sourceConfiguration.grainReduction,
                "controls" to sourceConfiguration.controls,
                "durationSeconds" to sourceConfiguration.durationSeconds,
                "pressureTimes" to sourceConfiguration.score.times,
                "pressureValues" to sourceConfiguration.score.values),
            "midi" to cell.midi, "velocity" to cell.velocity, "grow" to cell.grow,
            "kind" to if (cell.held) "held" else "finite", "macros" to cell.macros,
            "recipe" to recipe.toJsonValue(), "recipeRoundtrip" to (restored == recipe),
            "passed" to failures.isEmpty(), "failures" to failures,
            "files" to files, "fileSha256" to files.mapValues { (_, path) -> sha256(File(output, path).readBytes()) },
            "samplesSha256" to linkedMapOf("physicalRaw" to samplesHash(played.raw),
                "source" to samplesHash(rendered.source.samples), "native" to samplesHash(native.samples),
                "production" to samplesHash(rendered.snip.samples), "matched" to samplesHash(matched.samples)),
            "measurements" to linkedMapOf(
                "physicalSource" to linkedMapOf("rate" to played.rate, "frames" to played.raw.size,
                    "durationSeconds" to played.raw.size.toDouble() / played.rate, "seed" to played.seed,
                    "targetHz" to played.hz, "sourceEndSeconds" to played.sourceEndSeconds,
                    "maxAbs" to played.maxAbs, "maxEnergy" to played.maxEnergy,
                    "unconditioned" to metrics(played.raw)),
                "conditionedSource" to metrics(rendered.source), "nativeBeforeRawWavGain" to metrics(native),
                "rawWavBeforePcmQuantization" to metrics(scale(native, RAW_WAV_GAIN)),
                "matchedBeforePcmQuantization" to metrics(matched), "productionNormalized" to metrics(rendered.snip),
                "sourcePitchWindows" to sourcePitch, "nativePitchWindows" to outputPitch,
                "passiveRelease" to release, "heldCapture" to join, "independentHeldOutputChecks" to independentJoin,
            ),
            "warnings" to if (renderSeconds > RENDER_SOFT_LIMIT_SECONDS)
                listOf("render exceeded the ${RENDER_SOFT_LIMIT_SECONDS}s soft wall limit on this host") else emptyList<String>(),
            "timing" to linkedMapOf("renderIncludingSynthesisConditioningAndCaptureSeconds" to renderSeconds,
                "renderSoftLimitSeconds" to RENDER_SOFT_LIMIT_SECONDS,
                "renderSoftLimitExceeded" to (renderSeconds > RENDER_SOFT_LIMIT_SECONDS),
                "pitchReleaseAndJoinAnalysisSeconds" to analysisSeconds, "writeSeconds" to writeSeconds),
        )
    }

    /** Check the delivered loop and its real source independently of the capture's own report. */
    private fun heldEvidence(rendered: BrassGrow.Rendered): Map<String, Any?> {
        val join = requireNotNull(rendered.join)
        fun frame(key: String): Int {
            val value = requireNotNull(number(join, key)) { "missing held geometry $key" }
            require(value == value.toInt().toDouble()) { "non-integral held geometry $key" }
            return value.toInt()
        }
        val source = rendered.source.samples
        val output = rendered.raw.samples
        val start = frame("sourceCaptureStartFrames")
        val length = frame("lengthFrames")
        val blend = frame("blendFrames")
        val junction = frame("junctionFrames")
        val exit = frame("blendExitFrames")
        require(length == output.size && length > 1 && blend in 1 until length && junction in output.indices &&
            start >= 0 && start.toLong() + length + blend <= source.size && exit == (junction + blend) % length) {
            "held geometry does not address the delivered source and output"
        }
        require(frame("sourceCaptureEndExclusiveFrames") == start + length &&
            frame("sourceContinuationEndExclusiveFrames") == start + length + blend &&
            start + length + blend <= (rendered.played.sourceEndSeconds * Dsp.RATE).toInt()) {
            "held capture crosses the actively driven source boundary"
        }
        val steps = DoubleArray(length - 1) { i -> abs(output[i + 1].toDouble() - output[i]) }
        steps.sort()
        val p99 = steps[((steps.size - 1) * .99).toInt()]
        require(p99 > 0) { "held output has no measurable interior steps" }
        fun step(frame: Int) = abs(output[frame].toDouble() - output[(frame - 1 + length) % length])
        val actualSteps = linkedMapOf("junctionStep" to step(junction), "wrapStep" to step(0),
            "blendExitStep" to step(exit))
        fun same(key: String, actual: Double): Boolean = number(join, key)?.let {
            abs(it - actual) <= maxOf(1e-12, abs(actual) * 1e-9)
        } == true

        // Original frame zero was moved to junctionFrames by a circular rotation only.
        val unrotated = FloatArray(length) { i -> output[(junction + i) % length] }
        var headEnergy = 0.0
        var continuationEnergy = 0.0
        var dot = 0.0
        for (i in 0 until blend) {
            val head = source[start + i].toDouble()
            val continuation = source[start + length + i].toDouble()
            headEnergy += head * head
            continuationEnergy += continuation * continuation
            dot += head * continuation
        }
        val correlation = dot / sqrt(headEnergy * continuationEnergy)
        val window = (Dsp.RATE * .05).toInt()
        val windows = (0..blend - window step window / 2).map { from ->
            cancelled()
            fun energy(samples: FloatArray, offset: Int): Double = (0 until window).sumOf {
                val value = samples[offset + from + it].toDouble(); value * value
            } / window
            val head = energy(source, start)
            val continuation = energy(source, start + length)
            val actual = energy(unrotated, 0)
            linkedMapOf("fromFrames" to from, "windowFrames" to window,
                "headMeanSquare" to head, "realContinuationMeanSquare" to continuation,
                "outputMeanSquare" to actual, "outputAboveLouderInputDb" to
                    10.0 * log10(actual.coerceAtLeast(1e-300) / maxOf(head, continuation).coerceAtLeast(1e-300)))
        }
        return linkedMapOf(
            "method" to "Actual rotated raw output steps; undo rotation using junctionFrames; 50 ms blend windows every 25 ms compared with the matching source head and real later continuation",
            "interiorP99Step" to p99, "boundarySteps" to actualSteps,
            "maximumStepToInteriorP99Ratio" to actualSteps.values.max() / p99,
            "realContinuationCorrelation" to correlation,
            "metadataMatchesSamples" to (same("interiorP99Step", p99) && same("correlation", correlation) &&
                actualSteps.all { (key, value) -> same(key, value) && same(key + "ToP99Ratio", value / p99) }),
            "outsideBlendMatchesSource" to (blend until length).all { unrotated[it].toRawBits() == source[start + it].toRawBits() },
            "maxOutputAboveLouderInputDb" to windows.maxOf { (it.getValue("outputAboveLouderInputDb") as Number).toDouble() },
            "blendWindows" to windows,
        )
    }

    /** Raw model data before the output conditioner, loudness matching and finite tail fade. */
    private fun release(played: Embouchure.Played): Map<String, Any?> {
        val stop = played.sourceEndSeconds.toDouble()
        val seconds = played.raw.size.toDouble() / played.rate
        val before = rms(played.raw, played.rate, maxOf(0.0, stop - .25), stop)
        val early = rms(played.raw, played.rate, stop + .05, minOf(seconds, stop + .2))
        val tail = rms(played.raw, played.rate, maxOf(stop + .05, seconds - .2), seconds)
        val traceFrom = ceil(stop * played.trace.rate).toInt().coerceAtMost(played.trace.player.size)
        val sourceStops = played.trace.player.isNotEmpty() &&
            (traceFrom until played.trace.player.size).all { played.trace.player[it] == 0f }
        val sourceOffFrame = (stop * played.rate).toInt().coerceIn(0, played.raw.size)
        val noSubnormal = (sourceOffFrame until played.raw.size).none {
            played.raw[it] != 0f && abs(played.raw[it]) < java.lang.Float.MIN_NORMAL
        }
        return linkedMapOf(
            "method" to "Raw oversampled physical source before finish, normalization and tail fade",
            "sourceOffSeconds" to stop, "availableReleaseSeconds" to seconds - stop,
            "preReleaseRms" to before, "earlyReleaseRms" to early, "last200msRms" to tail,
            "lastToPreReleaseRmsRatio" to if (before > 0) tail / before else null,
            "tailDecays" to (seconds - stop >= .6 && before > 1e-7 && tail < before * .025),
            "sourceWorkStops" to sourceStops, "noSubnormalTail" to noSubnormal,
        )
    }

    private fun pitchWindows(snip: Snip, target: Float, bodyEnd: Double): List<Map<String, Any?>> {
        val end = minOf(bodyEnd, snip.durationSeconds.toDouble())
        val available = (end - .15 - PITCH_WINDOW_SECONDS).coerceAtLeast(0.0)
        return listOf(.2, .5, .8).map { fraction ->
            cancelled()
            val from = .15 + available * fraction
            val detected = Pitch.detect(snip, from.toFloat(), PITCH_WINDOW_SECONDS)
            val fine = FineTuning.measuredHz(snip, target, from.toFloat(), PITCH_WINDOW_SECONDS)
            val cents = detected?.let { 1200 * ln(it.hz / target.toDouble()) / ln(2.0) }
            val fineCents = FineTuning.cents(fine, target.toDouble())
            linkedMapOf("fromSeconds" to from, "windowSeconds" to PITCH_WINDOW_SECONDS,
                "targetHz" to target, "detectedHz" to detected?.hz, "confidence" to detected?.confidence,
                "errorCents" to cents, "localRootHz" to fine, "localRootErrorCents" to fineCents,
                "captured" to (detected != null && detected.confidence >= .5f && cents != null &&
                    abs(cents) < PITCH_LIMIT_CENTS && abs(fineCents) < PITCH_LIMIT_CENTS))
        }
    }

    private fun writeEvidence(output: File, rows: List<Map<String, Any?>>, provenance: Map<String, Any?>,
                              seconds: Double, complete: Boolean) {
        val failed = rows.filter { it["passed"] != true }.map { it["id"] }
        val evidence = linkedMapOf(
            "schemaVersion" to 1, "title" to "BRASS GROW single-source prototype matrix", "model" to MODEL,
            "complete" to complete, "passed" to (complete && failed.isEmpty()),
            "matrix" to linkedMapOf("midi" to notes, "velocity" to velocities, "grow" to grows,
                "kind" to listOf("finite", "held"), "expectedCells" to EXPECTED_CELLS,
                "recordedCells" to rows.size, "failedCells" to failed),
            "format" to linkedMapOf("channels" to 1, "sampleRate" to Dsp.RATE, "wavEncoding" to "PCM24",
                "rawWavGain" to RAW_WAV_GAIN, "matchedLoudnessTarget" to MATCHED_LEVEL,
                "loudnessMethod" to "Loudness.of: RMS of loudest 200 ms window",
                "matchingMethod" to "AuditionLevel.level on each complete final raw render; one scalar and 0.99 peak guard"),
            "checks" to linkedMapOf("pitchLimitCents" to PITCH_LIMIT_CENTS, "minimumPitchConfidence" to .5,
                "physicalRawPeakLimit" to 8, "physicalMaxEnergyLimit" to 16,
                "maximumAbsConditionedSourceAndNativeDc" to DC_LIMIT,
                "lastToPreReleaseRmsRatioLimit" to .025,
                "heldMinimumRealContinuationCorrelation" to .8, "heldMinimumBlendDipDb" to -1,
                "heldMaximumStepToInteriorP99Ratio" to 2, "heldMaximumCandidateCount" to 17,
                "heldMaximumBlendOutputAboveLouderInputDb" to BLEND_BUMP_LIMIT_DB,
                "renderSoftWallLimitSeconds" to RENDER_SOFT_LIMIT_SECONDS,
                "renderSoftWallLimitIsWarningOnly" to true),
            "method" to "One BRASS model-3 feedback path per cell. GROW interpolates source controls and pressure scores. " +
                "Native WAVs apply one declared 0.5 headroom gain; native metrics precede it. Matched WAVs use AuditionLevel. " +
                "Finite release is measured before output conditioning or tail fade. Held evidence compares the captured head " +
                "against actual later source continuation; copied-loop repetition is not evidence of model closure. " +
                "Production-normalized output is metered separately. Sample hashes allow exact regeneration; focused model " +
                "tests check replay and forced-stop held passivity without doubling this full matrix. Timings vary by host. " +
                "Numerical acceptance does not establish the owner's sonic verdict.",
            "provenance" to provenance, "totalWallSeconds" to seconds, "clips" to rows,
        )
        val text = Json.write(json(evidence)) + "\n"
        File(output, "matrix.json").writeText(text)
        // Preserve the ordinary audition-pack entry point alongside the explicitly named matrix.
        File(output, "manifest.json").writeText(text)
    }

    private fun requireAudio(snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE) { "unexpected audio format" }
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "nonfinite or empty audio" }
    }

    private fun metrics(snip: Snip): Map<String, Any?> = metrics(snip.samples) + linkedMapOf(
        "frames" to snip.frameCount, "durationSeconds" to snip.durationSeconds,
        "sampleRate" to snip.sampleRate, "channels" to snip.channels, "loudness" to Loudness.of(snip),
    )

    private fun metrics(samples: FloatArray): Map<String, Any?> = linkedMapOf(
        "allFinite" to samples.all { it.isFinite() }, "peak" to samples.maxOf { abs(it) },
        "rms" to sqrt(samples.sumOf { it.toDouble() * it } / samples.size),
        "dc" to samples.sumOf { it.toDouble() } / samples.size,
    )

    private fun rms(samples: FloatArray, rate: Int, from: Double, to: Double): Double {
        val first = (from * rate).toInt().coerceIn(0, samples.size)
        val end = (to * rate).toInt().coerceIn(first, samples.size)
        return if (first == end) 0.0 else sqrt((first until end).sumOf {
            samples[it].toDouble() * samples[it]
        } / (end - first))
    }

    private fun scale(snip: Snip, gain: Float) = Snip(FloatArray(snip.samples.size) { snip.samples[it] * gain }, 1, snip.sampleRate)
    private fun mean(samples: FloatArray) = samples.sumOf { it.toDouble() } / samples.size
    private fun number(map: Map<String, Any?>, key: String): Double? = (map[key] as? Number)?.toDouble()
    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1e9
    private fun cancelled() { if (Thread.currentThread().isInterrupted) throw CancellationException("BRASS GROW evidence no longer wanted") }

    private fun repository(): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        while (!File(current, "settings.gradle.kts").isFile) current = current.parentFile
            ?: error("Run the generator from the SnipSnap checkout")
        return current
    }

    private fun provenance(repository: File): Map<String, Any?> {
        val paths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/BrassGrow.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/BrassGrowCapture.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Embouchure.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/EmbouchurePatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Bore.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/FineTuning.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/BrassGrowPrototypeGenerator.kt",
        )
        fun git(vararg arguments: String): String {
            val process = ProcessBuilder(listOf("git") + arguments).directory(repository).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "Unable to record git provenance: $text" }
            return text
        }
        return linkedMapOf("repository" to "BertCalm/SnipSnap", "sourceCommit" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "generator" to "BrassGrowPrototypeGenerator", "sourceFiles" to paths.map { path ->
                linkedMapOf("path" to path, "sha256" to sha256(File(repository, path).readBytes()))
            })
    }

    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return sha256(bytes.array())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> { check(value.toDouble().isFinite()) { "nonfinite diagnostic value" }; JsonValue.Num(value.toDouble()) }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { (key, item) -> key as String to json(item) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        is IntArray -> JsonValue.Arr(value.map(::json))
        is FloatArray -> JsonValue.Arr(value.map(::json))
        is DoubleArray -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON value ${value.javaClass.name}")
    }
}
