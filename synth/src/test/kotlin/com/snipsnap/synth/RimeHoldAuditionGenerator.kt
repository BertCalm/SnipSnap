package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.*

/** Add model-5 listening evidence without rebuilding or changing the accepted finite bank. */
object RimeHoldAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single()).absoluteFile
        val manifestFile = File(root, "manifest.json")
        val manifest = LinkedHashMap(Json.parse(manifestFile.readText()).obj())
        val entries = manifest.getValue("clips").arr().filter { it.obj()["section"]?.str() != "hold" }.toMutableList()
        for (voice in Rime.LEGACY_VOICES) for (unfold in listOf(0f, 1f)) {
            val p = RimePresets.starter(voice).copy(model = 5, macros = Rime.defaults(voice) + mapOf("HOLD" to 1f, "UNFOLD" to unfold))
            val began = System.nanoTime()
            val r = RimeHeld.render(p)
            val ms = (System.nanoTime() - began) / 1e6
            val id = "hold-${voice.name.lowercase()}-${(unfold * 100).toInt()}"
            val head = r.note.snip.samples.copyOf(r.note.loopStartFrame.toInt())
            // Eight genuine loop repetitions expose the boundary. Only the preview
            // receives a terminal fade; the instrument's sustain stays untouched.
            val preview = FloatArray(head.size + r.cycle.size * 8)
            head.copyInto(preview)
            repeat(8) { r.cycle.copyInto(preview, head.size + it * r.cycle.size) }
            Dsp.fadeTail(preview)
            val native = "audio/raw/$id.wav"; val matched = "audio/matched/$id.wav"
            File(root, native).apply { parentFile.mkdirs(); WavWriter.write(this, Snip(preview, 1, Dsp.RATE)) }
            val levelled = preview.copyOf(); Dsp.levelTo(levelled, Dsp.RATE, .10f, ceiling = .9f)
            File(root, matched).apply { parentFile.mkdirs(); WavWriter.write(this, Snip(levelled, 1, Dsp.RATE)) }
            val recipe = "recipes/$id.json"
            File(root, recipe).writeText(p.toJsonText())
            entries += obj("id" to id, "section" to "hold", "voice" to voice.name,
                "title" to if (unfold == 0f) "Immediate detail" else "Unfolding opening",
                "description" to "The original key-on followed by eight resolved sustain cycles.",
                "midi" to p.midi, "velocity" to p.velocity, "gateSeconds" to p.gateSeconds,
                "model" to 5, "macros" to p.macros, "files" to mapOf("raw" to native, "matched" to matched, "recipe" to recipe),
                "metrics" to mapOf("durationSeconds" to preview.size.toDouble()/Dsp.RATE, "seamError" to r.seamError,
                    "actualHz" to r.actualHz, "renderMs" to ms, "internalPeak" to r.internalPeak, "maxBodyEnergy" to r.maxBodyEnergy,
                    "loopStartFrame" to r.note.loopStartFrame, "loopFrames" to r.cycle.size))
            println("$id: independently measured seam=${r.seamError}; $ms ms")
        }
        manifest["model"] = JsonValue.Num(5.0)
        manifest["status"] = JsonValue.Str("HOLD and app integration build; nine accepted finite recipes preserved. Additional voices await the original spec. Hardware and sonic acceptance pending.")
        manifest["clips"] = JsonValue.Arr(entries)
        manifestFile.writeText(Json.write(JsonValue.Obj(manifest)))
    }
    private fun obj(vararg fields: Pair<String, Any?>): JsonValue.Obj = JsonValue.Obj(fields.associate { it.first to json(it.second) })
    private fun json(v: Any?): JsonValue = when(v) {
        null -> JsonValue.Null; is String -> JsonValue.Str(v); is Number -> JsonValue.Num(v.toDouble())
        is Map<*,*> -> JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        else -> error("Unsupported field $v")
    }
}
