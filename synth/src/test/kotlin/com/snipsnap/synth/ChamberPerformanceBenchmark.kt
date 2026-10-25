package com.snipsnap.synth

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Reproducible desktop production-path timings, never a phone-latency claim. */
object ChamberPerformanceBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 2) { "Usage: ChamberPerformanceBenchmark [output-directory] [rounds]" }
        val output = File(args.getOrNull(0) ?: "../testkit/chamber-performance")
        val rounds = args.getOrNull(1)?.toInt() ?: 3
        require(rounds in 1..10)
        check(output.isDirectory || output.mkdirs()) { "Cannot create $output" }
        val table = File(output, "production-timings.tsv")
        val stages = File(output, "production-stages.tsv")
        table.writeText("voice\tmidi\titeration\tpath\telapsed_ms\tsamples\tsha256_float32_be\n")
        stages.writeText("voice\tmidi\tpreparation_ms\tsynthesis_ms\toutput_ms\n")
        File(output, "README.txt").writeText("""
            CHAMBER finite production benchmark
            Java: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}
            OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}
            Model: ${Chamber.MODEL_VERSION}; output: mono ${Chamber.RATE} Hz; internal: ${Dsp.OVERSAMPLE}x
            Duration: 4.5 seconds; velocity: 0.75; seed: 0; default controls and authored rooms.
            Two uncached warmup rounds precede $rounds measured rounds for HYBRID/AQUARIUM at MIDI 48/60.
            Each measured uncached render uses a new per-screen cache, followed by an exact-recipe hit.
            Diagnostics and stems are disabled. Full quality is retained; no preview approximation is used.
            Stage timings come from the same normalized production probe settings after warmup.
            Hashes encode every output Float32 bit in big-endian order, independently of WAV quantization.
            These are desktop JVM timings. They do not establish target-phone or MPC acceptance.
        """.trimIndent() + "\n")
        val recipes = ChamberVoice.entries.flatMap { voice -> listOf(48, 60).map { midi ->
            ChamberPatch("$voice MIDI$midi", voice, Chamber.defaults(voice), midi = midi)
        } }
        repeat(2) { recipes.forEach { it.render() } }
        repeat(rounds) { round ->
            for (patch in recipes) {
                val renderer = ChamberRenderer()
                val freshStart = System.nanoTime()
                val fresh = renderer.render(patch)
                val freshMs = (System.nanoTime() - freshStart) / 1e6
                val hitStart = System.nanoTime()
                val hit = renderer.render(patch)
                val hitMs = (System.nanoTime() - hitStart) / 1e6
                check(fresh.samples.contentEquals(hit.samples)) { "Cache changed ${patch.name}" }
                check(renderer.stats().hits == 1L)
                for ((path, millis, samples) in listOf(Triple("uncached", freshMs, fresh.samples), Triple("cache_hit", hitMs, hit.samples))) {
                    table.appendText("${patch.voice}\t${patch.midi}\t$round\t$path\t$millis\t${samples.size}\t${sha256(samples)}\n")
                }
                println("CHAMBER ${patch.voice} MIDI${patch.midi} fresh=${freshMs.roundToInt()}ms hit=${"%.3f".format(java.util.Locale.ROOT, hitMs)}ms")
            }
        }
        for (patch in recipes) {
            val probe = Chamber.probe(patch.voice, patch.macros, patch.midi, patch.velocity,
                patch.configuration, patch.seed, patch.model,
                Chamber.ProbeOptions(normalize = true, recordDiagnostics = false, recordStems = false))
            val d = probe.diagnostics
            stages.appendText("${patch.voice}\t${patch.midi}\t${d.preparationNanos / 1e6}\t${d.synthesisNanos / 1e6}\t${d.outputNanos / 1e6}\n")
        }
    }

    private fun sha256(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
}
