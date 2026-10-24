package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.security.MessageDigest
import java.time.Instant
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlin.math.ceil

/**
 * Manual, render-only TIDAL measurements. Timing is evidence, never a unit-test threshold.
 *
 * The same compiled main can load frozen baseline engine classes. Optional new cache/profile
 * hooks are reflected so measuring old code does not require recompiling that DSP. Record
 * loaded class hashes, not just the checkout revision, when comparing those runs.
 */
object TidalRenderBenchmark {
    @Volatile private var retained = FloatArray(0)
    private data class Case(val id: String, val patch: TidalPatch)
    private data class Options(val paths: List<String>, val states: List<String>, val cases: String,
        val warmups: Int, val samples: Int, val output: File?, val label: String?, val profile: Boolean,
        val jfr: File?)

    @JvmStatic
    fun main(args: Array<String>) {
        val options = options(args)
        val cases = cases(options.cases)
        val recording = options.jfr?.let { destination ->
            destination.parentFile?.mkdirs()
            Recording(Configuration.getConfiguration("profile")).apply {
                name = "Tidal render profile"
                this.destination = destination.toPath()
                dumpOnExit = true
                start()
            }
        }
        val rows = ArrayList<Map<String, Any?>>()
        val started = System.nanoTime()
        val thread = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val allocation = thread?.takeIf { it.isThreadAllocatedMemorySupported }?.also {
            if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
        }
        val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        for (case in cases) for (path in options.paths) for (state in options.states) {
            clearAll()
            // Establish classes/JIT and the warm geometry or finished-render state outside timers.
            repeat(maxOf(1, options.warmups)) { prepare(state); retained = render(case.patch, path) }
            val timings = ArrayList<Double>()
            val allocations = ArrayList<Long?>()
            val beforeHeap = ArrayList<Long>()
            val afterHeap = ArrayList<Long>()
            val peakHeap = ArrayList<Long>()
            var expectedHash: String? = null
            for (sample in 0 until options.samples) {
                prepare(state)
                retained = FloatArray(0)
                System.gc()
                heapPools.forEach { runCatching { it.resetPeakUsage() } }
                val before = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
                val allocationBefore = allocation?.getThreadAllocatedBytes(Thread.currentThread().id)
                val begin = System.nanoTime()
                val rendered = render(case.patch, path)
                val elapsed = System.nanoTime() - begin
                val allocationAfter = allocation?.getThreadAllocatedBytes(Thread.currentThread().id)
                val after = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
                val peak = heapPools.sumOf { it.peakUsage.used }
                retained = rendered
                require(rendered.isNotEmpty() && rendered.all(Float::isFinite)) { "${case.id}: invalid audio" }
                val hash = floatHash(rendered)
                require(expectedHash == null || expectedHash == hash) { "${case.id}: render changed across repetitions" }
                expectedHash = hash
                timings += elapsed / 1e6
                allocations += if (allocationBefore != null && allocationAfter != null) allocationAfter - allocationBefore else null
                beforeHeap += before; afterHeap += after; peakHeap += peak
            }
            val sorted = timings.sorted()
            val mid = sorted.size / 2
            val profile = if (options.profile) profile(case.patch) else null
            rows += linkedMapOf(
                "id" to case.id, "patch" to case.patch.toJsonValue(), "path" to path, "state" to state,
                "cacheHooksPresent" to (hook("clearAudioCache") != null),
                "warmupRenders" to maxOf(1, options.warmups), "timedRenders" to options.samples,
                "samplesMs" to timings, "minMs" to sorted.first(),
                "medianMs" to if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid],
                "p90Ms" to sorted[ceil(.9 * sorted.size).toInt() - 1], "maxMs" to sorted.last(),
                "allocatedBytesCurrentThread" to allocations,
                "heapUsedBeforeBytes" to beforeHeap, "heapUsedAfterBytes" to afterHeap,
                "sumOfHeapPoolPeakUsedBytes" to peakHeap,
                "frames" to retained.size, "durationSeconds" to retained.size.toDouble() / Dsp.RATE,
                "normalizedFloat32Sha256" to expectedHash,
                "prelevelFloat32Sha256" to if (options.cases == "references") floatHash(probe(case.patch).rawMix) else null,
                "cache" to cacheSnapshot(), "profileOutsideTimedSamples" to profile,
            )
            System.err.println("TIDAL benchmark: ${case.id}, $path/$state, median ${rows.last()["medianMs"]} ms")
        }
        val report = linkedMapOf(
            "schemaVersion" to 1, "engine" to "TIDAL", "label" to options.label,
            "generatedAt" to Instant.now().toString(), "checkoutRevision" to gitRevision(),
            "clock" to "System.nanoTime; render call only",
            "jfrRecording" to options.jfr?.absolutePath,
            "jfrNote" to "Use a separate profiling run; primary before/after timing runs leave JFR disabled.",
            "timedScope" to "Excludes cache reset, explicit GC, validation, hashes, logging, JSON and file I/O.",
            "states" to linkedMapOf(
                "cold" to "Clear geometry and finished audio before each call; JVM/classes/JIT are already warm.",
                "warm" to "Keep geometry but clear finished audio before each call; a fresh complete render is required.",
                "repeat" to "Repeat the same complete recipe with caches retained; old code has no finished-audio cache.",
            ),
            "memoryDefinitions" to linkedMapOf(
                "allocatedBytesCurrentThread" to "HotSpot ThreadMXBean allocation counter delta around the render call; null if unsupported.",
                "heapUsedBeforeBytes" to "MemoryMXBean heap used after explicit GC immediately before the timer.",
                "heapUsedAfterBytes" to "MemoryMXBean heap used immediately after the timer; includes garbage not yet collected.",
                "sumOfHeapPoolPeakUsedBytes" to "Sum of per-heap-pool peak used after reset immediately before render. Upper envelope, not simultaneous live heap or process RSS.",
            ),
            "floatHashEncoding" to "Float.toRawBits, little-endian four bytes per sample",
            "runtime" to linkedMapOf(
                "javaVersion" to System.getProperty("java.version"), "vm" to System.getProperty("java.vm.name"),
                "os" to System.getProperty("os.name"), "architecture" to System.getProperty("os.arch"),
                "processors" to Runtime.getRuntime().availableProcessors(), "cpuModel" to cpuModel(),
                "heapMaximumBytes" to Runtime.getRuntime().maxMemory(),
                "vmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-Xm") || it.startsWith("-XX:") },
                "loadedBenchmark" to origin(TidalRenderBenchmark::class.java),
                "loadedEngine" to origin(Tidal::class.java), "loadedDynamics" to origin(TidalDynamics::class.java),
                "loadedAudio" to origin(Snip::class.java),
            ),
            "benchmarkWallMs" to (System.nanoTime() - started) / 1e6, "cases" to rows,
        )
        val text = Json.write(json(report)) + "\n"
        recording?.stop()
        recording?.close()
        options.output?.let { it.parentFile?.mkdirs(); it.writeText(text) }
        print(text)
    }

    private fun cases(selection: String): List<Case> {
        fun c(id: String, voice: TidalVoice, midi: Int = 48, velocity: Float = .75f, seed: Long = 41L,
            model: Int = 2, macros: Map<String, Float> = emptyMap()) =
            Case(id, TidalPatch(id, voice, Tidal.defaults(voice, model) + macros, midi, velocity, seed, model))
        val defaults = listOf(c("pair-model2-c3", TidalVoice.PAIR), c("stream-model2-c3", TidalVoice.STREAM))
        if (selection == "defaults") return defaults
        return listOf(c("pair-model1-c3", TidalVoice.PAIR, model = 1), c("stream-model1-c3", TidalVoice.STREAM, model = 1)) +
            defaults + listOf(
                c("pair-low-seed-min", TidalVoice.PAIR, midi = 36, velocity = .35f, seed = Long.MIN_VALUE),
                c("stream-high-seed-max", TidalVoice.STREAM, midi = 84, velocity = 1f, seed = Long.MAX_VALUE),
                c("pair-zero-velocity", TidalVoice.PAIR, midi = 60, velocity = 0f, seed = 0L),
                c("stream-low-velocity", TidalVoice.STREAM, midi = 60, velocity = .25f, seed = 7L),
                c("pair-all-high", TidalVoice.PAIR, midi = 84, velocity = 1f, seed = -1L,
                    macros = listOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL").associateWith { 1f }),
                c("stream-all-low", TidalVoice.STREAM, midi = 36, seed = 1L,
                    macros = listOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL").associateWith { 0f }),
                c("stream-close-diffuse", TidalVoice.STREAM, midi = 60,
                    macros = mapOf("APPROACH" to .35f, "PASSAGE" to .9f, "TILT" to .15f, "BIND" to .1f, "TAIL" to .8f)),
                c("pair-inclined", TidalVoice.PAIR, midi = 60, seed = Long.MIN_VALUE,
                    macros = mapOf("TILT" to .85f, "PASSAGE" to .7f, "TAIL" to .85f)),
            )
    }

    private fun probe(p: TidalPatch) = Tidal.probe(p.voice, p.macros, p.midi, p.velocity, p.seed, p.model,
        Tidal.Options(normalize = true))

    private fun render(p: TidalPatch, path: String): FloatArray = when (path) {
        "diagnostics" -> probe(p).samples
        "uncached" -> hook("renderUncached")?.let { method ->
            (method.invoke(Tidal, p.voice, p.macros, p.midi, p.velocity, p.seed, p.model, { false }) as Snip).samples
        } ?: p.render().samples
        else -> p.render().samples
    }

    private fun prepare(state: String) { when (state) { "cold" -> clearAll(); "warm" -> clearAudio() } }
    private fun clearAll() {
        hook("clearCaches")?.let { it.invoke(Tidal); return }
        Tidal::class.java.getDeclaredField("preparationCache").also { it.isAccessible = true }
            .get(Tidal).let { @Suppress("UNCHECKED_CAST") (it as MutableMap<Any?, Any?>).clear() }
    }
    private fun clearAudio() { hook("clearAudioCache")?.invoke(Tidal) }
    private fun hook(name: String) = Tidal::class.java.declaredMethods.singleOrNull {
        !it.isSynthetic && java.lang.reflect.Modifier.isPublic(it.modifiers) &&
            (it.name == name || it.name.startsWith("$name$"))
    }

    private fun profile(p: TidalPatch): Map<String, Any?>? = hook("renderProfiled")?.let { method ->
        val result = method.invoke(Tidal, p.voice, p.macros, p.midi, p.velocity, p.seed, p.model, { false })
        val profile = result.javaClass.getMethod("getProfile").invoke(result)
        getters(profile)
    }
    private fun cacheSnapshot(): Map<String, Any?>? = hook("audioCacheSnapshot")?.invoke(Tidal)?.let(::getters)
    private fun getters(value: Any) = value.javaClass.methods.filter {
        it.name.startsWith("get") && it.name != "getClass" && it.parameterCount == 0
    }.sortedBy { it.name }.associate { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) to it.invoke(value) }

    internal fun floatHash(samples: FloatArray): String {
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
    private fun origin(type: Class<*>): Map<String, Any?> {
        val file = type.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
        val classBytes = type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!.use { it.readBytes() }
        return linkedMapOf("path" to file?.absolutePath, "classSha256" to hash(classBytes),
            "jarSha256" to file?.takeIf { it.isFile }?.readBytes()?.let(::hash))
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun cpuModel() = runCatching { File("/proc/cpuinfo").useLines { lines ->
        lines.firstOrNull { it.startsWith("model name") || it.startsWith("Hardware") }?.substringAfter(':')?.trim()
    } }.getOrNull()
    private fun gitRevision() = runCatching { ProcessBuilder("git", "rev-parse", "HEAD").start().let { p ->
        p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 }
    } }.getOrNull()

    private fun options(args: Array<String>): Options {
        val known = setOf("path", "state", "cases", "warmups", "samples", "output", "label", "profile", "jfr")
        val values = LinkedHashMap<String, String>()
        for (arg in args) {
            require(arg.startsWith("--") && '=' in arg) { "expected --option=value, got $arg" }
            val (key, value) = arg.removePrefix("--").split('=', limit = 2)
            require(key in known && value.isNotEmpty() && key !in values) { "unknown, empty or repeated option $arg" }
            values[key] = value
        }
        val path = values["path"] ?: "production"
        val state = values["state"] ?: "all"
        val cases = values["cases"] ?: "defaults"
        require(path in setOf("production", "diagnostics", "uncached", "all"))
        require(state in setOf("cold", "warm", "repeat", "all"))
        require(cases in setOf("defaults", "references"))
        val warmups = (values["warmups"] ?: "2").toInt()
        val samples = (values["samples"] ?: "5").toInt()
        require(warmups in 0..100 && samples in 1..100)
        val output = values["output"]?.let(::File)
        require(output?.exists() != true) { "refusing to overwrite benchmark evidence: $output" }
        val profile = values["profile"] ?: "false"
        require(profile in setOf("true", "false"))
        val jfr = values["jfr"]?.let(::File)
        require(jfr?.exists() != true) { "refusing to overwrite profiling evidence: $jfr" }
        return Options(if (path == "all") listOf("production", "diagnostics", "uncached") else listOf(path),
            if (state == "all") listOf("cold", "warm", "repeat") else listOf(state), cases, warmups, samples,
            output, values["label"], profile.toBoolean(), jfr)
    }
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
