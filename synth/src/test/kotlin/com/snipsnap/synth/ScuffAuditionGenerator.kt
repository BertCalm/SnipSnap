package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders the SCUFF round-1 Kotlin clips under testkit/scuff-audition/kotlin/ (gitignored), in the same sections
 * and names as the round-0b Python audition, so `prototype/scuff/compare_kotlin.py` can measure the port against
 * the accepted prototype pad by pad and `build_page.py` can put the pairs on the listening page.
 * Run via `./gradlew :synth:generateScuffAudition`.
 */
object ScuffAuditionGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/scuff-audition/kotlin")
        root.mkdirs()
        var count = 0
        val manifest = mutableListOf<String>()

        fun write(section: String, id: String, snip: Snip) {
            val dir = File(root, section).also { it.mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            manifest += "\"$section/$id.wav\""
            count++
        }
        fun pad(voice: ScuffVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0) =
            Scuff.render(voice, macros, velocity, take)
        fun tag(voice: ScuffVoice) = voice.name.lowercase()

        // The kit at the defaults.
        for (v in ScuffVoice.entries) write("kit", tag(v), pad(v))
        // Velocity on every voice.
        for (v in ScuffVoice.entries) for (vel in listOf(0.4f, 0.7f, 1.0f)) write("velocity", "${tag(v)}-${"%.1f".format(vel)}", pad(v, velocity = vel))
        // Four takes of every voice at velocity 0.8, one file per take.
        for (v in ScuffVoice.entries) for (take in 1..Scuff.MAX_TAKE) write("round-robin", "${tag(v)}-take$take", pad(v, velocity = 0.8f, take = take))
        // The chain one stage at a time on the rim and the clap.
        val chain = listOf(
            "1-dry" to emptySet(), "2-grit" to setOf(Scuff.Stage.GRIT), "3-locker" to setOf(Scuff.Stage.GRIT, Scuff.Stage.LOCKER),
            "4-bark" to setOf(Scuff.Stage.GRIT, Scuff.Stage.LOCKER, Scuff.Stage.BARK), "5-slam" to Scuff.ALL_STAGES,
        )
        for (v in listOf(ScuffVoice.RIM, ScuffVoice.CLAP)) for ((id, stages) in chain) {
            val buf = Scuff.renderPad(v, emptyMap(), 1f, 0, stages)
            write("chain-${tag(v)}", id, Snip(Scuff.finish(buf), 1, Dsp.RATE))
        }
        // The kick and the bass fusing on the bus.
        val pre = setOf(Scuff.Stage.GRIT, Scuff.Stage.LOCKER, Scuff.Stage.BARK)
        val kick = Scuff.renderPad(ScuffVoice.KICK, emptyMap(), 1f, 0, pre)
        val boom = Scuff.renderPad(ScuffVoice.BOOM, emptyMap(), 1f, 0, pre)
        val n = (0.5f * Dsp.RATE).toInt()
        val mix = FloatArray(n)
        for ((buf, gain) in listOf(kick to 1.0f, boom to 0.9f)) {
            val p = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9f)
            for (i in 0 until minOf(n, buf.size)) mix[i] += buf[i] / p * 0.89f * gain
        }
        for (amount in listOf(0f, 0.55f, 0.9f)) {
            val out = if (amount > 0f) Scuff.slamBus(mix, amount) else mix
            write("fuse", "pair-slam-${"%.2f".format(amount)}", Snip(Scuff.finish(out.copyOf(n)), 1, Dsp.RATE))
        }
        File(root, "manifest.json").writeText("{\"model\": ${Scuff.MODEL_VERSION}, \"files\": [\n" + manifest.joinToString(",\n") + "\n]}\n")
        println("wrote $count clips + manifest.json under ${root.absolutePath}")
    }
}
