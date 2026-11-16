package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.synth.*
import java.io.File

/** Actual exported PCM, so the listening page exercises the same saved instruments as the app. */
object BristleVoicesGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.single()).apply { mkdirs() }
        val previews=File(root,"previews").apply { mkdirs() }
        val recipes=File(root,"recipes").apply { mkdirs() }
        val report=File(root,"render-report.txt")
        report.writeText("The owner kept all six voices. Approved A/B routes are unchanged.\n")
        for (voice in Bristle.musicalVoices) {
            val patch=BristlePresets.forVoice(voice).single()
            File(recipes,"$voice.json").writeText(patch.toJsonText())
            for (held in listOf(false,true)) {
                val name="Bristle $voice"+(if(held)" Held" else "")+" RR"
                val spec=BristleKeygroupMaker.Spec(patch,held=held,takes=2,releaseSeconds=if(held).2f else .045f)
                val start=System.nanoTime()
                val folder=File(root,name)
                BristleKeygroupMaker.writeNewPackage(name,spec,BristleKeygroupMaker.render(spec),folder)
                val checked=BristleKeygroupMaker.validate(name,folder,spec)
                check(checked.samples.size==150)
                val instrument=InstrumentStore.read(InstrumentStore.sidecar(folder,name))
                val pcm=instrument.sampleFiles.associateWith { WavReader.read(File(folder,it)).samples }
                val player=InstrumentEngine(InstrumentEngine.Loaded(instrument,WavWriter.MPC_SAMPLE_RATE,pcm),WavWriter.MPC_SAMPLE_RATE)
                val heldFrames=if(held)WavWriter.MPC_SAMPLE_RATE*2 else WavWriter.MPC_SAMPLE_RATE/2
                val tailFrames=WavWriter.MPC_SAMPLE_RATE*3/10
                val output=FloatArray((heldFrames+tailFrames)*2*6)
                var offset=0
                for (velocity in BristleKeygroupMaker.TOUCHES) repeat(2) {
                    check(player.noteOn(36,velocity))
                    val a=FloatArray(heldFrames*2);player.render(a,heldFrames);a.copyInto(output,offset);offset+=a.size
                    player.noteOff(36)
                    val b=FloatArray(tailFrames*2);player.render(b,tailFrames);b.copyInto(output,offset);offset+=b.size
                    check(player.activeNotes.isEmpty())
                }
                WavWriter.write(File(previews,"${voice}_${if(held)"held" else "finite"}.wav"),Snip(output,2,WavWriter.MPC_SAMPLE_RATE),WavWriter.BitDepth.PCM_16)
                if (held) {
                    val plan=BristleChordPlan.fromStab(patch,ChordStabs.stabs(KeySpec.parse("Cmin")).last())
                    plan.notes.forEach { check(player.noteOn(it,.65f)) }
                    val a=FloatArray(WavWriter.MPC_SAMPLE_RATE*3*2);player.render(a,WavWriter.MPC_SAMPLE_RATE*3)
                    plan.notes.forEach { player.noteOff(it) }
                    val b=FloatArray(tailFrames*2);player.render(b,tailFrames)
                    WavWriter.write(File(previews,"${voice}_chord.wav"),Snip(a+b,2,WavWriter.MPC_SAMPLE_RATE),WavWriter.BitDepth.PCM_16)
                }
                val line="$name: 25 roots, 150 samples, three cycling companions, validated ${(System.nanoTime()-start)/1e9} sec\n"
                print(line);report.appendText(line)
            }
        }
        val source=File(root,"Starter Source")
        val kit=requireNotNull(StarterKits.byId("bristle")).render(BristleKits.NAME,source)
        check(kit==KitStore.load(source))
        val export=BristleStarterExport.write(File(root,"Starter MPC")).native
        val restored=Mpc3Importer.import(export.program,File(root,"Starter Readback"))
        check(restored.kit.pads.all { it.chain==null && it.velocityLayers.size==3 })
        println("Starter: full phone grid; MPC first-take velocity programs and three separate two-take cycling programs")
    }
}
