package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Isolated port evidence: actual stored-source PCM -> actual Kotlin core -> unscaled Float32.
 * Timing covers the public render call; fixture validation and file I/O stay outside the clock.
 */
object KotlinPortRenderer {
    private data class Input(val id: String, val snip: Snip, val original: FloatArray, val record: Map<String, Any?>)
    private data class Case(val id: String, val sourceId: String, val settings: ObliterateCore.Settings, val stages: Int)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5) { "KotlinPortRenderer <repo> <reference-inputs.json> <output> <warmups> <trials>" }
        val repo = File(args[0]).canonicalFile
        val manifestFile = File(args[1]).canonicalFile
        val output = File(args[2]).canonicalFile.apply { mkdirs() }
        val warmups = args[3].toInt().also { require(it in 0..50) }
        val trials = args[4].toInt().also { require(it in 0..100) }
        val sourceCommit = git(repo, "rev-parse", "HEAD")
        val sourceTree = git(repo, "rev-parse", "HEAD^{tree}")
        val dirty = git(repo, "status", "--porcelain", "--untracked-files=normal").isNotEmpty()
        val sourceHashes = sourceHashes(repo)
        val inputManifestHash = sha(manifestFile)
        val manifest = Json.parse(manifestFile.readText()).obj()
        require(manifest.getValue("schema").int() == 1)
        require(manifest.getValue("sampleRate").int() == ObliterateCore.RATE)
        val inputs = linkedMapOf<String, Input>()
        for (item in manifest.getValue("sources").arr()) {
            val data = item.obj()
            val id = id(data.getValue("id").str())
            require(id !in inputs) { "Duplicate source $id" }
            val raw = resolve(manifestFile.parentFile, data.getValue("inputFile").str())
            val wav = resolve(manifestFile.parentFile, data.getValue("sourceWavFile").str())
            check(sha(raw) == data.getValue("inputSha256").str()) { "Input Float32 hash changed: $id" }
            check(sha(wav) == data.getValue("sourceWavSha256").str()) { "Stored WAV hash changed: $id" }
            val channels = data.getValue("inputChannels").int()
            val frames = data.getValue("inputFrames").int()
            val samples = readFloat32(raw)
            check(samples.size == frames * channels && samples.all { it.isFinite() })
            val decoded = WavReader.read(wav)
            check(decoded.sampleRate == ObliterateCore.RATE && decoded.channels == channels && decoded.frameCount == frames)
            val difference = samples.indices.firstOrNull { samples[it].toRawBits() != decoded.samples[it].toRawBits() }
            check(difference == null) { "Reference input differs from actual WavReader at $id sample $difference" }
            val decodedHash = sha(float32Bytes(decoded.samples))
            check(decodedHash == data.getValue("inputSha256").str())
            inputs[id] = Input(id, decoded, decoded.samples.copyOf(), linkedMapOf(
                "id" to id, "inputFile" to data.getValue("inputFile").str(),
                "inputSha256" to sha(raw), "inputChannels" to channels, "inputFrames" to frames,
                "sourceWavFile" to data.getValue("sourceWavFile").str(), "sourceWavSha256" to sha(wav),
                "actualWavReaderFloat32BitEquality" to true, "actualWavReaderFloat32Sha256" to decodedHash,
            ))
        }
        val cases = manifest.getValue("cases").arr().map { value ->
            val data = value.obj()
            Case(id(data.getValue("id").str()), data.getValue("sourceId").str(), ObliterateCore.Settings(
                data.getValue("amount").num(), data.getValue("bpm").num(), data.getValue("division").int(),
                data.getValue("seed").let { if (it is JsonValue.Str) it.value.toLong() else it.long() },
            ), data.getValue("stages").int())
        }
        require(cases.isNotEmpty() && cases.map { it.id }.distinct().size == cases.size)
        val records = mutableListOf<Map<String, Any?>>()
        val reference = linkedMapOf<String, Snip>()
        for (case in cases) {
            val input = inputs.getValue(case.sourceId)
            val result = ObliterateCore.render(input.snip, case.settings, case.stages)
            validate(result, input, case)
            val repeated = ObliterateCore.render(input.snip, case.settings, case.stages)
            check(result.audio.samples.contentEquals(repeated.audio.samples)) { "Non-deterministic Float32: ${case.id}" }
            check(result.diagnostics == repeated.diagnostics) { "Non-deterministic diagnostics: ${case.id}" }
            check(input.original.contentEquals(input.snip.samples)) { "Core mutated source ${input.id}" }
            val raw = File(output, "kotlin-f32/${case.id}.f32")
            writeFloat32(raw, result.audio.samples)
            val diag = diagnostics(result.diagnostics)
            val diagnosticFile = File(output, "kotlin-diagnostics/${case.id}.json")
            writeJson(diagnosticFile, diag)
            records += caseFields(case) + linkedMapOf(
                "outputFile" to raw.relativeTo(output).invariantSeparatorsPath, "outputSha256" to sha(raw),
                "frames" to result.audio.frameCount, "channels" to result.audio.channels,
                "sampleRate" to result.audio.sampleRate,
                "diagnosticsFile" to diagnosticFile.relativeTo(output).invariantSeparatorsPath,
                "diagnostics" to diag, "deterministicRerender" to true,
            )
            if (case.stages == 3) reference[case.id] = result.audio
            println("Kotlin port: ${case.id}, ${result.audio.frameCount} frames/${result.audio.channels}ch, ${result.diagnostics.grainEvents.size} grains")
        }
        val timings = mutableListOf<Map<String, Any?>>()
        if (trials > 0) for (case in cases.filter { it.stages == 3 }) {
            val input = inputs.getValue(case.sourceId)
            val expected = reference.getValue(case.id)
            repeat(warmups) {
                val result = ObliterateCore.render(input.snip, case.settings, case.stages)
                check(expected.samples.contentEquals(result.audio.samples))
            }
            val milliseconds = List(trials) {
                val started = System.nanoTime()
                val result = ObliterateCore.render(input.snip, case.settings, case.stages)
                val elapsed = (System.nanoTime() - started) / 1_000_000.0
                check(expected.samples.contentEquals(result.audio.samples))
                check(input.original.contentEquals(input.snip.samples))
                elapsed
            }
            val sorted = milliseconds.sorted()
            val median = if (trials % 2 == 1) sorted[trials / 2] else (sorted[trials / 2 - 1] + sorted[trials / 2]) / 2.0
            timings += caseFields(case) + linkedMapOf(
                "inputFrames" to input.snip.frameCount, "inputChannels" to input.snip.channels,
                "warmups" to warmups, "trials" to trials, "milliseconds" to milliseconds,
                "minMs" to sorted.first(), "medianMs" to median, "meanMs" to milliseconds.average(),
                "p95Ms" to sorted[(ceil(trials * .95).toInt() - 1).coerceIn(sorted.indices)], "maxMs" to sorted.last(),
            )
            println("Kotlin benchmark: ${case.id}, median ${"%.3f".format(java.util.Locale.ROOT, median)} ms ($trials trials)")
        }
        check(sourceHashes == sourceHashes(repo)) { "A port source changed during rendering" }
        check(inputManifestHash == sha(manifestFile)) { "Reference manifest changed during rendering" }
        check(sourceCommit == git(repo, "rev-parse", "HEAD") && sourceTree == git(repo, "rev-parse", "HEAD^{tree}"))
        val result = linkedMapOf<String, Any?>(
            "schema" to 1, "model" to ObliterateCore.MODEL, "referenceModel" to ObliterateCore.REFERENCE_MODEL,
            "sourceCommit" to sourceCommit, "sourceTree" to sourceTree, "workingTreeDirty" to dirty,
            "sourceHashes" to sourceHashes,
            "sourceHashScope" to "Kotlin port/PCG source, actual WAV decoder/Snip boundary, canonical writer rate, retained renderer and isolated init script",
            "inputManifestSha256" to inputManifestHash,
            "encoding" to "IEEE754 Float32 little-endian interleaved", "sampleRate" to ObliterateCore.RATE,
            "levelPolicy" to "Actual public Kotlin render output; no audition gain, normalization, resampling, padding or trimming in this renderer",
            "sources" to inputs.values.map { it.record }, "cases" to records,
            "benchmark" to linkedMapOf(
                "meaning" to "Warm host JVM public-render wall time; not Android or phone performance",
                "loadPolicy" to "Other host load and garbage collection are uncontrolled; no forced GC or I/O inside the timed call",
                "javaVersion" to System.getProperty("java.version"), "javaVendor" to System.getProperty("java.vendor"),
                "osName" to System.getProperty("os.name"), "osArch" to System.getProperty("os.arch"),
                "availableProcessors" to Runtime.getRuntime().availableProcessors(),
                "jvmMaxHeapBytes" to Runtime.getRuntime().maxMemory(), "cases" to timings,
            ),
        )
        writeJson(File(output, "kotlin-port-results.json"), result)
        println("Wrote ${records.size} unscaled Kotlin Float32 renders and ${timings.size} warm benchmark cases to $output")
    }

    private fun validate(result: ObliterateCore.RenderResult, input: Input, case: Case) {
        val audio = result.audio
        check(audio.sampleRate == ObliterateCore.RATE && audio.samples.all { it.isFinite() })
        check(audio.frameCount == input.snip.frameCount + if (case.settings.amount == 0.0) 0 else ObliterateCore.TAIL_FRAMES)
        check(audio.channels == if (case.settings.amount == 0.0) input.snip.channels else 2)
        check(result.diagnostics.outputFrames == audio.frameCount && result.diagnostics.outputChannels == audio.channels)
    }
    private fun caseFields(case: Case): Map<String, Any?> = linkedMapOf(
        "id" to case.id, "sourceId" to case.sourceId, "amount" to case.settings.amount,
        "bpm" to case.settings.bpm, "division" to case.settings.division, "seed" to case.settings.seed, "stages" to case.stages,
    )
    private fun settings(value: ObliterateCore.Settings): Map<String, Any?> = linkedMapOf(
        "amount" to value.amount, "bpm" to value.bpm, "division" to value.division, "seed" to value.seed,
    )
    private fun metrics(value: ObliterateCore.Metrics): Map<String, Any?> = linkedMapOf(
        "peak" to value.peak, "rms" to value.rms, "channelMean" to value.channelMean,
    )
    private fun diagnostics(value: ObliterateCore.Diagnostics): Map<String, Any?> = linkedMapOf(
        "model" to value.model, "referenceModel" to value.referenceModel, "settings" to settings(value.settings),
        "stages" to value.stages, "inputFrames" to value.inputFrames, "inputChannels" to value.inputChannels,
        "outputFrames" to value.outputFrames, "outputChannels" to value.outputChannels, "tailFrames" to value.tailFrames,
        "bypass" to value.bypass, "guardGain" to value.guardGain, "preGuardPeak" to value.preGuardPeak,
        "grainEvents" to value.grainEvents.map { event -> linkedMapOf(
            "onsetFrame" to event.onsetFrame, "lengthFrames" to event.lengthFrames,
            "sourceFirstFrame" to event.sourceFirstFrame, "sourceLastFrame" to event.sourceLastFrame,
            "pitchRatio" to event.pitchRatio, "semitones" to event.semitones, "reverse" to event.reverse, "balance" to event.balance,
        ) },
        "inputMetrics" to metrics(value.inputMetrics), "outputMetrics" to metrics(value.outputMetrics),
        "stageMetrics" to value.stageMetrics.map { linkedMapOf("name" to it.name, "metrics" to metrics(it.metrics)) },
        "clockStepSeconds" to value.clockStepSeconds, "clockOriginFrame" to value.clockOriginFrame,
        "compensatedFirLatencyFrames" to value.compensatedFirLatencyFrames,
        "streamingCausal" to value.streamingCausal, "grainReadsBeforeEventOnset" to value.grainReadsBeforeEventOnset,
        "shimmer" to value.shimmer, "feedback" to value.feedback, "seamlessLoopClaim" to value.seamlessLoopClaim,
    )
    private fun id(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) }
    private fun resolve(root: File, path: String): File = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
    private fun readFloat32(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size % 4 == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { Float.fromBits(buffer.int) }
    }
    private fun writeFloat32(file: File, samples: FloatArray) {
        file.parentFile.mkdirs()
        file.writeBytes(float32Bytes(samples))
    }
    private fun float32Bytes(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) buffer.putInt(sample.toRawBits())
        return buffer.array()
    }
    private fun sourceHashes(repo: File): Map<String, String> {
        val core = File(repo, "synth/src/main/kotlin/com/snipsnap/synth").listFiles()!!.filter {
            it.isFile && it.extension == "kt" && it.name.startsWith("Obliterate")
        }
        val files = core + listOf("KotlinPortRenderer.kt", "port.init.gradle").map { File(repo, "experiments/obliterate-core/$it") } +
            listOf("WavReader.kt", "Cleanup.kt", "WavWriter.kt").map { File(repo, "audio/src/main/kotlin/com/snipsnap/audio/$it") }
        return files.sortedBy { it.relativeTo(repo).invariantSeparatorsPath }.associate { it.relativeTo(repo).invariantSeparatorsPath to sha(it) }
    }
    private fun sha(file: File): String = sha(file.readBytes())
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { output }
        return output
    }
    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)) + "\n")
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { check(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Cannot write ${value.javaClass}")
    }
}
