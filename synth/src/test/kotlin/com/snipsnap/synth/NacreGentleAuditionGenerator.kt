package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
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
import kotlin.math.pow
import kotlin.math.sqrt

/** Three model-1 gestures with a small, explicitly audition-only lift of the venue contribution. */
object NacreGentleAuditionGenerator {
    private const val RATE = 44_100
    private const val CEILING = .95f
    private const val LISTENING_ZIP = "nacre-gentle-listening.zip"
    private const val RAW_ZIP = "nacre-gentle-raw.zip"
    private const val HOSTED_FILE_LIMIT = 25L * 1024 * 1024

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one NACRE gentle output directory." }
        val repository = repositoryRoot()
        val root = File(args.firstOrNull() ?: "${repository.path}/testkit/nacre-gentle-audition").absoluteFile
        require(root.canonicalFile != repository.canonicalFile) { "Use a dedicated audition output directory." }
        root.mkdirs()
        val began = System.nanoTime()
        val cpuBefore = cpuNanoseconds()
        val clips = NacreGentleComparison.render()
        val renderMilliseconds = (System.nanoTime() - began) / 1e6
        val renderCpuMilliseconds = elapsedCpuMilliseconds(cpuBefore, cpuNanoseconds())
        require(clips.map { it.id } == listOf("original", "coral", "seaweed")) { "Expected original, coral and seaweed." }
        val original = clips.first()
        val invariantHashes = verifyInvariants(clips)
        val published = Json.parse(File(repository, "synth/src/test/resources/nacre-model1-reference.json").readText()).obj()
        val originalRawHash = samplesHash(original.raw)
        val originalNativeHash = samplesHash(original.native.samples)
        check(originalRawHash == published.getValue("prelevelSamplesSha256").str()) { "The untouched original differs from the published model-1 raw reference." }
        check(originalNativeHash == published.getValue("nativeSamplesSha256").str()) { "The untouched original differs from the published model-1 native reference." }

