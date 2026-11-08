package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
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

/** Actual Kotlin renders, native recipes, matched listening copies and two identical 88 BPM grooves. */
object BoomBapAuditionGenerator {
    private const val BPM = 88.0
    private data class Hit(val part: String, val beat: Double, val velocity: Float)
    private val hits = buildList {
        for (bar in 0..1) {
            val b = bar * 4.0
            for ((beat, v) in listOf(0.0 to 1f, 1.75 to .7f, 2.5 to .85f)) add(Hit("kick", b + beat, v))
            add(Hit("snare", b + 1.015, 1f)); add(Hit("snare", b + 3.015, .9f))
            add(Hit("snare", b + 2.79, .25f)); add(Hit("snare", b + 3.79, .3f))
            for (eighth in 0..7) {
                val offbeat = eighth % 2 == 1
                val part = if (eighth == 7) "open" else "closed"
                add(Hit(part, b + eighth * .5 + if (offbeat) .035 else 0.0, if (offbeat) .55f else .8f))
            }
        }
        add(Hit("stick", 5.52, .55f))
        add(Hit("shaker", 7.25, .5f))
    }.sortedBy { it.beat }

    // Preserve the accepted two-bar comparison. The longer phrase is a separate audition.
    private val phraseHits = buildList {
        addAll(hits)
        addAll(hits.map { it.copy(beat = it.beat + 8.0) })
        removeAll { it.part == "kick" && it.beat == 5.75 }
        add(Hit("kick", 5.5, .72f))
        add(Hit("kick", 6.75, .62f))
        add(Hit("snare", 4.79, .22f))
        removeAll { it.part == "snare" && it.beat == 15.79 }
        add(Hit("stick", 14.52, .42f))
        add(Hit("snare", 15.52, .34f))
        add(Hit("snare", 15.77, .48f))
        add(Hit("kick", 15.5, .65f))
    }.sortedBy { it.beat }

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/boom-bap-audition").absoluteFile
        root.mkdirs()
        val rows = mutableListOf<Map<String, Any?>>()
        val metrics = StringBuilder("id,seconds,peak,dc,centroid_hz,render_ms\n")
        val parts = BoomBapKits.parts()
        fun emit(id: String, label: String, section: String, s: Snip, ms: Double? = null,
                 velocity: Float? = null, recipe: String? = null) {
            check(s.samples.all { it.isFinite() }) { "$id is non-finite" }
            val native = File(root, "native/$id.wav").apply { parentFile.mkdirs() }
            val matched = File(root, "listen/$id.wav").apply { parentFile.mkdirs() }
            WavWriter.write(native, s, WavWriter.BitDepth.PCM_24)
            WavWriter.write(matched, AuditionLevel.level(s), WavWriter.BitDepth.PCM_24)
            val peak = s.samples.maxOf { abs(it) }
            check(peak <= 1f) { "$id would clip on export: $peak" }
            val centroid = FeatureExtractor.extract(s).centroidHz
            metrics.append(String.format(Locale.ROOT, "%s,%.6f,%.7f,%.8f,%.2f,%s%n",
                id, s.durationSeconds, peak, s.samples.average(), centroid,
                ms?.let { String.format(Locale.ROOT, "%.3f", it) } ?: ""))
            rows += mapOf("id" to id, "label" to label, "section" to section,
                "file" to "listen/$id.wav", "native" to "native/$id.wav",
                "velocity" to velocity, "recipe" to recipe, "renderMs" to ms)
        }
        for (part in parts) {
            // Warm the JVM before timing; this is host evidence, not a phone budget claim.
            repeat(2) { part.patch.render() }
            for (processed in listOf(false, true)) {
                val chain = BoomBapKits.colorationFor(part.id).takeIf { processed }
                val recipe = PadRecipe(part.patch, chain)
                val mode = if (processed) "processed" else "dry"
                val recipePath = "recipes/${part.id}_$mode.json"
                File(root, recipePath).apply { parentFile.mkdirs(); writeText(recipe.toJsonText()) }
                val ref = recipe.render()
                val spec = Velocity.brightnessSpec(part.patch)
                for (v in listOf(.25f, .6f, 1f)) {
                    val start = System.nanoTime()
                    val s = Velocity.layerAt(ref, part.patch, chain, v, spec, useAtVelocity = true)
                    val ms = (System.nanoTime() - start) / 1e6
                    emit("${part.id}_${mode}_${(v * 100).roundToInt()}",
                        "${part.patch.name}: $mode, ${(v * 100).roundToInt()}% strike", "Hits", s, ms, v, recipePath)
                }
                if (part.id == "snare") emit("snare_${mode}_old_soft", "Snare: $mode, old filtered ghost",
                    "Ghost comparison", Velocity.soften(ref, .75f), velocity = .25f, recipe = recipePath)
            }
        }
        emit("groove_dry", "88 BPM: dry kit", "Groove", groove(processed = false))
        emit("groove_processed", "88 BPM: processed kit", "Groove", groove(processed = true))
        emit("phrase_dry", "88 BPM: four-bar phrase, dry", "Groove", groove(false, fourBars = true))
        emit("phrase_processed", "88 BPM: four-bar phrase, processed", "Groove", groove(true, fourBars = true))
        // Warmed medians avoid treating the first JIT/GC event as the cost of a knob turn.
        val timings = parts.associate { part ->
            part.id to listOf(false, true).associate { processed ->
                val recipe = PadRecipe(part.patch, BoomBapKits.colorationFor(part.id).takeIf { processed })
                repeat(3) { recipe.render() }
                val runs = List(7) { val start = System.nanoTime(); recipe.render(); (System.nanoTime() - start) / 1e6 }
                (if (processed) "processedMedianMs" else "dryMedianMs") to runs.sorted()[3]
            }
        }
        val manifest = mapOf("status" to "More Snap: user-accepted sound; four-bar phrase awaiting listening; MPC hardware unverified", "bpm" to BPM,
            "leveling" to "Listening copies match loudest-200ms RMS; native files retain renderer levels. Groove dynamics are retained within each clip.",
            "replay" to "Velocity.layerAt(recipe.render(), recipe.patch, recipe.fx, velocity, Velocity.brightnessSpec(recipe.patch)). Old_soft clips use Velocity.soften(reference, .75).",
            "clips" to rows, "timings" to timings,
            "events" to hits.map { mapOf("part" to it.part, "beat" to it.beat, "velocity" to it.velocity) },
            "phraseEvents" to phraseHits.map { mapOf("part" to it.part, "beat" to it.beat, "velocity" to it.velocity) })
        val json = Json.write(value(manifest))
        File(root, "manifest.json").writeText(json)
        File(root, "metrics.csv").writeText(metrics.toString())
        val page = requireNotNull(javaClass.getResourceAsStream("/audition/boom-bap-audition.html"))
            .bufferedReader().use { it.readText() }
        File(root, "index.html").writeText(page.replace("/*MANIFEST*/{}", json))
        File(root, "README.txt").writeText("Backroom study: six editable pads, three velocity layers each. Open index.html for matched listening copies. Load the XPM folder on MPC for the processed kit. A02 holds the ghost snare in its lower velocity zones. More Snap sound accepted by the user; four-bar phrase listening and MPC hardware loading remain open. See manifest.json for the identical dry/wet groove event schedule and recipes.\n")
        val work = File(root, ".kit-work")
        try {
            val kit = KitAssembler.assembleArranged(BoomBapKits.NAME, BoomBapKits.study(), work)
            KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
        } finally { work.deleteRecursively() }
        val zip = File(root.parentFile, "boom-bap-audition.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).path }.forEach { f ->
                out.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                f.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
        }
        println("${rows.size} clips, recipes, metrics, six-pad XPM kit and ${zip.absolutePath}")
        println(Json.write(value(timings)))
    }

    internal fun groove(processed: Boolean, fourBars: Boolean = false): Snip {
        val events = if (fourBars) phraseHits else hits
        val beats = if (fourBars) 16 else 8
        val parts = BoomBapKits.parts().associateBy { it.id }
        fun fxFor(id: String) = BoomBapKits.colorationFor(id).takeIf { processed }
        val references = parts.mapValues { (_, p) -> PadRecipe(p.patch, fxFor(p.id)).render() }
        val cache = mutableMapOf<Pair<String, Float>, Snip>()
        val rate = Dsp.RATE
        val secondsPerBeat = 60.0 / BPM
        val out = FloatArray(((beats * secondsPerBeat + 1.5) * rate).roundToInt())
        for ((index, hit) in events.withIndex()) {
            val part = parts.getValue(hit.part)
            val s = cache.getOrPut(hit.part to hit.velocity) {
                Velocity.layerAt(references.getValue(hit.part), part.patch, fxFor(part.id), hit.velocity,
                    Velocity.brightnessSpec(part.patch), useAtVelocity = true)
            }
            val start = (hit.beat * secondsPerBeat * rate).roundToInt()
            // Match the kit's hat choke group, including a short ramp instead of a click.
            val nextHat = if (hit.part in listOf("open", "closed"))
                events.drop(index + 1).firstOrNull { it.part in listOf("open", "closed") } else null
            val stop = nextHat?.let { ((it.beat - hit.beat) * secondsPerBeat * rate).roundToInt() }
                ?: s.frameCount
            val count = minOf(s.frameCount, stop, out.size - start)
            val fade = (rate * .003).toInt()
            for (i in 0 until count) {
                val choke = if (stop < s.frameCount && i >= count - fade) (count - i).toFloat() / fade else 1f
                out[start + i] += s.samples[i] * part.mixGain * hit.velocity * choke
            }
        }
        // One common gain for both mixes, so neither is independently peak-normalized here.
        for (i in out.indices) out[i] *= .5f
        Dsp.fadeTail(out)
        return Snip(out, 1, rate)
    }

    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Number -> JsonValue.Num(v.toDouble())
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to value(it.value) })
        is List<*> -> JsonValue.Arr(v.map(::value))
        else -> error("unsupported JSON value: $v")
    }
}
