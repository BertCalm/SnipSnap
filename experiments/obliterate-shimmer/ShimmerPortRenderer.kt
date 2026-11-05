package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Public Kotlin shimmer and complete-chain evidence on retained Float32 inputs. */
object ShimmerPortRenderer {
    @JvmStatic fun main(args: Array<String>) {
        val repo=File(args[0]).canonicalFile
        val root=File(args[1]).canonicalFile
        val manifest=Json.parse(File(root,"reference.json").readText()).obj()
        val inputs=manifest.getValue("sources").arr().associate { item ->
            val s=item.obj();val f=File(root,s.getValue("inputFile").str())
            check(sha(f.readBytes())==s.getValue("inputSha256").str())
            s.getValue("id").str() to Snip(read(f),s.getValue("inputChannels").num().toInt(),ObliterateShimmer.RATE)
        }
        val cases=manifest.getValue("cases").arr().map { item ->
            val c=item.obj();val id=c.getValue("id").str();val input=inputs.getValue(c.getValue("sourceId").str())
            val preSettings=ObliterateShimmer.Settings(c.getValue("mix").num(),c.getValue("pitched").bool())
            val coreSettings=ObliterateCore.Settings(amount=c.getValue("amount").num())
            val before=input.samples.copyOf()
            val pre=ObliterateShimmer.render(input,preSettings,includeWet=true)
            val full=Obliterate.render(input,Obliterate.Settings(preSettings,coreSettings))
            check(before.contentEquals(input.samples)) { "Input mutation" }
            val again=Obliterate.render(input,full.settings)
            check(full.audio.samples.contentEquals(again.audio.samples)) { "Not deterministic" }
            val arrays=mapOf("pre" to pre.audio.samples,"wet" to pre.wet!!.samples,"full" to full.audio.samples)
            val files=arrays.mapValues { (kind,data) ->
                val name="kotlin/$id-$kind.f32";File(root,name).apply { parentFile.mkdirs();writeBytes(bytes(data)) };name
            }
            mapOf("id" to id,"files" to files,"hashes" to files.mapValues { sha(File(root,it.value).readBytes()) },
                "inputFrames" to input.frameCount,"preFrames" to pre.audio.frameCount,"preChannels" to pre.audio.channels,
                "fullFrames" to full.audio.frameCount,"fullChannels" to full.audio.channels,
                "guardGain" to pre.diagnostics.guardGain,"statePeak" to pre.diagnostics.statePeak,
                "last100msStatePeak" to pre.diagnostics.last100msStatePeak,"delayHistorySamples" to pre.diagnostics.delayHistorySamples,
                "model" to full.model,"determinismVerified" to true,"inputUnchanged" to true)
        }
        val benchmark=manifest.getValue("cases").arr().filter { it.obj().getValue("variant").str()=="shimmer15" }.map { item ->
            val c=item.obj();val input=inputs.getValue(c.getValue("sourceId").str());val settings=Obliterate.Settings(
                ObliterateShimmer.Settings(c.getValue("mix").num()),ObliterateCore.Settings(amount=c.getValue("amount").num()))
            repeat(3) { Obliterate.render(input,settings) }
            val preTimes=List(7) { val start=System.nanoTime();ObliterateShimmer.render(input,settings.shimmer);(System.nanoTime()-start)/1e6 }
            val times=List(7) { val start=System.nanoTime();Obliterate.render(input,settings);(System.nanoTime()-start)/1e6 }
            mapOf("sourceId" to c.getValue("sourceId").str(),"shimmerMix" to settings.shimmer.mix,
                "coreAmount" to settings.core.amount,"inputFrames" to input.frameCount,"trials" to 7,"warmups" to 3,
                "shimmerMs" to preTimes,"shimmerMedianMs" to preTimes.sorted()[3],"wholeChainMs" to times,"wholeChainMedianMs" to times.sorted()[3])
        }
        val tracked=listOf("Obliterate.kt","ObliterateShimmer.kt","ObliterateCore.kt","ObliteratePcg64.kt").map {
            File(repo,"synth/src/main/kotlin/com/snipsnap/synth/$it")
        }+listOf("ShimmerPortRenderer.kt","shimmer-port.init.gradle").map { File(repo,"experiments/obliterate-shimmer/$it") }
        val result=mapOf("schema" to 1,"sourceCommit" to git(repo,"rev-parse","HEAD"),
            "workingTreeDirty" to git(repo,"status","--porcelain").isNotEmpty(),
            "sourceHashes" to tracked.associate { it.relativeTo(repo).invariantSeparatorsPath to sha(it.readBytes()) },
            "referenceSha256" to sha(File(root,"reference.json").readBytes()),"cases" to cases,
            "benchmark" to mapOf("meaning" to "Warm host JVM public calls, not Android or phone performance; GC and host load uncontrolled",
                "javaVersion" to System.getProperty("java.version"),"availableProcessors" to Runtime.getRuntime().availableProcessors(),"cases" to benchmark))
        File(root,"kotlin-results.json").writeText(Json.write(json(result))+"\n")
        println("Rendered ${cases.size} public pre-stage/wet/full cases and ${benchmark.size} host benchmarks")
    }
    private fun read(f:File)=ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
    private fun bytes(a:FloatArray)=ByteBuffer.allocate(a.size*4).order(ByteOrder.LITTLE_ENDIAN).also { b -> a.forEach { b.putFloat(it) } }.array()
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun git(repo:File,vararg a:String):String {val p=ProcessBuilder(listOf("git")+a).directory(repo).redirectErrorStream(true).start();val s=p.inputStream.bufferedReader().readText().trim();check(p.waitFor()==0);return s}
    private fun JsonValue.obj()=(this as JsonValue.Obj).entries
    private fun JsonValue.arr()=(this as JsonValue.Arr).items
    private fun JsonValue.str()=(this as JsonValue.Str).value
    private fun JsonValue.num()=(this as JsonValue.Num).value
    private fun JsonValue.bool()=(this as JsonValue.Bool).value
    private fun json(v:Any?):JsonValue=when(v){
        null->JsonValue.Null;is String->JsonValue.Str(v);is Boolean->JsonValue.Bool(v);is Number->JsonValue.Num(v.toDouble())
        is Map<*,*>->JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) });is Iterable<*>->JsonValue.Arr(v.map(::json));else->error("Cannot encode")
    }
}