        // One linear gain for all three gestures. The original sets the target; a shared
        // attenuation protects every candidate's peaks without normalizing its venue lift away.
        val familyPeak = clips.maxOf { peak(it.raw) }
        val referenceLoudness = Loudness.of(snip(original.raw))
        require(referenceLoudness > 1e-8f) { "The original reference is silent." }
        val nominalGain = Dsp.MELODIC_LOUDNESS_TARGET / referenceLoudness
        val safetyAttenuation = minOf(1f, CEILING / (familyPeak * nominalGain).coerceAtLeast(1e-9f))
        val listeningGain = nominalGain * safetyAttenuation
        val rawGain = minOf(1f, CEILING / familyPeak.coerceAtLeast(1e-9f))
        val records = clips.map { clip ->
            writeClip(root, clip, original, listeningGain, rawGain, invariantHashes,
                originalRawHash, originalNativeHash)
        }
        val source = copySources(repository, root)
        val template = File(repository, "design/nacre-gentle-audition/index.html")
        check(template.isFile) { "Missing maintained gentle audition page: $template" }
        template.copyTo(File(root, "index.html"), overwrite = true)
        val downloads = listOf(
            mapOf("path" to LISTENING_ZIP, "title" to "Listening pack", "description" to "Three common-gain listening WAVs, page, recipes and evidence."),
            mapOf("path" to RAW_ZIP, "title" to "Raw pack", "description" to "Three common-gain raw WAVs, page, recipes and evidence."),
        )
        val manifest = linkedMapOf<String, Any?>(
            "format" to "snipsnap-nacre-gentle-audition", "version" to 1,
            "revision" to "gentle-venue-comparison-1", "title" to "Nacre / A gentle venue comparison",
            "description" to "The untouched original beside coral and seaweed with only their conditioned venue contribution lifted 1.8 times. Direct strings, bodies, sympathetic strings and event schedules stay unchanged.",
            "engine" to "NACRE", "model" to 1, "voice" to "PEARL", "midi" to original.patch.midi,
            "velocity" to original.patch.velocity, "seed" to original.patch.seed.toString(),
            "sampleRate" to RATE, "channels" to 1, "bitDepth" to 24, "clipCount" to records.size,
            "hold" to false, "productionChange" to false, "generatedAt" to Instant.now().toString(),
            "download" to LISTENING_ZIP, "downloads" to downloads,
            "sections" to listOf(mapOf("id" to "gentle", "title" to "The original, with a little more venue",
                "description" to "Keep the playback volume unchanged. These three clips share the original's playback gain; only the two endpoint venue contributions receive the 1.8 times audition lift.")),
            "clips" to records, "source" to source,
            "gainPolicy" to linkedMapOf(
                "matchingPolicy" to "shared-original-reference-gain", "referenceId" to "original",
                "listeningGain" to listeningGain, "nominalReferenceGain" to nominalGain,
                "commonSafetyAttenuation" to safetyAttenuation, "rawGain" to rawGain,
                "targetLoudness" to Dsp.MELODIC_LOUDNESS_TARGET, "peakCeiling" to CEILING,
                "description" to "One common listening gain for all three, calculated from the original reference and reduced together only if a candidate needs peak headroom. Raw uses one common safe gain. No candidate is leveled independently.",
            ),
            "invariants" to linkedMapOf(
                "directAndBodyUnchanged" to true, "sympatheticUnchanged" to true,
                "directEnvelopeUnchanged" to true, "eventsUnchanged" to true, "hashes" to invariantHashes,
                "originalRawMatchesPublishedReference" to true, "originalNativeMatchesPublishedReference" to true,
                "originalRawSamplesSha256" to originalRawHash, "originalNativeSamplesSha256" to originalNativeHash,
            ),
            "metricsNotes" to linkedMapOf(
                "energy" to "totalEnergy sums squared full-scale samples. energy90Seconds and energy99Seconds mark cumulative 90% and 99% energy from file start. They are signal metrics, not perceptual thresholds.",
                "above1k" to "above1kFraction is the fraction of one-sided squared FFT magnitude above 1000 Hz over the entire finite unwindowed signal, zero-padded to a power of two.",
                "pitch" to "A local 250 ms autocorrelation estimate after the final pluck; it is not a pitch-lock guarantee.",
                "classifier" to "The generic classifier labels files longer than 1.5 seconds LOOP by duration. These are finite six-second gestures. The first 750 ms are classified separately.",
                "nativeReference" to "Native hashes describe the underlying model-1 source renders before the audition-only venue lift. Listening WAVs derive exclusively from each candidate's prelevel raw sum.",
            ),
            "performance" to linkedMapOf("threeClipRenderMilliseconds" to renderMilliseconds,
                "threeClipRenderCpuMilliseconds" to renderCpuMilliseconds,
                "generationMilliseconds" to (System.nanoTime() - began) / 1e6,
                "javaVersion" to System.getProperty("java.version"), "architecture" to System.getProperty("os.arch")),
            "acceptance" to "Awaiting owner listening. No musical-quality verdict is inferred from the measurements.",
        )
        writeJson(File(root, "gentle/manifest.json"), manifest)
        writeMetrics(root, records)
        File(root, "gentle/README.txt").writeText(readme(listeningGain, rawGain))
        writeZip(root, LISTENING_ZIP, records.map { it.getValue("audio").toString() }.toSet())
        writeZip(root, RAW_ZIP, records.map { it.getValue("rawAudio").toString() }.toSet())
        println("NACRE gentle: three PCM24 mono gestures, shared gain $listeningGain, raw gain $rawGain, written to $root")
    }

    private fun verifyInvariants(clips: List<NacreGentleComparison.Clip>): Map<String, String> {
        val original = clips.first()
        check(original.venueLift == 1.0 && original.venueGeometry == .15f)
        val keys = listOf("bass", "upper", "sympathetic")
        val hashes = keys.associateWith { samplesHash(original.stems.getValue(it)) }
        val direct = direct(original)
        val result = hashes + linkedMapOf("directAndBody" to samplesHash(direct),
            "directEnvelope" to samplesHash(FloatArray(direct.size) { abs(direct[it]) }),
            "eventSchedule" to eventsHash(original.events))
        for (clip in clips) {
            require(clip.patch.model == 1 && clip.raw.size == RATE * 6 && clip.raw.all { it.isFinite() }) { "${clip.id}: invalid model-1 raw gesture." }
            require(clip.native.channels == 1 && clip.native.sampleRate == RATE && clip.native.samples.all { it.isFinite() })
            require(clip.diagnostics.values.all { it.isFinite() } && clip.renderMilliseconds.isFinite())
            check(clip.events == original.events) { "${clip.id}: the pluck schedule changed." }
            check(clip.patch.macros.filterKeys { it != "VENUE" } == original.patch.macros.filterKeys { it != "VENUE" })
            check(clip.patch.midi == original.patch.midi && clip.patch.velocity == original.patch.velocity && clip.patch.seed == original.patch.seed)
            for (key in keys) check(samplesHash(clip.stems.getValue(key)) == hashes.getValue(key)) { "${clip.id}: $key changed." }
            check(samplesHash(direct(clip)) == result.getValue("directAndBody")) { "${clip.id}: direct/body sum changed." }
            check(clip.stems.values.all { it.size == clip.raw.size && it.all { sample -> sample.isFinite() } })
            val maxSumError = clip.raw.indices.maxOf { i -> abs(clip.raw[i] - clip.stems.values.sumOf { it[i].toDouble() }.toFloat()) }
            check(maxSumError <= 3e-6f) { "${clip.id}: stems fail to reconstruct the lifted raw gesture ($maxSumError)." }
        }
        return result
    }

    private fun direct(clip: NacreGentleComparison.Clip): FloatArray {
        val bass = clip.stems.getValue("bass")
        val upper = clip.stems.getValue("upper")
        return FloatArray(bass.size) { bass[it] + upper[it] }
    }

    private fun writeClip(
        root: File, clip: NacreGentleComparison.Clip, original: NacreGentleComparison.Clip,
        listeningGain: Float, rawGain: Float, invariantHashes: Map<String, String>,
        originalRawHash: String, originalNativeHash: String,
    ): Map<String, Any?> {
        val audio = snip(FloatArray(clip.raw.size) { clip.raw[it] * listeningGain })
        val raw = snip(FloatArray(clip.raw.size) { clip.raw[it] * rawGain })
        require(audio.peak() <= CEILING + 1e-6f && raw.peak() <= CEILING + 1e-6f) { "${clip.id}: PCM headroom exceeded." }
        val audioPath = "gentle/audio/${clip.id}.wav"
        val rawPath = "gentle/audio/${clip.id}_raw.wav"
        val recipePath = "gentle/recipes/${clip.id}.json"
        val productionRecipePath = "gentle/recipes/${clip.id}-patch.json"
        val eventsPath = "gentle/traces/events/${clip.id}.csv"
        val diagnosticsPath = "gentle/diagnostics/${clip.id}.json"
        val audioFile = WavWriter.write(File(root, audioPath), audio, WavWriter.BitDepth.PCM_24)
        val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
        val productionRecipeFile = File(root, productionRecipePath).apply { parentFile.mkdirs(); writeText(clip.patch.toJsonText() + "\n") }
        check(NacrePatch.fromJsonText(productionRecipeFile.readText()) == clip.patch) { "${clip.id}: production recipe round-trip failed." }
        val audition = linkedMapOf<String, Any?>(
            "venueLift" to clip.venueLift, "venueGeometry" to clip.venueGeometry,
            "originalVenueGeometry" to original.venueGeometry, "originalReferenceId" to original.id,
            "auditionOnly" to (clip.id != "original"), "productionModelUnchanged" to true,
            "operation" to "Render the saved model-1 patch without independent output leveling, then multiply only its conditioned venue stem by venueLift and sum it with the unchanged direct/body and sympathetic contributions.",
            "playbackGain" to listeningGain, "rawExportGain" to rawGain,
        )
        val recipeFile = writeJson(File(root, recipePath), linkedMapOf(
            "format" to "snipsnap-nacre-gentle-audition-recipe", "version" to 1, "model" to 1,
            "patch" to Json.parse(clip.patch.toJsonText()), "audition" to audition,
            "note" to "The audition wrapper is not a production Patch file. The separate -patch.json reconstructs the underlying unlifted model-1 source; the venue factor remains an explicit audition operation.",
        ))
        val eventsFile = File(root, eventsPath).apply { parentFile.mkdirs(); writeText(eventsCsv(clip.events)) }
        val metric = metrics(audio, clip.patch.midi, clip.events)
        val rawMetric = metrics(snip(clip.raw), clip.patch.midi, clip.events)
        val nativeHash = samplesHash(clip.native.samples)
        val componentHashes = clip.stems.mapValues { samplesHash(it.value) }
        val diagnostics = linkedMapOf<String, Any?>(
            "id" to clip.id, "model" to 1, "audition" to audition, "engine" to clip.diagnostics,
            "invariantHashes" to invariantHashes, "componentHashes" to componentHashes,
            "eventsUnchanged" to true, "directAndBodyUnchanged" to true, "sympatheticUnchanged" to true,
            "directEnvelopeUnchanged" to true, "recipeRoundtripVerified" to true,
            "renderMilliseconds" to clip.renderMilliseconds,
            "nativeSourceSamplesSha256" to nativeHash,
            "originalNativeSamplesSha256" to originalNativeHash, "originalRawSamplesSha256" to originalRawHash,
            "prelevelSamplesSha256" to samplesHash(clip.raw), "rawPrelevelMetrics" to rawMetric,
            "listeningMetrics" to metric, "venueRms" to rms(clip.stems.getValue("venue")),
        )
        val diagnosticFile = writeJson(File(root, diagnosticsPath), diagnostics)
        return linkedMapOf(
            "id" to clip.id, "title" to clip.title, "description" to description(clip), "section" to "gentle",
            "engine" to "NACRE", "voice" to clip.patch.voice.name, "model" to 1,
            "macros" to clip.patch.macros, "midi" to clip.patch.midi, "velocity" to clip.patch.velocity,
            "seed" to clip.patch.seed.toString(), "hold" to false, "auditionOnly" to (clip.id != "original"),
            "venueLift" to clip.venueLift, "venueGeometry" to clip.venueGeometry,
            "originalVenueGeometry" to original.venueGeometry, "referenceId" to "original",
            "matchingPolicy" to "shared-original-reference-gain", "gains" to mapOf("matched" to listeningGain, "raw" to rawGain),
            "audio" to audioPath, "rawAudio" to rawPath, "recipe" to recipePath,
            "productionRecipe" to productionRecipePath, "events" to eventsPath, "diagnostics" to diagnosticsPath,
            "durationSeconds" to audio.durationSeconds, "sampleRate" to RATE, "channels" to 1, "bitDepth" to 24,
            "metrics" to metric, "rawMetrics" to rawMetric, "renderMilliseconds" to clip.renderMilliseconds,
            "waveform" to waveform(audio.samples), "waveformKind" to "absolute-peak-per-bin",
            "invariantHashes" to invariantHashes, "componentHashes" to componentHashes,
            "hashes" to linkedMapOf("audio" to fileHash(audioFile), "rawAudio" to fileHash(rawFile),
                "recipe" to fileHash(recipeFile), "productionRecipe" to fileHash(productionRecipeFile),
                "events" to fileHash(eventsFile), "diagnostics" to fileHash(diagnosticFile),
                "prelevelSamples" to samplesHash(clip.raw), "nativeSourceSamples" to nativeHash,
                "originalNativeSamples" to originalNativeHash),
        )
    }

    private fun description(clip: NacreGentleComparison.Clip): String = when (clip.id) {
        "original" -> "The untouched published model-1 gesture: original VENUE .15 geometry, original venue contribution, original direct strings, bodies, sympathetic strings and schedule."
        "coral" -> "The same model-1 gesture at coral VENUE 0, with only its conditioned venue contribution multiplied by 1.8. Direct strings, bodies, sympathetic strings and schedule remain identical to the original."
        "seaweed" -> "The same model-1 gesture at seaweed VENUE 1, with only its conditioned venue contribution multiplied by 1.8. Direct strings, bodies, sympathetic strings and schedule remain identical to the original."
        else -> error("Unexpected gentle clip ${clip.id}")
    }

    private fun metrics(audio: Snip, midi: Int, events: List<Nacre.Event>): Map<String, Any?> {
        val samples = audio.samples
        val totalEnergy = samples.sumOf { it.toDouble() * it }
        val lastPluck = events.maxOfOrNull { it.timeSeconds } ?: 0.0
        val pitchStart = (lastPluck + .04).coerceAtMost((audio.durationSeconds - .30).coerceAtLeast(0.0))
        val pitch = Pitch.detect(audio, pitchStart.toFloat(), .25f)
        val rootHz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val classification = Classifier.classify(audio)
        val onsetClass = Classifier.classify(snip(samples.copyOfRange(0, minOf(samples.size, RATE * 3 / 4))))
        return linkedMapOf(
            "peak" to peak(samples), "rms" to sqrt(totalEnergy / samples.size),
            "dc" to samples.sumOf { it.toDouble() } / samples.size, "loudness" to Loudness.of(audio),
            "totalEnergy" to totalEnergy, "energy90Seconds" to energyTime(samples, totalEnergy, .90),
            "energy99Seconds" to energyTime(samples, totalEnergy, .99), "above1kFraction" to above1k(samples),
            "classifier" to classification.drumClass.name, "classifierConfidence" to classification.confidence,
            "onsetClassifier" to onsetClass.drumClass.name, "onsetClassifierConfidence" to onsetClass.confidence,
            "pitch" to mapOf("hz" to pitch?.hz, "confidence" to pitch?.confidence, "rootHz" to rootHz,
                "errorCentsFromRoot" to pitch?.let { 1200 * ln(it.hz / rootHz) / ln(2.0) },
                "fromSeconds" to pitchStart, "windowSeconds" to .25), "finite" to samples.all { it.isFinite() },
        )
    }

    private fun energyTime(samples: FloatArray, energy: Double, fraction: Double): Double? {
        if (energy <= 0.0) return null
        var cumulative = 0.0
        for (i in samples.indices) {
            cumulative += samples[i].toDouble() * samples[i]
            if (cumulative >= energy * fraction) return i.toDouble() / RATE
        }
        return samples.lastIndex.toDouble() / RATE
    }

    private fun above1k(samples: FloatArray): Double {
        var size = 1
        while (size < samples.size) size = size shl 1
        val real = samples.copyOf(size)
        val imaginary = FloatArray(size)
        Fft.forward(real, imaginary)
        var all = 0.0
        var high = 0.0
        for (bin in 0..size / 2) {
            val weight = if (bin == 0 || bin == size / 2) 1.0 else 2.0
            val power = (real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]) * weight
            all += power
            if (bin.toDouble() * RATE / size > 1000.0) high += power
        }
        return if (all > 0.0) high / all else 0.0
    }

    private fun copySources(repository: File, root: File): Map<String, Any?> {
        val paths = listOf(
            "synth/src/test/kotlin/com/snipsnap/synth/NacreGentleComparison.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/NacreGentleComparisonTest.kt",
            "synth/NACRE_GENTLE_AUDITION.md",
            "synth/src/test/kotlin/com/snipsnap/synth/NacreGentleAuditionGenerator.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Nacre.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/NacrePatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Classifier.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Features.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt",
            "synth/src/test/resources/nacre-model1-reference.json",
            "design/nacre-gentle-audition/index.html", "synth/build.gradle.kts",
        )
        val files = paths.map { path ->
            val source = File(repository, path)
            check(source.isFile) { "Missing source snapshot input $path" }
            val target = "gentle/source/$path"
            File(root, target).apply { parentFile.mkdirs(); source.copyTo(this, overwrite = true) }
            mapOf("repositoryPath" to path, "path" to target, "sha256" to fileHash(source))
        }
        return linkedMapOf("repository" to "https://github.com/BertCalm/SnipSnap",
            "commit" to git(repository, "rev-parse", "HEAD"),
            "dirty" to (git(repository, "status", "--porcelain")?.isNotEmpty() ?: true),
            "snapshotRoot" to "gentle/source/", "files" to files,
            "note" to "The commit and exact file hashes identify the source used for generation. A dirty tree is disclosed rather than attributed to its parent commit alone.")
    }

    private fun writeMetrics(root: File, records: List<Map<String, Any?>>) {
        val keys = listOf("id", "model", "venueGeometry", "venueLift", "durationSeconds", "peak", "rms", "dc", "loudness", "totalEnergy", "energy90Seconds", "energy99Seconds", "above1kFraction", "classifier", "onsetClassifier", "renderMilliseconds")
        File(root, "gentle/metrics.csv").writeText(buildString {
            appendLine(keys.joinToString(","))
            for (record in records) {
                val metrics = record.getValue("metrics") as Map<*, *>
                val row = record + metrics.entries.associate { it.key.toString() to it.value }
                appendLine(keys.joinToString(",") { csv(row[it]) })
            }
        })
    }

    private fun eventsCsv(events: List<Nacre.Event>): String = buildString {
        appendLine("performer,stringId,timeSeconds,frequencyHz,strength,direction")
        for (event in events) appendLine(listOf(event.performer, event.stringId, event.timeSeconds,
            event.frequencyHz, event.strength, event.direction).joinToString(",", transform = ::csv))
    }
    private fun eventsHash(events: List<Nacre.Event>): String = bytesHash(eventsCsv(events).toByteArray(Charsets.UTF_8))
    private fun waveform(samples: FloatArray): List<Float> = (0 until 96).map { bin ->
        var value = 0f
        for (i in bin * samples.size / 96 until (bin + 1) * samples.size / 96) value = maxOf(value, abs(samples[i]))
        value
    }
    private fun readme(listeningGain: Float, rawGain: Float) = """
        NACRE / A gentle venue comparison
        Three six-second mono 44.1 kHz PCM24 gestures from the published model-1 synthesis.
        Original: original VENUE .15 and venue factor 1.
        Coral: VENUE 0 and an audition-only venue factor of 1.8.
        Seaweed: VENUE 1 and an audition-only venue factor of 1.8.
        The direct/body, sympathetic and event hashes are verified identical across all three.
        The original raw and native float hashes must match the published model-1 reference fixture.

        Listening gain $listeningGain is shared by all three, anchored to the untouched original.
        Raw export gain $rawGain is shared by all three. Candidates are never leveled independently.
        Production code, defaults and models are unchanged by this test-only comparison.

        Open index.html through a web server, or listen to gentle/audio/*.wav directly.
        Extract both ZIPs into the same folder for both playback modes.
        gentle/recipes/*.json wrappers save the exact model-1 patch and explicit audition factor.
        Separate *-patch.json files reproduce only the unlifted production source render.
        Native source hashes are evidence, not candidate playback sources.
        Event traces, metrics, diagnostic hashes and source snapshots are under gentle/.
        Cumulative energy timing and above-1k energy are numerical measurements, not listening verdicts.
        The generic classifier's LOOP result follows its duration rule; these are finite gestures.
        Owner listening remains the acceptance test.
    """.trimIndent() + "\n"
    private fun writeZip(root: File, name: String, audioPaths: Set<String>) {
        val files = listOf(File(root, "index.html")) + File(root, "gentle").walkTopDown().filter { file ->
            file.isFile && (file.extension != "wav" || file.relativeTo(root).invariantSeparatorsPath in audioPaths)
        }.toList()
        val archive = File(root, name)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                if (file == File(root, "index.html")) {
                    val mode = if (name == RAW_ZIP) "raw" else "listening"
                    zip.write(file.readText().replace("<html lang=\"en\">", "<html lang=\"en\" data-pack=\"$mode\">").toByteArray(Charsets.UTF_8))
                } else file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        check(archive.length() < HOSTED_FILE_LIMIT) { "${archive.name} exceeds the hosted 25 MiB file limit." }
    }
    private fun writeJson(file: File, value: Any?): File = file.apply { parentFile.mkdirs(); writeText(Json.write(json(value)) + "\n") }
    private fun snip(samples: FloatArray) = Snip(samples, channels = 1, sampleRate = RATE)
    private fun peak(samples: FloatArray) = samples.maxOfOrNull { abs(it) } ?: 0f
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun csv(value: Any?): String = value?.toString()?.let { if (it.any { char -> char in ",\"\n\r" }) "\"${it.replace("\"", "\"\"")}\"" else it } ?: ""
    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, ".git").exists() } ?: error("Run inside the SnipSnap checkout.")
    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(repository).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun cpuNanoseconds(): Long? = ManagementFactory.getThreadMXBean().let { bean ->
        if (bean.isCurrentThreadCpuTimeSupported && bean.isThreadCpuTimeEnabled) bean.currentThreadCpuTime.takeIf { it >= 0 } else null
    }
    private fun elapsedCpuMilliseconds(before: Long?, after: Long?): Double? = if (before == null || after == null) null else (after - before) / 1e6
    private fun fileHash(file: File): String = bytesHash(file.readBytes())
    private fun bytesHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
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
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported gentle audition field ${value.javaClass.name}")
    }
}
