package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** An isolated, provisional fourth-voice audition. Accepted foundation assets stay fixed. */
object HalobarGlockenspielAuditionGenerator {
    private val voice=HalobarVoice.GLOCKENSPIEL
    private fun json(x: Any?): JsonValue = when(x) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(x)
        is Boolean -> JsonValue.Bool(x)
        is Number -> JsonValue.Num(x.toDouble())
        is Map<*,*> -> JsonValue.Obj(x.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(x.map(::json))
        else -> error("Unsupported manifest value ${x.javaClass}")
    }
    private fun sha(samples: FloatArray): String {
        val bytes=ByteBuffer.allocate(samples.size*4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
    private fun escape(s: String)=s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;")
    private fun number(x: Any?)="%.1f".format(Locale.ROOT,x)

    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "../testkit/halobar-audition/glockenspiel").apply { mkdirs() }
        val rows=mutableListOf<Map<String,Any?>>()
        fun clip(id: String,title: String,group: String,m: Map<String,Float>,v: Float=1f,
            source: HalobarVoice=voice,pitch: Boolean=false) {
            val p=Halobar.renderProbe(source,m,velocity=v,normalize=true)
            val file="$group/$id.wav"
            WavWriter.write(File(root,file),Snip(p.samples,1,Halobar.RATE),WavWriter.BitDepth.PCM_16)
            val recipe="recipes/$id.patch"
            File(root,recipe).apply { parentFile.mkdirs();writeText(HalobarPatch(title,source,Halobar.settled(m,source)).toJsonText()) }
            val factor=if(p.plan.hz>1100)2 else 1
            val estimate=if(pitch)Pitch.detect(Snip(p.samples,1,Halobar.RATE/factor),
                fromSec=.05f*factor,windowSec=.25f*factor) else null
            val hz=estimate?.let { it.hz*factor }
            rows+=linkedMapOf("id" to id,"title" to title,"group" to group,"file" to file,"recipe" to recipe,
                "voice" to source.name,"velocity" to v,"model" to Halobar.MODEL_VERSION,"midi" to p.plan.midi,
                "macros" to Halobar.settled(m,source),"frames" to p.samples.size,"seconds" to p.samples.size.toDouble()/Halobar.RATE,
                "rawPeak" to p.rawPeak,"peak" to p.samples.maxOf { abs(it) },"mean" to p.samples.sumOf { it.toDouble() }/p.samples.size,
                "loudness" to Loudness.of(Snip(p.samples,1,Halobar.RATE)),"contactMs" to p.plan.contactSeconds*1000,
                "modeT60" to p.plan.modeT60,"sourceCapped" to p.plan.sourceCapped,"tailCapped" to p.plan.tailCapped,
                "endingRmsBeforeFade" to p.endingRmsBeforeFade,"loopPeak" to p.loopPeak,
                "pitchHz" to hz,"pitchConfidence" to estimate?.confidence,"pitchAnalysisRate" to Halobar.RATE/factor,
                "rootErrorCents" to hz?.let { 1200*log2(it/p.plan.hz) },"float32Sha256" to sha(p.samples))
        }
        for((midi,note) in listOf(65 to "F4",77 to "F5",89 to "F6")) for((space,kind) in listOf(0f to "dry",.35f to "dub"))
            clip("glockenspiel-$note-$kind","Glockenspiel $note $kind","registers",
                mapOf("TUNE" to Halobar.tuneForMidi(voice,midi),"SPACE" to space),pitch=true)
        for(name in Halobar.defaults(voice).keys) for(value in listOf(0f,.5f,1f))
            clip("${name.lowercase()}-$value","$name $value","macros",mapOf(name to value))
        for((a,b) in listOf("TOUCH" to "SPACE","RETURN" to "SPACE","RATE" to "RETURN","MOTION" to "RETURN","HOLD" to "RETURN"))
            for(x in listOf(0f,1f)) for(y in listOf(0f,1f))
                clip("${a.lowercase()}-$x-${b.lowercase()}-$y","$a $x / $b $y","pairs",mapOf(a to x,b to y))
        for(reference in HalobarPresets.auditionFor(voice))
            clip(reference.name.lowercase().replace(' ','-'),"Study: ${reference.name}","studies",reference.macros)
        for(value in listOf(0f,.5f,1f))
            clip("all-$value","All controls $value","edges",Halobar.defaults(voice).mapValues { value })
        for(midi in listOf(60,96)) for(space in listOf(0f,.35f))
            clip("note-$midi-space-$space","${if(midi==60)"C4" else "C7"} / SPACE $space","edges",
                mapOf("TUNE" to Halobar.tuneForMidi(voice,midi),"SPACE" to space),pitch=true)
        for(v in listOf(0f,.25f,.5f,.75f,1f)) clip("velocity-$v","Strike velocity $v","velocity",emptyMap(),v)
        for(other in HalobarPresets.acceptedVoices) for(space in listOf(0f,.35f))
            clip("${other.name.lowercase()}-F5-space-$space","${other.name} F5 / SPACE $space","comparison",
                mapOf("TUNE" to Halobar.tuneForMidi(other,77),"SPACE" to space),source=other,pitch=true)

        // Common gain for the phrase pair; overlap is the sampler mixing finite
        // native notes, rather than a second synthesis or reverb implementation.
        fun phrase(dub: Boolean): FloatArray {
            val beat=60.0/Halobar.BPM
            val out=FloatArray(ceil((5.25*beat+Halobar.MAX_SECONDS+.25)*Halobar.RATE).toInt())
            val notes=listOf(Triple(0.0,72,.8f),Triple(.75,79,.66f),Triple(1.5,75,.72f),
                Triple(2.75,82,.58f),Triple(3.5,79,.73f),Triple(5.25,72,.70f))
            for((at,midi,v) in notes) {
                val samples=Halobar.render(voice,mapOf("TUNE" to Halobar.tuneForMidi(voice,midi),
                    "SPACE" to if(dub).68f else 0f,"RETURN" to .62f,"MOTION" to .45f),v).samples
                val offset=(at*beat*Halobar.RATE).roundToInt()
                for(i in 0 until min(samples.size,out.size-offset))out[offset+i]+=samples[i]*sqrt(v)
            }
            return out
        }
        val dry=phrase(false);val dub=phrase(true)
        val gain=min(1f,.9f/max(dry.maxOf { abs(it) },dub.maxOf { abs(it) }))
        for((kind,samples) in listOf("dry" to dry,"dub" to dub))
            WavWriter.write(File(root,"phrases/glockenspiel-$kind.wav"),Snip(FloatArray(samples.size) { samples[it]*gain },1,Halobar.RATE),WavWriter.BitDepth.PCM_16)
        val sequence=FloatArray(dry.size+dub.size+Halobar.RATE/2)
        for(i in dry.indices)sequence[i]=dry[i]*gain
        for(i in dub.indices)sequence[dry.size+Halobar.RATE/2+i]=dub[i]*gain
        WavWriter.write(File(root,"START-HERE.wav"),Snip(sequence,1,Halobar.RATE),WavWriter.BitDepth.PCM_16)

        fun timing(prepared: Boolean): Map<String,Any?> {
            Halobar.clearCache()
            repeat(8) { i -> if(prepared)Halobar.render(voice,mapOf("SPACE" to (.2f+i*.001f))) else Halobar.renderProbe(voice,normalize=true) }
            val times=(0 until 30).map { i ->
                val start=System.nanoTime();val m=mapOf("SPACE" to (.4f+i*.003f))
                if(prepared)Halobar.render(voice,m) else Halobar.renderProbe(voice,m,normalize=true)
                (System.nanoTime()-start)/1e6
            }.sorted()
            return mapOf("samples" to times,"medianMs" to times[15],"p95Ms" to times[28],"budgetMs" to 50,
                "budgetMet" to (times[28]<=50),"preparedStemCache" to prepared,"completedAudioCache" to false)
        }
        val fresh=timing(false);val space=timing(true)
        val work=File(root,".kit-work")
        val kit=KitAssembler.assembleArranged("SnipSnap Halobar Glockenspiel Study",SynthKits.halobarGlockenspielStudy(),work)
        val exported=KitExporter.exportProgramFolder(kit,work,File(root,"mpc"),overwrite=true)
        work.deleteRecursively()
        ZipOutputStream(File(root,"Glockenspiel-Study-Kit.zip").outputStream()).use { zip ->
            for(file in exported.directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                zip.putNextEntry(ZipEntry(exported.directory.name+"/"+file.relativeTo(exported.directory).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val manifest=mapOf("engine" to "HALOBAR","voice" to voice.name,"model" to Halobar.MODEL_VERSION,
            "status" to "Provisional glockenspiel; native listening pending; original twelve factory presets remain accepted",
            "remainingPlannedVoices" to listOf("CROTALES","TUBULAR_BELLS"),"bpm" to Halobar.BPM,
            "runtime" to System.getProperty("java.runtime.version"),"processors" to Runtime.getRuntime().availableProcessors(),
            "pitchRangeMidi" to listOf(60,96),"pitchMidpointMidi" to 72,"freshPerformance" to fresh,
            "spacePerformance" to space,"clips" to rows,"phraseGain" to gain,"finiteNotes" to true)
        File(root,"manifest.json").writeText(Json.write(json(manifest)))
        File(root,"metrics.csv").printWriter().use { w ->
            w.println("id,midi,velocity,seconds,raw_peak,peak,pitch_hz,confidence,root_error_cents")
            rows.forEach { r -> w.println(listOf("id","midi","velocity","seconds","rawPeak","peak","pitchHz","pitchConfidence","rootErrorCents").joinToString(",") { r[it].toString() }) }
        }
        val cards=listOf("registers","studies","comparison","macros","pairs","edges","velocity").joinToString("\n") { group ->
            "<details ${if(group=="registers")"open" else ""}><summary>${escape(group.replaceFirstChar { it.uppercase() })}</summary>"+
                rows.filter { it["group"]==group }.joinToString("\n") { r ->
                    "<article><h3>${escape(r["title"].toString())}</h3><audio controls preload=\"none\" src=\"${r["file"]}\"></audio>"+
                        "<p><a href=\"${r["recipe"]}\">Source recipe</a></p></article>"
                }+"</details>"
        }
        val template=checkNotNull(javaClass.getResource("/audition/halobar-glockenspiel.html")).readText()
        File(root,"index.html").writeText(template.replace("{{CARDS}}",cards)
            .replace("{{FRESH_MEDIAN}}",number(fresh["medianMs"])).replace("{{FRESH_P95}}",number(fresh["p95Ms"]))
            .replace("{{SPACE_MEDIAN}}",number(space["medianMs"])).replace("{{SPACE_P95}}",number(space["p95Ms"])))
        println(Json.write(json(mapOf("clips" to rows.size,"fresh" to fresh,"space" to space))))
    }
}
