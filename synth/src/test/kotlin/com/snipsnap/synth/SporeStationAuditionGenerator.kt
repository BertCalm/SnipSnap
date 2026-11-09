package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.DrumProgram
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Offline source-of-truth for the published audition: no browser synthesizer approximations. */
object SporeStationAuditionGenerator {
    data class Event(val beat: Double, val pad: Int, val velocity: Float)
    fun events(): List<Event> = buildList {
        for (bar in 0..1) {
            val b = bar * 4.0
            listOf(0.0, 1.75, 2.5, 3.75).forEachIndexed { i, t -> add(Event(b+t, if(i==1) 1 else 0, if(i==3) .65f else .9f)) }
            add(Event(b+1,2,1f)); add(Event(b+3,if(bar==0) 2 else 4,.95f))
            listOf(.75,1.5,2.75,3.5).forEach { add(Event(b+it,3,.45f)) }
            for (i in 0..7) add(Event(b+i*.5,if(i==5) 7 else 6,if(i%2==0) .65f else .4f))
            for (i in 0..7) add(Event(b+i*.5+.25,8,.35f))
            add(Event(b+2.25,5,.55f)); add(Event(b+3.25,11,.6f))
        }
        add(Event(7.5,10,.7f)); add(Event(7.75,12,.6f))
    }.sortedBy { it.beat }

