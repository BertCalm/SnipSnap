package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.util.Locale
import kotlin.math.*

/** Production Kotlin renders, exact saved recipes, native/matched WAVs and measured diagnostics. */
object TempestAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/tempest-audition").apply { mkdirs() }
        val rows = mutableListOf<String>()
        val manifest = mutableListOf<JsonValue>()
        val html = StringBuilder("<!doctype html><meta charset=utf-8><title>TEMPEST R2</title><h1>TEMPEST R2</h1><p>Digital strike → driven bite → degrading repeats. HOLD reserved. Owner-accepted finite model 2. Compare matched files; native files retain production levels.</p>")
        fun emit(id: String, voice: TempestVoice, macros: Map<String,Float>) {
            val patch = TempestPatch(id,voice,Tempest.settled(voice,macros))
            val start = System.nanoTime()
            val raw = Tempest.synthesize(voice,patch.macros,Dsp.RATE*4)
            val snip = Snip(Tempest.finish(raw),1,Dsp.RATE)
            val ms = (System.nanoTime()-start)/1e6
            WavWriter.write(File(root,"$id.wav"),snip,WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root,"$id-matched.wav"),AuditionLevel.level(snip),WavWriter.BitDepth.PCM_24)
            File(root,"$id.json").writeText(patch.toJsonText())
            manifest += JsonValue.Obj(mapOf("id" to JsonValue.Str(id), "patch" to patch.toJsonValue(),
                "native" to JsonValue.Str("$id.wav"), "matched" to JsonValue.Str("$id-matched.wav")))
            val classified = Classifier.classify(snip).drumClass
            val tailRms = sqrt(raw.takeLast(Dsp.RATE*4/100).sumOf { it.toDouble().pow(2) }/(Dsp.RATE*4/100))
            val pitch=Pitch.detect(snip,fromSec=.12f,windowSec=.15f)
            rows += listOf(id,ms,snip.peak(),snip.samples.average(),Loudness.of(snip),raw.maxOf { abs(it) },tailRms,classified,
                pitch?.hz ?: "",pitch?.confidence ?: "").joinToString(",")
            html.append("<p>$id <audio controls preload=none src='$id-matched.wav'></audio> <a href='$id.wav'>native</a> <a href='$id.json'>recipe</a></p>")
        }
        for (voice in Tempest.APPROVED_VOICES) {
            emit("${voice.name}-default",voice,emptyMap())
            for (tune in listOf(.25f,.5f,1f)) emit("${voice.name}-note-$tune",voice,mapOf("TUNE" to tune))
            for (key in listOf("SOURCE","FILTER","DRIVE","SPACE")) for (x in (if (key=="SOURCE") (0..8).map { it/8f } else listOf(0f,.25f,.5f,.75f,1f)))
                emit("${voice.name}-$key-$x",voice,mapOf(key to x))
            for ((a,b) in listOf("SOURCE" to "DRIVE","FILTER" to "DRIVE","SPACE" to "FILTER"))
                for (x in listOf(0f,1f)) for (y in listOf(0f,1f)) emit("${voice.name}-$a-$x-$b-$y",voice,mapOf(a to x,b to y))
        }
        // Noise-free SOURCE 0 at three DRIVE positions only: noise draws at different sample rates are not
        // the same continuous signal, so their sample differences cannot measure aliasing.
        for (voice in Tempest.APPROVED_VOICES) for ((anchor, driveAmount) in listOf(0f,.5f,1f).withIndex()) {
            val m=mapOf("TUNE" to 1f,"SOURCE" to 0f,"DRIVE" to driveAmount,"FILTER" to 1f,"SPACE" to 0f)
            for (factor in listOf(4,16)) {
                val rate=Dsp.RATE*factor
                val raw=Tempest.synthesize(voice,m,rate)
                WavWriter.write(File(root,"alias-${voice.name}-$anchor-${factor}x.wav"),Snip(FloatArray(raw.size) { raw[it] * .25f },1,rate),WavWriter.BitDepth.PCM_24, allowNonMpcRate = true)
            }
        }
        File(root,"metrics.csv").writeText("id,render_ms,peak,dc,loudness,raw_peak,prefade_tail_rms,capture_class,raw_pitch_hz,raw_pitch_confidence\n"+rows.joinToString("\n"))
        // Performance is recorded, never a flaky correctness assertion or a phone claim.
        val times = mutableListOf<Double>()
        repeat(10) { Tempest.render(TempestVoice.METAL) }
        repeat(100) {
            val start=System.nanoTime(); Tempest.render(TempestVoice.METAL)
            times += (System.nanoTime()-start)/1e6
        }
        times.sort()
        File(root,"timing.txt").writeText(String.format(Locale.ROOT,
            "Kotlin JVM on %s, %s. Warm METAL default, 100 production renders: median %.3f ms; p95 %.3f ms. Not a phone measurement.\n",
            System.getProperty("os.arch"),System.getProperty("java.version"),times[50],times[94]))
        File(root,"manifest.json").writeText(Json.write(JsonValue.Obj(mapOf(
            "engine" to JsonValue.Str("TEMPEST"), "model" to JsonValue.Num(2.0),
            "status" to JsonValue.Str("Owner-accepted model 2; finite integration; HOLD reserved"),
            "cases" to JsonValue.Arr(manifest)))))
        File(root,"index.html").writeText(html.toString())
        println("Wrote ${rows.size} exact Kotlin recipes and native/matched pairs to ${root.absolutePath}")
    }
}
