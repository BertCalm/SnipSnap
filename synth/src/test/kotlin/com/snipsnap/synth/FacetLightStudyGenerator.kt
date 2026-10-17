package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** A bounded directional audition. It neither changes FACET model 2 nor introduces a patch model. */
object FacetLightStudyGenerator {
    private const val REVISION = "light-study-v1"
    private const val SECONDS = 6f
    private const val VELOCITY = .8f
    private const val SEED = 17
    private const val PREFIX = "light-study"

    private data class Case(val voice: FacetVoice, val midi: Int, val direction: FacetLightDirection?) {
        val variant get() = direction?.name?.lowercase() ?: "r2"
        val group get() = "light_${voice.name.lowercase()}_$midi"
        val id get() = "${group}_$variant"
        val order get() = when (direction) { null -> 0; FacetLightDirection.GLASS_GLINTS -> 1; FacetLightDirection.AIRY_WASH -> 2 }
        val label get() = when (direction) { null -> "R2 reference"; FacetLightDirection.GLASS_GLINTS -> "Glass glints"; FacetLightDirection.AIRY_WASH -> "Airy wash" }
        val sourceClass get() = if (direction == null) Facet::class.java.name else FacetLightStudy::class.java.name
    }

    private data class Previous(val descriptor: Map<String, Any?>?, val protectedFiles: Map<File, String>)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) { "usage: FacetLightStudyGenerator [output-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit/facet-audition").canonicalFile
        require(root.mkdirs() || root.isDirectory)
        val repository = repositoryRoot()
        val generatedAt = Instant.now().toString()
        val started = System.nanoTime()
        val sourceHashes = rendererSources(repository)
        val runtimeHashes = runtimeHashes()
        val provenance = linkedMapOf("repository" to "https://github.com/BertCalm/SnipSnap",
            "revision" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "sourceSha256" to sourceHashes, "runtimeClassSha256" to runtimeHashes)
        val previous = preservePrevious(root)
        val cases = buildList {
            for (voice in listOf(FacetVoice.BLOOM, FacetVoice.SHIMMER)) for (midi in listOf(48, 60)) {
                add(Case(voice, midi, null))
                add(Case(voice, midi, FacetLightDirection.GLASS_GLINTS))
                add(Case(voice, midi, FacetLightDirection.AIRY_WASH))
            }
        }
        val clips = mutableListOf<Map<String, Any?>>()
        val replayChecks = linkedMapOf<String, Boolean>()
        for ((index, case) in cases.withIndex()) {
            val macros = publishedDefaults(case.voice)
            val options = FacetOptions(seconds = SECONDS, resonance = true, commonGainRouting = false, oversample = 4, sourceMotion = true)
            fun render(): FacetRender = if (case.direction == null) Facet.probe(case.voice, macros, case.midi, VELOCITY, SEED,
                initialAlignment = 0f, options = options, modelVersion = 2)
            else FacetLightStudy.probe(case.voice, case.direction, macros, case.midi, VELOCITY, SEED, 0f, options)
            val renderStart = System.nanoTime()
            val rendered = render()
            val elapsed = (System.nanoTime() - renderStart) / 1_000_000.0
            val rawSource = Snip(rendered.raw, 1, Dsp.RATE)
            validate(rawSource, case.id)
            validate(rendered.snip, case.id)
            require(rawSource.frameCount == rendered.snip.frameCount && rawSource.frameCount == (SECONDS * Dsp.RATE).toInt())
            if (case.variant !in replayChecks) {
                val replay = render()
                val same = rawSource.samples.contentEquals(replay.raw) && rendered.snip.samples.contentEquals(replay.snip.samples)
                require(same) { "${case.id}: non-deterministic replay" }
                replayChecks[case.variant] = same
            }
            val exportGain = minOf(1f, .99f / rawSource.peak().coerceAtLeast(1e-9f))
            val raw = Snip(FloatArray(rawSource.samples.size) { rawSource.samples[it] * exportGain }, 1, Dsp.RATE)
            val native = rendered.snip
            val matched = AuditionLevel.level(native)
            require(native.peak() <= 1f && matched.peak() <= 1f) { "${case.id}: export clipping" }
            val audio = linkedMapOf("raw" to raw, "native" to native, "matched" to matched)
            val paths = audio.keys.associateWith { "$PREFIX/$it/${case.id}.wav" }
            val hashes = linkedMapOf<String, String>()
            val decoded = linkedMapOf<String, Snip>()
            for ((variant, snip) in audio) {
                val file = WavWriter.write(File(root, paths.getValue(variant)), snip, WavWriter.BitDepth.PCM_24, smpl = SmplChunk(case.midi))
                require(file.length() < 24L * 1024 * 1024)
                val read = WavReader.read(file)
                require(read.sampleRate == Dsp.RATE && read.channels == 1 && read.frameCount == snip.frameCount)
                require(read.samples.indices.all { abs(read.samples[it] - snip.samples[it]) < .0000003f }) { "${case.id}: PCM24 round trip" }
                hashes[variant] = sha256(file)
                decoded[variant] = read
            }
            val golden = if (case.direction == null) {
                val expected = R2_GOLDENS.getValue("${case.voice.name.lowercase()}_${case.midi}")
                require(hashes == expected) { "${case.id}: the published R2 reference changed: $hashes" }
                mapOf("publishedR2PcmIdentical" to true, "sha256" to expected)
            } else null
            val settingsPath = "$PREFIX/settings/${case.id}.json"
            val tracePath = "$PREFIX/traces/${case.id}.json"
            val settings = linkedMapOf<String, Any?>(
                "format" to "snipsnap.facet-light-study", "studyVersion" to FacetLightStudy.STUDY_VERSION,
                "sourceClass" to case.sourceClass, "entrypoint" to "probe", "direction" to (case.direction?.name ?: "R2_REFERENCE"),
                "referenceModelVersion" to if (case.direction == null) 2 else null,
                "voice" to case.voice.name, "macros" to macros, "midi" to case.midi, "velocity" to VELOCITY,
                "seed" to SEED, "initialAlignment" to 0f,
                "options" to mapOf("seconds" to SECONDS, "resonance" to true, "commonGainRouting" to false, "oversample" to 4, "sourceMotion" to true),
                "provenance" to provenance,
                "notes" to "This is a replayable directional study setting, not registered patch JSON. Call the recorded source class and entrypoint with these arguments. R2_REFERENCE uses Facet.probe(modelVersion=2).",
            )
            writeJson(File(root, settingsPath), settings)
            writeJson(File(root, tracePath), mapOf("clip" to case.id, "sourceClass" to case.sourceClass,
                "samples" to rendered.trace.map { mapOf("seconds" to it.seconds, "phase" to it.phase, "routeEnergy" to it.routeEnergy) }))
            val diagnostics = rendered.diagnostics
            val diagnosticMap = linkedMapOf<String, Any?>(
                "settings" to settingsPath, "trace" to tracePath, "renderMillis" to elapsed,
                "preparationMillis" to diagnostics.preparationMillis, "synthesisMillis" to diagnostics.synthesisMillis,
                "rawPeak" to diagnostics.rawPeak, "rawRms" to diagnostics.rawRms, "rawDc" to diagnostics.rawDc,
                "statePeak" to diagnostics.statePeak, "rotorHz" to diagnostics.rotorHz, "startPhase" to diagnostics.startPhase,
                "endPhase" to diagnostics.endPhase, "effectiveFacets" to diagnostics.effectiveFacets,
                "routeDiversity" to diagnostics.routeDiversity, "routeMotion" to diagnostics.routeMotion, "oversample" to diagnostics.oversample,
            )
            val pitch = pitch(decoded.getValue("native"), case.midi)
            val metrics = linkedMapOf<String, Any?>("sourceRaw" to amplitudes(rawSource), "rawExportGain" to exportGain,
                "raw" to amplitudes(decoded.getValue("raw")), "native" to amplitudes(decoded.getValue("native")),
                "matched" to amplitudes(decoded.getValue("matched")), "pitch" to pitch,
                "features" to features(decoded.getValue("matched")))
            clips += linkedMapOf(
                "id" to case.id, "title" to "${case.voice.name} · ${noteName(case.midi)} · ${case.label}",
                "description" to description(case), "voice" to case.voice.name, "midi" to case.midi, "noteName" to noteName(case.midi),
                "velocity" to VELOCITY, "seconds" to SECONDS, "frames" to native.frameCount, "macros" to macros,
                "alignment" to 0f, "seed" to SEED, "section" to "light-study",
                "direction" to (case.direction?.name ?: "R2_REFERENCE"), "directionLabel" to case.label,
                "directionStatus" to if (case.direction == null) "Reference" else "Directional study",
                "sourceClass" to case.sourceClass, "studyVersion" to FacetLightStudy.STUDY_VERSION,
                "modelVersion" to if (case.direction == null) 2 else null,
                "comparison" to mapOf("group" to case.group, "variant" to case.variant, "order" to case.order),
                "variant" to case.variant, "raw" to paths.getValue("raw"), "native" to paths.getValue("native"), "matched" to paths.getValue("matched"),
                "recipe" to settingsPath, "diagnostics" to diagnosticMap, "metrics" to metrics, "sha256" to hashes,
                "waveform" to waveform(decoded.getValue("matched").samples), "goldenReplay" to golden,
            )
            println("FACET light ${index + 1}/12: ${case.id}, ${elapsed.toInt()} ms, raw peak ${rawSource.peak()}, pitch ${pitch["hz"] ?: "unresolved"} Hz, confidence ${pitch["confidence"] ?: "n/a"}")
        }
        require(sourceHashes == rendererSources(repository)) { "Renderer sources changed during generation; recompile and regenerate" }
        require(previous.protectedFiles.all { (file, hash) -> file.isFile && sha256(file) == hash }) { "A previous-study asset changed during generation" }
        val summary = linkedMapOf("clipCount" to clips.size, "wavCount" to clips.size * 3, "totalAudioSeconds" to 72,
            "pcmExportsVerified" to true, "allFinite" to true, "deterministicByDirection" to replayChecks,
            "publishedR2ReferenceClipsVerified" to clips.count { it["goldenReplay"] != null },
            "previousFilesPreserved" to previous.protectedFiles.size,
            "pitchUnresolvedClips" to clips.filter { ((it["metrics"] as Map<*, *>)["pitch"] as Map<*, *>)["hz"] == null }.map { it["id"] },
            "pitchNotes" to "Pitch estimates describe what the stock detector found. Noise and inharmonic glints may have weak or absent periodic estimates; no pitch threshold approves or rejects these candidates.",
            "renderMillis" to clips.sumOf { ((it["diagnostics"] as Map<*, *>)["renderMillis"] as Number).toDouble() })
        val reportPath = "$PREFIX/report.json"
        writeJson(File(root, reportPath), mapOf("schemaVersion" to 1, "title" to "FACET light studies", "revision" to REVISION,
            "generatedAt" to generatedAt, "provenance" to provenance, "metricNotes" to METRIC_NOTES, "summary" to summary, "clips" to clips))
        writeCsv(File(root, "$PREFIX/metrics.csv"), clips)
        File(root, "$PREFIX/README.txt").writeText("FACET light studies: R2 reference, Glass glints and Airy wash. Twelve dry six-second clips, mono 44.1 kHz PCM24. The ZIP contains matched audio and complete study settings; raw/native files remain individually downloadable. These are directional studies, not a new default or accepted FACET revision. Previous R2 assets remain intact.\n$METRIC_NOTES\n")
        writeZip(root, clips, reportPath)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "FACET · Light studies", "revision" to REVISION, "generatedAt" to generatedAt,
            "description" to "Two focused directions for how reflections could sound: glass glints and an airy wash, each beside the unchanged R2 reference.",
            "status" to "Directional listening studies · no default change or sonic approval", "engine" to "FACET study",
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24, "provenance" to provenance, "metricNotes" to METRIC_NOTES,
            "summary" to summary, "previousStudy" to previous.descriptor,
            "sections" to listOf(mapOf("id" to "light-study", "title" to "Light studies",
                "description" to "Four trios share pitch, velocity, voice controls, seed, alignment and duration. Compare the published R2 reference with Glass glints and Airy wash. Level matching helps compare texture; Raw retains source energy differences.", "clips" to clips)),
            "downloads" to mapOf("zip" to "light-study.zip", "report" to reportPath, "metrics" to "$PREFIX/metrics.csv"),
        )
        writeJson(File(root, "manifest.json"), manifest)
        for (asset in listOf("index.html", "style.css", "app.js")) {
            val resource = requireNotNull(FacetLightStudyGenerator::class.java.getResourceAsStream("/audition/facet-audition/$asset")) { "Missing frontend $asset" }
            resource.use { input -> File(root, asset).outputStream().use { input.copyTo(it) } }
        }
        println("FACET light studies ready: 12 clips, 36 PCM24 WAVs, ${(System.nanoTime() - started) / 1e9}s total, $root")
    }

    private fun description(case: Case): String = when (case.direction) {
        null -> "The published R2 source, rendered unchanged and checked against its original PCM24 hashes."
        FacetLightDirection.GLASS_GLINTS -> "A directional study of brief glass-like reflections and lingering resonances. Compare the texture and the space between glints."
        FacetLightDirection.AIRY_WASH -> "A directional study of diffuse, overlapping reflections. Compare the airy texture and continuity against the reference."
    }

    private fun preservePrevious(root: File): Previous {
        val saved = File(root, "r2-manifest.json")
        val current = File(root, "manifest.json")
        val source = if (saved.isFile) saved else current.takeIf { it.isFile && Json.parse(it.readText()).obj()["revision"]?.str() == "r2-living-source" }
        if (source == null) return Previous(null, emptyMap())
        val parsed = Json.parse(source.readText()).obj()
        require(parsed["revision"]?.str() == "r2-living-source") { "Prior study must be the published R2 audition" }
        if (source != saved) source.copyTo(saved, overwrite = false)
        val priorReport = File(root, "r2-report.json")
        if (!priorReport.exists() && File(root, "report.json").isFile) File(root, "report.json").copyTo(priorReport)
        val priorClips = parsed.getValue("sections").arr().flatMap { it.obj().getValue("clips").arr() }
        require(priorClips.size == 57) { "Expected the complete 57-clip R2 study" }
        for (value in priorClips) {
            val clip = value.obj()
            val hashes = clip.getValue("sha256").obj()
            for (variant in listOf("raw", "native", "matched")) {
                val file = File(root, clip.getValue(variant).str())
                require(file.isFile && sha256(file) == hashes.getValue(variant).str()) { "Previous R2 asset changed: $file" }
            }
        }
        val protectedFiles = root.walkTopDown().filter { file ->
            if (!file.isFile) false else {
                val path = file.relativeTo(root).invariantSeparatorsPath
                path !in setOf("manifest.json", "index.html", "style.css", "app.js", "light-study.zip", "facet-light-study-source.zip") && !path.startsWith("$PREFIX/")
            }
        }.associateWith(::sha256)
        return Previous(mapOf("manifest" to "r2-manifest.json", "report" to "r2-report.json", "title" to "Earlier R2 audition",
            "description" to "The complete earlier 57-clip R2 source study, with its original audio, measurements, settings and downloads.",
            "revision" to "r2-living-source", "clipCount" to 57, "manifestSha256" to sha256(saved)), protectedFiles)
    }

    private fun publishedDefaults(voice: FacetVoice) = if (voice == FacetVoice.BLOOM) linkedMapOf(
        "ILLUMINATE" to .45f, "ELEVATION" to .30f, "CHROMA" to .20f, "REVOLVE" to .15f, "FACETS" to .25f, "HOLD" to 0f,
    ) else linkedMapOf("ILLUMINATE" to .60f, "ELEVATION" to .65f, "CHROMA" to .65f, "REVOLVE" to .65f, "FACETS" to .80f, "HOLD" to 0f)

    private fun validate(snip: Snip, id: String) {
        require(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$id: invalid audio" }
        require(snip.peak() > 1e-8f) { "$id: silent candidate" }
    }

    private fun amplitudes(snip: Snip): Map<String, Any?> {
        val mean = snip.samples.sumOf { it.toDouble() } / snip.samples.size
        val rms = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)
        return mapOf("peak" to snip.peak(), "rms" to rms, "dc" to mean, "loudestWindowRms" to Loudness.of(snip), "finite" to true)
    }

    private fun pitch(snip: Snip, midi: Int): Map<String, Any?> {
        val target = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val estimates = listOf(.35f, 1.1f, 2.4f).map { from ->
            val found = Pitch.detect(snip, from, .25f)
            mapOf("fromSeconds" to from, "hz" to found?.hz, "confidence" to found?.confidence,
                "cents" to found?.let { 1200 * ln(it.hz / target) / ln(2.0) })
        }
        val best = estimates.filter { it["confidence"] != null }.maxByOrNull { (it.getValue("confidence") as Number).toDouble() }
        return mapOf("requestedHz" to target, "hz" to best?.get("hz"), "confidence" to best?.get("confidence"),
            "cents" to best?.get("cents"), "windows" to estimates, "method" to "Shared Pitch.detect, highest confidence of three 250 ms windows; no periodicity acceptance gate")
    }

    private fun features(snip: Snip): Map<String, Any?> {
        val from = (.4f * snip.sampleRate).toInt()
        val body = Snip(snip.samples.copyOfRange(from, from + (.3f * snip.sampleRate).toInt()), 1, snip.sampleRate)
        val features = FeatureExtractor.extract(body)
        val classification = Classifier.classify(snip)
        return mapOf("bodyStartSeconds" to .4, "bodyWindowSeconds" to .3, "centroidHz" to features.centroidHz,
            "spectralWindowFrames" to 4096, "flatness" to features.flatness, "highRatio" to features.highRatio,
            "wholeClassifier" to classification.drumClass.name, "wholeClassifierConfidence" to classification.confidence,
            "notes" to "Whole-file LOOP is a duration label. The stock extractor measures spectrum on the first 4096 frames of the specified body crop; other features use the crop. These are diagnostics, not musical acceptance.")
    }

    private fun waveform(samples: FloatArray) = (0 until 120).map { bin ->
        val from = bin * samples.size / 120
        val to = ((bin + 1) * samples.size / 120).coerceAtLeast(from + 1).coerceAtMost(samples.size)
        var peak = 0f
        for (i in from until to) peak = maxOf(peak, abs(samples[i]))
        peak
    }

    private fun writeCsv(file: File, clips: List<Map<String, Any?>>) {
        file.parentFile.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine("id,voice,midi,direction,rawPeak,rawRms,rawDc,nativePeak,matchedPeak,matchedRms,pitchHz,pitchConfidence,pitchCents,bodyCentroidHz,bodyFlatness,renderMillis")
            for (clip in clips) {
                val metrics = clip["metrics"] as Map<*, *>
                val raw = metrics["sourceRaw"] as Map<*, *>
                val native = metrics["native"] as Map<*, *>
                val matched = metrics["matched"] as Map<*, *>
                val pitch = metrics["pitch"] as Map<*, *>
                val features = metrics["features"] as Map<*, *>
                out.appendLine(listOf(clip["id"], clip["voice"], clip["midi"], clip["direction"], raw["peak"], raw["rms"], raw["dc"],
                    native["peak"], matched["peak"], matched["rms"], pitch["hz"], pitch["confidence"], pitch["cents"], features["centroidHz"], features["flatness"],
                    (clip["diagnostics"] as Map<*, *>)["renderMillis"]).joinToString(",") { it?.toString() ?: "" })
            }
        }
    }

    private fun writeZip(root: File, clips: List<Map<String, Any?>>, report: String) {
        val paths = linkedSetOf("$PREFIX/README.txt", report, "$PREFIX/metrics.csv")
        for (clip in clips) {
            paths += clip.getValue("matched") as String
            paths += clip.getValue("recipe") as String
            paths += (clip["diagnostics"] as Map<*, *>)["trace"] as String
        }
        val file = File(root, "light-study.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for (path in paths.sorted()) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                File(root, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        require(file.length() < 24L * 1024 * 1024) { "Focused listening ZIP exceeds static-host file limit" }
    }

    private fun rendererSources(repository: File): Map<String, String> = SOURCE_FILES.associateWith { path ->
        val file = File(repository, path)
        require(file.isFile) { "Missing provenance source $path" }
        sha256(file)
    }

    private fun runtimeHashes(): Map<String, String> {
        val types = linkedSetOf<Class<*>>()
        fun collect(type: Class<*>) { if (types.add(type)) type.declaredClasses.forEach(::collect) }
        listOf(FacetLightStudy::class.java, FacetLightDirection::class.java, Facet::class.java, FacetOptions::class.java,
            FacetRender::class.java, FacetDiagnostics::class.java, FacetTrace::class.java, MacroSpec::class.java, Dsp::class.java, Tide::class.java,
            Resampler::class.java, Snip::class.java, Loudness::class.java, AuditionLevel::class.java,
            WavWriter::class.java, WavReader::class.java, SmplChunk::class.java, Pitch::class.java, Fft::class.java,
            FeatureExtractor::class.java, Classifier::class.java, Json::class.java, FacetLightStudyGenerator::class.java).forEach(::collect)
        return types.associate { type ->
            val bytes = requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class")) { "Missing class ${type.name}" }.use { it.readBytes() }
            type.name to digest(bytes)
        }
    }

    private fun writeJson(file: File, value: Any?) { file.parentFile?.mkdirs(); file.writeText(Json.write(json(value)) + "\n") }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON ${value::class}")
    }
    private fun sha256(file: File) = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun repositoryRoot() = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile } ?: File("..").canonicalFile
    private fun git(root: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)

    private val SOURCE_FILES = listOf("FacetLightStudy.kt", "Facet.kt", "FacetModel1.kt", "Dsp.kt", "Tide.kt", "Thump.kt").map { "synth/src/main/kotlin/com/snipsnap/synth/$it" } +
        listOf("FacetLightStudyGenerator.kt", "AuditionLevel.kt").map { "synth/src/test/kotlin/com/snipsnap/synth/$it" } +
        listOf("Resampler.kt", "Cleanup.kt", "Loudness.kt", "WavWriter.kt", "WavReader.kt", "SmplChunk.kt", "Pitch.kt", "Fft.kt", "Features.kt", "Classifier.kt", "CaptureProfile.kt").map { "audio/src/main/kotlin/com/snipsnap/audio/$it" } +
        "json/src/main/kotlin/com/snipsnap/json/Json.kt"

    private val R2_GOLDENS = mapOf(
        "bloom_48" to mapOf("raw" to "a74cd2ace57301b3bad6f2c7d6013463950f4be4efab2d0e89985af357a22a74", "native" to "804db85ae91fb40375037cd02d0113815681b4f12f7473c75df7ae202c3e71c5", "matched" to "ffea1a1e2cbed6cd8929305dd49e2ca18f765dda439cfe43fa01f34a0c084363"),
        "bloom_60" to mapOf("raw" to "854b0a62439e1dba7e828704fac539b4da3245381831c068520ab188ad0c3409", "native" to "70f413c532910a1283821e03b7b04acaccf7d3325ca36f3d71abc18a731f7a5e", "matched" to "9434d6372a9b39a44709d7b970cd28150a519fd7926184c5b5d619de1ba8b6aa"),
        "shimmer_48" to mapOf("raw" to "80c6311c9887c845a6de20d861de4e8a2c32b30ecc19cb11eb9a3a97e9a28cd3", "native" to "2c4d1285b9c6543021b5b31923034264ec0fb0f5a510717bb913f3d8a455d825", "matched" to "0b9ecac9bdecd0ea1af7122a1c780a8f10948888b68d4a1c9ce0ce43cca44a03"),
        "shimmer_60" to mapOf("raw" to "0c61fd2b666f82cd7e45e7f96bb2d77ee3255ffbd67b28de9cbe88cf599ceccc", "native" to "c184137b24e80eac55b862a82d81528cfdb4de956ce8f02b91d0de1a5ae629b4", "matched" to "547ac77f5021522571c2d635d8d822886975901925f2167ab5fa60cb72b11b5d"),
    )
    private const val METRIC_NOTES = "All audio is dry mono 44.1 kHz PCM24 from Kotlin DSP. R2 references use Facet model 2 with the published voice controls, velocity .8, seed 17, alignment 0 and six seconds; all four reference clips must match the published raw/native/matched hashes. Glass glints and Airy wash use a separate study renderer, not a registered FACET patch model or new default. Replay settings name the source class, study version, direction and full arguments. Raw floating-point source metrics precede melodic leveling; rawExportGain records PCM protection. Native uses normal renderer leveling; matched uses shared AuditionLevel loudest 200 ms RMS .03 with .99 peak guard. Playback amplitude metrics are measured after decoding the exported WAVs. Pitch uses the highest-confidence stock detector estimate from three 250 ms windows and may be absent, weak or drawn to an inharmonic component; no periodicity gate is applied to these directional studies. Body spectral features describe a .3-second crop starting at .4 seconds. Whole-file LOOP is a duration label, not pitch evidence. Internal routing traces and numerical measurements do not establish sonic identity or acceptance. Prior R2 audio, metadata and downloads remain unchanged."
}
