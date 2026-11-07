package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Bounded offline timing evidence, not a machine-dependent unit-test gate.
 * Run after audition generation so this report can travel with the actual audio.
 * These JVM measurements do not establish Android or physical-phone performance.
 */
object WickRenderProfile {
    private data class Case(
        val id: String,
        val voice: WickVoice,
        val held: Boolean,
        val source: Wick.RattleSource = Wick.RattleSource.WARM_WIRE,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val output = File(args.firstOrNull { !it.startsWith("--") }
            ?: "../testkit/wick-audition/render-profile.json").absoluteFile
        fun count(name: String, fallback: Int, range: IntRange): Int {
            val raw = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
            val value = raw?.toIntOrNull() ?: if (raw == null) fallback else error("$name requires an integer")
            require(value in range) { "$name must be in $range" }
            return value
        }
        val warmups = count("warmups", 1, 0..4)
        val repetitions = count("repetitions", 5, 3..9)
        val revision = args.firstOrNull { it.startsWith("--source-revision=") }?.substringAfter('=')
        val cases = WickVoice.entries.flatMap { voice -> listOf(false, true).map { held ->
            Case("${voice.name.lowercase()}_${if (held) "held" else "finite"}", voice, held)
        } } + listOf(Wick.RattleSource.HOLLOW_REED, Wick.RattleSource.RUBBED_GLASS).flatMap { source ->
            listOf(false, true).map { held ->
                Case("rattle_${source.name.lowercase()}_${if (held) "held" else "finite"}", WickVoice.RATTLE, held, source)
            }
        }
        val rows = cases.map { case ->
            val macros = Wick.defaults(case.voice) + ("HOLD" to if (case.held) 1f else 0f)
            fun render() = Wick.inspect(case.voice, macros, rattleSource = case.source)
            // This first render captures class loading/JIT effects separately. Each following
            // sample measures inspect only; hashing and JSON construction stay outside it.
            var start = System.nanoTime()
            val first = render()
            val initialMs = (System.nanoTime() - start) / 1e6
            repeat(warmups) { render() }
            val times = List(repetitions) {
                start = System.nanoTime()
                val probe = render()
                val elapsed = (System.nanoTime() - start) / 1e6
                check(probe.samples.isNotEmpty() && probe.samples.all { it.isFinite() })
                elapsed
            }
            val sorted = times.sorted()
            val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * .5
            val p95 = sorted[(ceil(.95 * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
            println("WICK ${case.id}: median %.2f ms, p95 %.2f ms".format(median, p95))
            linkedMapOf<String, Any?>(
                "id" to case.id, "voice" to case.voice.name,
                "rattleSource" to if (case.voice == WickVoice.RATTLE) case.source.name else null,
                "midi" to Wick.rootMidi(case.voice), "macros" to macros,
                "frames" to first.samples.size, "sampleRate" to Dsp.RATE,
                "durationSeconds" to first.samples.size.toDouble() / Dsp.RATE,
                "initialRenderMs" to initialMs, "warmups" to warmups, "repetitions" to repetitions,
                "medianMs" to median, "p95Ms" to p95, "minimumMs" to sorted.first(),
                "maximumMs" to sorted.last(), "renderMs" to times,
                "rawFloatSha256" to digest(first.raw), "sampleFloatSha256" to digest(first.samples),
                "contextFloatSha256" to first.seamContext?.let(::digest),
                "heldAttempts" to first.diagnostics.loop?.attempts,
                "heldStrategy" to first.diagnostics.loop?.strategy,
                "heldSeam" to first.diagnostics.loop?.seam,
            )
        }
        val report = linkedMapOf<String, Any?>(
            "status" to "measured", "engine" to "WICK", "sourceRevision" to revision,
            "runtime" to System.getProperty("java.runtime.version"),
            "architecture" to System.getProperty("os.arch"),
            "availableProcessors" to Runtime.getRuntime().availableProcessors(),
            "sampleRate" to Dsp.RATE, "oversampling" to Dsp.OVERSAMPLE,
            "method" to "System.nanoTime wall time around Wick.inspect only. First render and subsequent warm-ups are excluded from the timed samples. Case order is fixed. Nearest-rank p95 is the maximum for these bounded sample counts. JVM/cloud evidence, not Android or physical-phone performance.",
            "cases" to rows,
        )
        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        output.writeText(Json.write(json(report)) + "\n")
        println("WICK profile written to ${output.absolutePath}")
    }

    private fun digest(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported WICK profile value ${value.javaClass}")
    }
}
