package com.snipsnap.synth

import com.snipsnap.audio.*
import java.io.File
import kotlin.math.*

/** Source revision study, deliberately outside saved model 4 and its 1.49-second ceiling. */
object BackwashPercussionRevisionAudition {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/backwash-percussion-revision-2").apply { mkdirs() }
        val matching = StringBuilder("voice,stage,target,measured,gain,peak\n")
        val metrics = StringBuilder("voice,stage,seconds,lateAfter200ms,lateAfter1s,renderMs\n")
        for (patch in BackwashPresets.kitOrder().takeLast(4)) {
            val id = patch.voice.name.lowercase()
            val m = patch.macros
            val start = System.nanoTime()
            val dry = BackwashPercussionRevision.render(patch.voice, m.getValue("TUNE"), m.getValue("DAMP"), 1f)
            val ms = (System.nanoTime() - start) / 1e6
            check(dry.samples.contentEquals(BackwashPercussionRevision.render(patch.voice, m.getValue("TUNE"), m.getValue("DAMP"), 1f).samples))
            for (v in listOf(.3f, .6f)) {
                val soft = BackwashPercussionRevision.render(patch.voice, m.getValue("TUNE"), m.getValue("DAMP"), v)
                check(soft.samples.all { it.isFinite() && abs(it) <= 1f })
            }
            val moving = Backwash.chorus(dry, .55f, .55f, patch.voice)
            val pressed = Backwash.press(moving, .75f)
            // Same amp mapping as the previous DRIVE=.15 comparison, with the full source tail retained.
            val amp = Valve.process(pressed, mapOf("DRIVE" to Dsp.lin(.15f, .32f, .65f), "SAG" to .2f, "TONE" to .48f, "CAB" to .55f))
            val out = amp.samples.copyOf()
            val mean = out.average().toFloat(); val dc = Dsp.FixedOnePole(20f, Dsp.RATE)
            for (i in out.indices) { val x = out[i] - mean; out[i] = x - dc.lp(x) }
            Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET); Dsp.fadeTail(out)
            val raw = linkedMapOf("previous-dry" to Backwash.source(patch.voice, m), "dry" to dry,
                "lower-drive" to Snip(out, 1, Dsp.RATE))
            fun late(s: Snip, seconds: Double): Double {
                val energy = s.samples.sumOf { it.toDouble() * it }
                return s.samples.drop((seconds * Dsp.RATE).toInt()).sumOf { it.toDouble() * it } / energy
            }
            if (patch.voice == BackwashVoice.RIDE) check(late(dry, 1.0) > .001)
            if (patch.voice == BackwashVoice.TAMBOURINE) check(late(dry, .2) > .05)
            if (patch.voice == BackwashVoice.COWBELL) check(late(dry, .2) > .03)
            val levels = raw.mapValues { Loudness.of(it.value) }
            val target = minOf(Dsp.MELODIC_LOUDNESS_TARGET, raw.minOf { (key,s) -> .85f * levels.getValue(key) / s.peak() })
            for ((stage,s) in raw) {
                val gain = target / levels.getValue(stage)
                val matched = Snip(FloatArray(s.samples.size) { s.samples[it] * gain },1,Dsp.RATE)
                check(matched.samples.all { it.isFinite() && abs(it) <= .85001f })
                check(abs(Loudness.of(matched) - target) < 1e-5)
                WavWriter.write(File(root,"$id-$stage.wav"), matched,WavWriter.BitDepth.PCM_24)
                matching.append("$id,$stage,$target,${Loudness.of(matched)},$gain,${matched.peak()}\n")
                metrics.append("$id,$stage,${s.samples.size.toDouble()/Dsp.RATE},${late(s,.2)},${late(s,1.0)},$ms\n")
            }
        }
        File(root,"matching.csv").writeText(matching.toString())
        File(root,"metrics.csv").writeText(metrics.toString())
        println("PASS: 12 matched clips; deterministic sources, three velocity checks, and sustained cowbell/ride/rattle energy.")
    }
}
