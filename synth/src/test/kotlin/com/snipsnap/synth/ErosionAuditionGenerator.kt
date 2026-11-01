package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

/** Audio is rendered by the actual saved patch, never synthesized in the web page. */
object ErosionAuditionGenerator {
    private fun json(v: Any?): JsonValue = when(v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Number -> JsonValue.Num(v.toDouble())
        is Map<*,*> -> JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::json))
        else -> error("unsupported JSON")
    }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.first());File(root,"audio").mkdirs();File(root,"recipes").mkdirs()
        val cases=mutableListOf<Pair<String,ErosionPatch>>()
        for(voice in ErosionVoice.entries) {
            val stem=voice.name.lowercase()
            cases += "$stem-default" to ErosionPatch("${voice.name} native",voice)
            for(v in listOf(.25f,.5f,.75f,1f)) cases += "$stem-touch-${(v*100).toInt()}" to ErosionPatch("Touch $v",voice,velocity=v)
            for(name in Erosion.defaults(voice).keys) for(step in listOf(0f,.25f,.5f,.75f,1f))
                cases += "$stem-${name.lowercase()}-${(step*100).toInt()}" to ErosionPatch("$name $step",voice,Erosion.defaults(voice)+(name to step))
            for(voltage in listOf(0f,1f)) for(feed in listOf(0f,1f))
                cases += "$stem-pair-${voltage.toInt()}-${feed.toInt()}" to ErosionPatch("VOLTAGE x FEED",voice,Erosion.defaults(voice)+mapOf("VOLTAGE" to voltage,"FEED" to feed))
        }
        val rows=mutableListOf<Map<String,Any>>()
        val cards=StringBuilder()
        for((id,patch) in cases) {
            val start=System.nanoTime()
            val snip=patch.render()
            val ms=(System.nanoTime()-start)/1e6
            val trace=Erosion.schedule(patch.voice,patch.macros)
            File(root,"recipes/$id.json").writeText(patch.toJsonText())
            WavWriter.write(File(root,"audio/$id-native.wav"),snip,depth=WavWriter.BitDepth.PCM_24)
            // One gain per clip, measured against its loudest 50 ms window.
            var active=0.0
            val window=(Dsp.RATE*.05).toInt()
            for(i in snip.samples.indices step window) {
                val end=min(snip.samples.size,i+window)
                active=max(active,sqrt((i until end).sumOf { snip.samples[it].toDouble().pow(2) }/(end-i)))
            }
            val peak=snip.samples.maxOf { abs(it).toDouble() }
            val gain=min(.08/active.coerceAtLeast(1e-12),.95/peak.coerceAtLeast(1e-12))
            val matched=Snip(FloatArray(snip.samples.size) { (snip.samples[it]*gain).toFloat() },1,Dsp.RATE)
            WavWriter.write(File(root,"audio/$id.wav"),matched,depth=WavWriter.BitDepth.PCM_24)
            val row=mapOf("id" to id,"voice" to patch.voice.name,"velocity" to patch.velocity,"events" to trace.events.size,
                "emitted" to trace.emitted,"conservationError" to trace.conservationError,"seconds" to snip.samples.size.toDouble()/Dsp.RATE,
                "peak" to peak,"mean" to snip.samples.sumOf { it.toDouble() }/snip.samples.size,
                "class" to Classifier.classify(snip).drumClass.name,"localRenderMs" to ms,"matchedGain" to gain)
            rows+=row
            cards.append("<article><h3>$id</h3><audio controls preload='none' src='audio/$id.wav'></audio><p><a href='audio/$id-native.wav'>Native WAV</a> · <a href='recipes/$id.json'>Saved recipe</a></p></article>\n")
        }
        val hashes = listOf("src/main/kotlin/com/snipsnap/synth/Erosion.kt", "src/main/kotlin/com/snipsnap/synth/ErosionPatch.kt",
            "src/main/kotlin/com/snipsnap/synth/Dsp.kt", "src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "../audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt").associateWith { path ->
            MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).joinToString("") { "%02x".format(it) }
        }
        File(root,"manifest.json").writeText(Json.write(json(mapOf("model" to Erosion.MODEL_VERSION,"renderer" to "Kotlin","sourceSha256" to hashes,"renders" to rows))))
        File(root,"index.html").writeText("""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>EROSION native round</title>
<style>body{margin:40px auto;max-width:1100px;padding:20px;background:#131a20;color:#e4edf4;font:16px/1.5 system-ui}a{color:#8cdbd5}article{display:inline-block;vertical-align:top;margin:8px;padding:16px;width:min(290px,calc(100% - 48px));background:#202d36}audio{width:100%}h3{font-size:15px}</style>
<h1>EROSION · native SnipSnap round</h1><p>Charge → irregular discharge → delayed collapse → thinning dust.</p>
<p>The first Python audition was accepted by Josh. This round renders the same parameter laws in Kotlin with the house random generator, filters and output stage. It adds native saved touch and exact event-age sampling. The resulting audio still needs a listen.</p>
<p><a href="../index.html">Original audition</a> · <a href="manifest.json">Native measurements</a></p>
<h2>Listen for</h2><p>Do ARC and CRATER retain their first-round character? Do the soft hits change contact rather than just level? Do low and middle macro steps remain useful? Matched clips use one gain against the loudest 50 ms window; native WAVs retain the engine's output level.</p>
$cards
<h2>Why it is built this way</h2><p>The finite reservoir retains the ending. Exact event ages avoid sample-rounding changes at higher render rates. Softer velocity widens contact and darkens the fluid response without changing the schedule. The original clips remain available because this is a port, not a byte-identical replacement.</p>""")
        println("Rendered ${rows.size} native EROSION cases under ${root.absolutePath}")
    }
}
