package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Manual production-call measurements. Timings are evidence, never correctness thresholds. */
object ScannedChainRenderBenchmark {
    internal data class Case(val id: String, val patch: ScannedChainPatch)
    private data class Options(val output: File?, val label: String?, val warmups: Int, val samples: Int,
        val caseGroup: String, val paths: Set<String>, val states: Set<String>, val cancellation: Boolean)
    @Volatile private var retained = FloatArray(0)

    @JvmStatic fun main(args: Array<String>) {
        val o = options(args)
        val rows = cases(o.caseGroup).map { case ->
            val p = case.patch
            // Reference snapshots and hashes are outside every timing interval.
            val probe = ScannedChain.probeV2(p.voice, p.macros, p.midi, p.velocity, p.shape, p.config)
            clearCompletedCache()
            val output = finished(case)
            val measurements = ArrayList<Map<String, Any?>>()
            if ("probe" in o.paths) measurements += measure("raw_probe", o) { { raw(case).samples } }
            if ("finish" in o.paths) measurements += measure("finishing_only", o) {
                val samples = probe.raw.samples.copyOf()
                val call: () -> FloatArray = {
                    Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * p.velocity,
                        ceiling = .9f * p.velocity)
                    samples
                }
                call
            }
            if ("production" in o.paths) for (state in o.states) {
                val target = if (state == "changed_knob_miss") Case(case.id + "-changed-motion",
                    p.copy(macros = p.macros + ("MOTION" to if (p.macros.getValue("MOTION") == .97f) .96f else .97f))) else case
                val measured = measure(state, o) {
                    when (state) {
                        "fresh" -> clearCompletedCache()
                        "cache_hit", "changed_knob_miss" -> { finished(case); Unit }
                    }
                    val call: () -> FloatArray = { finished(target).samples }
                    call
                }.toMutableMap()
                measured["renderedPatchJson"] = target.patch.toJsonValue()
                measured["cacheAfter"] = getters(ScannedChainRenderCache.stats())
                measurements += measured
            }
            linkedMapOf<String, Any?>("id" to case.id, "patchJson" to p.toJsonValue(),
                "inputShapeSha256" to floatHash(p.shape.points), "raw" to audio(probe.raw),
                "finished" to audio(output), "initialShapeSha256" to floatHash(probe.initialShape),
                "finalShapeSha256" to floatHash(probe.finalShape), "diagnostics" to getters(probe.diagnostics),
                "headroom" to getters(probe.headroom), "measurements" to measurements).also {
                System.err.println("SCANNED CHAIN benchmark: ${case.id} " + measurements.joinToString { m ->
                    "${m["kind"]}=${m["medianMs"]}ms" })
            }
        }
        val types = listOf(ScannedChainRenderBenchmark::class.java, ScannedChain::class.java,
            ScannedChainModelV1::class.java, ScannedChainModelV2::class.java, ScannedChainHeadroom::class.java,
            ScannedChainRenderCache::class.java, ScannedChainShape::class.java, Dsp::class.java, Snip::class.java)
            .flatMap(::classTree).distinctBy { it.name }
        val report = linkedMapOf<String, Any?>("schemaVersion" to 1, "engine" to "SCANNED_CHAIN",
            "baselineCommit" to "307f125ef2d9ef0c4d6e6c816bb008b12a235392", "caseGroup" to o.caseGroup,
            "label" to o.label, "generatedAt" to Instant.now().toString(), "checkoutRevision" to gitRevision(),
            "scope" to "Actual host JVM production code. No phone timing or listening judgement. Loaded class hashes identify mixed baseline/harness classpaths.",
            "timedScope" to "System.nanoTime around the call only; excludes cache reset/priming, hashes, validation, buffer preparation, logging, JSON and I/O. No explicit GC.",
            "stateDefinitions" to mapOf("fresh" to "Completed audio cleared before each warmed call; immutable preparation caches, if any, remain warm.",
                "cache_hit" to "Exact recipe primed outside timer; measured call returns an owned completed result.",
                "changed_knob_miss" to "Original recipe primed outside timer; measured call changes only MOTION to .97 (or .96 if already .97)."),
            "stageDefinitions" to mapOf("raw_probe" to "Production diagnostic raw probe including physics, projection and scan; not a subtraction-derived stage estimate.",
                "finishing_only" to "Actual Dsp.levelTo on an outside-timer copy of the raw result; excludes cache ownership copies."),
            "floatHashEncoding" to "IEEE754 raw float32 bits, little endian, four bytes per sample",
            "runtime" to mapOf("javaVersion" to System.getProperty("java.version"), "vm" to System.getProperty("java.vm.name"),
                "os" to System.getProperty("os.name"), "architecture" to System.getProperty("os.arch"),
                "processors" to Runtime.getRuntime().availableProcessors(), "heapMaximumBytes" to Runtime.getRuntime().maxMemory(),
                "vmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-Xm") || it.startsWith("-XX:") },
                "loadedClasses" to types.associate { it.name to origin(it) }),
            "cases" to rows, "cancellation" to if (o.cancellation) cancellation() else null)
        val text = Json.write(json(report)) + "\n"
        o.output?.also { it.parentFile?.mkdirs(); it.writeText(text) }
        print(text)
    }

    /** Stable, bounded recipe matrix shared by the baseline-capture and preservation tests. */
    internal fun cases(selection: String): List<Case> {
        require(selection in setOf("defaults", "preservation", "short"))
        fun make(id: String, voice: ScannedChainVoice, midi: Int = 60, macros: Map<String, Float> = emptyMap(),
            seconds: Float = 3f, velocity: Float = .8f, shape: ScannedChainShape = ScannedChainShape.factory(voice)) =
            Case(id, ScannedChainPatch(id, voice, ScannedChain.defaults(voice) + macros, shape,
                ScannedChainConfig.forVoice(voice).copy(seconds = seconds), midi, velocity, model = 2))
        if (selection == "defaults" || selection == "short") return ScannedChainVoice.entries.map {
            make("${it.name.lowercase()}-m60", it, seconds = if (selection == "short") .25f else 3f)
        }
        val result = ArrayList<Case>()
        for (voice in ScannedChainVoice.entries) {
            for (midi in listOf(36, 60, 96)) result += make("${voice.name.lowercase()}-m$midi", voice, midi)
            val corners = linkedMapOf(
                "all-zero" to mapOf("MOTION" to 0f, "DAMP" to 0f, "BEND" to 0f, "BLOOM" to 0f),
                "all-one" to mapOf("MOTION" to 1f, "DAMP" to 1f, "BEND" to 1f, "BLOOM" to 1f),
                "undamped-hot" to mapOf("MOTION" to 1f, "DAMP" to 0f, "BEND" to 1f, "BLOOM" to 1f),
                "frozen-hot" to mapOf("MOTION" to 0f, "DAMP" to 1f, "BEND" to 1f, "BLOOM" to 1f),
                "low-mid" to mapOf("MOTION" to .1f, "DAMP" to .25f, "BEND" to .5f, "BLOOM" to .8f))
            for ((name, macros) in corners) result += make("${voice.name.lowercase()}-$name", voice,
                macros = macros, seconds = .5f, velocity = 1f)
        }
        val points = requireNotNull(ScannedChainRenderBenchmark::class.java.getResourceAsStream(
            "/scanned-chain-model2/focused-shape.json")) { "focused regression resource missing from classpath" }
            .bufferedReader().use { Json.parse(it.readText()).arr().map { p -> p.num().toFloat() }.toFloatArray() }
        result += make("metal-focused", ScannedChainVoice.METAL, macros = mapOf("MOTION" to 1f,
            "DAMP" to 0f, "BEND" to 0f, "BLOOM" to 0f), seconds = .25f, velocity = 1f,
            shape = ScannedChainShape(points, ScannedChainShapeSource.DRAWN, "Exact focused contour"))
        result += make("pluck-zero-velocity", ScannedChainVoice.PLUCK, seconds = .25f, velocity = 0f)
        result += make("bloom-zero-shape", ScannedChainVoice.BLOOM, seconds = .25f,
            shape = ScannedChainShape.drawn(FloatArray(128), "Zero excitation"))
        return result
    }

    internal fun raw(case: Case): Snip = case.patch.let {
        ScannedChain.probe(it.voice, it.macros, it.midi, it.velocity, it.shape, it.config, it.model).raw
    }
    internal fun finished(case: Case): Snip = case.patch.render()
    internal fun clearCompletedCache() = ScannedChainRenderCache.clear()

    private fun measure(kind: String, o: Options, prepare: () -> (() -> FloatArray)): Map<String, Any?> {
        repeat(o.warmups) { retained = prepare()() }
        val times = ArrayList<Double>(); val allocations = ArrayList<Long?>(); var expected: String? = null
        val bean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported }?.also { if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true }
        repeat(o.samples) {
            val call = prepare()
            val before = bean?.getThreadAllocatedBytes(Thread.currentThread().id)
            val start = System.nanoTime()
            val samples = call()
            val nanos = System.nanoTime() - start
            val after = bean?.getThreadAllocatedBytes(Thread.currentThread().id)
            retained = samples
            require(samples.all(Float::isFinite)) { "$kind produced nonfinite audio" }
            val hash = floatHash(samples)
            require(expected == null || expected == hash) { "$kind is not deterministic across repetitions" }
            expected = hash; times += nanos / 1e6
            allocations += if (after != null && before != null) after - before else null
        }
        val sorted = times.sorted(); val mid = sorted.size / 2
        return linkedMapOf("kind" to kind, "warmups" to o.warmups, "samples" to o.samples,
            "samplesMs" to times, "minMs" to sorted.first(), "maxMs" to sorted.last(),
            "medianMs" to if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid],
            "allocatedBytesCurrentThread" to allocations, "sha256" to expected, "frames" to retained.size)
    }

    private fun cancellation(): Map<String, Any?> {
        clearCompletedCache()
        val p = ScannedChainPatch("Cancellation observation", ScannedChainVoice.METAL,
            config = ScannedChainConfig.forVoice(ScannedChainVoice.METAL).copy(seconds = 4f))
        val started = CountDownLatch(1); val error = AtomicReference<Throwable?>(); val completed = CountDownLatch(1)
        val worker = Thread({ started.countDown(); try { retained = p.render().samples }
            catch (t: Throwable) { error.set(t) } finally { completed.countDown() } }, "scanned-chain-cancellation-probe")
        worker.isDaemon = true
        val bean = ManagementFactory.getThreadMXBean()
        if (bean.isThreadCpuTimeSupported && !bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
        worker.start(); started.await(2, TimeUnit.SECONDS)
        val waitStart = System.nanoTime()
        while (completed.count > 0 && System.nanoTime() - waitStart < 200_000_000L) {
            if (bean.isThreadCpuTimeSupported && bean.getThreadCpuTime(worker.id) >= 10_000_000L) break
            LockSupport.parkNanos(100_000)
        }
        val cpuBeforeInterrupt = if (bean.isThreadCpuTimeSupported) bean.getThreadCpuTime(worker.id) else null
        val completedBeforeInterrupt = completed.count == 0L
        val start = System.nanoTime(); worker.interrupt()
        val terminated = completed.await(5, TimeUnit.SECONDS)
        val latency = (System.nanoTime() - start) / 1e6
        val failure = error.get()
        return mapOf("requestedWorkerCpuNanos" to 10_000_000L, "observedWorkerCpuNanos" to cpuBeforeInterrupt,
            "completedBeforeInterrupt" to completedBeforeInterrupt, "terminated" to terminated,
            "interruptToObservationMs" to latency, "failureClass" to failure?.javaClass?.name,
            "cancelled" to (failure is CancellationException), "cacheAfter" to getters(ScannedChainRenderCache.stats()),
            "note" to "Observation only; no cancellation latency threshold asserts correctness. Five-second wait bounds the harness, not acceptable product latency.")
    }

    private fun audio(snip: Snip) = mapOf("sha256" to floatHash(snip.samples), "frames" to snip.frameCount,
        "channels" to snip.channels, "sampleRate" to snip.sampleRate)
    internal fun floatHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(16_384); var used = 0
        for (sample in samples) {
            val bits = sample.toRawBits()
            for (b in 0..3) bytes[used++] = (bits ushr (b * 8)).toByte()
            if (used == bytes.size) { digest.update(bytes); used = 0 }
        }
        digest.update(bytes, 0, used)
        return hashDigest(digest.digest())
    }
    private fun hashDigest(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun classTree(type: Class<*>): List<Class<*>> = listOf(type) + type.declaredClasses.flatMap(::classTree)
    private fun origin(type: Class<*>) = mapOf("path" to type.protectionDomain?.codeSource?.location?.toString(),
        "sha256" to hashDigest(MessageDigest.getInstance("SHA-256").digest(requireNotNull(
            type.getResourceAsStream("/${type.name.replace('.', '/')}.class")).use { it.readBytes() })))
    private fun getters(value: Any) = value.javaClass.methods.filter {
        it.name.startsWith("get") && it.name != "getClass" && it.parameterCount == 0
    }.sortedBy { it.name }.associate { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) to it.invoke(value) }
    private fun gitRevision() = runCatching { ProcessBuilder("git", "rev-parse", "HEAD").start().let { p ->
        p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 } } }.getOrNull()
    private fun options(args: Array<String>): Options {
        val values = linkedMapOf<String, String>()
        val known = setOf("output", "label", "warmups", "samples", "casegroup", "paths", "states", "cancellation")
        for (arg in args) {
            require(arg.startsWith("--") && '=' in arg) { "expected --option=value" }
            val (key, value) = arg.removePrefix("--").split('=', limit = 2)
            require(key in known && value.isNotEmpty() && key !in values) { "invalid option $arg" }; values[key] = value
        }
        val warmups = (values["warmups"] ?: "2").toInt(); val samples = (values["samples"] ?: "5").toInt()
        require(warmups in 0..100 && samples in 1..100)
        val paths = (values["paths"] ?: "production,probe,finish").split(',').toSet()
        val states = (values["states"] ?: "fresh,cache_hit,changed_knob_miss").split(',').toSet()
        require(paths.all { it in setOf("production", "probe", "finish") })
        require(states.all { it in setOf("fresh", "cache_hit", "changed_knob_miss") })
        val group = values["casegroup"] ?: "defaults"; require(group in setOf("defaults", "preservation", "short"))
        val cancellation = values["cancellation"] ?: "false"; require(cancellation in setOf("true", "false"))
        val output = values["output"]?.let(::File); require(output?.exists() != true) { "refusing to overwrite evidence: $output" }
        return Options(output, values["label"], warmups, samples, group, paths, states, cancellation.toBoolean())
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null; is JsonValue -> value; is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble()); is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json)); else -> JsonValue.Str(value.toString())
    }
}
