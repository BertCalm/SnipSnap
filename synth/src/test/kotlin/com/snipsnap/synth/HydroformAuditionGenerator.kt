package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Native house-stage WAVs paired by id with the frozen Python R5 manifest. */
object HydroformAuditionGenerator {
    private fun json(x: Any?): JsonValue = when (x) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(x)
        is Number -> JsonValue.Num(x.toDouble())
        is Boolean -> JsonValue.Bool(x)
        is Map<*,*> -> JsonValue.Obj(x.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(x.map(::json))
        else -> error("unsupported JSON $x")
    }
    private fun floats(file: File, samples: FloatArray) {
        file.parentFile.mkdirs()
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in samples) bytes.putFloat(x)
        file.writeBytes(bytes.array())
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "manifest.json output-directory" }
        val source = Json.parse(File(args[0]).readText()).arr()
        val out = File(args[1]).apply { mkdirs() }
        val entries = mutableListOf<Map<String,Any?>>()
        for (entry in source) {
            val e = entry.obj()
            val id = e.getValue("id").str()
            val group = e.getValue("group").str()
            if (group in listOf("legacy", "slope-study") || id == "compare-bypass") continue
            // Include the native 4x hot corners beside Python's 4x/16x studies.
            // A 16x raw reference is exported below; the house renderer remains 4x.
            if (group == "hot-corner" && e["internal_factor"]?.int() != 4) continue
            val voice = HydroformVoice.valueOf(e["voice"]?.str() ?: "DOME")
            val macros = e["macros"]?.obj()?.mapValues { it.value.num().toFloat() } ?: mapOf("HOLD" to 1f)
            val velocity = e["velocity"]?.num()?.toFloat() ?: 1f
            val patch = HydroformPatch(id, voice, macros, velocity)
            val start = System.nanoTime()
            val snip = if (id == "compare-amplitude") {
                val conditioned = Hydroform.condition(Hydroform.raw(voice, macros, velocity, frozenSlope = true))
                Dsp.levelTo(conditioned, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
                Dsp.fadeTail(conditioned)
                Snip(conditioned, 1, Dsp.RATE)
            } else patch.render()
            val milliseconds = (System.nanoTime() - start) / 1e6
            val audio = "audio/native-$id.wav"
            WavWriter.write(File(out, audio), snip, WavWriter.BitDepth.PCM_16)
            if (id != "compare-amplitude") {
                val recipe = File(out, "recipes/$id.json").also { it.parentFile.mkdirs(); it.writeText(patch.toJsonText()) }
                check(Patches.fromJsonText(recipe.readText()).render().samples.contentEquals(snip.samples))
            }
            if (id in listOf("dome-default", "bulkhead-default")) {
                floats(File(out, "raw/$id.f32"), Hydroform.raw(voice, macros, velocity))
                floats(File(out, "conditioned/$id.f32"), snip.samples)
            }
            val pitch = Pitch.detect(snip)
            entries += linkedMapOf("id" to id, "voice" to voice.name, "file" to audio,
                "group" to group, "milliseconds" to milliseconds, "peak" to snip.peak(),
                "dc" to snip.samples.sumOf { it.toDouble() } / snip.frameCount,
                "loudness" to Loudness.of(snip), "frames" to snip.frameCount,
                "pitch_hz" to pitch?.hz, "pitch_confidence" to pitch?.confidence,
                "recipe" to if (id == "compare-amplitude") null else "recipes/$id.json")
            println("HYDROFORM $id: %.1f ms".format(java.util.Locale.ROOT, milliseconds))
        }
        File(out, "native-manifest.json").writeText(Json.write(json(entries)))
        for (voice in HydroformVoice.entries) for (factor in listOf(4, 16)) {
            val hard = mapOf("CHARGE" to 1f, "YIELD" to 0f, "ARREST" to 1f, "TUNE" to 1f)
            floats(File(out, "raw/${voice.name.lowercase()}-hard-$factor.f32"),
                Hydroform.raw(voice, hard, rate = Dsp.RATE * factor))
        }
    }
}
