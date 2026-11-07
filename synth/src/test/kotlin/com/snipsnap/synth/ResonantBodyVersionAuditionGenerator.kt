package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.*

/** Explicit v1/v2 listening candidate; saved v1 packs remain archival and are never rewritten. */
object ResonantBodyVersionAuditionGenerator {
    private const val GENERATOR = "snipsnap-resonant-body-version-comparison"
    private const val SCHEMA = 1
    private const val SEED = 0x13579
    private data class Source(val id: String, val audio: Snip, val provenance: String)
    private data class Case(val id: String, val group: String, val source: Source, val settings: BodySettings)

    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "output directory required" }
        val root = File(args[0])
        if (args.drop(1) == listOf("--verify")) { verify(root); return }
        require(args.size == 1 || (args.size == 3 && args[1] == "--baseline")) { "use OUT [--baseline ORIGINAL_PACK] or OUT --verify" }
        generate(root, args.getOrNull(2)?.let(::File))
    }

    fun generate(root: File, baseline: File? = null) {
        require(!root.exists() || root.listFiles()?.isEmpty() == true) { "use a fresh output directory; existing files are preserved" }
        val baselineResult = baseline?.let(::verifyBaseline)
        root.mkdirs()
        listOf("sources", "raw", "matched", "recipes").forEach { File(root, it).mkdirs() }
        val sources = sources()
        // Exact source formulas from the original v1 audition, so the before/after changes only the model.
        val sourceRows = sources.map { source ->
            val path = "sources/${source.id}.wav"
            ResonantBodyAuditionGenerator.writeFloatWav(File(root, path), source.audio)
            if (baseline != null) require(sha(safeFile(baseline, path).readBytes()) == sha(File(root, path).readBytes())) {
                "recreated source differs from original pack: $path"
            }
            linkedMapOf("id" to source.id, "path" to path, "provenance" to source.provenance,
                "sha256" to sha(File(root, path).readBytes()), "pcmSha256" to pcmHash(source.audio),
                "frames" to source.audio.frameCount, "rate" to source.audio.sampleRate, "channels" to source.audio.channels)
        }
        val rows = mutableListOf<Map<String, Any?>>()
        val processor = ResonantBody()
        for (case in cases(sources)) {
            val started = System.nanoTime()
            val result = processor.process(case.source.audio, case.settings, captureWet = true)
            val elapsed = (System.nanoTime() - started) / 1e6
            val raw = "raw/${case.id}.wav"
            val wet = result.rawWet?.let { "raw/${case.id}-wet.wav" }
            val matched = "matched/${case.id}.wav"
            ResonantBodyAuditionGenerator.writeFloatWav(File(root, raw), result.audio)
            if (wet != null) ResonantBodyAuditionGenerator.writeFloatWav(File(root, wet), result.rawWet!!)
            val gain = matchedGain(result.audio)
            val matchedAudio = scaled(result.audio, gain)
            WavWriter.write(File(root, matched), matchedAudio, allowNonMpcRate = true)
            val diagnostic = diagnostics(result.diagnostics)
            val row = linkedMapOf<String, Any?>(
                "id" to case.id, "comparisonGroup" to case.group, "modelVersion" to case.settings.modelVersion,
                "coefficientVersion" to result.diagnostics.coefficientVersion, "settings" to settings(case.settings),
                "source" to "sources/${case.source.id}.wav", "sourceSha256" to sha(File(root, "sources/${case.source.id}.wav").readBytes()),
                "sourcePcmSha256" to pcmHash(case.source.audio), "rate" to case.source.audio.sampleRate, "channels" to case.source.audio.channels,
                "sourceProvenance" to case.source.provenance, "referenceHz" to case.settings.referenceHz, "rootMidi" to case.settings.rootMidi,
                "rootT60" to case.settings.rootT60, "outputFrames" to result.audio.frameCount,
                "raw" to raw, "rawSha256" to sha(File(root, raw).readBytes()), "rawPcmSha256" to pcmHash(result.audio),
                "wet" to wet, "wetSha256" to wet?.let { sha(File(root, it).readBytes()) }, "wetPcmSha256" to result.rawWet?.let(::pcmHash),
                "matched" to matched, "matchedSha256" to sha(File(root, matched).readBytes()),
                "matchedGain" to gain, "loudness" to Loudness.of(result.audio), "rms" to rms(result.audio),
                "mean" to result.audio.samples.average(), "samplePeak" to result.audio.peak(), "truePeak4x" to TruePeak.of(result.audio),
                "renderMsDesktop" to elapsed, "diagnostics" to diagnostic)
            val recipe = "recipes/${case.id}.json"
            writeJson(File(root, recipe), row)
            rows += row + ("recipe" to recipe)
        }
        val ledger = implementationFiles().associateWith { sha(File(repoRoot(), it).readBytes()) }
        writeJson(File(root, "manifest.json"), linkedMapOf(
            "generator" to GENERATOR, "schema" to SCHEMA, "baselineVersion" to ResonantBody.MODEL_VERSION,
            "candidateVersion" to ResonantBody.CANDIDATE_MODEL_VERSION, "gitCommit" to git("rev-parse", "HEAD"),
            "workingTreeDirty" to git("status", "--porcelain").isNotEmpty(), "implementationSha256" to implementationHash(),
            "implementationFiles" to ledger, "runtime" to System.getProperty("java.runtime.version"),
            "sources" to sourceRows, "cases" to rows, "originalPackCompatibility" to baselineResult,
            "matchedGainLaw" to "min(0.1/Loudness.of(raw), 0.9/rawSamplePeak), or 1 for silence; audition only",
            "heard" to false, "candidateMusicallyAccepted" to false, "phoneMeasured" to false, "captureIncluded" to false))
        val headers = listOf("id", "comparisonGroup", "modelVersion", "coefficientVersion", "samplePeak", "truePeak4x", "rms", "mean", "loudness", "matchedGain", "renderMsDesktop")
        File(root, "metrics.csv").writeText(headers.joinToString(",") + "\n" + rows.joinToString("\n") { row ->
            headers.joinToString(",") { key -> csv(row[key]) }
        } + "\n")
        desktopBenchmark(root, sources.first { it.id == "harmonic" }.audio)
        verify(root)
        println("Rendered ${rows.size} v1/v2 comparison cases into ${root.absolutePath}; candidate listening and Nothing Phone (3) measurements pending")
    }

    /** Replays this new pack. The original generator's stricter implementation-hash gate is unchanged. */
    fun verify(root: File) {
        val manifest = readJson(safeFile(root, "manifest.json"))
        require(manifest.getValue("generator").str() == GENERATOR && manifest.getValue("schema").int() == SCHEMA) { "unsupported comparison manifest" }
        require(manifest["baselineVersion"]?.int() == ResonantBody.MODEL_VERSION &&
            manifest["candidateVersion"]?.int() == ResonantBody.CANDIDATE_MODEL_VERSION) { "unsupported or missing comparison model versions" }
        require(manifest.getValue("implementationSha256").str() == implementationHash()) { "comparison implementation changed" }
        val sources = manifest.getValue("sources").arr()
        require(sources.isNotEmpty() && sources.map { it.obj().getValue("id").str() }.distinct().size == sources.size &&
            sources.map { it.obj().getValue("path").str() }.distinct().size == sources.size) { "empty or duplicate comparison sources" }
        val sourceHashes = sources.associate { value ->
            val row = value.obj()
            val path = row.getValue("path").str()
            val file = safeFile(root, path)
            require(sha(file.readBytes()) == row.getValue("sha256").str()) { "comparison source file changed: $path" }
            require(pcmHash(WavReader.read(file)) == row.getValue("pcmSha256").str()) { "comparison source PCM changed: $path" }
            path to row.getValue("sha256").str()
        }
        val entries = manifest.getValue("cases").arr()
        require(entries.isNotEmpty() && entries.map { it.obj().getValue("id").str() }.distinct().size == entries.size) { "empty or duplicate comparison cases" }
        require(entries.map { it.obj().getValue("modelVersion").int() }.toSet() ==
            setOf(ResonantBody.MODEL_VERSION, ResonantBody.CANDIDATE_MODEL_VERSION)) { "comparison must contain both supported model versions" }
        val processor = ResonantBody()
        for (value in entries) {
            val row = value.obj()
            val sourcePath = row.getValue("source").str()
            require(sourceHashes[sourcePath] == row.getValue("sourceSha256").str()) { "unrecorded comparison source" }
            val source = WavReader.read(safeFile(root, sourcePath))
            require(source.sampleRate == row.getValue("rate").int() && source.channels == row.getValue("channels").int()) { "comparison source format changed" }
            require(pcmHash(source) == row.getValue("sourcePcmSha256").str()) { "comparison source PCM differs" }
            val s = readSettings(row.getValue("settings").obj(), row.getValue("modelVersion").int())
            val result = processor.process(source, s, captureWet = true)
            require(result.diagnostics.modelVersion == row.getValue("modelVersion").int() && result.diagnostics.coefficientVersion == row.getValue("coefficientVersion").int()) { "comparison version differs" }
            require(result.diagnostics.tableIdentity == row.getValue("diagnostics").obj().getValue("tableIdentity").str()) { "comparison table differs" }
            require(result.audio.frameCount == row.getValue("outputFrames").int()) { "comparison frame count differs" }
            verifyAudio(root, row, "raw", "rawSha256", "rawPcmSha256", result.audio)
            if (row.getValue("wet") != JsonValue.Null) verifyAudio(root, row, "wet", "wetSha256", "wetPcmSha256", requireNotNull(result.rawWet))
            val gain = matchedGain(result.audio)
            require(gain == row.getValue("matchedGain").num()) { "comparison matching gain differs" }
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, scaled(result.audio, gain)) }.toByteArray()
            val matched = safeFile(root, row.getValue("matched").str())
            require(sha(matched.readBytes()) == row.getValue("matchedSha256").str() && sha(bytes) == row.getValue("matchedSha256").str()) { "comparison matched file differs" }
            val recipe = readJson(safeFile(root, row.getValue("recipe").str()))
            require(recipe == row - "recipe") { "comparison recipe differs from manifest" }
        }
        println("Verified ${entries.size} comparison PCM replays, matching gains and saved-file hashes")
    }

    /** Check v1 PCM compatibility against an archival pack, without claiming its old source hash is current. */
    private fun verifyBaseline(root: File): Map<String, Any?> {
        val manifest = readJson(safeFile(root, "manifest.json"))
        require(manifest.getValue("generator").str() == "snipsnap-resonant-body-r1" && manifest.getValue("schema").int() == 1 && manifest.getValue("modelVersion").int() == 1) { "baseline must be the original v1 pack" }
        val entries = manifest.getValue("cases").arr()
        require(entries.isNotEmpty() && entries.map { it.obj().getValue("id").str() }.distinct().size == entries.size) { "empty or duplicate baseline cases cannot establish compatibility" }
        val processor = ResonantBody()
        for (value in entries) {
            val entry = value.obj()
            val row = readJson(safeFile(root, entry.getValue("recipe").str()))
            require(row.getValue("schema").int() == 1 && row.getValue("modelVersion").int() == 1 && row.getValue("coefficientVersion").int() == 1) { "unsupported baseline recipe" }
            require(entry.getValue("source") == row.getValue("source") && entry.getValue("raw") == row.getValue("output") && entry.getValue("matched") == row.getValue("matched")) { "baseline manifest and recipe paths differ" }
            require(entry.getValue("wet") == row.getValue("wet")) { "baseline wet path differs" }
            val sourceFile = safeFile(root, row.getValue("source").str())
            require(sha(sourceFile.readBytes()) == row.getValue("sourceSha256").str()) { "baseline source file changed" }
            val source = WavReader.read(sourceFile)
            require(pcmHash(source) == row.getValue("sourcePcmSha256").str()) { "baseline source PCM changed" }
            require(source.sampleRate == row.getValue("rate").int() && source.channels == row.getValue("channels").int()) { "baseline source format changed" }
            val result = processor.process(source, readSettings(row.getValue("settings").obj(), 1), captureWet = true)
            require(result.diagnostics.modelVersion == 1 && result.diagnostics.coefficientVersion == 1) { "v1 selected another algorithm" }
            require(result.diagnostics.tableIdentity == row.getValue("tableIdentity").str()) { "v1 table identity changed" }
            require(result.audio.frameCount == row.getValue("outputFrames").int()) { "v1 output frame count changed" }
            verifyAudio(root, row, "output", "outputSha256", "outputPcmSha256", result.audio)
            if (row.getValue("wet") != JsonValue.Null) {
                val wetFile = safeFile(root, row.getValue("wet").str())
                val wet = requireNotNull(result.rawWet)
                require(pcmHash(wet) == row.getValue("wetPcmSha256").str() && pcmHash(WavReader.read(wetFile)) == pcmHash(wet)) { "v1 wet PCM changed" }
                // The original recipe lacks a wet-file hash. Reuse its writer in a scratch file to check the complete archived bytes too.
                require(sha(wetFile.readBytes()) == floatFileHash(wet)) { "v1 wet WAV bytes changed" }
            }
            val matched = safeFile(root, row.getValue("matched").str())
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, scaled(result.audio, matchedGain(result.audio))) }.toByteArray()
            require(sha(matched.readBytes()) == row.getValue("matchedSha256").str() && sha(bytes) == row.getValue("matchedSha256").str()) { "v1 matched output changed" }
        }
        val wetFileHashes = entries.mapNotNull { value -> value.obj().getValue("wet").takeIf { it != JsonValue.Null }?.str() }
            .associateWith { sha(safeFile(root, it).readBytes()) }
        println("Verified ${entries.size} original v1 source/output/wet PCM replays and matched-file hashes")
        return linkedMapOf("cases" to entries.size, "passed" to true, "exactSourceAndOutputPcm" to true,
            "originalImplementationSha256" to manifest.getValue("implementationSha256").str(),
            "originalManifestSha256" to sha(safeFile(root, "manifest.json").readBytes()), "archivedWetFileSha256" to wetFileHashes,
            "method" to "explicit modelVersion 1 replay against recorded source/output/wet PCM, source/output/matched file hashes, and canonical original-writer wet-file bytes; archival implementation hash is not rewritten")
    }

    private fun sources(): List<Source> {
        val rate = WavWriter.MPC_SAMPLE_RATE
        val reference = BodySettings(modelVersion = 1).referenceHz
        val impulse = Source("impulse", Snip(FloatArray(rate / 4).also { it[0] = 0.8f }, 1, rate), "generated: impulse 0.8; 250 ms buffer; original v1 formula")
        var state = SEED
        val burst = Source("burst", Snip(FloatArray(rate / 4) { frame ->
            state = state * 1664525 + 1013904223
            val x = (state ushr 8) / 8388608.0 - 1
            if (frame < 882) (x * 0.5 * sin(PI * frame / 881).pow(2)).toFloat() else 0f
        }, 1, rate), "generated: LCG seed $SEED; 20 ms sine-squared noise envelope; scale 0.5; original v1 formula")
        val harmonic = Source("harmonic", Snip(FloatArray(rate) { n ->
            (0.2 * (1..4).sumOf { h -> sin(2 * PI * reference * h * n / rate) / h } / (1..4).sumOf { 1.0 / it }).toFloat()
        }, 1, rate), "generated: four C3 harmonics, normalized 1/n weights, scale 0.2; one second; original v1 formula")
        return listOf(burst, harmonic, impulse)
    }

    private fun cases(sources: List<Source>): List<Case> {
        val byId = sources.associateBy { it.id }
        val rows = mutableListOf<Case>()
        fun add(group: String, sourceId: String, bodies: List<Float>, tune: Float = 1f / 3f, ring: Float = 0.5f, damp: Float = 0.5f) {
            for (version in listOf(ResonantBody.MODEL_VERSION, ResonantBody.CANDIDATE_MODEL_VERSION)) for (body in bodies) {
                val label = when (body) { 0f -> "harmonic"; 1f -> "bar"; else -> "blend" }
                rows += Case("$group-v$version-$label", group, byId.getValue(sourceId), BodySettings(tune = tune, body = body, ring = ring, damp = damp, mix = 1f, modelVersion = version))
            }
        }
        add("default-burst", "burst", listOf(0f, 0.5f, 1f))
        add("default-sustained", "harmonic", listOf(0f, 0.5f, 1f))
        add("c3-impulse", "impulse", listOf(0f, 1f), ring = 1f)
        add("long-ring-undamped", "burst", listOf(0f, 1f), ring = 1f, damp = 0f)
        add("c5-impulse", "impulse", listOf(0f, 1f), tune = 1f, ring = 1f)
        return rows
    }

    private fun desktopBenchmark(root: File, source: Snip) {
        val versions = listOf(ResonantBody.MODEL_VERSION, ResonantBody.CANDIDATE_MODEL_VERSION)
        val processor = ResonantBody()
        val settingsByVersion = versions.associateWith { BodySettings(body = 0.5f, ring = 1f, mix = 0.5f, modelVersion = it) }
        var checksum = 0.0
        repeat(20) { for (version in versions) checksum += processor.process(source, settingsByVersion.getValue(version)).audio.samples[1000] }
        val warm = versions.associateWith { mutableListOf<Double>() }
        val cold = versions.associateWith { mutableListOf<Double>() }
        fun timed(version: Int, cleared: Boolean): Double {
            if (cleared) processor.clearCoefficientCache()
            val start = System.nanoTime()
            val out = processor.process(source, settingsByVersion.getValue(version))
            val elapsed = (System.nanoTime() - start) / 1e6
            checksum += out.audio.samples[1000]
            return elapsed
        }
        repeat(100) { n -> for (version in if (n % 2 == 0) versions else versions.reversed()) warm.getValue(version) += timed(version, false) }
        repeat(20) { n -> for (version in if (n % 2 == 0) versions else versions.reversed()) cold.getValue(version) += timed(version, true) }
        fun summary(samples: List<Double>): Map<String, Any> {
            val sorted = samples.sorted()
            return linkedMapOf("samplesMs" to samples, "p50Ms" to sorted[ceil(sorted.size * 0.5).toInt() - 1], "p95Ms" to sorted[ceil(sorted.size * 0.95).toInt() - 1], "maxMs" to sorted.last())
        }
        writeJson(File(root, "benchmark-desktop.json"), linkedMapOf(
            "platform" to "desktop JVM, not Nothing Phone (3)", "runtime" to System.getProperty("java.runtime.version"), "os" to System.getProperty("os.name"),
            "source" to "sources/harmonic.wav", "rate" to source.sampleRate, "sourceFrames" to source.frameCount, "channels" to source.channels,
            "workload" to "same one-second C3 harmonic input, BODY .5 RING 1 DAMP .5 MIX .5, complete synchronous process including tail and guard",
            "order" to "20 warmups per version; 100 alternating-order warm samples and 20 separately cleared-coefficient-cache samples per version",
            "versions" to versions.associate { version ->
                val example = processor.process(source, settingsByVersion.getValue(version))
                version.toString() to linkedMapOf("settings" to settings(settingsByVersion.getValue(version)),
                    "outputFrames" to example.audio.frameCount, "activeModes" to example.diagnostics.activeModes,
                    "warm" to summary(warm.getValue(version)), "clearedCoefficientCache" to summary(cold.getValue(version)))
            },
            "checksum" to checksum, "phoneGatePassed" to false))
    }

    private fun verifyAudio(root: File, row: Map<String, JsonValue>, pathKey: String, fileHashKey: String, pcmHashKey: String, expected: Snip) {
        val file = safeFile(root, row.getValue(pathKey).str())
        require(sha(file.readBytes()) == row.getValue(fileHashKey).str()) { "audio file changed: ${file.name}" }
        require(pcmHash(expected) == row.getValue(pcmHashKey).str() && pcmHash(WavReader.read(file)) == pcmHash(expected)) { "audio PCM replay differs: ${file.name}" }
    }
    private fun floatFileHash(source: Snip): String {
        val scratch = File.createTempFile("resonant-body-replay-", ".wav")
        return try {
            ResonantBodyAuditionGenerator.writeFloatWav(scratch, source)
            sha(scratch.readBytes())
        } finally { check(scratch.delete()) { "could not remove replay scratch WAV" } }
    }
    private fun diagnostics(d: BodyDiagnostics) = linkedMapOf(
        "modelVersion" to d.modelVersion, "coefficientVersion" to d.coefficientVersion, "tableIdentity" to d.tableIdentity,
        "rootMidi" to d.rootMidi, "referenceHz" to d.referenceHz, "rootT60" to d.rootT60, "tailFrames" to d.tailFrames,
        "activeModes" to d.activeModes, "cacheHit" to d.coefficientCacheHit, "inputPeak" to if (d.activeModes == 0) null else d.inputPeak,
        "statePeak" to d.statePeak, "wetPeak" to d.wetPeak, "mixedPeak" to d.mixedPeak, "attenuation" to d.attenuation)
    private fun settings(s: BodySettings) = linkedMapOf("modelVersion" to s.modelVersion, "tune" to s.tune, "body" to s.body,
        "ring" to s.ring, "damp" to s.damp, "mix" to s.mix, "from" to s.from.name, "to" to s.to.name)
    private fun readSettings(row: Map<String, JsonValue>, version: Int): BodySettings {
        row["modelVersion"]?.let { require(it.int() == version) { "settings model differs" } }
        return BodySettings(row.getValue("tune").num().toFloat(), row.getValue("body").num().toFloat(), row.getValue("ring").num().toFloat(),
            row.getValue("damp").num().toFloat(), row.getValue("mix").num().toFloat(), BodyFamily.valueOf(row.getValue("from").str()), BodyFamily.valueOf(row.getValue("to").str()), version)
    }
    private fun matchedGain(s: Snip): Double {
        val level = Loudness.of(s).toDouble()
        return if (level > 1e-12 && s.peak() > 0) min(0.1 / level, 0.9 / s.peak()) else 1.0
    }
    private fun scaled(s: Snip, gain: Double) = s.copy(samples = s.samples.map { (it * gain).toFloat() }.toFloatArray())
    private fun rms(s: Snip) = sqrt(s.samples.sumOf { it.toDouble().pow(2) } / s.samples.size)
    private fun safeFile(root: File, relative: String): File {
        val file = File(root, relative).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) { "missing or escaping audition file: $relative" }
        return file
    }
    private fun implementationFiles() = listOf("audio/src/main/kotlin/com/snipsnap/audio/ResonantBody.kt", "audio/src/main/kotlin/com/snipsnap/audio/ResonantBodyModels.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/ResonantBodyVersionAuditionGenerator.kt", "synth/src/test/kotlin/com/snipsnap/synth/ResonantBodyAuditionGenerator.kt")
    private fun implementationHash() = sha(implementationFiles().joinToString("\n") { "$it:${sha(File(repoRoot(), it).readBytes())}" }.toByteArray())
    private fun repoRoot() = if (File("audio/src/main").exists()) File(".") else File("..")
    private fun git(vararg args: String): String = ProcessBuilder(listOf("git") + args).directory(repoRoot()).redirectErrorStream(true)
        .start().let { p -> val output = p.inputStream.bufferedReader().readText().trim(); check(p.waitFor() == 0); output }
    private fun pcmHash(s: Snip): String {
        val bytes = ByteBuffer.allocate(8 + s.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(s.sampleRate).putInt(s.channels)
        s.samples.forEach { bytes.putInt(it.toRawBits()) }
        return sha(bytes.array())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun readJson(file: File) = Json.parse(file.readText()).obj()
    private fun csv(v: Any?) = "\"${v?.toString()?.replace("\"", "\"\"") ?: ""}\""
    private fun writeJson(file: File, value: Any?) = file.writeText(Json.write(json(value)) + "\n")
    private fun json(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Number -> JsonValue.Num(v.toDouble())
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::json))
        else -> error("unsupported JSON type ${v.javaClass}")
    }
}
