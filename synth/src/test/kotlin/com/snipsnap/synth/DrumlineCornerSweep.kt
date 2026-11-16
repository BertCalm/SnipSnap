package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.io.File
import kotlin.math.*

/** Explicit long-running numerical audit; independent of listening, alias and hardware gates. */
object DrumlineCornerSweep {
    data class Case(val voice: DrumlineVoice, val corner: Int, val macros: Map<String,Float>, val velocity: Float, val take: Int)
    fun cases(): List<Case> = DrumlineVoice.entries.flatMap { voice ->
        val names=Drumline.macrosFor(voice).map { it.name }
        buildList {
            for(bits in 0 until (1 shl names.size)) {
                val macros=names.mapIndexed { i,name -> name to ((bits shr i) and 1).toFloat() }.toMap()
                for(v in listOf(.05f,1f)) add(Case(voice,bits,macros,v,bits%3))
            }
            add(Case(voice,-1,Drumline.defaults(voice),.6f,1))
        }
    }
    fun violations(snip: Snip): List<String> = buildList {
        val x=snip.samples
        if(snip.sampleRate!=44100 || snip.channels!=1)add("format")
        if(x.isEmpty()) { add("empty");return@buildList }
        if(x.any { !it.isFinite() }) { add("nonfinite");return@buildList }
        if(x.maxOf { abs(it) }>1f)add("peak")
        if(x.maxOf { abs(it) }<=.01f)add("silent")
        if(abs(x.average())>=.005)add("dc")
        if(abs(x.last())!=0f)add("tail")
    }
    @JvmStatic fun main(args: Array<String>) {
        val file=File(args.firstOrNull() ?: "testkit/drumline-corners.csv")
        file.absoluteFile.parentFile.mkdirs()
        var failures=0;var completed=0
        val cases=cases()
        file.bufferedWriter().use { out ->
            out.appendLine("voice,corner,velocity,take,frames,peak,dc,rms,render_ms,result")
            for(c in cases) {
                val patch=DrumlinePatch("Corner",c.voice,c.macros,4)
                val decoded=Patches.fromJsonText(patch.toJsonText())
                check(patch==decoded) { "recipe changed: $c" }
                val start=System.nanoTime()
                val rendered=(decoded as DrumlinePatch).render(c.velocity,c.take)
                val elapsed=(System.nanoTime()-start)/1e6
                val x=rendered.samples
                val problems=violations(rendered)
                if(c.corner==-1)check(x.contentEquals((decoded as DrumlinePatch).render(c.velocity,c.take).samples)) { "non-deterministic ${c.voice}" }
                if(problems.isNotEmpty())failures++
                out.appendLine("${c.voice},${c.corner},${c.velocity},${c.take},${x.size},${x.maxOf { abs(it) }},${x.average()},${sqrt(x.sumOf { it.toDouble()*it }/x.size)},$elapsed,${problems.joinToString("+").ifEmpty { "pass" }}")
                completed++
                if(c.corner==-1) { out.flush();println("${c.voice}: $completed/${cases.size}, failures=$failures") }
            }
        }
        check(failures==0) { "$failures of $completed cases failed; see $file" }
        println("PASS $completed cases, all voices and finite figures")
    }
}
