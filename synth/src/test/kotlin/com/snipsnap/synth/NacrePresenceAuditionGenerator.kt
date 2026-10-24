package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Testkit-only space returns around the exact published model-1 foreground. */
object NacrePresenceAuditionGenerator {
    private const val RATE = 44_100
    private const val CEILING = .95f
    private const val ARCHIVE = "nacre-space-study.zip"
    private const val TEMPLATE = "design/nacre-presence-audition/index.html"
    private data class Artifact(val path: String, val file: File, val sha256: String)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one NACRE space-study output directory." }
        val repo = repositoryRoot()
        val root = File(args.firstOrNull() ?: "${repo.path}/testkit/nacre-presence-audition").absoluteFile
        require(root.canonicalFile != repo.canonicalFile) { "Use a dedicated output directory." }
        root.mkdirs()
        val began = System.nanoTime()
        val cpuBefore = cpuNanoseconds()
        val clips = NacrePresenceComparison.render()
        val renderMilliseconds = (System.nanoTime() - began) / 1e6
        val renderCpuMilliseconds = cpuNanoseconds()?.let { after -> cpuBefore?.let { (after - it) / 1e6 } }
        val invariantHashes = verify(clips, repo)
        val original = clips.first()
        val foreground = FloatArray(original.raw.size) { (original.raw[it].toDouble() - original.originalReturn[it]).toFloat() }
        val nominalGain = Dsp.MELODIC_LOUDNESS_TARGET / Loudness.of(snip(original.raw)).coerceAtLeast(1e-9f)
        val attenuation = minOf(1f, CEILING / clips.maxOf { peak(it.raw) * nominalGain }.coerceAtLeast(1e-9f))
        val listeningGain = nominalGain * attenuation
        val spaceNominal = Dsp.MELODIC_LOUDNESS_TARGET / clips.maxOf { Loudness.of(snip(it.returnSamples)) }.coerceAtLeast(1e-9f)
        val spaceGain = minOf(spaceNominal, CEILING / clips.maxOf { peak(it.returnSamples) }.coerceAtLeast(1e-9f))
        val source = copySources(repo, root)
        val records = clips.map { writeClip(root, it, original, foreground, listeningGain, spaceGain, invariantHashes, source) }
        val comparison = comparison(root, clips, listeningGain)
        File(repo, TEMPLATE).copyTo(File(root, "index.html"), overwrite = true)
        val manifest = linkedMapOf<String, Any?>(
            "format" to "snipsnap-nacre-space-study", "version" to 1, "revision" to "presence-1",
            "title" to "Nacre / Space around the original", "engine" to "NACRE", "model" to 1,
            "description" to "The exact original foreground with two larger, later feed-forward space returns. Each return can also be heard alone at a separate common monitor gain.",
            "voice" to original.patch.voice.name, "midi" to original.patch.midi,
            "velocity" to original.patch.velocity, "seed" to original.patch.seed.toString(),
            "sampleRate" to RATE, "channels" to 1, "bitDepth" to 24, "clipCount" to 3,
            "hold" to false, "productionChange" to false, "generatedAt" to Instant.now().toString(),
            "source" to source, "clips" to records,
            "sections" to listOf(mapOf("id" to "presence", "title" to "One foreground, three spaces",
                "description" to "The full mixes retain one original-anchored gain. Space-only previews retain their own common gain; they are diagnostic taps.")),
            "comparisonAudio" to comparison.path, "comparisonAudioSha256" to comparison.sha256,
            "comparisonDurationSeconds" to 6.7, "comparisonSequence" to comparisonSequence(),
            "comparisonNote" to "Preview trim: the first two seconds of original, coral and seaweed at the full mixes' common gain, separated by .35-second silence. Only the preview segments' final 20 ms fade. The six-second individual WAVs are canonical.",
            "download" to ARCHIVE, "downloads" to listOf(mapOf("path" to ARCHIVE, "title" to "Complete space study",
                "description" to "Three full mixes, three space-only previews, comparison montage, page, exact audition recipes and evidence.")),
            "gainPolicy" to linkedMapOf(
                "fullMix" to "One common gain, anchored to the untouched original's loudest-window level. A single safety attenuation covers all full mixes; candidates are never leveled independently.",
                "listeningGain" to listeningGain, "nominalOriginalGain" to nominalGain, "commonSafetyAttenuation" to attenuation,
                "spaceOnly" to "A separate common monitor gain covers all three return arrays, anchored to their maximum loudest-window level and peak-guarded together. It does not describe their level in the full mixes.",
                "spacePreviewGain" to spaceGain, "targetLoudness" to Dsp.MELODIC_LOUDNESS_TARGET, "peakCeiling" to CEILING,
            ),
            "invariants" to invariantHashes,
            "metricsNotes" to mapOf(
                "energy" to "Total energy sums squared full-scale samples. E90/E99 mark cumulative energy times, not perceptual thresholds. timeEnergy records fixed time windows.",
                "difference" to "prelevelDifferenceRms and timeEnergy.prelevelDifferenceEnergy use the unscaled Float bus. Main peak/rms/totalEnergy describe listening-gain audio. Relative dB, correlations and fractions are gain invariant. A numerical difference does not establish audibility.",
                "spectrum" to "above1kFraction uses one-sided squared FFT magnitude over the complete finite signal, zero-padded without a window.",
                "classifier" to "Full mixes classify LOOP by duration. Delayed space-only previews may classify UNKNOWN because the classifier inspects a silent head before checking duration. All six-second files are finite gestures.",
                "recipe" to "The production patch identifies only the original model-1 source. The audition wrapper also specifies the new path geometry, common return gain and postmix operation; the production patch alone cannot replay these prototype returns.",
            ),
            "performance" to mapOf("threeClipRenderMilliseconds" to renderMilliseconds,
                "threeClipRenderCpuMilliseconds" to renderCpuMilliseconds, "generationMillisecondsBeforeArchive" to (System.nanoTime() - began) / 1e6,
                "javaVersion" to System.getProperty("java.version"), "architecture" to System.getProperty("os.arch")),
            "acceptance" to "Listening pending. No audibility or musical-quality verdict is inferred from the measurements.",
        )
        writeJson(File(root, "presence/manifest.json"), manifest)
        writeMetrics(root, records)
        File(root, "presence/README.txt").writeText(readme(listeningGain, spaceGain))
        val wavePaths = records.flatMap { listOf(it.getValue("audio").toString(), it.getValue("spaceAudio").toString()) }.toSet() + comparison.path
        writeZip(root, wavePaths)
        println("NACRE space study: three full mixes, three space-only taps and comparison preview written to $root")
    }

    private fun verify(clips: List<NacrePresenceComparison.Clip>, repo: File): Map<String, Any?> {
        check(clips.map { it.id } == listOf("original", "coral", "seaweed"))
        val original = clips.first()
        val fixture = Json.parse(File(repo, "synth/src/test/resources/nacre-model1-reference.json").readText()).obj()
        val rawHash = samplesHash(original.raw)
        val nativeHash = samplesHash(original.native.samples)
        check(rawHash == fixture.getValue("prelevelSamplesSha256").str()) { "The original prelevel reference changed." }
        check(nativeHash == fixture.getValue("nativeSamplesSha256").str()) { "The original native reference changed." }
        check(original.returnSamples.contentEquals(original.originalReturn)) { "The original venue contribution changed." }
        val branches = listOf("bass", "upper", "sympathetic").associateWith { samplesHash(original.stems.getValue(it)) }
        val direct = FloatArray(original.raw.size) { original.stems.getValue("bass")[it] + original.stems.getValue("upper")[it] }
        val oldReturnHash = samplesHash(original.originalReturn)
        for (clip in clips) {
            check(clip.patch.model == 1 && clip.patch.midi == 48 && clip.patch.velocity == 1f && clip.patch.seed == 7L)
            check(clip.patch.voice == original.patch.voice)
            check(clip.patch.macros == original.patch.macros) { "${clip.id}: original source macros changed." }
            check(clip.raw.size == RATE * 6 && clip.returnSamples.size == clip.raw.size)
            check(clip.raw.all { it.isFinite() } && clip.returnSamples.all { it.isFinite() })
            check(clip.native.sampleRate == RATE && clip.native.channels == 1 && clip.native.samples.all { it.isFinite() })
            check(samplesHash(clip.native.samples) == nativeHash) { "${clip.id}: native original source changed." }
            check(clip.events == original.events) { "${clip.id}: pluck events changed." }
            check(samplesHash(clip.originalReturn) == oldReturnHash) { "${clip.id}: original return source changed." }
            for ((key, hash) in branches) check(samplesHash(clip.stems.getValue(key)) == hash) { "${clip.id}: $key changed." }
            check(clip.stems.getValue("venue").contentEquals(clip.returnSamples)) { "${clip.id}: return stem differs." }
            check(clip.diagnostics.values.all { it.isFinite() } && clip.returnGain.isFinite())
            val error = clip.raw.indices.maxOf { i ->
                abs(clip.raw[i] - (original.raw[i].toDouble() - original.originalReturn[i] + clip.returnSamples[i]).toFloat())
            }
            check(error <= 2e-7f) { "${clip.id}: unexpected postmix operation ($error)." }
        }
        check(clips[1].returnGain == clips[2].returnGain) { "Prototype return gains must be common." }
        return linkedMapOf("originalRawMatchesGolden" to true, "originalNativeMatchesGolden" to true,
            "originalRawSamplesSha256" to rawHash, "originalNativeSamplesSha256" to nativeHash,
            "nonVenueBranchesUnchanged" to true, "nonVenueBranchHashes" to branches,
            "directAndBodySamplesSha256" to samplesHash(direct),
            "directEnvelopeSha256" to samplesHash(FloatArray(direct.size) { abs(direct[it]) }),
            "eventsUnchanged" to true, "eventScheduleSha256" to bytesHash(eventsCsv(original.events).toByteArray()),
            "originalReturnSamplesSha256" to oldReturnHash)
    }

    private fun writeClip(
        root: File, clip: NacrePresenceComparison.Clip, original: NacrePresenceComparison.Clip,
        foreground: FloatArray, gain: Float, spaceGain: Float, invariants: Map<String, Any?>, source: Map<String, Any?>,
    ): Map<String, Any?> {
        val full = snip(FloatArray(clip.raw.size) { clip.raw[it] * gain })
        val space = snip(FloatArray(clip.returnSamples.size) { clip.returnSamples[it] * spaceGain })
        check(full.peak() <= CEILING + 1e-6f && space.peak() <= CEILING + 1e-6f)
        val audio = writeAudio(root, clip.id, full)
        val spaceAudio = writeAudio(root, "${clip.id}-space", space)
        val recipePath = "presence/recipes/${clip.id}.json"
        val sourcePatchPath = "presence/recipes/${clip.id}-source-patch.json"
        val eventsPath = "presence/traces/events/${clip.id}.csv"
        val diagnosticsPath = "presence/diagnostics/${clip.id}.json"
        val sourcePatch = File(root, sourcePatchPath).apply { parentFile.mkdirs(); writeText(clip.patch.toJsonText() + "\n") }
        check(NacrePatch.fromJsonText(sourcePatch.readText()) == clip.patch)
        val prototype = linkedMapOf<String, Any?>(
            "auditionOnly" to (clip.id != "original"), "geometry" to clip.id, "geometryCoordinate" to clip.venueGeometry,
            "originalProductionVenueControl" to original.patch.macros.getValue("VENUE"),
            "returnGain" to clip.returnGain, "operation" to clip.operation, "paths" to paths(clip),
            "feedback" to false, "processingSampleRate" to RATE,
            "pathSource" to "Float(original bass stem[i] + original upper stem[i]); all filter states start at zero.",
            "pathRule" to "delayFrames = round(delaySeconds * 44100); delayed input is zero before delayFrames. a = 1 - exp(-2*pi*lowPassHz/44100); filtered += a*(delayedInput-filtered). aperture = 1-depth + depth*(0.5+0.5*cos(2*pi*apertureHz*i/44100+phaseRadians)). Sum weight*filtered*aperture in Double, then convert to Float.",
            "returnRule" to "finalReturnSamples[i] = Float(commonReturnGain * rawPathReturn[i]); the original reference retains its existing return instead.",
            "postmixRule" to "candidateRaw[i] = originalRaw[i] - originalReturn[i] + finalReturnSamples[i]",
            "fullMixPlaybackGain" to gain, "separateSpaceMonitorGain" to spaceGain,
        )
        val recipe = writeJson(File(root, recipePath), linkedMapOf(
            "format" to "snipsnap-nacre-space-study-recipe", "version" to 1, "sourceModel" to 1,
            "originalReferencePatch" to Json.parse(clip.patch.toJsonText()), "prototype" to prototype,
            "replayNote" to "This wrapper and the bundled testkit helper specify the prototype return. The separate source-patch file reproduces the original source only; it does not replay the new return by itself.",
        ))
        val events = File(root, eventsPath).apply { parentFile.mkdirs(); writeText(eventsCsv(clip.events)) }
        val fullMetrics = metrics(full, clip.patch.midi, clip.events) + differences(clip.raw, original.raw) + returnMetrics(clip.returnSamples, foreground)
        val spaceMetrics = metrics(space, clip.patch.midi, clip.events) + returnMetrics(clip.returnSamples, foreground) + mapOf(
            "monitorGain" to spaceGain, "prelevelRms" to rms(clip.returnSamples), "prelevelTotalEnergy" to energy(clip.returnSamples),
        )
        val returnHash = samplesHash(clip.returnSamples)
        val diagnostic = writeJson(File(root, diagnosticsPath), linkedMapOf(
            "id" to clip.id, "prototype" to prototype, "engine" to clip.diagnostics, "invariants" to invariants,
            "prelevelFullMixSamplesSha256" to samplesHash(clip.raw), "returnSamplesSha256" to returnHash,
            "originalReturnSamplesSha256" to samplesHash(clip.originalReturn), "nativeSourceSamplesSha256" to samplesHash(clip.native.samples),
            "renderMilliseconds" to clip.renderMilliseconds, "metrics" to fullMetrics, "spaceMetrics" to spaceMetrics,
            "timeEnergy" to timeEnergy(clip.raw, clip.returnSamples, foreground, original.raw),
        ))
        return linkedMapOf(
            "id" to clip.id, "title" to clip.title, "section" to "presence", "description" to clip.operation,
            "model" to 1, "sourceModel" to 1, "auditionOnly" to (clip.id != "original"),
            "macros" to clip.patch.macros, "midi" to clip.patch.midi, "velocity" to clip.patch.velocity, "seed" to clip.patch.seed.toString(),
            "audio" to audio.path, "spaceAudio" to spaceAudio.path, "recipe" to recipePath,
            "sourcePatch" to sourcePatchPath, "diagnostics" to diagnosticsPath, "events" to eventsPath,
            "durationSeconds" to full.durationSeconds, "sampleRate" to RATE, "channels" to 1, "bitDepth" to 24,
            "matchingPolicy" to "shared-original-reference-gain", "spaceMatchingPolicy" to "separate-common-space-monitor-gain",
            "gains" to mapOf("fullMix" to gain, "spacePreview" to spaceGain, "prototypeReturn" to clip.returnGain),
            "metrics" to fullMetrics, "spaceMetrics" to spaceMetrics,
            "timeEnergy" to timeEnergy(clip.raw, clip.returnSamples, foreground, original.raw),
            "renderMilliseconds" to clip.renderMilliseconds, "waveform" to waveform(full.samples), "spaceWaveform" to waveform(space.samples),
            "waveformKind" to "absolute-peak-per-bin", "prototype" to prototype,
            "source" to mapOf("commit" to source["commit"], "snapshotRoot" to "presence/source/", "patchModel" to 1),
            "hashes" to mapOf("audio" to audio.sha256, "spaceAudio" to spaceAudio.sha256,
                "recipe" to fileHash(recipe), "sourcePatch" to fileHash(sourcePatch), "diagnostics" to fileHash(diagnostic), "events" to fileHash(events),
                "prelevelFullMixSamples" to samplesHash(clip.raw), "returnSamples" to returnHash,
                "nativeOriginalSourceSamples" to samplesHash(clip.native.samples)),
        )
    }

    private fun paths(clip: NacrePresenceComparison.Clip): List<Map<String, Any?>> = clip.pathDetails.mapIndexed { index, path ->
        linkedMapOf("index" to index, "delaySeconds" to path.delaySeconds, "delayFrames" to path.delayFrames, "weight" to path.weight,
            "lowPassHz" to path.lowPassHz, "apertureHz" to path.apertureHz,
            "apertureDepth" to path.apertureDepth, "aperturePhaseRadians" to path.aperturePhaseRadians)
    }

    private fun comparison(root: File, clips: List<NacrePresenceComparison.Clip>, gain: Float): Artifact {
        val segment = RATE * 2
        val gap = RATE * 35 / 100
        val fade = RATE * 20 / 1000
        val output = FloatArray(3 * segment + 2 * gap)
        for ((index, clip) in clips.withIndex()) for (i in 0 until segment) {
            val taper = if (i < segment - fade) 1f else (segment - 1 - i).toFloat() / (fade - 1)
            output[index * (segment + gap) + i] = clip.raw[i] * gain * taper
        }
        return writeAudio(root, "comparison-preview", snip(output))
    }

    private fun comparisonSequence(): List<Map<String, Any?>> = listOf("original", "coral", "seaweed").mapIndexed { index, id ->
        mapOf("id" to id, "fromSeconds" to index * 2.35, "toSeconds" to index * 2.35 + 2,
            "sourceFromSeconds" to 0, "sourceToSeconds" to 2, "segmentEndFadeMilliseconds" to 20)
    }

    private fun writeAudio(root: File, label: String, audio: Snip): Artifact {
        val stream = ByteArrayOutputStream()
        WavWriter.write(stream, audio, WavWriter.BitDepth.PCM_24)
        val bytes = stream.toByteArray()
        val hash = bytesHash(bytes)
        val path = "presence/audio/$label-${hash.take(12)}.wav"
        val file = File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
        return Artifact(path, file, hash)
    }

    private fun metrics(audio: Snip, midi: Int, events: List<Nacre.Event>): Map<String, Any?> {
        val x = audio.samples
        val sum = energy(x)
        val pitchStart = ((events.maxOfOrNull { it.timeSeconds } ?: 0.0) + .04).coerceAtMost((audio.durationSeconds - .30).coerceAtLeast(0.0))
        val pitch = Pitch.detect(audio, pitchStart.toFloat(), .25f)
        val root = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val classification = Classifier.classify(audio)
        return linkedMapOf("peak" to peak(x), "rms" to sqrt(sum / x.size), "dc" to x.sumOf { it.toDouble() } / x.size,
            "loudness" to Loudness.of(audio), "totalEnergy" to sum, "energy90Seconds" to energyTime(x, .90),
            "energy99Seconds" to energyTime(x, .99), "above1kFraction" to above1k(x),
            "classifier" to classification.drumClass.name, "classifierConfidence" to classification.confidence,
            "pitch" to mapOf("hz" to pitch?.hz, "confidence" to pitch?.confidence, "rootHz" to root,
                "errorCentsFromRoot" to pitch?.let { 1200 * ln(it.hz / root) / ln(2.0) }, "fromSeconds" to pitchStart, "windowSeconds" to .25),
            "finite" to x.all { it.isFinite() })
    }

    private fun differences(x: FloatArray, reference: FloatArray): Map<String, Any?> {
        val delta = FloatArray(x.size) { x[it] - reference[it] }
        val xe = energy(x)
        val re = energy(reference)
        val cross = x.indices.sumOf { x[it].toDouble() * reference[it] }
        return mapOf("energyRatioToOriginal" to xe / re, "energyDeltaDb" to 10 * log10(xe / re),
            "correlationWithOriginal" to cross / sqrt(xe * re), "prelevelDifferenceRms" to rms(delta),
            "differenceRmsDbFromOriginal" to ratioDb(rms(delta), rms(reference)), "differenceEnergy99Seconds" to energyTime(delta, .99))
    }

    private fun returnMetrics(space: FloatArray, foreground: FloatArray): Map<String, Any?> = mapOf(
        "returnToForegroundRmsRatio" to rms(space) / rms(foreground), "returnToForegroundDb" to ratioDb(rms(space), rms(foreground)),
        "prelevelReturnRms" to rms(space), "prelevelForegroundRms" to rms(foreground))

    private fun timeEnergy(full: FloatArray, space: FloatArray, foreground: FloatArray, original: FloatArray): List<Map<String, Any?>> {
        val edges = listOf(0.0, .1, .3, .6, 1.0, 2.0, 6.0)
        val total = energy(full)
        val returnTotal = energy(space)
        return edges.zipWithNext().map { (from, to) ->
            val a = (from * RATE).toInt()
            val b = (to * RATE).toInt().coerceAtMost(full.size)
            var mixEnergy = 0.0; var returnEnergy = 0.0; var foregroundEnergy = 0.0; var deltaEnergy = 0.0
            for (i in a until b) {
                mixEnergy += full[i].toDouble() * full[i]; returnEnergy += space[i].toDouble() * space[i]
                foregroundEnergy += foreground[i].toDouble() * foreground[i]
                val delta = full[i].toDouble() - original[i]; deltaEnergy += delta * delta
            }
            mapOf("fromSeconds" to from, "toSeconds" to to, "mixEnergyFraction" to mixEnergy / total,
                "returnEnergyFraction" to if (returnTotal > 0.0) returnEnergy / returnTotal else 0.0,
                "prelevelDifferenceEnergy" to deltaEnergy, "returnToForegroundDb" to ratioDb(sqrt(returnEnergy), sqrt(foregroundEnergy)))
        }
    }

    private fun energyTime(x: FloatArray, fraction: Double): Double? {
        val target = energy(x) * fraction
        if (target <= 0.0) return null
        var sum = 0.0
        for (i in x.indices) { sum += x[i].toDouble() * x[i]; if (sum >= target) return i.toDouble() / RATE }
        return x.lastIndex.toDouble() / RATE
    }

    private fun above1k(x: FloatArray): Double {
        var size = 1
        while (size < x.size) size = size shl 1
        val real = x.copyOf(size)
        val imaginary = FloatArray(size)
        Fft.forward(real, imaginary)
        var all = 0.0; var high = 0.0
        for (bin in 0..size / 2) {
            val power = (real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]) *
                if (bin == 0 || bin == size / 2) 1 else 2
            all += power
            if (bin.toDouble() * RATE / size > 1000) high += power
        }
        return if (all > 0) high / all else 0.0
    }

    private fun copySources(repo: File, root: File): Map<String, Any?> {
        val paths = mutableListOf(
            "synth/src/test/kotlin/com/snipsnap/synth/NacrePresenceComparison.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/NacrePresenceComparisonTest.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/NacrePresenceAuditionGenerator.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/NacreModel1.kt", "synth/src/main/kotlin/com/snipsnap/synth/NacreModels.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Nacre.kt", "synth/src/main/kotlin/com/snipsnap/synth/NacrePatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt", "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt", "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt", "audio/src/main/kotlin/com/snipsnap/audio/Classifier.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Features.kt", "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt", "synth/src/test/resources/nacre-model1-reference.json",
            TEMPLATE, "synth/build.gradle.kts")
        for (path in listOf("synth/NACRE_PRESENCE_AUDITION.md", "synth/NACRE_SPACE_STUDY.md")) if (File(repo, path).isFile) paths += path
        val files = paths.distinct().map { path ->
            val original = File(repo, path)
            check(original.isFile) { "Missing source snapshot input $path" }
            val target = "presence/source/$path"
            File(root, target).apply { parentFile.mkdirs(); original.copyTo(this, overwrite = true) }
            mapOf("repositoryPath" to path, "path" to target, "sha256" to fileHash(original))
        }
        return mapOf("repository" to "https://github.com/BertCalm/SnipSnap", "commit" to git(repo, "rev-parse", "HEAD"),
            "dirty" to (git(repo, "status", "--porcelain").isNotEmpty()),
            "repositoryCommitPushed" to false,
            "note" to "Experimental source is a local commit with complete changed-file snapshots here. No SnipSnap repository push or production promotion is claimed.",
            "snapshotRoot" to "presence/source/", "files" to files)
    }

    private fun writeMetrics(root: File, records: List<Map<String, Any?>>) {
        val keys = listOf("id", "durationSeconds", "peak", "rms", "loudness", "dc", "totalEnergy", "energy99Seconds", "above1kFraction",
            "energyDeltaDb", "differenceRmsDbFromOriginal", "correlationWithOriginal", "returnToForegroundDb", "renderMilliseconds")
        File(root, "presence/metrics.csv").writeText(buildString {
            appendLine(keys.joinToString(","))
            for (record in records) {
                val metric = record.getValue("metrics") as Map<*, *>
                val row = record + metric.entries.associate { it.key.toString() to it.value }
                appendLine(keys.joinToString(",") { csv(row[it]) })
            }
        })
    }

    private fun writeZip(root: File, wavePaths: Set<String>) {
        val files = listOf(File(root, "index.html")) + File(root, "presence").walkTopDown().filter {
            it.isFile && (it.extension != "wav" || it.relativeTo(root).invariantSeparatorsPath in wavePaths)
        }.toList()
        val archive = File(root, ARCHIVE)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        check(archive.length() < 25L * 1024 * 1024) { "The hosted study ZIP must remain below 25 MiB." }
    }

    private fun readme(gain: Float, spaceGain: Float) = """
        NACRE / Space around the original / prototype listening study
        Three canonical six-second full mixes plus three space-only diagnostic previews.
        The original model-1 patch, its direct/body/sympathetic branches and schedule remain fixed.
        New feed-forward return paths replace only the original venue contribution in two candidates.
        Full mixes share one original-reference gain: $gain.
        Space-only previews share a separate common monitor gain: $spaceGain.
        That monitor gain is not the return level in the full mix. No candidate is leveled separately.

        The comparison montage previews the first two seconds of each mix at the same full-mix gain,
        with .35-second gaps and a 20 ms end fade on each preview segment. It is not a canonical render.
        Individual WAVs retain all six seconds. All seven WAVs and evidence are in nacre-space-study.zip.
        Extract once, serve index.html over HTTP, or listen to the WAVs directly.

        presence/recipes/*.json wrappers record the original source patch and prototype path delays,
        low-pass cutoffs, weights, apertures, common return gain and exact postmix operation.
        The separate *-source-patch.json reproduces only the original production source, not the new space.
        This is testkit processing, not a new production engine, model, default or preset.
        Source snapshots, exact float hashes, event traces, CPU timings and signal measurements are included.
        A numerical difference does not establish audibility. Owner listening remains pending.
    """.trimIndent() + "\n"

    private fun eventsCsv(events: List<Nacre.Event>) = buildString {
        appendLine("performer,stringId,timeSeconds,frequencyHz,strength,direction")
        for (e in events) appendLine(listOf(e.performer, e.stringId, e.timeSeconds, e.frequencyHz, e.strength, e.direction).joinToString(",", transform = ::csv))
    }
    private fun waveform(x: FloatArray) = (0 until 96).map { bin ->
        var value = 0f
        for (i in bin * x.size / 96 until (bin + 1) * x.size / 96) value = maxOf(value, abs(x[i]))
        value
    }
    private fun writeJson(file: File, value: Any?): File = file.apply { parentFile.mkdirs(); writeText(Json.write(json(value)) + "\n") }
    private fun snip(x: FloatArray) = Snip(x, channels = 1, sampleRate = RATE)
    private fun energy(x: FloatArray) = x.sumOf { it.toDouble() * it }
    private fun rms(x: FloatArray) = sqrt(energy(x) / x.size)
    private fun peak(x: FloatArray) = x.maxOfOrNull { abs(it) } ?: 0f
    private fun ratioDb(a: Double, b: Double): Double? = if (a > 0 && b > 0) 20 * log10(a / b) else null
    private fun csv(value: Any?): String = value?.toString()?.let { if (it.any { c -> c in ",\"\n\r" }) "\"${it.replace("\"", "\"\"")}\"" else it } ?: ""
    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, ".git").exists() } ?: error("Run inside the SnipSnap checkout.")
    private fun git(repo: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor() == 0) { "git command failed" }
        return output
    }
    private fun cpuNanoseconds(): Long? = ManagementFactory.getThreadMXBean().let {
        if (it.isCurrentThreadCpuTimeSupported && it.isThreadCpuTimeEnabled) it.currentThreadCpuTime.takeIf { value -> value >= 0 } else null
    }
    private fun fileHash(file: File) = bytesHash(file.readBytes())
    private fun bytesHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun samplesHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            if (block.remaining() < 4) { digest.update(block.array(), 0, block.position()); block.clear() }
            block.putInt(sample.toRawBits())
        }
        digest.update(block.array(), 0, block.position())
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported space-study field ${value.javaClass.name}")
    }
}
