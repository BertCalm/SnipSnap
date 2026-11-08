package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native Kotlin first-listen pack. Recipes are replayed by this generator, not the phone picker. */
object FiredBronzeAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            require(args.size == 3) { "--replay recipe.json output.wav" }
            WavWriter.write(File(args[2]), FiredBronze.Recipe.fromJsonText(File(args[1]).readText()).render(), WavWriter.BitDepth.PCM_24)
            return
        }
        val root = File(args.firstOrNull() ?: "testkit/fired-bronze-audition").absoluteFile
        root.mkdirs()
        val rows = mutableListOf<JsonValue>()
        val metrics = StringBuilder("id,seconds,peak,mean,render_ms\n")
        val table = StringBuilder()
        fun emit(id: String, label: String, snip: Snip, recipe: FiredBronze.Recipe? = null, ms: Double = 0.0) {
            check(snip.samples.all { it.isFinite() && abs(it) <= 1f }) { "$id cannot be exported safely" }
            val file = "native/$id.wav"
            File(root, file).apply { parentFile.mkdirs(); WavWriter.write(this, snip, WavWriter.BitDepth.PCM_24) }
            val matched = "listen/$id.wav"
            File(root, matched).apply { parentFile.mkdirs(); WavWriter.write(this, AuditionLevel.level(snip), WavWriter.BitDepth.PCM_24) }
            val recipeFile = recipe?.let {
                "recipes/$id.json".also { path -> File(root, path).apply { parentFile.mkdirs(); writeText(it.toJsonText()) } }
            }
            metrics.append(String.format(Locale.ROOT, "%s,%.6f,%.7f,%.8f,%.3f%n",
                id, snip.durationSeconds, snip.peak(), snip.samples.average(), ms))
            rows += JsonValue.Obj(mapOf("id" to JsonValue.Str(id), "label" to JsonValue.Str(label),
                "native" to JsonValue.Str(file), "matched" to JsonValue.Str(matched),
                "recipe" to (recipeFile?.let(JsonValue::Str) ?: JsonValue.Null), "renderMs" to JsonValue.Num(ms)))
            table.append("<tr><td>$label</td><td><audio controls preload=\"none\" src=\"$matched\"></audio></td><td><a href=\"$file\">Native WAV</a></td></tr>\n")
        }
        for (part in FiredBronze.Part.entries) {
            for (stage in FiredBronze.Stage.entries) {
                val r = FiredBronze.Recipe(part, stage = stage)
                repeat(2) { r.render() }
                val times = List(5) { val start = System.nanoTime(); r.render(); (System.nanoTime() - start) / 1e6 }
                val id = "${part.name.lowercase()}_${stage.name.lowercase()}"
                emit(id, "${part.label}: ${stage.name.lowercase()}", r.render(), r, times.sorted()[2])
            }
            for (v in listOf(.3f, .65f)) {
                val r = FiredBronze.Recipe(part, velocity = v)
                emit("${part.name.lowercase()}_touch_${(v * 100).roundToInt()}", "${part.label}: ${(v * 100).roundToInt()}% strike", r.render(), r)
            }
        }
        // Small reusable diagnosis set: macro movement and its two important pairs.
        for (name in FiredBronze.defaults().keys) for (value in listOf(.2f, .7f)) {
            val r = FiredBronze.Recipe(FiredBronze.Part.DAYAN_OPEN, FiredBronze.defaults() + (name to value))
            emit("sweep_${name.lowercase()}_${(value * 100).roundToInt()}", "Open Skin: $name $value", r.render(), r)
        }
        for ((a, b) in listOf("TOUCH" to "PRESS", "TRAIL" to "HEAT")) for (lo in listOf(.2f, .7f)) for (hi in listOf(.2f, .7f)) {
            val r = FiredBronze.Recipe(FiredBronze.Part.BONANG_LOW, FiredBronze.defaults() + mapOf(a to lo, b to hi))
            emit("pair_${a}_${b}_${(lo * 100).roundToInt()}_${(hi * 100).roundToInt()}", "Low Kettle: $a $lo / $b $hi", r.render(), r)
        }
        emit("groove_dry", "96 BPM hybrid phrase: dry", groove(FiredBronze.Stage.DRY))
        emit("groove_amp", "96 BPM hybrid phrase: processor then amp", groove(FiredBronze.Stage.AMP))
        File(root, "manifest.json").writeText(Json.write(JsonValue.Obj(mapOf(
            "status" to JsonValue.Str("Kotlin first-build study; not heard; no authenticity or hardware acceptance claimed"),
            "modelVersion" to JsonValue.Num(FiredBronze.VERSION.toDouble()),
            "bpm" to JsonValue.Num(96.0), "clips" to JsonValue.Arr(rows)))))
        File(root, "metrics.csv").writeText(metrics.toString())
        File(root, "index.html").writeText("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Fired Bronze | First build</title><style>body{font:16px system-ui;background:#171b1c;color:#eee;max-width:1080px;margin:40px auto;padding:20px}h1{color:#e7b97b}td{padding:12px;border-bottom:1px solid #444}a{color:#e7b97b}audio{max-width:100%}table{width:100%}</style>
            <h1>Fired Bronze</h1><p>Sixteen percussion candidates through compression, saturation, dark repeats, space, then an amp.</p>
            <p>First build, not listening-approved. Start with the dry/processed phrases, then compare each source dry, processor-only, and through the amp. The research-inspired sources are authored approximations.</p>
            <p>Players use loudness-matched copies. Native WAV links retain velocity and renderer levels. Timings are warmed JVM medians on this host, not phone measurements.</p>
            <p><a href="listen/groove_dry.wav">Dry phrase</a> · <a href="listen/groove_amp.wav">Processed phrase</a> · <a href="metrics.csv">Measurements</a> · <a href="manifest.json">Manifest</a></p>
            <table><thead><tr><th>Comparison</th><th>Matched listening copy</th><th>Download</th></tr></thead><tbody>$table</tbody></table></html>""".trimIndent())
        val work = File(root, ".kit-work")
        try {
            val kit = KitAssembler.assembleArranged(FiredBronzeKits.NAME, FiredBronzeKits.study(), work, tempoBpm = 96f)
            KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        } finally { work.deleteRecursively() }
        File(root, "README.txt").writeText("""
            FIRED BRONZE v${FiredBronze.VERSION}: native Kotlin first-build study.
            Open index.html for 16 sources, dry/processor/amp comparisons, velocity, macro and pair probes.
            Load the exported program folder on MPC. Three timbre layers per pad; hats share choke group 1.
            The kit contains WAVs, not registered editable phone-engine patches. Full standalone study recipes
            are in recipes/. Replay with :synth:generateFiredBronzeAudition -PfiredBronzeReplay=<json>
            -PfiredBronzeReplayOutput=<wav>. No traditional performance, exact pedal, or hardware-load claim.
            The two 96 BPM phrases use the same authored hybrid schedule; they are not a traditional tala or gendhing.
            Source code and research specification remain in the repository; all modal constants are audition shapes.
        """.trimIndent())
        val zip = File(root.parentFile, "fired-bronze-audition.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).path }.forEach { f ->
                out.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                f.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
        }
        println("${rows.size} clips, three-layer sixteen-pad kit, recipes, metrics: ${zip.absolutePath}")
    }

    internal fun groove(stage: FiredBronze.Stage): Snip {
        data class Hit(val part: FiredBronze.Part, val beat: Double, val velocity: Float)
        val events = buildList {
            for (bar in 0..1) {
                val b = bar * 4.0
                add(Hit(FiredBronze.Part.KICK, b, 1f)); add(Hit(FiredBronze.Part.KICK, b + 2.5, .8f))
                add(Hit(FiredBronze.Part.SNARE, b + 1, .9f)); add(Hit(FiredBronze.Part.SNARE, b + 3, 1f))
                for (i in 0..7) add(Hit(if (i == 7) FiredBronze.Part.OPEN_HAT else FiredBronze.Part.CLOSED_HAT, b + i * .5, if (i % 2 == 0) .8f else .5f))
                add(Hit(FiredBronze.Part.DAYAN_OPEN, b + .75, .7f))
                add(Hit(FiredBronze.Part.DAYAN_DAMPED, b + 1.75, .55f))
                add(Hit(FiredBronze.Part.BAYAN, b + 2, .8f))
                add(Hit(FiredBronze.Part.BAMBOO, b + 3.25, .6f))
            }
            add(Hit(FiredBronze.Part.DHOLAK, 3.75, .8f))
            add(Hit(FiredBronze.Part.KENDHANG_LOW, 5.25, .75f)); add(Hit(FiredBronze.Part.KENDHANG_HIGH, 5.75, .8f))
            add(Hit(FiredBronze.Part.GHATAM, 6.75, .75f))
            add(Hit(FiredBronze.Part.BONANG_LOW, .25, .8f)); add(Hit(FiredBronze.Part.BONANG_HIGH, 4.25, .8f))
            add(Hit(FiredBronze.Part.CHHING, 7.25, .7f)); add(Hit(FiredBronze.Part.KEMPUL, 8.0, .8f))
        }.sortedBy { it.beat }
        val out = FloatArray((6.6 * Dsp.RATE).toInt())
        val cache = mutableMapOf<Pair<FiredBronze.Part, Float>, Snip>()
        fun isHat(p: FiredBronze.Part) = p == FiredBronze.Part.CLOSED_HAT || p == FiredBronze.Part.OPEN_HAT
        for ((index, hit) in events.withIndex()) {
            val s = cache.getOrPut(hit.part to hit.velocity) { FiredBronze.Recipe(hit.part, velocity = hit.velocity, stage = stage).render() }
            val start = (hit.beat * 60 / 96 * Dsp.RATE).roundToInt()
            val nextHat = if (isHat(hit.part)) events.drop(index + 1).firstOrNull { isHat(it.part) } else null
            val stop = nextHat?.let { ((it.beat - hit.beat) * 60 / 96 * Dsp.RATE).roundToInt() } ?: s.frameCount
            val count = minOf(s.frameCount, stop, out.size - start)
            for (i in 0 until count) {
                val choke = if (stop < s.frameCount) ((count - 1 - i) / (Dsp.RATE * .003f)).coerceIn(0f, 1f) else 1f
                out[start + i] += s.samples[i] * hit.part.gain * choke * .35f
            }
        }
        Dsp.fadeTail(out)
        return Snip(out, 1, Dsp.RATE)
    }
}
