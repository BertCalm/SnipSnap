package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Acvs
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.math.abs

/** Verify actual saved PCM and the actual exported ACVS, not just writer inputs. */
object TriadKitProof {
    fun verify(kit: Kit, dir: File, expected: List<ArrangedPad>) {
        check(kit.pads.size == expected.size)
        for ((index, pad) in kit.pads.withIndex()) {
            val grid = expected[index].velocityTakes
            val file = File(dir,pad.sampleFile)
            val info = WavInfo.read(file)
            check(info.sampleRate == 44100 && info.channels == 1 && info.bitsPerSample == 24)
            val wav = WavReader.read(file)
            val chain = requireNotNull(pad.chain)
            check(chain.zones?.size == grid.size && chain.boundaries.size == grid.sumOf { it.size })
            for ((z,row) in grid.withIndex()) for (hit in 0 until row.size*2) {
                val velocity = ((chain.zones!![z].velStart + chain.zones!![z].velEnd) / 2f) / 127f
                val resolved = requireNotNull(PadHit.resolve(pad,velocity,hit) { wav.frameCount.toLong() })
                val take = row[hit%row.size]
                check(resolved.endFrameExclusive-resolved.startFrame == take.frameCount.toLong())
                val start = resolved.startFrame.toInt()
                check(take.samples.indices.all { abs(take.samples[it]-wav.samples[start+it]) <= 3f/8388608 })
            }
            check(pad.source == expected[index].source)
        }
    }
    fun verifyTrack(file: File, kit: Kit) {
        val stack = ArrayDeque<JsonValue>(); stack.add(requireNotNull(Acvs.read(file).payload))
        var drum: JsonValue.Obj? = null
        while(stack.isNotEmpty()) when(val cur=stack.removeLast()) {
            is JsonValue.Obj -> if("instruments" in cur.entries) { drum=cur; break } else stack.addAll(cur.entries.values)
            is JsonValue.Arr -> stack.addAll(cur.items)
            else -> Unit
        }
        val instruments = (requireNotNull(drum).entries.getValue("instruments") as JsonValue.Arr).items
        for(pad in kit.pads) {
            val layers = ((instruments[pad.slot-1] as JsonValue.Obj).entries.getValue("layersv") as JsonValue.Arr).items
            val chain = requireNotNull(pad.chain)
            for((li,z) in chain.zones!!.reversed().withIndex()) {
                val layer=(layers[li] as JsonValue.Obj).entries
                fun num(k:String)=(layer.getValue(k) as JsonValue.Num).value.toInt()
                check(num("velocityStart")==z.velStart && num("velocityEnd")==z.velEnd)
                check(num("sliceIndex")==z.baseSlice && num("sliceIncrement")==1 && num("sliceCycleLength")==z.cycle)
                val info=(layer.getValue("sliceInfo") as JsonValue.Obj).entries
                check((info.getValue("Start") as JsonValue.Num).value.toLong()==chain.boundaries[z.baseSlice])
                check((info.getValue("End") as JsonValue.Num).value.toLong()==chain.boundaries[z.baseSlice+1])
            }
        }
    }
}
