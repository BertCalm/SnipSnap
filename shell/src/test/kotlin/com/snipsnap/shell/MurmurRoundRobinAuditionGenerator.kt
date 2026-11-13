package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource.RoundRobinPolicy
import java.io.File
import java.nio.file.Files

/**
 * The private round-robin-only listen for MURMUR's accepted C2 pluck: V1 (heard as "no
 * variation") against V2, both played by the saved-sample player from real saved banks.
 * Nothing changes the accepted C2 tone or touch; only takes 1..3 differ between the two.
 *
 * `./gradlew :shell:generateMurmurRoundRobinAudition` writes testkit/murmur-rr-v2-audition/.
 */
object MurmurRoundRobinAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "testkit/murmur-rr-v2-audition").apply { deleteRecursively(); mkdirs() }
        val work = Files.createTempDirectory("murmur-rr-audition-").toFile()
        try {
            val measured = linkedMapOf<String, JsonValue>()
            for (policy in listOf(RoundRobinPolicy.BREATH_V1, RoundRobinPolicy.CONTRAST_V2)) {
                val tag = if (policy == RoundRobinPolicy.BREATH_V1) "v1" else "v2"
                val policyWork = File(work, tag)
                val saved = MurmurRoundRobinPlayback.savedBank(policy, policyWork, "MURMUR RR ${tag.uppercase()}")
                val shelf = File(policyWork, "shelf")

                val (runTakes, run) = MurmurRoundRobinPlayback.repeatedRun(saved, shelf)
                write(File(out, "$tag-repeats.wav"), run, 2)

                val strikes = MurmurRoundRobinPlayback.isolatedStrikes(saved, shelf, 4)
                val gap = FloatArray((.35 * MurmurRoundRobinPlayback.RATE).toInt())
                write(File(out, "$tag-takes.wav"), strikes.flatMap { it.samples.toList() + gap.toList() }.toFloatArray(), 1)

                val four = strikes.map { it.samples }
                measured[tag] = JsonValue.Obj(linkedMapOf(
                    "policy" to JsonValue.Str(policy.json),
                    "runTakes" to JsonValue.Arr(runTakes.map { JsonValue.Num(it.toDouble()) }),
                    "isolatedTakes" to JsonValue.Arr(strikes.map { JsonValue.Num(it.take.toDouble()) }),
                    "pairwiseGainRemovedResidual" to nums(MurmurRoundRobinPlayback.pairwiseResiduals(four)),
                    "pairwiseSpectralDistance" to nums(MurmurRoundRobinPlayback.pairwiseSpectral(four)),
                ))
                println("$tag: takes=$runTakes residual=${MurmurRoundRobinPlayback.pairwiseResiduals(four)} " +
                    "spectral=${MurmurRoundRobinPlayback.pairwiseSpectral(four)}")
            }
            File(out, "measurements.json").writeText(Json.write(JsonValue.Obj(linkedMapOf(
                "kind" to JsonValue.Str("MURMUR_RR_SAVED_PLAYER_AUDITION"),
                "note" to JsonValue.Num(MurmurRoundRobinPlayback.C2.toDouble()),
                "touch" to JsonValue.Num(MurmurRoundRobinPlayback.TOUCH.toDouble()),
                "path" to JsonValue.Str("native cells -> saved bank -> shelf admission -> PCM24 reload -> MurmurRoundRobinPlayer"),
                "policies" to JsonValue.Obj(measured),
            ))) + "\n")
        } finally { work.deleteRecursively() }
    }

    private fun nums(values: List<Double>) = JsonValue.Arr(values.map { JsonValue.Num(it) })

    private fun write(file: File, samples: FloatArray, channels: Int) {
        val peak = samples.maxOf { kotlin.math.abs(it) }
        require(peak in 1e-4f..1f) { "${file.name}: peak $peak" }
        WavWriter.write(file, Snip(samples, channels, MurmurRoundRobinPlayback.RATE), WavWriter.BitDepth.PCM_16)
    }
}
