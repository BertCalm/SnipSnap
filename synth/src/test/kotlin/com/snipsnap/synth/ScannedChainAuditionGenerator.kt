package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A first listening experiment, deliberately outside the product engine/patch registries.
 * Every A/B pair uses one saved initial shape and one amplitude envelope. Only chain motion
 * changes; the frozen baseline does not run the physics or drive. Source-derived shapes are
 * short waveform windows, not a claim of reconstructing the original sound or its envelope.
 */
object ScannedChainAuditionGenerator {
    private const val RATE = 44_100
    private const val SHAPE_SIZE = 128
    private const val PEAK_CEILING = .90
    private const val MATCHED_RMS_TARGET = .12
    private const val SILENCE_RMS = 1e-8
    private const val MODEL = "scanned-chain-prototype-v1"

    private data class Case(
        val id: String,
        val title: String,
        val description: String,
        val config: ScannedChainPrototype.Config,
        val sourceKind: String,
        val sourceLabel: String,
        val shape: FloatArray,
        val provenance: Map<String, Any?>,
        val sourcePreview: Snip? = null,
    )

    private data class Rendered(
        val case: Case,
        val frozen: ScannedChainPrototype.Result,
        val moving: ScannedChainPrototype.Result,
        val frozenMs: Double,
        val movingMs: Double,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: ScannedChainAuditionGenerator [output-directory]" }
        val repo = repositoryRoot()
        val destination = File(args.firstOrNull() ?: File(repo, "testkit/scanned-chain-audition").path)
        check(destination.isDirectory || destination.mkdirs()) { "Cannot create $destination" }
        val coreFile = File(repo, "synth/src/test/kotlin/com/snipsnap/synth/ScannedChainPrototype.kt")
        val generatorFile = File(repo, "synth/src/test/kotlin/com/snipsnap/synth/ScannedChainAuditionGenerator.kt")
        val template = File(repo, "scripts/scanned-chain-audition.html")
        require(coreFile.isFile && template.isFile) { "Run with the experimental core and listening-page template present" }
        val coreHash = sha256(coreFile.readBytes())
        val templateHash = sha256(template.readBytes())
        val cases = cases(repo)
        check(cases.map { it.id }.distinct().size == cases.size)
        val rendered = cases.mapIndexed { index, case ->
            check(sha256(coreFile.readBytes()) == coreHash) { "Core source changed during rendering; regenerate the pack" }
            val beganFrozen = System.nanoTime()
            val frozen = ScannedChainPrototype.render(case.config.copy(frozen = true), case.shape.copyOf())
            val frozenMs = (System.nanoTime() - beganFrozen) / 1e6
            val beganMoving = System.nanoTime()
            val moving = ScannedChainPrototype.render(case.config.copy(frozen = false), case.shape.copyOf())
            val movingMs = (System.nanoTime() - beganMoving) / 1e6
            for (result in listOf(frozen, moving)) {
                check(result.raw.channels == 1 && result.raw.sampleRate == RATE)
                check(result.raw.samples.all { it.isFinite() }) { "${case.id}: non-finite render" }
                check(result.diagnostics.finite) { "${case.id}: non-finite physics" }
            }
            check(frozen.initialShape.contentEquals(moving.initialShape)) { "${case.id}: A/B initial shapes differ" }
            println("SCANNED CHAIN ${index + 1}/${cases.size}: ${case.id} (frozen ${frozenMs.roundToInt()} ms, moving ${movingMs.roundToInt()} ms)")
            Rendered(case, frozen, moving, frozenMs, movingMs)
        }
        // One gain across the whole pack preserves all relative raw levels; never amplify raw.
        val maximumRawPeak = rendered.flatMap { listOf(it.frozen.raw.peak(), it.moving.raw.peak()) }.maxOrNull()!!.toDouble()
        val rawExportGain = if (maximumRawPeak > PEAK_CEILING) PEAK_CEILING / maximumRawPeak else 1.0
        val pairs = rendered.map { pair -> writePair(destination, pair, rawExportGain) }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "modelVersion" to MODEL,
            "generatedAt" to Instant.now().toString(),
            "sampleRate" to RATE,
            "shapeSize" to SHAPE_SIZE,
            "durationSeconds" to 3.0,
            "sourceRevision" to revision(repo),
            "provenance" to linkedMapOf(
                "coreFile" to coreFile.relativeTo(repo).invariantSeparatorsPath,
                "coreSha256" to coreHash,
                "generatorFile" to generatorFile.relativeTo(repo).invariantSeparatorsPath,
                "generatorSha256" to sha256(generatorFile.readBytes()),
                "pageTemplateSha256" to templateHash,
                "compiledCoreSha256" to ScannedChainPrototype::class.java
                    .getResourceAsStream("/com/snipsnap/synth/ScannedChainPrototype.class")?.use { sha256(it.readBytes()) },
            ),
            "comparisonMethod" to linkedMapOf(
                "frozen" to "The entire chain stays at its initial shape; scanner and amplitude envelope remain active; physical drive is disabled.",
                "moving" to "The same initial shape, scanner pitch and amplitude envelope; bounded physics evolve the chain.",
                "raw" to "One common attenuation across all 18 renders, only if required for 0.90 PCM headroom. No raw normalization or limiting.",
                "rawExportGain" to rawExportGain,
                "maximumUnscaledRawPeak" to maximumRawPeak,
                "matched" to "Each pair has equal whole-file RMS, aimed at 0.12. The shared target is reduced if either variant would exceed 0.90 peak. Silence is never amplified.",
                "matchedTargetRms" to MATCHED_RMS_TARGET,
                "peakCeiling" to PEAK_CEILING,
                "exportMetrics" to "Export RMS, peak and DC are measured after decoding the written 24-bit PCM WAV; pitch and centroid probes are reused from the unscaled render because export uses only constant gains.",
                "levelLimitation" to "Whole-file RMS matching is a comparison aid, not perceptual loudness matching. The identical amplitude envelope controls onset/release; physics can still redistribute energy in time and frequency.",
                "pitchLimitation" to "Scanner rate is exact MIDI pitch. A pitch detector can report a harmonic or fail on an inharmonic/weak-root shape; pitch estimates are evidence, not guarantees of a perceptually stable fundamental.",
            ),
            "scope" to "Finite mono one-shots only. Experimental test-source core; no saved product Patch, engine picker, live DSP port or HOLD loop.",
            "pairs" to pairs,
        )
        val manifestText = Json.write(json(manifest))
        File(destination, "manifest.json").writeText(manifestText + "\n")
        val templateText = template.readText()
        check("__SCANNED_CHAIN_MANIFEST__" in templateText) { "Listening-page template lacks manifest placeholder" }
        // Escaping '<' prevents shape/provenance text from ending an inline script element.
        File(destination, "index.html").writeText(templateText.replace("__SCANNED_CHAIN_MANIFEST__", manifestText.replace("<", "\\u003c")))
        File(destination, "README.txt").writeText(
            "Scanned-chain first audition\nOpen index.html in a browser; all comparisons are actual offline Kotlin renders.\n" +
                "Run: ./gradlew :synth:generateScannedChainAudition\n" +
                "Raw gain across the whole pack: $rawExportGain. Matched comparison uses pair-equal whole-file RMS and <=0.90 peak.\n" +
                "Factory/drawn shapes and every derived 128-point shape, exact source frame windows, hashes and physics diagnostics are in manifest.json.\n" +
                "The room source is a recording. Melodic-kit source WAVs are explicitly synthesized, not captures.\n" +
                "This pack contains finite mono one-shots only; no product registration or sustain-loop claim.\n",
        )
        println("SCANNED CHAIN: ${pairs.size} A/B pairs written to ${destination.absolutePath}; common raw gain $rawExportGain")
    }

    private fun cases(repo: File): List<Case> {
        val factories = ScannedChainPrototype.factoryConfigs().associateBy { it.voice }
        fun factory(voice: ScannedChainPrototype.Voice, midi: Int = 60): Case {
            val config = factories.getValue(voice).copy(name = "${voice.name} ${noteName(midi)}", midi = midi, seconds = 3f, frozen = false)
            val id = "factory_${voice.name.lowercase()}_${noteName(midi).lowercase()}"
            return Case(id, "${voice.name.lowercase().replaceFirstChar { it.uppercase() }} · ${noteName(midi)}",
                "A factory shape. Compare a static wavetable with the same shape evolving under the voice's physics.",
                config, "FACTORY", "Factory ${voice.name} initial shape", ScannedChainPrototype.initialShape(config),
                linkedMapOf("kind" to "FACTORY", "factoryVoice" to voice.name, "seed" to config.seed))
        }
        val pluck = factories.getValue(ScannedChainPrototype.Voice.PLUCK).copy(midi = 60, seconds = 3f, frozen = false)
        val drawn = Case("drawn_line_c4", "Drawn line · C4",
            "An explicit 128-point drawing supplies the initial displacement. It uses the PLUCK physics so the shape is the changed ingredient.",
            pluck.copy(name = "Drawn line C4"), "DRAWN", "Deterministic drawn 128-point line", ScannedChainPrototype.drawnShape(),
            linkedMapOf("kind" to "DRAWN", "method" to "ScannedChainPrototype.drawnShape(); exact 128-point shape embedded below"))
        val room = sourceCase(repo, pluck, "capture_room_c4", "Room recording · C4", "CAPTURE",
            "reference/live3 room take.wav", 20.125, .020, 20.0, 1.5,
            "An arbitrary fixed 20 ms window from the real room recording becomes a timbral imprint at the C4 scanner rate. It does not preserve the source's pitch or envelope; the source is not played through the model continuously.")
        val tines = sourceCase(repo, pluck, "source_tines_c4", "Synthesized kalimba source · C4", "SYNTHESIZED",
            "testkit/SnipSnap Melodic Kit/A07_Tonal_07.wav", .030, null, 0.0, 1.5,
            "One confidently detected cycle of a synthesized TINES KALIMBA kit WAV becomes the initial shape at the C4 scanner rate. This is a synthesized source, not a recording; no supporting fundamental is added.")
        val nylon = sourceCase(repo, pluck, "source_pluck_c4", "Synthesized nylon source · C4", "SYNTHESIZED",
            "testkit/SnipSnap Melodic Kit/A01_Tonal_01.wav", .025, null, 0.0, 1.5,
            "One confidently detected cycle of a synthesized PLUCK NYLON kit WAV becomes the initial shape at the C4 scanner rate. This is a synthesized source, not a recording; no supporting fundamental is added.")
        return listOf(factory(ScannedChainPrototype.Voice.PLUCK), factory(ScannedChainPrototype.Voice.METAL),
            factory(ScannedChainPrototype.Voice.BLOOM), drawn, room, tines, nylon,
            factory(ScannedChainPrototype.Voice.PLUCK, 48), factory(ScannedChainPrototype.Voice.PLUCK, 72))
    }

    private fun sourceCase(
        repo: File, config: ScannedChainPrototype.Config, id: String, title: String, kind: String,
        relativePath: String, windowSeconds: Double, windowDuration: Double?,
        previewSeconds: Double, previewDuration: Double, description: String,
    ): Case {
        val file = File(repo, relativePath)
        require(file.isFile) { "Audition source missing: $relativePath" }
        val original = WavReader.read(file)
        val mono = Cleanup.toMono(original)
        val detectFromSeconds = .05f
        val detectWindowSeconds = .25f
        val sourcePitch = if (windowDuration == null) {
            val estimate = Pitch.detect(mono, fromSec = detectFromSeconds, windowSec = detectWindowSeconds)
                ?: error("$relativePath: one-cycle extraction requires a detectable source pitch (window $detectFromSeconds + $detectWindowSeconds seconds)")
            require(estimate.hz.isFinite() && estimate.hz > 0f && estimate.confidence >= .70f) {
                "$relativePath: one-cycle source pitch is insufficiently confident (Hz=${estimate.hz}, confidence=${estimate.confidence}; requires >=0.70)"
            }
            estimate
        } else null
        val from = (windowSeconds * mono.sampleRate).roundToInt()
        val count = if (sourcePitch != null) (mono.sampleRate / sourcePitch.hz.toDouble()).roundToInt()
            else (requireNotNull(windowDuration) * mono.sampleRate).roundToInt()
        require(count >= SHAPE_SIZE) {
            "$relativePath: detected single cycle has only $count frames; the 128-mass extraction requires >=128 (source pitch ${sourcePitch?.hz} Hz)"
        }
        require(from >= 0 && from + count <= mono.samples.size) { "$relativePath is too short for the fixed source window" }
        val window = mono.samples.copyOfRange(from, from + count)
        val derived = deriveShape(window)
        val previewFrom = (previewSeconds * mono.sampleRate).roundToInt()
        val previewEnd = minOf(mono.samples.size, previewFrom + (previewDuration * mono.sampleRate).roundToInt())
        require(previewFrom < previewEnd)
        // These chosen sources are native 44.1 kHz; do not silently resample provenance.
        require(mono.sampleRate == RATE) { "$relativePath preview must be native $RATE Hz" }
        val preview = Snip(mono.samples.copyOfRange(previewFrom, previewEnd), 1, RATE)
        return Case(id, title, description, config.copy(name = title), kind, relativePath, derived,
            linkedMapOf(
                "kind" to kind, "sourcePath" to relativePath, "sourceSha256" to sha256(file.readBytes()),
                "sourceBytes" to file.length(), "sourceSampleRate" to original.sampleRate,
                "sourceChannels" to original.channels, "sourceFrames" to original.frameCount,
                "sourceDurationSeconds" to original.durationSeconds,
                "windowStartFrame" to from, "windowEndFrameExclusive" to from + count,
                "windowStartSeconds" to from.toDouble() / mono.sampleRate,
                "windowDurationSeconds" to count.toDouble() / mono.sampleRate,
                "extractionMode" to if (sourcePitch != null) "ONE_DETECTED_SOURCE_CYCLE" else "FIXED_TIMBRAL_WINDOW",
                "sourcePitchHz" to sourcePitch?.hz, "sourcePitchConfidence" to sourcePitch?.confidence,
                "sourcePitchDetectionStartSeconds" to sourcePitch?.let { detectFromSeconds },
                "sourcePitchDetectionWindowSeconds" to sourcePitch?.let { detectWindowSeconds },
                "cycleFrameCount" to sourcePitch?.let { count },
                "actualWindowCycleHz" to sourcePitch?.let { mono.sampleRate.toDouble() / count },
                "cycleFrameRoundingCents" to sourcePitch?.let {
                    1200.0 * ln((mono.sampleRate.toDouble() / count) / it.hz) / ln(2.0)
                },
                "windowRms" to rms(window), "windowPeak" to peak(window), "windowDc" to window.average(),
                "previewStartFrame" to previewFrom, "previewEndFrameExclusive" to previewEnd,
                "derivation" to ((if (sourcePitch != null)
                    "Detect the source fundamental at 0.05 seconds over 0.25 seconds with confidence >=0.70; select one period rounded to source frames at the stated fixed start. "
                    else "Select the stated arbitrary fixed 20 ms window as a timbral imprint; do not infer or preserve source pitch. ") +
                    "Arithmetic stereo-to-mono; subtract window mean; equal-width box averages into 128 masses; Hann window; subtract shape mean; scale shape peak to 0.35. The core then performs its documented initial-only centering/bounding. No added root or source-envelope transfer; source windowing can still change perceived pitch."),
                "shapePeakTarget" to .35, "derivedInputShapeSha256" to floatHash(derived),
            ), preview)
    }

    private fun deriveShape(window: FloatArray): FloatArray {
        require(window.size >= SHAPE_SIZE && window.all { it.isFinite() })
        val mean = window.average()
        val shape = FloatArray(SHAPE_SIZE) { mass ->
            val from = mass * window.size / SHAPE_SIZE
            val to = (mass + 1) * window.size / SHAPE_SIZE
            var sum = 0.0
            for (i in from until to) sum += window[i] - mean
            ((sum / (to - from)) * (.5 - .5 * cos(2.0 * PI * mass / (SHAPE_SIZE - 1)))).toFloat()
        }
        val shapeMean = shape.average()
        for (i in shape.indices) shape[i] = (shape[i] - shapeMean).toFloat()
        val shapePeak = peak(shape)
        require(shapePeak > 1e-7) { "The selected source window has no usable displacement shape" }
        for (i in shape.indices) shape[i] = (shape[i] * (.35 / shapePeak)).toFloat()
        return shape
    }

    private fun writePair(root: File, pair: Rendered, rawGain: Double): Map<String, Any?> {
        val frozenRms = rms(pair.frozen.raw.samples)
        val movingRms = rms(pair.moving.raw.samples)
        val activeRenders = listOf(pair.frozen.raw, pair.moving.raw).filter { rms(it.samples) > SILENCE_RMS }
        val matchedTarget = activeRenders.fold(MATCHED_RMS_TARGET) { target, snip ->
            minOf(target, PEAK_CEILING * rms(snip.samples) / peak(snip.samples).coerceAtLeast(1e-30))
        }
        fun variant(result: ScannedChainPrototype.Result, name: String, elapsedMs: Double, variantRms: Double): Map<String, Any?> {
            val rawFile = "raw/${pair.case.id}_$name.wav"
            val matchedFile = "matched/${pair.case.id}_$name.wav"
            val matchedGain = if (variantRms > SILENCE_RMS) matchedTarget / variantRms else 1.0
            val raw = scaled(result.raw, rawGain)
            val matched = scaled(result.raw, matchedGain)
            val physicalMetrics = metrics(result.raw, pair.case.config.midi)
            check(raw.peak() <= PEAK_CEILING + 1e-6 && matched.peak() <= PEAK_CEILING + 1e-6)
            WavWriter.write(File(root, rawFile), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedFile), matched, WavWriter.BitDepth.PCM_24)
            val exportedRaw = WavReader.read(File(root, rawFile))
            val exportedMatched = WavReader.read(File(root, matchedFile))
            return linkedMapOf(
                "rawFile" to rawFile, "matchedFile" to matchedFile,
                "rawSha256" to sha256(File(root, rawFile).readBytes()),
                "matchedSha256" to sha256(File(root, matchedFile).readBytes()),
                "sha256" to sha256(File(root, matchedFile).readBytes()),
                "rawFloatSha256" to floatHash(result.raw.samples),
                "config" to configuration(pair.case.config.copy(frozen = name == "frozen")),
                "rawExportGain" to rawGain, "matchedGain" to matchedGain,
                "renderMs" to elapsedMs,
                "metrics" to physicalMetrics,
                "rawExportMetrics" to scaledMetrics(physicalMetrics, exportedRaw),
                "matchedMetrics" to scaledMetrics(physicalMetrics, exportedMatched),
                "diagnostics" to diagnostics(result.diagnostics),
                "finalShape" to result.finalShape.toList(),
                "finalShapeSha256" to floatHash(result.finalShape),
            )
        }
        val sourcePreview = pair.case.sourcePreview?.let { snip ->
            val gain = minOf(1.0, PEAK_CEILING / snip.peak().coerceAtLeast(1e-30f))
            val path = "source/${pair.case.id}.wav"
            WavWriter.write(File(root, path), scaled(snip, gain), WavWriter.BitDepth.PCM_24)
            linkedMapOf("file" to path, "gain" to gain, "sha256" to sha256(File(root, path).readBytes()),
                "metrics" to metrics(snip, null))
        }
        return linkedMapOf(
            "id" to pair.case.id, "title" to pair.case.title, "description" to pair.case.description,
            "noteLabel" to noteName(pair.case.config.midi), "sourceKind" to pair.case.sourceKind,
            "sourceLabel" to pair.case.sourceLabel, "sourcePreview" to sourcePreview?.get("file"),
            "sourcePreviewInfo" to sourcePreview, "sourceProvenance" to pair.case.provenance,
            "modelVersion" to MODEL, "config" to configuration(pair.case.config),
            "shape" to pair.moving.initialShape.toList(), "shapeSha256" to floatHash(pair.moving.initialShape),
            "inputShape" to pair.case.shape.toList(), "inputShapeSha256" to floatHash(pair.case.shape),
            "shapeRms" to rms(pair.moving.initialShape), "shapePeak" to peak(pair.moving.initialShape),
            "matchedTargetRms" to matchedTarget,
            "comparisonNormalizedRmsDistance" to normalizedDistance(pair.frozen.raw.samples, pair.moving.raw.samples),
            "frozen" to variant(pair.frozen, "frozen", pair.frozenMs, frozenRms),
            "moving" to variant(pair.moving, "moving", pair.movingMs, movingRms),
        )
    }

    private fun configuration(c: ScannedChainPrototype.Config): Map<String, Any?> = linkedMapOf(
        "name" to c.name, "voice" to c.voice.name, "midi" to c.midi, "velocity" to c.velocity,
        "seconds" to c.seconds, "motion" to c.motion, "frozen" to c.frozen,
        "physicsRate" to c.physicsRate, "snapshotRate" to c.snapshotRate,
        "mechanicalHz" to c.mechanicalHz, "dampingPerSecond" to c.dampingPerSecond,
        "nonlinear" to c.nonlinear, "restFraction" to c.restFraction, "drive" to c.drive,
        "driveHz" to c.driveHz, "seed" to c.seed, "attackSeconds" to c.attackSeconds,
        "releaseSeconds" to c.releaseSeconds,
    )

    private fun diagnostics(d: ScannedChainPrototype.Diagnostics): Map<String, Any?> = linkedMapOf(
        "initialEnergy" to d.initialEnergy, "finalEnergy" to d.finalEnergy, "maxEnergy" to d.maxEnergy,
        "driveWork" to d.driveWork, "dampingLoss" to d.dampingLoss,
        "maxDisplacement" to d.maxDisplacement, "maxVelocity" to d.maxVelocity,
        "maxCourant" to d.maxCourant, "shapeMotionRms" to d.shapeMotionRms,
        "maxLinearizedModeHz" to d.maxLinearizedModeHz, "energyBalanceError" to d.energyBalanceError,
        "physicsSteps" to d.physicsSteps, "finite" to d.finite,
        "retainedHarmonics" to d.retainedHarmonics, "scannerHz" to d.scannerHz,
        "mechanicalFundamentalHz" to d.mechanicalFundamentalHz, "rawRms" to d.rawRms,
    )

    private fun metrics(snip: Snip, midi: Int?): Map<String, Any?> {
        val samples = snip.samples
        val estimate = if (rms(samples) > SILENCE_RMS) Pitch.detect(snip, fromSec = .15f, windowSec = .30f) else null
        val lateEstimate = if (rms(samples) > SILENCE_RMS) Pitch.detect(snip, fromSec = 1.5f, windowSec = .30f) else null
        return linkedMapOf(
            "rms" to rms(samples), "peak" to peak(samples), "dc" to samples.average(),
            "finite" to samples.all { it.isFinite() }, "frames" to snip.frameCount,
            "durationSeconds" to snip.durationSeconds,
            "pitchHz" to estimate?.hz, "pitchConfidence" to estimate?.confidence,
            "latePitchHz" to lateEstimate?.hz, "latePitchConfidence" to lateEstimate?.confidence,
            "requestedScannerHz" to midi?.let { Keys.midiHz(it) },
            "detectorErrorCents" to if (estimate != null && midi != null)
                1200.0 * ln(estimate.hz / Keys.midiHz(midi).toDouble()) / ln(2.0) else null,
            "earlySpectralCentroidHz" to centroid(samples, .15),
            "lateSpectralCentroidHz" to centroid(samples, 1.5),
        )
    }

    private fun centroid(samples: FloatArray, fromSeconds: Double): Double {
        val size = 8192
        val from = minOf((fromSeconds * RATE).roundToInt(), (samples.size - size).coerceAtLeast(0))
        val spectrum = Fft.magnitudeSpectrum(samples.copyOfRange(from, minOf(samples.size, from + size)), size)
        var weighted = 0.0
        var total = 0.0
        for (bin in 1 until spectrum.size) {
            val power = spectrum[bin].toDouble() * spectrum[bin]
            weighted += bin.toDouble() * RATE / size * power
            total += power
        }
        return if (total > 1e-30) weighted / total else 0.0
    }

    /** Constant gains preserve pitch and spectral centroid; do not repeat those costly probes. */
    private fun scaledMetrics(original: Map<String, Any?>, snip: Snip): Map<String, Any?> =
        original + linkedMapOf("rms" to rms(snip.samples), "peak" to peak(snip.samples), "dc" to snip.samples.average())

    private fun normalizedDistance(a: FloatArray, b: FloatArray): Double {
        val ra = rms(a); val rb = rms(b)
        if (ra <= SILENCE_RMS || rb <= SILENCE_RMS) return 0.0
        var sum = 0.0
        for (i in 0 until minOf(a.size, b.size)) {
            val difference = a[i] / ra - b[i] / rb
            sum += difference * difference
        }
        return sqrt(sum / minOf(a.size, b.size).coerceAtLeast(1))
    }

    private fun scaled(snip: Snip, gain: Double): Snip = Snip(FloatArray(snip.samples.size) {
        (snip.samples[it] * gain).toFloat()
    }, snip.channels, snip.sampleRate)

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
    private fun peak(samples: FloatArray): Double = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun floatHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putFloat(it) }
        return sha256(bytes.array())
    }
    private fun noteName(midi: Int): String = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12] + (midi / 12 - 1)
    private fun repositoryRoot(): File = listOf(File("."), File("..")).firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?.canonicalFile ?: error("Run from the repository root or synth module")
    private fun revision(repo: File): String? = runCatching {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(repo).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40}"))) output else null
    }.getOrNull()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> if (value.toDouble().isFinite()) JsonValue.Num(value.toDouble()) else JsonValue.Null
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported audition JSON ${value.javaClass.name}")
    }
}
