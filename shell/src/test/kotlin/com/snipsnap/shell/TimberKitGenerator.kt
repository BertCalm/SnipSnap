package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.TimberKits
import java.io.File
import kotlin.math.*

/** A listening pack made from the saved and exported chain PCM, using the real pad resolver. */
object TimberKitGenerator {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.firstOrNull()?:"testkit/timber-kits").absoluteFile.apply{mkdirs()}
        val contacts=File(root,"contacts").apply { mkdirs() }
        for(ins in TimberKits.Instrument.entries) for(gesture in TimberKits.Gesture.entries) {
            WavWriter.write(File(contacts,"${ins.name.lowercase()}-${gesture.name.lowercase()}.wav"),
                TimberKits.contact(ins,gesture,1f,0),WavWriter.BitDepth.PCM_24)
        }
        val html=StringBuilder("""<!doctype html><meta charset="utf-8"><title>Timber acoustic percussion</title>
<style>body{background:#171c19;color:#f1eadc;font:17px system-ui;max-width:1100px;margin:40px auto;padding:20px}h1{font-size:44px}h2{margin-top:60px}article{background:#29312b;padding:18px;border-radius:10px}section{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px}audio{width:100%}p{line-height:1.6}small{color:#b6c4b8}</style>
<h1>TIMBER</h1><p>Acoustic instrument percussion: guitar, acoustic bass guitar, cello and upright bass.<br>Procedural models, no recorded samples. Three strike levels and four contact takes per pad.</p>
<p>Listen for wood, strings and fingerboard contact in the drum roles. Each kit has a different core instrument arrangement. Touch strips play soft, medium, hard, four strikes each. The groove uses saved/exported PCM through SnipSnap's pad resolver.</p>
<p><strong>Software evidence only.</strong> Phone and MPC hardware playback and owner sound acceptance remain open. MPC 2 preserves velocity but takes the first strike per zone; MPC 3 cycle metadata passes read-back, with the hardware slice-map limitation recorded in the repository.</p>""")
        val csv=StringBuilder("genre,slot,name,peak,soft_hard_shape_db,closest_take_shape_db,render_ms_per_strike,save_playback_mpc3\n")
        for(g in TimberKits.Genre.entries) {
            val folder=File(root,g.id).apply{mkdirs()}
            val native=File(folder,"native")
            val started=System.nanoTime(); val pads=TimberKits.build(g)
            val renderMs=(System.nanoTime()-started)/1e6/192
            val kit=KitAssembler.assembleArranged(g.title,pads,native,tempoBpm=g.bpm.toFloat())
            val saved=KitStore.load(native)
            TriadKitProof.verify(saved,native,pads)
            val export=Mpc3Exporter.exportTrack(saved,native,File(folder,"mpc3"),overwrite=true)
            val data=File(export.directory,Mpc3TrackWriter.trackDataDirName(saved.name))
            TriadKitProof.verify(saved,data,pads)
            TriadKitProof.verifyTrack(export.program,saved)
            KitExporter.exportProgramFolder(saved,native,File(folder,"mpc2"),overwrite=true)
            val wavs=saved.pads.associate { it.slot to WavReader.read(File(data,it.sampleFile)) }
            fun play(slot:Int,v:Float,index:Int):FloatArray {
                val pad=saved.pads.single{it.slot==slot}; val wav=wavs.getValue(slot)
                val h=requireNotNull(PadHit.resolve(pad,v,index){wav.frameCount.toLong()})
                check(h.pitchRatio==1.0 && h.gainLeft==h.gainRight)
                return FloatArray((h.endFrameExclusive-h.startFrame).toInt()) { wav.samples[h.startFrame.toInt()+it]*h.gainLeft }
            }
            fun write(name:String,x:FloatArray)=WavWriter.write(File(folder,name),Snip(x,1,44100),WavWriter.BitDepth.PCM_24)
            val beat=60.0/g.bpm*44100
            val groove=FloatArray((beat*8+44100).roundToInt())
            val counts=IntArray(16)
            data class Event(val slot:Int,val pos:Double,val v:Float)
            val events=mutableListOf<Event>()
            for(bar in 0..1) {
                val offset=bar*4.0
                val kicks=when(g) { TimberKits.Genre.POCKET->listOf(0.0,2.0,2.75); TimberKits.Genre.BOOM_BAP->listOf(0.0,1.75,2.5); else->listOf(0.0,1.5,2.75) }
                kicks.forEach { events+=Event(1,offset+it,1f) }
                listOf(1.0,3.0).forEach { events+=Event(3,offset+it,1f) }
                for(i in 0..7) {
                    val swing=if(g==TimberKits.Genre.BOOM_BAP && i%2==1) .07 else if(g==TimberKits.Genre.POCKET && i%2==1) .025 else 0.0
                    events+=Event(if(i==7)4 else 5,offset+i*.5+swing,if(i%2==0).65f else .3f)
                }
                events+=Event(8,offset+2.5,.65f)
                events+=Event(10,offset+3.5,.3f)
                if(g==TimberKits.Genre.TWO_STEP) {
                    events+=Event(3,offset+2.75,.3f); events+=Event(11,offset+3.75,.65f)
                }
            }
            val ordered=events.sortedBy{it.pos}
            for((i,e) in ordered.withIndex()) {
                val x=play(e.slot,e.v,counts[e.slot-1]++)
                val start=(e.pos*beat).roundToInt()
                val nextHat=if(e.slot in 4..5) ordered.drop(i+1).firstOrNull{it.slot in 4..5}?.let{(it.pos*beat).roundToInt()}?:groove.size else groove.size
                val n=min(x.size,min(groove.size-start,nextHat-start))
                for(j in 0 until n) groove[start+j]+=x[j]*(if(e.slot in 4..5) ((n-1-j)/132f).coerceIn(0f,1f) else 1f)
            }
            check(groove.all{it.isFinite() && abs(it)<1})
            write("groove.wav",groove)
            html.append("<h2>${g.title} / ${g.bpm} BPM</h2><p>Groove</p><audio controls src='${g.id}/groove.wav'></audio><section>")
            for((r,pad) in pads.withIndex()) {
                val hard=play(r+1,1f,0); write("pad-${r+1}.wav",hard)
                // Full tails, no cropping at a one-second stride for the longer roll/riser.
                val stride=hard.size+4410
                val strip=FloatArray(stride*12)
                for(z in 0..2) for(t in 0..3) {
                    val x=play(r+1,TimberKits.velocities[z],t)
                    x.copyInto(strip,(z*4+t)*stride)
                }
                write("touch-${r+1}.wav",strip)
                val grid=pad.velocityTakes
                val reference=TimberKitTest.rms(grid[2][0].samples)
                val matched=(0..2).map { z ->
                    val x=play(r+1,TimberKits.velocities[z],0)
                    val scale=reference/TimberKitTest.rms(x).coerceAtLeast(1e-12)
                    FloatArray(x.size){(x[it]*scale).toFloat()}
                }
                val ceiling=min(1f,.95f/matched.maxOf{it.maxOf{v->abs(v)}})
                val matchedStrip=FloatArray(stride*3)
                matched.forEachIndexed { z,x -> for(i in x.indices) matchedStrip[z*stride+i]=x[i]*ceiling }
                write("matched-${r+1}.wav",matchedStrip)
                val touch=TimberKitTest.distanceDb(grid[0][0].samples,grid[2][0].samples)
                val diversity=grid.minOf{row->(0..3).minOf{a->(a+1..3).minOfOrNull{b->TimberKitTest.distanceDb(row[a].samples,row[b].samples)}?:0.0}}
                val peak=grid.flatten().maxOf{it.samples.maxOf{v->abs(v)}}
                csv.append("${g.name},${r+1},${pad.displayName},$peak,$touch,$diversity,$renderMs,pass\n")
                html.append("<article><strong>A${(r+1).toString().padStart(2,'0')} ${pad.displayName}</strong><p>Hard strike</p><audio controls preload='none' src='${g.id}/pad-${r+1}.wav'></audio><p>Touch and takes</p><audio controls preload='none' src='${g.id}/touch-${r+1}.wav'></audio><p>Level-matched touch: soft, medium, hard</p><audio controls preload='none' src='${g.id}/matched-${r+1}.wav'></audio></article>")
            }
            html.append("</section>")
            println("TIMBER ${g.name}: 192 strikes; save, playback windows and MPC3 read-back verified; mean render $renderMs ms")
        }
        File(root,"index.html").writeText(html.toString())
        File(root,"verification.csv").writeText(csv.toString())
        File(root,"README.txt").writeText("TIMBER model ${TimberKits.VERSION}. Procedural acoustic-instrument percussion, not sampled real instruments.\nOpen index.html for pads, native-touch/take strips and genre grooves.\nnative: SnipSnap kit folders with 3 velocity zones x 4 strikes and full provenance.\nmpc3: native .xtd plus TrackData, cycle metadata and PCM verified in software.\nmpc2: .xpm velocity layers, first take per zone; round robin is unsupported for this combined grid.\nHardware slice-map capture, phone/MPC playback and owner sound acceptance remain OPEN. This pack does not claim all acceptance gates passed.\n")
    }
}
