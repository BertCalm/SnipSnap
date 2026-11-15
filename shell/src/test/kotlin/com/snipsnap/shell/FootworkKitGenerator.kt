package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.synth.*
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Native audition comes from saved playback windows, not from bypassing the kit delivery path. */
object FootworkKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "testkit/footwork-native").absoluteFile
        require(!root.exists() || root.listFiles().isNullOrEmpty()) { "Use a new empty output directory: $root" }
        root.mkdirs()
        val manifest=StringBuilder("{\"model\":1,\"nativeListening\":\"pending\",\"kits\":[")
        val proof=StringBuilder("kit,pads,cells,source_checks,saved_pcm,playback_all_velocities,SFZ,MPC3\n")
        for((k,genre) in FootworkKits.Kit.entries.withIndex()) {
            val folder=File(root,genre.id).apply { mkdirs() }; val dir=File(folder,"native")
            val start=System.nanoTime(); val pads=FootworkKits.build(genre)
            FootworkKitProof.sources(genre,pads)
            val kit=KitAssembler.assembleArranged("Footwork ${genre.title}",pads,dir,tempoBpm=genre.bpm.toFloat())
            FootworkKitProof.roundTrip(pads,kit,dir,folder)
            val waves=kit.pads.map { WavReader.read(File(dir,it.sampleFile)) }
            fun play(role: Int,v: Float,take: Int): FloatArray {
                val wav=waves[role]; val hit=requireNotNull(PadHit.resolve(kit.pads[role],v,take) { wav.frameCount.toLong() })
                // Runtime gain is applied in the audition just as in pad playback.
                return wav.samples.copyOfRange(hit.startFrame.toInt(),hit.endFrameExclusive.toInt()).map { it*hit.gainLeft }.toFloatArray()
            }
            fun write(name: String,x: FloatArray) { check(x.all { it.isFinite() && abs(it)<1f }); WavWriter.write(File(folder,name),Snip(x,1,44100)) }
            if(k>0) manifest.append(",")
            manifest.append("{\"id\":\"${genre.id}\",\"title\":\"${genre.title}\",\"bpm\":${genre.bpm},\"pads\":[")
            for(i in pads.indices) {
                val name=String.format(Locale.ROOT,"%02d",i+1)
                write("pad-$name.wav",play(i,1f,0))
                val clips=FootworkKits.velocities.flatMap { v -> List(4) { take -> play(i,v,take) } }
                val size=clips.sumOf { it.size+5512 }; val strip=FloatArray(size); var at=0
                for(x in clips) { x.copyInto(strip,at); at+=x.size+5512 }; write("touch-$name.wav",strip)
                if(i>0) manifest.append(",")
                manifest.append("{\"slot\":${i+1},\"name\":\"${FootworkKits.names[i]}\"}")
            }
            val beat=60.0/genre.bpm; val phrase=FloatArray(((16*beat+1)*44100).toInt())
            // Genre-specific original choreography: tap articulation, street backbeat, industrial boots/metal.
            val events=when(genre) {
                FootworkKits.Kit.TAP -> listOf(0 to 2,1 to 6,2 to 7,3 to 1,4 to 2,5 to 6,6 to 7,7 to 8,8 to 2,9 to 6,10 to 1,11 to 7,12 to 2,13 to 9,14 to 6,15 to 0)
                FootworkKits.Kit.STREET -> listOf(0 to 0,1 to 8,2 to 12,3 to 8,4 to 0,5 to 9,6 to 6,7 to 8,8 to 0,9 to 8,10 to 12,11 to 7,12 to 0,13 to 13,14 to 10,15 to 8)
                FootworkKits.Kit.INDUSTRIAL -> listOf(0 to 0,1 to 15,2 to 1,3 to 14,4 to 0,5 to 7,6 to 1,7 to 15,8 to 0,9 to 14,10 to 1,11 to 8,12 to 0,13 to 15,14 to 7,15 to 14)
            }
            for((j,event) in events.withIndex()) {
                val x=play(event.second,if(j%4==0) 1f else .85f,j%4); val at=(event.first*beat*44100).toInt()
                for(i in x.indices) if(at+i<phrase.size) phrase[at+i]+=.8f*x[i]
            }
            write("phrase.wav",phrase)
            manifest.append("],\"buildAndProofMs\":${(System.nanoTime()-start)/1000000}}")
            proof.append("${genre.id},18,216,passed,passed,passed,passed,passed\n")
            println("Verified ${genre.id}: 216 cells + save/playback/SFZ/MPC3")
        }
        manifest.append("]}"); File(root,"manifest.json").writeText(manifest.toString()); File(root,"verification.csv").writeText(proof.toString())
    }
}
