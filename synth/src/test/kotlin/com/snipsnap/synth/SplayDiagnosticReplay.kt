package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.File

/** The downloaded audition recipe includes ablation mode and recorded output gains. */
object SplayDiagnosticReplay {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size in 2..3) { "recipe.json output.wav [raw|matched]" }
        val level=args.getOrNull(2) ?: "matched"
        require(level in setOf("raw","matched")) { "level must be raw or matched" }
        val recipe=Json.parse(File(args[0]).readText()).obj()
        val patch=SplayPatch.fromJsonValue(recipe.getValue("patch"))
        val options=recipe.getValue("render").obj()
        val mode=SplayMode.valueOf(options.getValue("mode").str())
        val gain=options.getValue(if(level=="raw")"rawGain" else "matchedGain").num()
        require(gain.isFinite() && gain>=0.0)
        val result=Splay.renderDetailed(patch.voice,patch.macros,patch.midi,patch.velocity,patch.seed,patch.model,mode)
        val samples=FloatArray(result.raw.size){(result.raw[it]*gain).toFloat()}
        WavWriter.write(File(args[1]),Snip(samples,1,Dsp.RATE),WavWriter.BitDepth.PCM_16)
        println("Replayed ${patch.name} (${mode.name}, $level) to ${File(args[1]).absolutePath}")
    }
}
