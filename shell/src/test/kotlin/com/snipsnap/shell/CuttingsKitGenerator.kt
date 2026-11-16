package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.*
import com.snipsnap.synth.CuttingsKits
import com.snipsnap.synth.CuttingsBus
import com.snipsnap.audio.WavWriter.MPC_SAMPLE_RATE as RATE
import java.io.File
import kotlin.math.*

/** Auditions the actual saved kit windows, not a parallel Python approximation. */
object CuttingsKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.single()).absoluteFile.apply { mkdirs() }
        val proof=StringBuilder("kit,pads,zones,takes,pcm,save_load,playback_windows,mpc3_metadata\n")
        for(genre in CuttingsKits.Genre.entries) {
            val folder=File(root,genre.id).apply { mkdirs() }
            val native=File(folder,"native")
            val expected=CuttingsKits.build(genre)
            val kit=requireNotNull(StarterKits.byId(genre.id)).render(genre.title,native)
            check(KitStore.load(native)==kit)
            TriadKitProof.verify(kit,native,expected)
            val mpc=Mpc3Exporter.exportTrack(kit,native,File(folder,"mpc3"),overwrite=true)
            TriadKitProof.verifyTrack(mpc.program,kit)
            SfzWriter.write(kit,native,File(folder,"sfz"))
            KitExporter.exportProgramFolder(kit,native,File(folder,"mpc2"))
            val waves=kit.pads.associate { it.slot to WavReader.read(File(native,it.sampleFile)) }
            fun play(slot:Int,d:Int,t:Int):FloatArray {
                val p=kit.pads.single { it.slot==slot };val wav=waves.getValue(slot)
                val hit=requireNotNull(PadHit.resolve(p,floatArrayOf(.20f,.55f,1f)[d],t) { wav.frameCount.toLong() })
                return wav.samples.copyOfRange(hit.startFrame.toInt(),hit.endFrameExclusive.toInt())
            }
            val audio=File(folder,"audio").apply { mkdirs() }
            val entries=mutableListOf<String>(); val names=mutableListOf<String>(); val gains=mutableListOf<String>()
            for(p in kit.pads) {
                val voice="p%02d".format(p.slot)
                names+="\"$voice\":\"${p.displayName}\""; gains+="\"$voice\":{\"finished\":1}"
                for(d in 0..2)for(t in 0..3) {
                    val name="${voice}_v${d}_r${t}_finished.wav"; val x=play(p.slot,d,t)
                    WavWriter.write(File(audio,name),Snip(x,1,RATE))
                    entries+="{\"voice\":\"$voice\",\"dynamic\":$d,\"take\":$t,\"stage\":\"finished\",\"file\":\"audio/$name\",\"frames\":${x.size}}"
                }
            }
            val step=60.0/genre.bpm/4; val out=FloatArray(((64*step+1)*RATE).roundToInt()); val counts=mutableMapOf<Int,Int>()
            fun add(position:Double,slot:Int,d:Int,gain:Float) {
                val n=counts.getOrDefault(slot,0);counts[slot]=n+1
                val hit=play(slot,d,n%4);val at=(position*step*RATE).roundToInt()
                for(i in 0 until min(hit.size,out.size-at))out[at+i]+=hit[i]*gain
            }
            val shaker=expected.indexOfFirst { it.source["role"]=="shaker" }+1
            val perc=expected.indexOfFirst { it.source["role"]==if(genre==CuttingsKits.Genre.NIGHT)"hand" else "wood" }+1
            for(bar in 0..3) {
                val kicks=when(genre) { CuttingsKits.Genre.POCKET->listOf(0,6,10,14);CuttingsKits.Genre.NIGHT->listOf(0,9,14);else->listOf(0,3,6,10,14) }
                for(s in kicks)add((bar*16+s).toDouble(),1,if(s==14)0 else 2,.72f)
                for(s in if(genre==CuttingsKits.Genre.NIGHT)listOf(8) else listOf(4,12))add(bar*16+s+.04,2,2,.67f)
                val hats=when(genre) { CuttingsKits.Genre.NIGHT->listOf(0,3,6,10,13);CuttingsKits.Genre.CIRCUIT->(0..15).toList();else->(0..14 step 2).toList() }
                for(s in hats)add(bar*16+s+if(s%2==0)0.0 else .07,3,if(s%4==0)1 else 0,.40f)
                for(s in listOf(7,15))add(bar*16+s+.05,perc,1,.40f)
                for(s in listOf(3,11))add((bar*16+s).toDouble(),shaker,0,.30f)
            }
            check(out.all { it.isFinite() && abs(it)<1f })
            WavWriter.write(File(audio,"groove.wav"),Snip(out,1,RATE))
            val bus=CuttingsBus.comparisons(out)
            for (mode in listOf("glue", "squeeze"))
                WavWriter.write(File(audio,"groove_${mode}_v2.wav"),Snip(bus.getValue(mode),1,RATE))
            // Portable full-mix bounce alongside each kit, never fake per-pad bus processing.
            val mixes=File(folder,"mixes").apply { mkdirs() }
            WavWriter.write(File(mixes,"${genre.id}-glue.wav"),Snip(bus.getValue("glue"),1,RATE))
            File(mixes,"README.txt").writeText("Default: Glue, CUTTINGS bus v${CuttingsBus.VERSION}. Full mixed performance, 24-bit mono 44.1 kHz. Fixed ${genre.bpm} BPM, four bars plus decay tail. Editable native/SFZ/MPC pads remain original; no live bus compressor is installed.\n")
            val originalPeak=out.maxOf { abs(it) }
            val originalGain=if(originalPeak==0f)1f else bus.getValue("finished").maxOf { abs(it) }/originalPeak
            val voices=kit.pads.joinToString(",") { "\"p%02d\"".format(it.slot) }
            File(folder,"manifest.json").writeText("""{"version":"cuttings-native-1","title":"${genre.title}","bpm":${genre.bpm},"voices":[$voices],"names":{${names.joinToString(",")}},"stages":["finished"],"entries":[${entries.joinToString(",")}],"comparisonGain":{${gains.joinToString(",")}},"grooves":{"finished":{"file":"audio/groove.wav","gain":$originalGain},"glue":{"file":"audio/groove_glue_v2.wav","gain":1},"squeeze":{"file":"audio/groove_squeeze_v2.wav","gain":1}},"defaultCompression":"glue","busVersion":2}""")
            proof.append("${genre.id},20,3,4,verified,verified,verified,verified\n")
            println("${genre.title}: saved and verified 20 grids; wrote 240 audition WAVs and native/SFZ/MPC exports")
        }
        File(root,"verification.csv").writeText(proof.toString())
    }
}
