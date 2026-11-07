package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.abs

/** Native prototype evidence, with complete recipes and explicit velocity per clip. */
object RivetAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/rivet-audition").apply { mkdirs() }
        val manifest = mutableListOf<JsonValue>()
        val rows = StringBuilder()
        val metrics = StringBuilder("id,velocity,seconds,rawPeak,peak,loudness,matchedLoudness,renderMs\n")
        val times = mutableListOf<Double>()
        val spaces = linkedMapOf("workshop" to .10f, "factory" to .55f, "warehouse" to 1f)
        // Keep the source and wall material fixed across this comparison: only the building grows.
        fun space(size: Float) = mapOf("SIZE" to size, "WALLS" to .75f)

        for (voice in RivetVoice.entries) {
            repeat(3) { Rivet.render(voice) } // Warm the JVM separately from the recorded calls.
            val cases = linkedMapOf<String, Pair<Map<String, Float>, Float>>()
            for (patch in RivetPresets.forVoice(voice))
                cases[patch.name.substringBefore(' ').lowercase()] = patch.macros to 1f
            for (tune in listOf(.1f, .5f, .9f)) cases["tune-$tune"] = mapOf("TUNE" to tune) to 1f
            for (key in Rivet.defaults(voice).keys.filter { it != "HOLD" })
                for (v in listOf(0f, .25f, .5f, .75f, 1f)) cases["${key.lowercase()}-$v"] = mapOf(key to v) to 1f
            for ((a, b) in listOf("STRIKE" to "DRIVE", "METAL" to "DRIVE", "SIZE" to "WALLS"))
                for (x in listOf(.2f, .8f)) for (y in listOf(.2f, .8f))
                    cases["${a.lowercase()}-$x-${b.lowercase()}-$y"] = mapOf(a to x, b to y) to 1f
            cases["soft"] = emptyMap<String, Float>() to .25f
            cases["middle"] = emptyMap<String, Float>() to .6f
            for ((suffix, settings) in cases) {
                val (overrides, velocity) = settings
                val id = "${voice.name.lowercase()}-$suffix"
                val patch = RivetPatch(id, voice, Rivet.defaults(voice) + overrides)
                val start = System.nanoTime()
                val p = Rivet.inspect(voice, patch.macros, patch.modelVersion, velocity)
                val ms = (System.nanoTime() - start) / 1e6
                times += ms
                val loudness = Loudness.of(p.snip)
                val matched = p.snip.samples.copyOf()
                Dsp.levelTo(matched, Rivet.RATE, .12f)
                val matchedSnip = Snip(matched, 1, Rivet.RATE)
                WavWriter.write(File(root, "native/$id.wav"), p.snip)
                WavWriter.write(File(root, "matched/$id.wav"), matchedSnip)
                File(root, "recipes/$id.json").apply { parentFile.mkdirs(); writeText(patch.toJsonText()) }
                metrics.append("$id,$velocity,${p.snip.durationSeconds},${p.rawPeak},${p.snip.peak()},$loudness,${Loudness.of(matchedSnip)},$ms\n")
                manifest += JsonValue.Obj(linkedMapOf(
                    "id" to JsonValue.Str(id), "native" to JsonValue.Str("native/$id.wav"),
                    "matched" to JsonValue.Str("matched/$id.wav"), "recipe" to JsonValue.Str("recipes/$id.json"),
                    "velocity" to JsonValue.Num(velocity.toDouble()), "renderMs" to JsonValue.Num(ms),
                ))
                rows.append("<article><b>$id</b><p>Native <audio controls preload='none' src='native/$id.wav'></audio>Matched <audio controls preload='none' src='matched/$id.wav'></audio></p><a href='recipes/$id.json'>Saved patch</a> · velocity $velocity</article>\n")
            }
            val close = Rivet.inspect(voice).close.samples.copyOf()
            Dsp.normalize(close, .95f)
            val id = "${voice.name.lowercase()}-close-reference"
            WavWriter.write(File(root, "native/$id.wav"), Snip(close, 1, Rivet.RATE))
            rows.append("<article><b>$id</b><audio controls preload='none' src='native/$id.wav'></audio><p>Close branch only; diagnostic ablation, outside the patch's normal signal flow.</p></article>")
        }

        // A two-bar 120 BPM beat uses precisely the same strikes in all three rooms.
        // Mix at one common gain across spaces, so automatic per-groove normalizing cannot sell size.
        val grooves = spaces.mapValues { (_, size) ->
            val out = FloatArray(Rivet.RATE * 6)
            val events = mutableListOf<JsonValue>()
            for (step in 0 until 16) {
                val voice = if (step % 4 == 0 || step == 7 || step == 14) RivetVoice.KICK
                    else if (step % 4 == 2) RivetVoice.SNARE else continue
                val velocity = if (step == 7 || step == 14) .6f else 1f
                val patch = RivetPatch("Groove ${voice.name}", voice, Rivet.defaults(voice) + space(size))
                val snip = Rivet.render(voice, patch.macros, velocity = velocity)
                val start = (step * .25 * Rivet.RATE).toInt()
                for (i in snip.samples.indices) if (start + i < out.size) out[start + i] += snip.samples[i]
                events += JsonValue.Obj(linkedMapOf(
                    "startFrame" to JsonValue.Num(start.toDouble()), "velocity" to JsonValue.Num(velocity.toDouble()),
                    "patch" to patch.toJsonValue(),
                ))
            }
            out to events
        }
        val peak = grooves.values.maxOf { (x, _) -> x.maxOf { abs(it) } }.coerceAtLeast(1e-9f)
        val commonGain = .90f / peak
        for ((space, content) in grooves) {
            val (x, events) = content
            for (i in x.indices) x[i] *= commonGain
            WavWriter.write(File(root, "groove-$space.wav"), Snip(x, 1, Rivet.RATE))
            File(root, "groove-$space.json").writeText(Json.write(JsonValue.Obj(linkedMapOf(
                "bpm" to JsonValue.Num(120.0), "gain" to JsonValue.Num(commonGain.toDouble()),
                "events" to JsonValue.Arr(events),
            ))))
            rows.insert(0, "<article><b>$space · two bars at 120 BPM</b><audio controls preload='none' src='groove-$space.wav'></audio><a href='groove-$space.json'>Event recipes</a></article>")
        }
        times.sort()
        val summary = "${manifest.size} native/matched pairs. Warm host render median ${times[times.size / 2]} ms; maximum ${times.last()} ms. ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}; ${Runtime.getRuntime().availableProcessors()} processors. These are host measurements, not phone timings."
        val template = requireNotNull(javaClass.getResource("/audition/rivet-audition.html")).readText()
        File(root, "index.html").writeText(template.replace("{{ROWS}}", rows.toString()).replace("{{SUMMARY}}", summary))
        File(root, "manifest.json").writeText(Json.write(JsonValue.Arr(manifest)))
        File(root, "metrics.csv").writeText(metrics.toString())
        File(root, "README.txt").writeText("RIVET model ${Rivet.MODEL_VERSION}, Kotlin prototype. Original kick/snare core approved; new voices and candidates await listening.\n$summary\nOpen index.html. Each clip's saved patch is in recipes/. Replay soft clips with Rivet.render(voice, patch.macros, patch.modelVersion, velocity); velocity is in manifest.json. Grooves contain complete event patches and one shared gain. HOLD is refused. No factory kit or Android picker yet.\n")
        println("RIVET audition: ${root.absolutePath}\n$summary")
    }
}
