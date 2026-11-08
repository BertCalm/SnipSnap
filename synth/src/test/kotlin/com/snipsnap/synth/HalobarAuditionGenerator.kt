package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.util.Locale
import kotlin.math.*

/** Native finite one-shots, references, playable phrases and uncached timing evidence. */
object HalobarAuditionGenerator {
    private data class Clip(val id: String,val title: String,val group: String,val file: String,val metrics: Map<String,Any?>)
    private fun json(x: Any?): JsonValue = when(x) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(x)
        is Boolean -> JsonValue.Bool(x)
        is Number -> JsonValue.Num(x.toDouble())
        is Map<*,*> -> JsonValue.Obj(x.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(x.map(::json))
        else -> error("unsupported audition value ${x.javaClass}")
    }
    private fun rms(x: FloatArray)=sqrt(x.sumOf { it.toDouble()*it }/x.size.coerceAtLeast(1))
    private fun db(x: Double)=20*log10(max(x,1e-12))
    private fun escape(x: String)=x.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;")

    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "../testkit/halobar-audition").apply { mkdirs() }
        val clips=mutableListOf<Clip>()
        fun clip(voice: HalobarVoice,m: Map<String,Float>,id: String,title: String,group: String,measurePitch: Boolean=false,velocity: Float=1f) {
            val start=System.nanoTime()
            val p=Halobar.renderProbe(voice,m,velocity=velocity,normalize=true)
            val milliseconds=(System.nanoTime()-start)/1e6
            val snip=Snip(p.samples,1,Halobar.RATE)
            val path="$group/$id.wav"
            File(root,"recipes/$id.patch").apply { parentFile.mkdirs(); writeText(HalobarPatch(title,voice,Halobar.settled(m,voice)).toJsonText()) }
            WavWriter.write(File(root,path),snip,WavWriter.BitDepth.PCM_16)
            if(group=="registers") {
                // A fixed -12 dB reference preserves relative source levels while
                // PCM remains bounded. The manifest records the fixed gain.
                val reference=FloatArray(p.samples.size) { (.25*p.samples[it]/p.outputGain).toFloat() }
                WavWriter.write(File(root,"reference/$id.wav"),Snip(reference,1,Halobar.RATE),WavWriter.BitDepth.PCM_24)
            }
            val pitch=if(measurePitch)Pitch.detect(snip) else null
            val row=linkedMapOf<String,Any?>("id" to id,"voice" to voice.name,"velocity" to velocity,"midi" to p.plan.midi,
                "frames" to snip.frameCount,"seconds" to snip.durationSeconds,"peak" to snip.peak(),
                "mean" to p.samples.sumOf { it.toDouble() }/p.samples.size,"loudness" to Loudness.of(snip),
                "rawPeak" to p.rawPeak,"outputGain" to p.outputGain,"loopPeak" to p.loopPeak,"kernelL1" to p.kernelL1,
                "pitchHz" to pitch?.hz,"pitchConfidence" to pitch?.confidence,
                "rootErrorCents" to pitch?.let { 1200*log2(it.hz/p.plan.hz) },
                "contactMs" to p.plan.contactSeconds*1000,"sourceFrames" to p.plan.sourceFrames,
                "delayFrames" to p.plan.delayFrames,"headFrames" to p.plan.headFrames,"loopGain" to p.plan.loopGain,
                "loopCutoffHz" to p.plan.loopCutoffHz,"sourceCapped" to p.plan.sourceCapped,"tailCapped" to p.plan.tailCapped,
                "endingDbfsBeforeFade" to db(p.endingRmsBeforeFade),"uncachedRenderMs" to milliseconds,
                "macros" to Halobar.settled(m,voice),"model" to Halobar.MODEL_VERSION)
            clips+=Clip(id,title,group,path,row)
            println("$id: ${p.samples.size} frames, ${"%.1f".format(Locale.ROOT,milliseconds)} ms")
        }
        for(v in HalobarVoice.entries) for((midi,note) in listOf(if(v==HalobarVoice.XYLOPHONE)57 to "A3" else 53 to "F3",65 to "F4",77 to "F5")) for((space,kind) in listOf(0f to "dry",.35f to "dub"))
            clip(v,mapOf("TUNE" to Halobar.tuneForMidi(v,midi),"SPACE" to space),"${v.name.lowercase()}-$note-$kind","${v.name} $note $kind","registers",true)
        for(v in HalobarVoice.entries) for(name in Halobar.defaults(v).keys) for(value in listOf(0f,.5f,1f))
            clip(v,mapOf(name to value),"${v.name.lowercase()}-${name.lowercase()}-$value","${v.name} $name $value","macros")
        for((a,b) in listOf("TOUCH" to "SPACE","RETURN" to "SPACE","RATE" to "RETURN","MOTION" to "RETURN","HOLD" to "RETURN"))
            for(x in listOf(0f,1f)) for(y in listOf(0f,1f))
                clip(HalobarVoice.XYLOPHONE,mapOf(a to x,b to y),"${a.lowercase()}-$x-${b.lowercase()}-$y","XYLOPHONE $a $x / $b $y","pairs")
        for(v in HalobarVoice.entries) for(reference in HalobarPresets.forVoice(v))
            clip(v,reference.macros,"${v.name.lowercase()}-${reference.name.lowercase().replace(' ','-')}","${v.name} ${reference.name}","references")
        for(v in HalobarVoice.entries) {
            for(value in listOf(0f,.5f,1f))
                clip(v,Halobar.defaults(v).mapValues { value },"${v.name.lowercase()}-all-$value","${v.name} all controls $value","edges")
            for(value in listOf(0f,.25f,.5f,.75f,1f))
                clip(v,emptyMap(),"${v.name.lowercase()}-velocity-$value","${v.name} velocity $value","velocity",velocity=value)
        }
        for((i,pad) in SynthKits.halobar().withIndex()) {
            val patch=PadRecipe.fromJsonValue(requireNotNull(pad?.recipe)).patch as HalobarPatch
            clip(patch.voice,patch.macros,"pad-${i+1}","Pad ${i+1}: ${patch.name}","kit")
        }
        val voices=HalobarVoice.entries
        val phraseFiles=mutableListOf<Map<String,Any?>>()
        val all=mutableListOf<FloatArray>()
        val focused=mutableListOf<FloatArray>()
        for(v in voices) {
            val dry=phrase(v,false);val dub=phrase(v,true)
            val peak=max(dry.maxOf { abs(it) },dub.maxOf { abs(it) })
            val gain=min(1f,.9f/peak)
            for((kind,raw) in listOf("dry" to dry,"dub" to dub)) {
                val samples=FloatArray(raw.size) { raw[it]*gain }
                val name="phrases/${v.name.lowercase()}-$kind.wav"
                WavWriter.write(File(root,name),Snip(samples,1,Halobar.RATE),WavWriter.BitDepth.PCM_16)
                phraseFiles+=mapOf("voice" to v.name,"kind" to kind,"file" to name,
                    "startsAtSeconds" to all.sumOf { it.size }.toDouble()/Halobar.RATE)
                all+=samples;all+=FloatArray(Halobar.RATE/2)
                if(v==HalobarVoice.XYLOPHONE) { focused+=samples;focused+=FloatArray(Halobar.RATE/2) }
            }
        }
        fun sequence(parts: List<FloatArray>): FloatArray {
            val result=FloatArray(parts.sumOf { it.size });var offset=0
            for(part in parts) { part.copyInto(result,offset);offset+=part.size }
            return result
        }
        WavWriter.write(File(root,"START-HERE.wav"),Snip(sequence(focused),1,Halobar.RATE),WavWriter.BitDepth.PCM_16)
        WavWriter.write(File(root,"ALL-VOICES.wav"),Snip(sequence(all),1,Halobar.RATE),WavWriter.BitDepth.PCM_16)

        val performance=voices.map { v ->
            repeat(8) { Halobar.renderProbe(v,normalize=true) }
            val times=(0 until 30).map { i ->
                val start=System.nanoTime()
                Halobar.renderProbe(v,mapOf("SPACE" to (.35f+(i%3)*.01f)),normalize=true)
                (System.nanoTime()-start)/1e6
            }.sorted()
            mapOf("voice" to v.name,"samples" to times,"medianMs" to times[15],"p95Ms" to times[28],
                "targetMs" to 50,"budgetMet" to (times[28]<=50),"completedAudioCache" to false)
        }
        // Every SPACE value is new: completed-audio hits cannot satisfy this
        // playability measurement. Only the private prepared stems are reused.
        val spacePerformance=voices.map { v ->
            Halobar.clearCache()
            repeat(8) { i -> Halobar.render(v,mapOf("SPACE" to (.2f+i*.001f))) }
            val times=(0 until 30).map { i ->
                val start=System.nanoTime()
                Halobar.render(v,mapOf("SPACE" to (.4f+i*.003f)))
                (System.nanoTime()-start)/1e6
            }.sorted()
            mapOf("voice" to v.name,"samples" to times,"medianMs" to times[15],"p95Ms" to times[28],
                "targetMs" to 50,"budgetMet" to (times[28]<=50),"completedAudioCache" to false,
                "preparedStemCache" to true,"control" to "SPACE")
        }
        val manifest=mapOf("engine" to "HALOBAR","model" to Halobar.MODEL_VERSION,"bpm" to Halobar.BPM,
            "status" to "Owner accepted native audition on 2026-11-08; twelve unchanged factory recipes; finite notes",
            "runtime" to System.getProperty("java.runtime.version"),"processors" to Runtime.getRuntime().availableProcessors(),
            "referenceGain" to .25,"performance" to performance,"spacePerformance" to spacePerformance,"phrases" to phraseFiles,
            "clips" to clips.map { it.metrics+mapOf("file" to it.file,"title" to it.title,"group" to it.group) })
        File(root,"manifest.json").writeText(Json.write(json(manifest)))
        File(root,"metrics.csv").printWriter().use { writer ->
            writer.println("id,voice,midi,seconds,raw_peak,output_peak,pitch_hz,confidence,source_capped,tail_capped,render_ms")
            for(c in clips)with(c.metrics) { writer.println(listOf(get("id"),get("voice"),get("midi"),get("seconds"),get("rawPeak"),get("peak"),get("pitchHz"),get("pitchConfidence"),get("sourceCapped"),get("tailCapped"),get("uncachedRenderMs")).joinToString(",")) }
        }
        val cards=listOf("registers","macros","pairs","references","edges","velocity","kit").joinToString("\n") { group ->
            "<details ${if(group=="registers")"open" else ""}><summary>${escape(group.replaceFirstChar { it.uppercase() })}</summary>"+
                clips.filter { it.group==group }.joinToString("\n") { "<article><h3>${escape(it.title)}</h3><audio controls preload=\"none\" src=\"${it.file}\"></audio>"+
                    (if(group=="registers")"<p><a href=\"reference/${it.id}.wav\">Fixed-gain 24-bit reference</a></p>" else "")+"</article>" }+"</details>"
        }
        val timings=performance.joinToString("") { "<tr><td>${it["voice"]}</td><td>${"%.1f".format(Locale.ROOT,it["medianMs"])}</td><td>${"%.1f".format(Locale.ROOT,it["p95Ms"])}</td><td>${it["budgetMet"]}</td></tr>" }
        val spaceTimings=spacePerformance.joinToString("") { "<tr><td>${it["voice"]}</td><td>${"%.1f".format(Locale.ROOT,it["medianMs"])}</td><td>${"%.1f".format(Locale.ROOT,it["p95Ms"])}</td><td>${it["budgetMet"]}</td></tr>" }
        val template=checkNotNull(javaClass.getResource("/audition/halobar-audition.html")) { "HALOBAR audition template missing" }.readText()
        File(root,"index.html").writeText(template.replace("{{TIMINGS}}",timings).replace("{{SPACE_TIMINGS}}",spaceTimings).replace("{{CARDS}}",cards))
        println(Json.write(json(mapOf("clips" to clips.size,"performance" to performance,"spacePerformance" to spacePerformance))))
    }

    private fun phrase(voice: HalobarVoice,dub: Boolean): FloatArray {
        val beat=60.0/Halobar.BPM
        val out=FloatArray(ceil((5.25*beat+Halobar.MAX_SECONDS+.25)*Halobar.RATE).toInt())
        val notes=listOf(Triple(0.0,60,.8f),Triple(.75,67,.66f),Triple(1.5,63,.72f),
            Triple(2.75,70,.58f),Triple(3.5,67,.73f),Triple(5.25,60,.70f))
        for((whenBeat,midi,velocity) in notes) {
            val m=mapOf("TUNE" to Halobar.tuneForMidi(voice,midi),"SPACE" to if(dub).68f else 0f,
                "RETURN" to .62f,"MOTION" to .45f)
            val samples=Halobar.render(voice,m,velocity).samples
            val offset=(whenBeat*beat*Halobar.RATE).roundToInt()
            for(i in 0 until min(samples.size,out.size-offset))out[offset+i]+=samples[i]*sqrt(velocity)
        }
        return out
    }
}
