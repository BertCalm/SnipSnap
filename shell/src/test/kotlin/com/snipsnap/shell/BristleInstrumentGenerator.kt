package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Bristle
import com.snipsnap.synth.BristlePresets
import java.io.File

/** Finite and opt-in held packages, through the same maker as the phone sheet. */
object BristleInstrumentGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.first()).apply { mkdirs() }
        for (patch in BristlePresets.all().filter { it.voice in Bristle.anchors }) for (held in listOf(false, true)) {
            val rr = args.getOrNull(1) == "rr"
            val name = "Bristle ${patch.voice}" + (if (held) " Held" else "") + (if (rr) " RR" else "")
            val spec = BristleKeygroupMaker.Spec(patch, releaseSeconds = if (held) .2f else .045f, held = held, takes = if (rr) 2 else 1)
            val output = File(root, name)
            val start = System.nanoTime()
            BristleKeygroupMaker.writeNewPackage(name, spec, BristleKeygroupMaker.render(spec), output)
            val read = BristleKeygroupMaker.validate(name, output, spec)
            println("$name: ${read.program.keygroups.size} keys, ${read.samples.size} samples, validated; elapsed ${(System.nanoTime()-start)/1e9} s")
            run {
                val instrument = InstrumentStore.read(InstrumentStore.sidecar(output, name))
                val samples = instrument.sampleFiles.associateWith { WavReader.read(File(output, it)).samples }
                val previews = File(root, "previews").apply { mkdirs() }
                for ((touch, velocity) in BristleKeygroupMaker.TOUCHES.withIndex().filter { rr || held || it.index == 2 }) {
                    val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44100, samples), 44100)
                    val clips = ArrayList<Float>()
                    repeat(if (rr) 4 else 1) {
                    check(player.noteOn(36, velocity))
                    val frames = if (rr) 22050 else 44100 * 3
                    val sustain = FloatArray(frames * 2)
                    player.render(sustain, frames)
                    player.noteOff(36)
                    val release = FloatArray(13230 * 2)
                    player.render(release, 13230)
                    check(player.activeNotes.isEmpty())
                    clips.addAll((sustain + release).toList())
                    }
                    WavWriter.write(File(previews, "${patch.voice}_${if (held) "held" else "finite"}_T${touch+1}.wav"),
                        Snip(clips.toFloatArray(), 2, 44100), WavWriter.BitDepth.PCM_16)
                }
            }
        }
    }
}
