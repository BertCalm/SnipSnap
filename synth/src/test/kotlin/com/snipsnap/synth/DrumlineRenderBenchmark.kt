package com.snipsnap.synth

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Standalone host timings, outside the parallel test workers. No wall-clock CI assertions. */
object DrumlineRenderBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val out=File(args.firstOrNull() ?: "testkit/drumline-render-benchmark.csv")
        out.parentFile.mkdirs()
        val rows=mutableListOf("voice,case,median_ms,float_sha256")
        for(voice in DrumlineVoice.entries) for(case in listOf("default", "soft_damped")) {
            val macros=if(case=="default") emptyMap() else mapOf("DAMP" to 1f,"STRIKE" to 0f)
            val velocity=if(case=="default")1f else .3f
            val take=if(case=="default")0 else 2
            Drumline.render(voice,macros,velocity,take)
            val times=mutableListOf<Double>();var samples=FloatArray(0)
            repeat(3) {
                val start=System.nanoTime()
                samples=Drumline.render(voice,macros,velocity,take).samples
                times+=(System.nanoTime()-start)/1e6
            }
            val bytes=ByteBuffer.allocate(samples.size*4).order(ByteOrder.LITTLE_ENDIAN)
            for(x in samples)bytes.putInt(x.toRawBits())
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            val row="$voice,$case,${times.sorted()[1]},$hash"
            rows+=row;println(row)
        }
        out.writeText(rows.joinToString("\n")+"\n")
    }
}
