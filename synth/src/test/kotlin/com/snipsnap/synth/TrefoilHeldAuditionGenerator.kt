package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.system.measureNanoTime

/** Native audition, raw contact/state bridge, and separately labelled host timings. */
object TrefoilHeldAuditionGenerator {
    private fun json(x: Any?): JsonValue=when(x) {
        null -> JsonValue.Null
        is JsonValue -> x
        is String -> JsonValue.Str(x)
        is Number -> JsonValue.Num(x.toDouble())
        is Boolean -> JsonValue.Bool(x)
        is Map<*,*> -> JsonValue.Obj(x.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(x.map(::json))
        else -> error("unsupported evidence ${x::class}")
    }
    private fun diagnostics(d: TrefoilHeld.Diagnostics)=linkedMapOf<String,Any>(
        "periodFrames" to d.periodFrames,"sourceHz" to d.sourceHz,"referenceHz" to d.referenceHz,
        "stateRelativeError" to d.stateRelativeError,"precedingSeam" to d.precedingSeam,
        "followingSeam" to d.followingSeam,"rawPeak" to d.rawPeak,"gain" to d.gain,
        "coreContraction" to d.coreContraction,"planMillis" to d.planMillis,"solveMillis" to d.solveMillis,"totalMillis" to d.totalMillis)
    @JvmStatic fun main(args: Array<String>) {
        val output=File(args.firstOrNull() ?: "testkit/trefoil-held-native");output.mkdirs()
        val base=TrefoilPatch("Ordered Held",TrefoilVoice.WEAVE,TrefoilHeld.defaults(TrefoilVoice.WEAVE),
            velocity=.78f,seed=43012026L,playback=TrefoilPlayback.HELD)
        val cases=linkedMapOf("ordered" to base,"still" to base.copy(macros=base.macros+("WEAVE" to 0f)),
            "no-exchange" to base.copy(macros=base.macros+("EXCHANGE" to 0f)),
            "memory" to base.copy(macros=base.macros+("MEMORY" to .8f)),
            "bass" to base.copy(midi=48,macros=base.macros+("THREAD" to .45f)),
            "bright" to base.copy(midi=72,macros=base.macros+("THREAD" to .7f)))
        val rows=linkedMapOf<String,Any>();val first=linkedMapOf<String,Double>()
        for((name,patch) in cases) {
            val start=System.nanoTime();val loop=TrefoilHeld.loop(patch);first[name]=(System.nanoTime()-start)/1e6
            WavWriter.write(File(output,"$name-loop.wav"),Snip(loop.samples,1,Dsp.RATE))
            val off=2.0*loop.diagnostics.periodFrames/Dsp.RATE+.371
            val note=TrefoilHeld.renderNote(patch,off)
            WavWriter.write(File(output,"$name-release.wav"),note.snip)
            val p=TrefoilHeld.plan(patch);val state=TrefoilHeld.solve(p)
            val raw=TrefoilHeld.run(p,state).raw
            DataOutputStream(BufferedOutputStream(File(output,"$name-force.f64be").outputStream())).use { s -> p.force.forEach(s::writeDouble) }
            DataOutputStream(BufferedOutputStream(File(output,"$name-raw.f32be").outputStream())).use { s -> raw.forEach(s::writeFloat) }
            File(output,"$name.patch.json").writeText(patch.toJsonText())
            rows[name]=mapOf("patch" to patch.toJsonValue(),"diagnostics" to diagnostics(loop.diagnostics),
                "noteOffSeconds" to off,"powerStopsSeconds" to note.driveStopsFrame.toDouble()/Dsp.RATE,
                "unforcedCoreRise" to note.unforcedCoreRise)
            println("TREFOIL native $name: ${loop.diagnostics}")
        }
        // These are cache-cold after JIT warmup, not fresh-process or phone timings.
        val cold=List(3){TrefoilHeld.clearCache();measureNanoTime{TrefoilHeld.render(base)}/1e6}
        val warm=List(7){measureNanoTime{TrefoilHeld.render(base)}/1e6}
        val report=mapOf("status" to "Kotlin held source; physical phone timing not measured",
            "sourceNoise" to "Dsp.Noise LCG, seedFor(TREFOIL-HELD-1, seed, midi)",
            "cases" to rows,"firstCaseMillis" to first,
            "benchmark" to mapOf("runtime" to System.getProperty("java.runtime.version"),"os" to System.getProperty("os.name"),
                "cacheColdAfterJitMillis" to cold,"cacheHitMillis" to warm,"cacheBytes" to TrefoilHeld.cacheBytes(),
                "phoneMeasured" to false))
        File(output,"native.json").writeText(Json.write(json(report))+"\n")
    }
}
