package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ceil

/**
 * Manual render-only benchmark. No timing assertion, WAV export or listening judgement belongs here.
 *
 * Run `:synth:benchmarkFilament` or call this main directly with the baseline/new engine jars:
 * `--path=production|diagnostics|both --warmups=2 --samples=5 [--output=report.json] [--label=before]`.
 * The same main uses only established patch/render APIs so frozen baseline jars can be measured
 * without recompiling their DSP. Loaded code origins and jar hashes identify that distinction;
 * the checkout revision alone does not identify a separately loaded baseline engine.
 */
object FilamentPerformanceProbe {
    @Volatile private var retainedSamples = FloatArray(0)
    private val names = listOf("Glass Thread", "Four Routes", "Held Thread", "Held Procession")

    @JvmStatic
    fun main(args: Array<String>) {
        val options = options(args)
        val patches = names.map { name -> FilamentPresets.all().single { it.name == name } }
        require(patches.all { it.model == 3 }) { "this benchmark retains the approved model-three recipes" }
        val started = System.nanoTime()
        val rows = ArrayList<Map<String, Any?>>()
        var renderCount = 0
        for (patch in patches) for (path in options.paths) {
            repeat(options.warmups) {
                retainedSamples = render(patch, path)
                renderCount++
            }
            val elapsed = LongArray(options.samples)
            var frames = 0
            for (sample in elapsed.indices) {
                val began = System.nanoTime()
                val rendered = render(patch, path)
                elapsed[sample] = System.nanoTime() - began
                // Retention and validation happen after the timer. Encoding, checksums,
                // progress logging and JSON/file I/O never contribute to render timings.
                retainedSamples = rendered
                renderCount++
                require(rendered.isNotEmpty() && rendered.all(Float::isFinite)) { "${patch.name} produced invalid audio" }
                if (sample != 0) require(rendered.size == frames) { "${patch.name} changed frame count across repetitions" }
                frames = rendered.size
            }
            val sorted = elapsed.sorted()
            val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 0) (sorted[middle - 1].toDouble() + sorted[middle]) * .5 else sorted[middle].toDouble()
            rows += linkedMapOf(
                "preset" to patch.name,
                "path" to path,
                "patch" to patch.toJsonValue(),
                "midi" to patch.midi,
                "velocity" to patch.velocity,
                "frames" to frames,
                "durationSeconds" to frames.toDouble() / Dsp.RATE,
                "warmupRenders" to options.warmups,
                "timedRenders" to options.samples,
                "renderCount" to options.warmups + options.samples,
                "minMs" to sorted.first() / 1e6,
                "medianMs" to median / 1e6,
                "p90Ms" to sorted[ceil(.90 * sorted.size).toInt() - 1] / 1e6,
                "maxMs" to sorted.last() / 1e6,
                "samplesMs" to elapsed.map { it / 1e6 },
                "nativeFloat32Sha256" to floatHash(retainedSamples),
            )
            System.err.println("FILAMENT benchmark complete: ${patch.name} ($path), ${options.samples} timed renders")
        }
        val report = linkedMapOf(
            "schemaVersion" to 1,
            "engine" to FilamentPatch.ENGINE,
            "model" to 3,
            "label" to options.label,
            "generatedAt" to Instant.now().toString(),
            "checkoutRevision" to git("rev-parse", "HEAD"),
            "clock" to "System.nanoTime",
            "timedScope" to "render call only; excludes validation, hashes, encoding, file I/O and logging",
            "cache" to "none; every timed sample renders the complete recipe",
            "percentileConvention" to "p90 uses nearest rank; even-sample median averages the middle two",
            "floatHashEncoding" to "Float.toRawBits, little-endian four bytes per sample",
            "runtime" to linkedMapOf(
                "javaVersion" to System.getProperty("java.version"),
                "javaVendor" to System.getProperty("java.vendor"),
                "vm" to System.getProperty("java.vm.name"),
                "os" to System.getProperty("os.name"),
                "architecture" to System.getProperty("os.arch"),
                "processors" to Runtime.getRuntime().availableProcessors(),
                "cpuModel" to cpuModel(),
                "heapMaximumBytes" to Runtime.getRuntime().maxMemory(),
                "heapVmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments.filter {
                    it.startsWith("-Xm") || it.startsWith("-XX:ActiveProcessorCount=") || it.startsWith("-XX:MaxRAMPercentage=")
                },
                "loadedEngine" to codeOrigin(Filament::class.java),
                "loadedAudio" to codeOrigin(com.snipsnap.audio.Snip::class.java),
                "codeOriginNote" to "jar SHA identifies separately loaded baseline code; a classes directory has no jar hash",
            ),
            "warmupsPerCase" to options.warmups,
            "timedSamplesPerCase" to options.samples,
            "caseCount" to rows.size,
            "renderCount" to renderCount,
            "benchmarkWallMs" to (System.nanoTime() - started) / 1e6,
            "cases" to rows,
        )
        val text = Json.write(json(report)) + "\n"
        options.output?.let { file -> file.parentFile?.mkdirs(); file.writeText(text) }
        print(text)
    }

    private data class Options(val paths: List<String>, val warmups: Int, val samples: Int,
                               val output: File?, val label: String?)

    private fun options(args: Array<String>): Options {
        val known = setOf("path", "warmups", "samples", "output", "label")
        val values = LinkedHashMap<String, String>()
        for (arg in args) {
            require(arg.startsWith("--") && '=' in arg) { "expected --option=value, got $arg" }
            val (key, value) = arg.removePrefix("--").split('=', limit = 2)
            require(key in known && value.isNotEmpty() && key !in values) { "unknown, empty or repeated option $arg" }
            values[key] = value
        }
        val path = values["path"] ?: "production"
        require(path in setOf("production", "diagnostics", "both")) { "path must be production, diagnostics or both" }
        val warmups = (values["warmups"] ?: "2").toInt()
        val samples = (values["samples"] ?: "5").toInt()
        require(warmups in 0..1_000 && samples in 1..1_000) { "warmups must be 0..1000 and samples 1..1000" }
        val output = values["output"]?.let(::File)
        require(output?.exists() != true) { "refusing to overwrite benchmark evidence: $output" }
        return Options(if (path == "both") listOf("production", "diagnostics") else listOf(path),
            warmups, samples, output, values["label"])
    }

    private fun render(patch: FilamentPatch, path: String): FloatArray = if (path == "production") patch.render().samples else
        Filament.inspect(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, model = patch.model).samples

    private fun codeOrigin(type: Class<*>): Map<String, Any?> {
        val origin = type.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
        return linkedMapOf("path" to origin?.absolutePath,
            "jarSha256" to origin?.takeIf { it.isFile }?.readBytes()?.let { bytes -> hash(bytes) })
    }

    private fun floatHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(16_384)
        var used = 0
        for (sample in samples) {
            val bits = sample.toRawBits()
            for (byte in 0..3) bytes[used++] = (bits ushr (byte * 8)).toByte()
            if (used == bytes.size) { digest.update(bytes); used = 0 }
        }
        digest.update(bytes, 0, used)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun git(vararg args: String): String? = runCatching {
        ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start().let { process ->
            val text = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) text else null
        }
    }.getOrNull()

    private fun cpuModel(): String? = runCatching {
        File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("model name") || it.startsWith("Hardware") }
                ?.substringAfter(':')?.trim()
        }
    }.getOrNull()

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported benchmark JSON value ${value.javaClass.name}")
    }
}
