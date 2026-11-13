package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File

/** Isolate the nonlinear stage using one identical captured 4x filter output at both rates. */
object TempestDriveProbeGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.single()).apply { mkdirs() }
        // Remove stale probe inputs from earlier diagnostics in this owned generated folder.
        root.listFiles()?.filter { it.name.startsWith("drive-") && it.extension=="wav" }?.forEach { it.delete() }
        val metadata=linkedMapOf<String,JsonValue>()
        for (v in Tempest.EXPANSION_VOICES) for (source in listOf(0f,.5f,1f)) for (filter in listOf(.75f,1f)) {
            val capture=FloatArray(Tempest.OUTPUT_FRAMES*4)
            val m=mapOf("TUNE" to 1f,"SOURCE" to source,"FILTER" to filter,"DRIVE" to 1f,"SPACE" to 0f,"DECAY" to .5f)
            TempestPercussion.synthesize(v,m,Dsp.RATE*4,capture)
            val name="drive-${v.name}-S$source-F$filter-input-4x.wav"
            WavWriter.write(File(root,name),Snip(FloatArray(capture.size) { capture[it]*.125f },1,Dsp.RATE*4),
                WavWriter.BitDepth.PCM_24,allowNonMpcRate=true)
            metadata[name]=JsonValue.Obj(mapOf("voltage" to JsonValue.Num(TempestPercussion.driveVoltageFor(v,source.toDouble(),filter.toDouble())),
                "source" to JsonValue.Num(source.toDouble()),"filter" to JsonValue.Num(filter.toDouble())))
        }
        File(root,"manifest.json").writeText(Json.write(JsonValue.Obj(metadata)))
        println("Wrote ${metadata.size} captured inputs and the production voltage metadata")
    }
}
