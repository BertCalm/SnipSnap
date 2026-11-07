package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File

/** Actual Kotlin PCM, replayable recipes, raw port samples, and local timing evidence. */
object LorentzAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/lorentz-audition")
        root.mkdirs()
        val manifest = mutableListOf<JsonValue>()
        val rows = mutableListOf("voice\tclip\tcollision_ms\tcontact_ms\tframes\tclass\tpeak\tdc\tloudness\trender_ms")
        val cards = StringBuilder()
        for (voice in LorentzVoice.entries) {
            val cases = mutableListOf<Pair<String, Map<String, Float>>>()
            for (tune in listOf(0f, .5f, 1f)) cases += "tune_$tune" to mapOf("TUNE" to tune)
            for (macro in listOf("CAPACITOR", "GAP", "IMPACT", "HOLD")) {
                for (v in if (macro in listOf("GAP", "IMPACT")) listOf(0f, .5f, 1f) else listOf(0f, 1f))
                    cases += "${macro}_${if (v == .5f) "0.5" else v.toInt().toString()}" to mapOf(macro to v)
            }
            for ((id, overrides) in cases) {
                val patch = LorentzPatch("${voice.name} $id", voice, Lorentz.defaults(voice) + overrides)
                val p = Lorentz.plan(voice, patch.macros)
                val start = System.nanoTime()
                val snip = patch.render()
                val ms = (System.nanoTime() - start) / 1e6
                val name = "${voice.name}_$id.wav"
                WavWriter.write(File(root, name), snip)
                File(root, "$name.patch.json").writeText(patch.toJsonText())
                rows += listOf(voice, id, p.collisionSeconds * 1000, p.contactSeconds * 1000,
                    snip.frameCount, Classifier.classify(snip).drumClass, snip.peak(),
                    snip.samples.average(), Loudness.of(snip), ms).joinToString("\t")
                manifest += JsonValue.Obj(linkedMapOf(
                    "file" to JsonValue.Str(name), "recipe" to patch.toJsonValue(),
                    "frames" to JsonValue.Num(snip.frameCount.toDouble()), "renderMs" to JsonValue.Num(ms),
                ))
                cards.append("<article><h3>${patch.name}</h3><audio controls preload=none src='$name'></audio><p><a href='$name' download>WAV</a> · <a href='$name.patch.json'>Recipe</a></p></article>\n")
            }
            // Raw samples are portable numerical evidence; their first column is frame.
            val raw = Lorentz.synthesize(voice)
            File(root, "${voice.name}_raw.tsv").writeText(raw.indices.step(101).joinToString("\n") { "$it\t${raw[it]}" })
        }
        File(root, "manifest.json").writeText(Json.write(JsonValue.Arr(manifest)))
        File(root, "metrics.tsv").writeText(rows.joinToString("\n") + "\n")
        val html = """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LORENTZ Kotlin audition</title><style>body{font:16px system-ui;max-width:1000px;margin:36px auto;padding:20px;background:#111414;color:#edf0e8}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:16px}article{padding:16px;background:#1b1f1e;border-radius:8px}audio{width:100%}a{color:#d8ed97}</style><h1>LORENTZ Kotlin audition</h1><p>Actual SnipSnap output from model 3. HOLD is a finite decay. Source equations match the approved Python audition; output filtering and perceived-level conditioning use Kotlin.</p><main>$cards</main><script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})));</script></html>"""
        File(root, "index.html").writeText(html)
        println("Wrote ${manifest.size} Kotlin WAVs and saved patches to ${root.absolutePath}")
    }
}
