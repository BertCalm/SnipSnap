package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Host benchmarks and same-passband output-reference checks for all native parade families. */
object ParadeAudit {
    data class Source(val name: String,val render: (Map<String,Float>,Float,Int)->Snip,val raw: (Map<String,Float>,Float,Int)->FloatArray)
    fun sources() = DrumlineVoice.entries.map { v-> Source("drumline/$v",{ m,t,k->Drumline.render(v,m,t,k) },{ m,t,k->Drumline.renderRaw(v,m,t,k) }) } +
        ParadeSamba.Voice.entries.map { v->Source("samba/$v",{ m,t,k->ParadeSamba.render(v,m,t,k) },{ m,t,k->ParadeSamba.renderRaw(v,m,t,k) }) } +
        ParadeSecondLine.Voice.entries.map { v->Source("secondline/$v",{ m,t,k->ParadeSecondLine.render(v,m,t,k) },{ m,t,k->ParadeSecondLine.renderRaw(v,m,t,k) }) }
    fun kitSources(): List<Source> = DrumlineKits.Kit.entries.flatMap { kit -> DrumlineKits.patches(kit).map { p ->
        Source("kit/${kit.id}/${p.voice}",{ m,v,t -> p.copy(macros=p.macros+m).render(v,t) },{ m,v,t -> Drumline.renderRaw(p.voice,p.macros+m,v,t,p.model) })
    } } + SambaKits.patches().map { p -> Source("kit/samba-bateria/${p.voice}",
        { m,v,t -> p.copy(macros=p.macros+m).render(v,t) },{ m,v,t -> ParadeSamba.renderRaw(p.voice,p.macros+m,v,t) }) } +
        SecondlineKits.Kit.entries.flatMap { kit -> SecondlineKits.patches(kit).map { p -> Source("kit/${kit.id}/${p.voice}",
            { m,v,t -> p.copy(macros=p.macros+m).render(v,t) },{ m,v,t -> ParadeSecondLine.renderRaw(p.voice,p.macros+m,v,t,p.model) }) } }
    fun hash(x: FloatArray): String {
        val bytes=ByteBuffer.allocate(x.size*4).order(ByteOrder.LITTLE_ENDIAN)
        for(v in x)bytes.putInt(v.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size==2 && args[0] in setOf("speed","tuned","kit_speed","secondline_speed","alias","kit_alias"))
        val file=File(args[1]);file.parentFile?.mkdirs()
        file.bufferedWriter().use { out->
            if(args[0]=="speed" || args[0]=="tuned" || args[0]=="kit_speed" || args[0]=="secondline_speed") {
                out.appendLine("source,case,initial_ms,median_ms,float_sha256")
                val selected=(if(args[0]=="kit_speed")kitSources() else sources()).filter { args[0] !in listOf("tuned","secondline_speed") || it.name.startsWith("secondline/") }
                check(selected.isNotEmpty()) { "No benchmark sources selected" }
                for(s in selected)for(case in when(args[0]) { "tuned"->listOf("tuned_1p1");"kit_speed"->listOf("kit_recipe");else->listOf("default","soft_damped") }) {
                    val m=when(case) { "default","kit_recipe"->emptyMap();"tuned_1p1"->mapOf("TUNE" to (.5+kotlin.math.log2(1.1)).toFloat());else->mapOf("DAMP" to 1f,"STRIKE" to 0f) }
                    val v=when(case) { "default","kit_recipe"->1f;"tuned_1p1"->.6f;else->.3f };val take=if(case in listOf("default","kit_recipe"))0 else 2
                    // Optional cache reset allows the same harness to benchmark the baseline and optimization.
                    if(s.name.startsWith("secondline/") || s.name.startsWith("kit/second-line")) ParadeSecondLine::class.java.declaredMethods.firstOrNull { it.name.startsWith("clearCrashCache") }?.invoke(ParadeSecondLine)
                    val first=System.nanoTime();s.render(m,v,take);val initial=(System.nanoTime()-first)/1e6
                    val times=mutableListOf<Double>();var pcm=FloatArray(0)
                    repeat(3) { val at=System.nanoTime();pcm=s.render(m,v,take).samples;times+=(System.nanoTime()-at)/1e6 }
                    val row="${s.name},$case,$initial,${times.sorted()[1]},${hash(pcm)}";out.appendLine(row);out.flush();println(row)
                }
            } else {
                out.appendLine("source,case,reference_db,reference_20_18k_db,edge_db,projected_db,decomposition_db")
                for(s in if(args[0]=="kit_alias")kitSources() else sources())for(case in if(args[0]=="kit_alias")listOf("kit_recipe") else listOf("default","bright_open","bright_damped")) {
                    val m=if(case in listOf("default","kit_recipe"))emptyMap() else mapOf("TUNE" to 1f,"STRIKE" to 1f,"DAMP" to if(case=="bright_open")0f else 1f,"PLAYERS" to 1f,"TIGHT" to 1f)
                    val raw=s.raw(m,1f,if(case in listOf("default","kit_recipe"))0 else 2);Tide.bandLimit(raw,176400)
                    val r=DrumlineEdgeReference.measure(raw)
                    check(listOf(r.referenceDb,r.reference20To18kDb,r.edgeDb,r.projectedDb,r.decompositionDb).all { it.isFinite() })
                    check(r.decompositionDb < -110) { "${s.name} $case decomposition $r" }
                    val row="${s.name},$case,${r.referenceDb},${r.reference20To18kDb},${r.edgeDb},${r.projectedDb},${r.decompositionDb}"
                    out.appendLine(row);out.flush();println(row)
                }
            }
        }
    }
}