    fun groove(pads: List<ArrangedPad>, hybrid: List<ArrangedPad>? = null): Snip {
        val ev = events() + if(hybrid!=null) listOf(Event(3.5,14,.6f),Event(6.75,13,.7f)) else emptyList()
        val frames = (8*60.0/SporeStation.BPM*Dsp.RATE).roundToInt() +
            (pads + hybrid.orEmpty()).maxOf { it.snip.frameCount }
        val out = FloatArray(frames*2)
        val programLevel = DrumProgram("gain",emptyList()).level
        val fade = (.005*Dsp.RATE).roundToInt()
        ev.sortedBy { it.beat }.forEachIndexed { _, event ->
            val bank = if(hybrid != null && event.pad >= 10) hybrid else pads
            val pad = bank[event.pad]
            val midiVelocity = (event.velocity * 127).roundToInt().coerceIn(1,127)
            val snip = when { midiVelocity < 42 && pad.softVariants.isNotEmpty() -> pad.softVariants[0]
                midiVelocity < 85 && pad.softVariants.size>1 -> pad.softVariants[1]
                else -> pad.snip }
            val start = (event.beat*60.0/SporeStation.BPM*Dsp.RATE).roundToInt()
            val chokeBeat = if(pad.muteGroup==1) ev.filter { it.beat>event.beat && pads[it.pad].muteGroup==1 }.minOfOrNull { it.beat } else null
            val choke = chokeBeat?.let { (it*60.0/SporeStation.BPM*Dsp.RATE).roundToInt()-start }
            for(f in 0 until snip.frameCount) {
                if(start+f>=frames || choke!=null && f>=choke+fade) break
                val envelope = if(choke!=null && f>=choke) (1f-(f-choke).toFloat()/fade) else 1f
                for(c in 0..1) out[(start+f)*2+c] += snip.samples[f*snip.channels+minOf(c,snip.channels-1)] *
                    (pad.level ?: 1f)*event.velocity*envelope*programLevel
            }
        }
        return Snip(out,2,Dsp.RATE)
    }
    private fun scale(s: Snip, g: Float) = s.copy(samples=FloatArray(s.samples.size) { s.samples[it]*g })
    private fun json(v: Any?): JsonValue = when(v) {
        null -> JsonValue.Null; is JsonValue -> v; is String -> JsonValue.Str(v); is Boolean -> JsonValue.Bool(v)
        is Number -> JsonValue.Num(v.toDouble()); is Map<*,*> -> JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::json)); else -> error("unsupported $v")
    }
    private fun hash(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun zip(root: File, files: List<File>, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { z -> files.sortedBy { it.path }.forEach { f ->
            z.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath).apply { time=0L }); f.inputStream().use { it.copyTo(z) };z.closeEntry()
        } }
    }
    private fun export(root: File, pads: List<ArrangedPad>, bank: String): Map<String,Any> {
        val name = "Spore Station $bank"
        val work = File(root,"work-$bank").apply { mkdirs() }
        try {
            val kit = KitAssembler.assembleArranged(name,pads,work,tempoBpm=SporeStation.BPM.toFloat())
            val dest = File(root,name)
            val notes=events().map { Mpc3Note(Mpc3Note.noteFor(it.pad+1), (it.beat*Mpc3Clip.PULSES_PER_BEAT).roundToLong(),it.velocity) }
            val result = Mpc3Exporter.exportTrack(kit,work,dest,overwrite=true,mpc2Twin=true,
                clip=Mpc3Clip("Spore Station Two Bars",2,notes))
            val data = File(dest,Mpc3TrackWriter.trackDataDirName(name))
            val exported=KitStore.load(data)
            val checks = ExportReadBack.verify(kit, ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings)) +
                ExportReadBack.verify(kit, ExportOutcome(ExportFormat.PROGRAM_FOLDER, File(data,"$name.xpm"), null, result.findings))
            check(!checks.blocked()) { checks.toString() }
            check(exported.pads.size==16)
            exported.pads.forEachIndexed { i,p ->
                check(p.recipe==pads[i].recipe && p.level==pads[i].level && p.muteGroup==pads[i].muteGroup)
                check(p.velocityLayers.size==3)
                p.velocityLayers.forEachIndexed { zone, layer ->
                    val actual = WavReader.read(File(data, layer.sampleFile))
                    val expected = (pads[i].softVariants + pads[i].snip)[zone]
                    check(actual.frameCount == expected.frameCount)
                    check(actual.samples.indices.maxOf { abs(actual.samples[it]-expected.samples[it]) } <= 3f/8388608f)
                }
                check(File(work,kit.pads[i].sampleFile).readBytes().contentEquals(File(data,p.sampleFile).readBytes()))
            }
            File(dest,"README.txt").writeText("Candidate 170 BPM jungle kit. MPC 3: load the XTD. MPC 2: load the XPM in the TrackData folder. Keep folders intact. Three velocity zones per pad; A07/A08 choke each other. Recipes retained for SnipSnap. Baked echoes do not follow host tempo. Hardware audition pending.\n")
            val target=File(root,"spore-${bank.lowercase()}-mpc.zip")
            zip(root,dest.walkTopDown().filter { it.isFile }.toList(),target)
            return mapOf("name" to bank,"href" to target.name,"sha256" to hash(target))
        } finally { work.deleteRecursively() }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "testkit/spore-station").apply { mkdirs() }
        val clips=mutableListOf<Map<String,Any?>>()
        val started=System.nanoTime()
        val timings=mutableMapOf<String,Double>()
        fun bank(mutation: Boolean): List<ArrangedPad> {
            val start=System.nanoTime(); val result=SporeStation.arranged(mutation)
            timings[if(mutation) "mutationBankMs" else "coreBankMs"]=(System.nanoTime()-start)/1e6;return result
        }
        val core=bank(false); val mutations=bank(true)
        val dry=SporeStation.parts().mapIndexed { i,p ->
            val begin=System.nanoTime();val source=p.patch.render();timings["source${i+1}Ms"]=(System.nanoTime()-begin)/1e6
            core[i].copy(snip=source,softVariants=listOf(.3f,.6f).map { Velocity.peakMatch(source,Velocity.atVelocity(p.patch,it)) })
        }
        fun clip(id: String,title: String,group: String,snip: Snip,recipe: PadRecipe?=null,pad: Int?=null,matchedGain: Float?=null) {
            check(snip.samples.all { it.isFinite() }) { "$id nonfinite" }
            check(snip.peak() in 1e-6f..1.00001f) { "$id peak ${snip.peak()}" }
            val raw=File(root,"audio/$id.wav");WavWriter.write(raw,snip)
            val matched=matchedGain?.let { scale(snip,it) } ?: AuditionLevel.level(snip)
            check(matched.peak()<=1.00001f)
            WavWriter.write(File(root,"audio/$id-matched.wav"),matched)
            val replay=recipe?.let { PadRecipe.fromJsonValue(it.toJsonValue()).render() }
            if(replay!=null) check(replay.samples.contentEquals(snip.samples)) { "$id recipe mismatch" }
            recipe?.let { File(root,"recipes").mkdirs();File(root,"recipes/$id.json").writeText(Json.write(it.toJsonValue())) }
            clips += linkedMapOf("id" to id,"title" to title,"group" to group,"pad" to pad,
                "raw" to "audio/$id.wav","matched" to "audio/$id-matched.wav","seconds" to snip.durationSeconds,
                "peak" to snip.peak(),"dc" to snip.samples.average(),"loudness" to Loudness.of(snip),
                "channels" to snip.channels,"frames" to snip.frameCount,"sha256" to hash(raw),
                "recipe" to recipe?.toJsonValue(),"recipeHref" to recipe?.let { "recipes/$id.json" })
        }
        SporeStation.parts().forEachIndexed { i,p ->
            clip("dry-${i+1}",p.name,"Dry",dry[i].snip,PadRecipe(p.patch),i+1)
            clip("core-${i+1}",p.name,"Core",core[i].snip,SporeStation.recipe(i),i+1)
            clip("mutation-${i+1}",p.name,"Mutations",mutations[i].snip,SporeStation.recipe(i,true),i+1)
        }
        val grooves=listOf(groove(dry),groove(core),groove(mutations),groove(core,mutations))
        val busGain=minOf(1f,.92f/grooves.maxOf { it.peak() })
        val bounces=grooves.map { scale(it,busGain) }
        val matchGain=minOf(.03f/bounces.maxOf { Loudness.of(it) },.99f/bounces.maxOf { it.peak() })
        listOf("Dry sources","Core groove","Mutation groove","Hybrid performance").forEachIndexed { i,n ->
            clip("groove-$i",n,"Grooves",bounces[i],matchedGain=matchGain)
        }
        for(control in listOf("BODY","BITE","DUST","TWIST","SPACE")) for(value in listOf(.1f,.5f,.9f)) {
            val d=SporeStation.Controls();val c=when(control) {
                "BODY" -> d.copy(body=value);"BITE" -> d.copy(bite=value);"DUST" -> d.copy(dust=value)
                "TWIST" -> d.copy(twist=value);else -> d.copy(space=value)
            }
            val r=SporeStation.recipe(12,true,c)
            clip("sweep-${control.lowercase()}-${(value*10).toInt()}","Chrome Seed · $control $value","Sweeps",r.render(),r)
        }
        for(pair in listOf("body-space","bite-dust","twist-space")) for(a in listOf(.1f,.9f)) for(b in listOf(.1f,.9f)) {
            val d=SporeStation.Controls();val c=when(pair) {"body-space"->d.copy(body=a,space=b);"bite-dust"->d.copy(bite=a,dust=b);else->d.copy(twist=a,space=b)}
            val r=SporeStation.recipe(12,true,c)
            clip("pair-$pair-${(a*10).toInt()}-${(b*10).toInt()}","$pair · $a / $b","Interactions",r.render(),r)
        }
        val downloads=listOf(export(root,core,"Core"),export(root,mutations,"Mutations"))
        val manifest=linkedMapOf<String,Any?>("title" to "Spore Station","version" to 1,"bpm" to SporeStation.BPM,
            "baseline" to "c00edb9970d2967b1716a0ece2cd7146bfbe0b14","status" to "candidate-awaiting-owner-listen",
            "sourceSha256" to listOf("synth/src/main/kotlin/com/snipsnap/synth/SporeStation.kt", "synth/src/test/kotlin/com/snipsnap/synth/SporeStationAuditionGenerator.kt").associateWith { hash(File(it)) },
            "sampleRate" to Dsp.RATE,"clips" to clips,"downloads" to downloads,"timings" to timings,
            "renderEnvironment" to "Kotlin/JVM 21 cloud container; not phone timing",
            "groove" to mapOf("bars" to 2,"rawBusGain" to busGain,"matchedGain" to matchGain,"hatChokeMs" to 5,"programLevel" to DrumProgram("gain",emptyList()).level,
                "events" to events().map { mapOf("beat" to it.beat,"pad" to it.pad+1,"velocity" to it.velocity) },
                "hybridExtraEvents" to listOf(mapOf("beat" to 3.5,"pad" to 15,"velocity" to .6),mapOf("beat" to 6.75,"pad" to 14,"velocity" to .7))),
            "checks" to mapOf("recipeReplay" to true,"finite" to true,"pcmPeak" to true,"kitReadback" to true),
            "totalGenerationMs" to (System.nanoTime()-started)/1e6)
        File(root,"manifest.json").writeText(Json.write(json(manifest)))
        File(root,"metrics.csv").writeText("id,peak,dc,loudness,seconds,sha256\n"+clips.joinToString("\n") { c -> listOf("id","peak","dc","loudness","seconds","sha256").joinToString(",") { c[it].toString() } })
        File("docs/superpowers/specs/2026-11-09-spore-station-jungle-kit.md").copyTo(File(root,"spec.md"),overwrite=true)
        File("synth/src/test/resources/audition/spore-station.html").copyTo(File(root,"index.html"),overwrite=true)
        zip(root,root.walkTopDown().filter { it.isFile && (it.parentFile.name in listOf("audio","recipes") || it.name in listOf("manifest.json","metrics.csv","spec.md")) }.toList(),File(root,"spore-listening-pack.zip"))
        println("Spore Station: ${clips.size} clips, two 16-pad/three-layer kits. Output: ${root.absolutePath}")
    }
}
