package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.abs

/** Reproducible held-field listening evidence, with independent cycle errors and complete recipes. */
object TidalHoldAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single()).apply { require(!exists()) { "use a fresh destination" }; mkdirs() }
        val rows = mutableListOf<JsonValue>()
        val html = StringBuilder("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Tidal HOLD / SnipSnap</title><style>body{background:#111b23;color:#e4eee9;font:17px/1.6 system-ui;max-width:900px;margin:40px auto;padding:20px}a{color:#8ee2cf}h1{font-size:56px;line-height:1}article{border-top:1px solid #49616b;padding:24px 0}audio{width:100%}.muted{color:#b0c2c9}nav{display:flex;gap:24px;flex-wrap:wrap}</style><body><nav><a href="../">Source audition</a><a href="manifest.json">Measurements + recipes</a></nav><p class="muted">SNIPSNAP / TIDAL / HOLD</p><h1>The threads remain.</h1><p>The encounter unfolds once. Its string and metal populations become a moving sustain. Pads start in that aftermath; instruments retain the opening.</p><p class="muted">Dry mono. The first player demonstrates the authored encounter, repeated sustain and a 0.7-second release fade. The second plays eight complete held cycles without seam fades. The finite sounds remain unchanged. Listening acceptance and phone/MPC hardware checks remain open.</p>""")
        val cases = TidalVoice.entries.flatMap { voice -> listOf(36, 60, 84).map { voice to it } }
        for ((voice, midi) in cases) {
            val id = "${voice.name.lowercase()}-$midi"
            val patch = TidalPatch("${voice.name} HOLD", voice, Tidal.defaults(voice) + ("HOLD" to 1f), midi = midi)
            val began = System.nanoTime()
            val probe = TidalKeys.probe(patch)
            val millis = (System.nanoTime() - began) / 1e6
            val note = probe.note
            val marker = note.loopStartFrame.toInt()
            val release = (Dsp.RATE * .7).toInt()
            val holdFrames = marker + probe.loop.size * 3
            val demo = FloatArray(holdFrames + release)
            note.snip.samples.copyInto(demo, 0, 0, marker)
            for (i in marker until demo.size) {
                val gain = if (i < holdFrames) 1f else (demo.size - 1 - i).toFloat() / release
                demo[i] = probe.loop[(i - marker) % probe.loop.size] * gain
            }
            fun wav(name: String, samples: FloatArray) = File(root, name).outputStream().buffered().use {
                WavWriter.write(it, Snip(samples, 1, Dsp.RATE))
            }
            wav("$id-note.wav", demo)
            val pad = TidalKeys.padLoop(probe.loop)
            wav("$id-loop-x8.wav", FloatArray(pad.size * 8) { pad[it % pad.size] })
            File(root, "$id.patch.json").writeText(patch.toJsonText())
            fun num(x: Double) = JsonValue.Num(x)
            rows += JsonValue.Obj(linkedMapOf("id" to JsonValue.Str(id), "patch" to patch.toJsonValue(),
                "renderMillis" to num(millis), "loopFrames" to num(probe.loop.size.toDouble()),
                "loopStartFrame" to num(marker.toDouble()), "seamError" to num(probe.seamError),
                "independentCycleError" to num(probe.cycleError), "rootCents" to num(probe.rootCents),
                "maxFrequencyCorrectionHz" to num(probe.maxFrequencyCorrectionHz),
                "phaseClosureCycles" to num(probe.maxPhaseClosureCycles), "rawPeak" to num(probe.rawPeak),
                "outputPeak" to num(note.snip.samples.maxOf { abs(it) }.toDouble()),
                "amplitudeMotion" to num(probe.amplitudeMotion), "padEntryMagnitude" to num(abs(pad.first()).toDouble()),
                "windowStartSeconds" to num(probe.windowStartSeconds), "windowEndSeconds" to num(probe.windowEndSeconds)))
            html.append("""<article><h2>$voice / MIDI $midi</h2><p>Encounter → hold → release</p><audio controls preload="none" src="$id-note.wav"></audio><p>Eight held cycles / no wrap fades</p><audio controls preload="none" src="$id-loop-x8.wav"></audio><p><a href="$id.patch.json" download>Recipe</a> · <a href="$id-note.wav" download>Note WAV</a> · <a href="$id-loop-x8.wav" download>Eight-cycle WAV</a></p></article>""")
            println("$id %.1f ms; seam %.3g; cycle %.3g; root %.5f cents".format(java.util.Locale.ROOT,
                millis, probe.seamError, probe.cycleError, probe.rootCents))
        }
        html.append("<script>document.addEventListener('play',e=>{if(e.target.tagName==='AUDIO')document.querySelectorAll('audio').forEach(a=>{if(a!==e.target)a.pause()})},true)</script></body></html>")
        File(root, "index.html").writeText(html.toString())
        File(root, "manifest.json").writeText(Json.write(JsonValue.Obj(linkedMapOf(
            "heldModel" to JsonValue.Num(TidalKeys.MODEL_VERSION.toDouble()), "cases" to JsonValue.Arr(rows)))))
    }
}
