package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.*
import java.io.File

/** Matched bass phrases and actual exported sustain playback for the optional treatments. */
object BristleEffectsGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.single()).apply { mkdirs() }
        val previews=File(root,"previews").apply { mkdirs() }
        val recipes=File(root,"recipes").apply { mkdirs() }
        val report=File(root,"render-report.txt")
        report.writeText("All eight dry voices retained. Compression and fuzz are new optional candidates, off by default.\n")
        val modes=linkedMapOf(
            "dry" to BristleEffects.Settings(),
            "comp" to BristleEffects.Settings(compress=.7f),
            "fuzz" to BristleEffects.Settings(fuzz=.7f),
            "both" to BristleEffects.Settings(.7f,.7f),
        )
        val midis=listOf(24,31,36,27)
        val velocities=listOf(.65f,1f,.65f,.35f)
        File(root,"phrase.txt").writeText("Four one-second notes: MIDI 24,31,36,27; velocity .65,1,.65,.35; takes 0,1,0,1.\nCOMP and FUZZ comparisons use .7 for the enabled control. No extra audition normalization.\n")
        for (voice in Bristle.Route.entries) {
            val dry=BristlePresets.forVoice(voice).single()
            for ((mode,effects) in modes) {
                val patch=dry.copy(effects=effects)
                File(recipes,"${voice}_$mode.json").writeText(patch.toJsonText())
                val phrase=FloatArray(44100*4)
                midis.forEachIndexed { index,midi ->
                    patch.copy(midi=midi,velocity=velocities[index],take=index%2).render().samples.copyInto(phrase,index*44100)
                }
                WavWriter.write(File(previews,"${voice}_$mode.wav"),Snip(phrase,1,44100),WavWriter.BitDepth.PCM_16)
            }
            for (held in listOf(false,true)) {
                val patch=dry.copy(effects=modes.getValue("both"))
                val name="Bristle $voice FX"+(if(held)" Held" else "")
                val spec=BristleKeygroupMaker.Spec(patch,held=held,takes=2,releaseSeconds=if(held).2f else .045f)
                val start=System.nanoTime()
                val folder=File(root,name)
                BristleKeygroupMaker.writeNewPackage(name,spec,BristleKeygroupMaker.render(spec),folder)
                val checked=BristleKeygroupMaker.validate(name,folder,spec)
                check(checked.samples.size==150)
                check(checked.recipes.values.all { it.effects==patch.effects })
                if(held) {
                    val instrument=InstrumentStore.read(InstrumentStore.sidecar(folder,name))
                    val pcm=instrument.sampleFiles.associateWith { WavReader.read(File(folder,it)).samples }
                    val player=InstrumentEngine(InstrumentEngine.Loaded(instrument,44100,pcm),44100)
                    check(player.noteOn(24,.65f))
                    val body=FloatArray(44100*3*2);player.render(body,44100*3)
                    player.noteOff(24)
                    val tail=FloatArray(44100*2/2);player.render(tail,44100/2)
                    check(player.activeNotes.isEmpty())
                    WavWriter.write(File(previews,"${voice}_held_both.wav"),Snip(body+tail,2,44100),WavWriter.BitDepth.PCM_16)
                }
                val line="$name: 150 samples, three fixed-touch cycling companions, effects retained and validated; ${(System.nanoTime()-start)/1e9} sec\n"
                print(line);report.appendText(line)
            }
        }
    }
}
