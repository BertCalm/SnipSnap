package com.snipsnap.shell

import com.snipsnap.synth.*
import com.snipsnap.audio.WavWriter
import java.io.File

object DrumlineKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull()?:"testkit/drumline-kit").apply { mkdirs() }
        for(kit in DrumlineKits.Kit.entries) {
            println("Building ${kit.id}")
            DrumlineKitProof.build(kit,File(root,kit.id))
        }
        val times=StringBuilder("voice,render_ms\n")
        for(v in DrumlineVoice.entries) {
            Drumline.render(v) // warm the same source before the recorded timing
            val start=System.nanoTime();val hit=Drumline.render(v)
            times.append("$v,${(System.nanoTime()-start)/1000000.0}\n")
            WavWriter.write(File(root,"${v.name.lowercase()}.wav"),hit,WavWriter.BitDepth.PCM_24)
        }
        File(root,"render-times.csv").writeText(times.toString())
    }
}
