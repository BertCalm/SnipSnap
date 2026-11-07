package com.snipsnap.synth

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

/** Opt-in host measurements of the production held path. Timing never decides correctness. */
object OverlapHeldBenchmark {
    private const val MIDI = 48
    private const val VELOCITY = .8f
    private const val CANCELLATION_CHECK = 32

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.all { it.startsWith("--output=") }) {
            "Use OverlapHeldBenchmark [--output=/absolute/path/report.json]"
        }
        val output = args.singleOrNull()?.substringAfter("--output=")?.also {
            require(it.isNotBlank()) { "Benchmark output must name a JSON file" }
        }?.let(::File)
        require(output?.exists() != true) { "Refusing to overwrite benchmark evidence: $output" }
        val repo = repository()
        val source = source(repo)
        val rows = mutableListOf<Map<String, Any?>>()
        val started = System.nanoTime()
        for (topology in OverlapTopology.entries) {
            val patch = OverlapPatch("OVERLAP benchmark ${topology.name}", topology,
                OverlapHeld.defaults(topology), midi = MIDI, velocity = VELOCITY,
                model = 2, playback = OverlapPlayback.HELD, heldVersion = OverlapHeld.VERSION)
            OverlapHeld.clearCache()
            val fresh = measure("fresh_uncached", patch, expectedHit = false)
            rows += fresh
            val expectedHash = fresh.getValue("pcm24WavSha256")
            for ((kind, selected) in listOf(
                "exact_cache_hit" to patch,
                "name_only_cache_hit" to patch.copy(name = "Renamed held benchmark"),
                "release_only_cache_hit" to patch.copy(releaseSeconds = .9f),
            )) {
                val row = measure(kind, selected, expectedHit = true)
                check(row.getValue("pcm24WavSha256") == expectedHash) { "$kind altered source audio" }
                rows += row
            }
            val knot = measure("changed_knot_miss", patch.copy(macros = patch.macros + ("KNOT" to .5f)),
                expectedHit = false).toMutableMap()
            knot["controlMeaning"] = if (topology == OverlapTopology.SELF)
                "KNOT is inactive in SELF; the current cache still distinguishes its exact value."
                else "Only KNOT changed from full depth to .5."
            check((knot.getValue("pcm24WavSha256") == expectedHash) == (topology == OverlapTopology.SELF)) {
                "$topology KNOT changed an inactive control or failed to change an active one"
            }
            rows += knot
            val ring = measure("changed_ring_miss", patch.copy(macros = patch.macros + ("RING" to .62f)),
                expectedHit = false)
            check(ring.getValue("pcm24WavSha256") != expectedHash) { "$topology RING did not alter source audio" }
            rows += ring
        }
        val cancellationPatch = OverlapPatch("Cancellation benchmark", OverlapTopology.TREFOIL,
            OverlapHeld.defaults(OverlapTopology.TREFOIL), midi = MIDI, velocity = VELOCITY,
            model = 2, playback = OverlapPlayback.HELD)
        val cancellation = callbackCancellation(cancellationPatch)
        val interruption = threadInterruption(cancellationPatch)
        val report = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "OVERLAP held", "heldVersion" to OverlapHeld.VERSION,
            "generatedAt" to Instant.now().toString(), "source" to source,
            "scope" to "Actual host JVM production calls at MIDI 48, velocity .8. No phone timing or listening judgment.",
            "timedScope" to "System.nanoTime around OverlapHeld.render only. Excludes cache clear, stats reads, validation, PCM encoding/hashes, logging, JSON and file I/O. No explicit GC or warmup solves.",
            "order" to "Each topology starts with an empty completed cache. The first row can include process/JIT cold costs; later topology rows are not fresh JVM processes.",
            "coldMeaning" to "Completed-note cache miss; no claim that JIT, class loading or the operating system is cold.",
            "cacheEvidence" to "Hit/miss counter deltas prove path selection. Cache entries and bytes are recorded after every call; latency is not used to infer a hit.",
            "hashEncoding" to "SHA256 of PCM24 WAV including the rendered root and sustain-loop smpl metadata; encoding runs outside the timer.",
            "runtime" to runtime(),
            "cacheBounds" to mapOf("maximumEntries" to OverlapHeld.CACHE_MAX_ENTRIES,
                "maximumSampleBytes" to OverlapHeld.CACHE_MAX_BYTES),
            "measurements" to rows, "callbackCancellation" to cancellation,
            "threadInterruption" to interruption, "benchmarkWallMs" to elapsed(started),
        )
        val text = Json.write(json(report)) + "\n"
        output?.also { it.parentFile?.mkdirs(); it.writeText(text) }
        print(text)
    }

    private fun measure(kind: String, patch: OverlapPatch, expectedHit: Boolean): Map<String, Any?> {
        val before = OverlapHeld.cacheStats()
        val started = System.nanoTime()
        val held = OverlapHeld.render(patch, MIDI)
        val milliseconds = elapsed(started)
        val after = OverlapHeld.cacheStats()
        check(after.hits - before.hits == if (expectedHit) 1L else 0L) { "$kind used the wrong hit path" }
        check(after.misses - before.misses == if (expectedHit) 0L else 1L) { "$kind used the wrong miss path" }
        check(after.entries in 1..OverlapHeld.CACHE_MAX_ENTRIES && after.bytes in 1..OverlapHeld.CACHE_MAX_BYTES)
        check(held.snip.channels == 1 && held.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE)
        check(held.loopStartFrame > 0 && held.loopEndExclusive == 2 * held.loopStartFrame &&
            held.snip.frameCount == held.loopEndExclusive)
        check(held.snip.samples.all { it.isFinite() } && held.snip.samples[0] == 0f)
        val peak = held.snip.samples.maxOf { abs(it.toDouble()) }
        check(peak > 0 && peak <= .900001)
        val d = held.diagnostics.sourceDiagnostics
        val bytes = ByteArrayOutputStream().also { stream ->
            WavWriter.write(stream, held.snip, WavWriter.BitDepth.PCM_24,
                smpl = SmplChunk(MIDI, SmplChunk.Loop(held.loopStartFrame.toLong(), held.loopEndExclusive.toLong())))
        }.toByteArray()
        System.err.println("OVERLAP held benchmark ${patch.topology} $kind: $milliseconds ms")
        return linkedMapOf(
            "kind" to kind, "topology" to patch.topology.name, "patch" to patch.toJsonValue(),
            "renderMillis" to milliseconds, "expectedCacheHit" to expectedHit,
            "cacheBefore" to stats(before), "cacheAfter" to stats(after),
            "hitDelta" to after.hits - before.hits, "missDelta" to after.misses - before.misses,
            "pcm24WavSha256" to hash(bytes), "frames" to held.snip.frameCount,
            "sampleRate" to held.snip.sampleRate, "channels" to held.snip.channels,
            "loopStartFrame" to held.loopStartFrame, "loopEndExclusive" to held.loopEndExclusive,
            "peak" to peak, "nativeOnlyDownGain" to held.diagnostics.gain,
            "sourcePeriodFrames" to d.periodFrames, "sourceRootHz" to d.sourceRootHz,
            "referenceHz" to d.referenceHz, "sourceStateError" to d.stateError,
            "sourceAudioError" to d.audioError, "sourcePrecedingSeam" to d.seam,
            "sourceFollowingSeam" to d.nextSeam, "sourceAttempts" to d.attempts,
            "sourceInternalFrames" to d.internalFrames,
            "originalCaptureSolveMillis" to d.solveMillis,
        )
    }

    private fun callbackCancellation(patch: OverlapPatch): Map<String, Any?> {
        OverlapHeld.clearCache()
        var checks = 0
        val started = System.nanoTime()
        val failure = runCatching {
            OverlapHeld.render(patch, cancelled = { ++checks >= CANCELLATION_CHECK })
        }.exceptionOrNull()
        val milliseconds = elapsed(started)
        val after = OverlapHeld.cacheStats()
        check(failure is CancellationException && after.entries == 0 && after.bytes == 0)
        return linkedMapOf("triggerAtCheck" to CANCELLATION_CHECK, "observedChecks" to checks,
            "renderToCancellationMillis" to milliseconds, "failureClass" to failure.javaClass.name,
            "cacheAfter" to stats(after),
            "meaning" to "The callback becomes true during real uncached preparation/synthesis. This measures callback-triggered cancellation, not a UI event or latency acceptance threshold.")
    }

    private fun threadInterruption(patch: OverlapPatch): Map<String, Any?> {
        OverlapHeld.clearCache()
        val checkpoint = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val checks = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val finished = AtomicLong()
        val worker = Thread({
            try {
                OverlapHeld.render(patch, cancelled = {
                    if (checks.incrementAndGet() == CANCELLATION_CHECK) {
                        checkpoint.countDown()
                        while (!Thread.currentThread().isInterrupted) LockSupport.parkNanos(100_000L)
                    }
                    false
                })
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                finished.set(System.nanoTime())
                complete.countDown()
            }
        }, "overlap-held-interruption-benchmark").apply { isDaemon = true }
        worker.start()
        try {
            check(checkpoint.await(5, TimeUnit.SECONDS)) { "Cancellation benchmark did not reach the production checkpoint" }
            val requested = System.nanoTime()
            worker.interrupt()
            check(complete.await(5, TimeUnit.SECONDS)) { "Interrupted renderer did not terminate within the bounded harness wait" }
            val after = OverlapHeld.cacheStats()
            check(failure.get() is CancellationException && after.entries == 0 && after.bytes == 0)
            return linkedMapOf("triggerAtCheck" to CANCELLATION_CHECK, "observedChecks" to checks.get(),
                "interruptToObservationMillis" to (finished.get() - requested) / 1e6,
                "failureClass" to failure.get()!!.javaClass.name, "cacheAfter" to stats(after),
                "meaning" to "Worker pauses at a real production callback checkpoint, then receives Thread.interrupt. The measured interval excludes that controlled wait. Five-second waits bound the harness; they are not product-latency targets.")
        } finally {
            worker.interrupt()
        }
    }

    private fun stats(s: OverlapHeld.CacheStats) = linkedMapOf(
        "entries" to s.entries, "sampleBytes" to s.bytes, "hits" to s.hits, "misses" to s.misses)

    private fun runtime(): Map<String, Any?> = linkedMapOf(
        "javaVersion" to System.getProperty("java.version"), "vm" to System.getProperty("java.vm.name"),
        "os" to System.getProperty("os.name"), "architecture" to System.getProperty("os.arch"),
        "cpuModel" to runCatching { File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("model name") || it.startsWith("Hardware") }
                ?.substringAfter(':')?.trim()
        } }.getOrNull(),
        "processors" to Runtime.getRuntime().availableProcessors(),
        "heapMaximumBytes" to Runtime.getRuntime().maxMemory(),
        "vmMemoryArguments" to ManagementFactory.getRuntimeMXBean().inputArguments
            .filter { it.startsWith("-Xm") || it.startsWith("-XX:") },
        "loadedClasses" to listOf(OverlapHeldBenchmark::class.java, OverlapHeld::class.java,
            OverlapHeldPrototype::class.java, OverlapPatch::class.java).associate { type ->
            type.name to type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!
                .use { hash(it.readBytes()) }
        },
    )

    private fun source(repo: File): Map<String, Any?> {
        val paths = listOf("synth/src/main/kotlin/com/snipsnap/synth/OverlapHeld.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OverlapHeldPrototype.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OverlapPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OverlapV2.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OverlapHeldBenchmark.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt")
        return linkedMapOf("revision" to git(repo, "rev-parse", "HEAD"),
            "treeDirty" to git(repo, "status", "--porcelain").isNotEmpty(),
            "files" to paths.associateWith { hash(File(repo, it).readBytes()) })
    }

    private fun repository(): File = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull {
        File(it, "synth/src/main/kotlin/com/snipsnap/synth/OverlapHeld.kt").isFile
    } ?: error("Run from the SnipSnap checkout or a module directory")

    private fun git(repo: File, vararg args: String): String =
        ProcessBuilder(listOf("git", "-C", repo.path) + args).redirectErrorStream(true).start().let { process ->
            val text = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "Cannot record source revision" }
            text
        }

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1e6
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported benchmark JSON value ${value::class}")
    }
}
