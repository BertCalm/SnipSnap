package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.*

/** A small, production-rendered before/after for the held singing-bowl motion. */
object CairnSingMotionAuditionGenerator {
    private data class Case(val id: String, val label: String, val patch: CairnPatch)
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/cairn-sing-motion").canonicalFile.apply { mkdirs() }
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, ".git").exists() }
        val revision = ProcessBuilder("git", "rev-parse", "HEAD").directory(repo).start().let {
            val value = it.inputStream.bufferedReader().readText().trim(); check(it.waitFor() == 0); value
        }
        val hashes = listOf("Cairn.kt", "CairnSing.kt", "CairnPatch.kt", "CairnTumble.kt", "Modes.kt").associateWith {
            sha(File(repo, "synth/src/main/kotlin/com/snipsnap/synth/$it").readBytes())
        }
        val voice = CairnVoice.SING; val base = Cairn.defaults(voice) + ("HOLD" to 1f)
        var start = System.nanoTime(); Cairn.inspect(voice, base, model = 5)
        val cold = (System.nanoTime() - start) / 1e6
        val cases = mutableListOf<Case>()
        fun add(id: String, label: String, model: Int = 5, note: Int = 60,
                macros: Map<String, Float> = base, velocity: Float = 1f) {
            cases += Case(id, label, CairnPatch(label, voice, macros, note, velocity, model = model))
        }
        for (note in listOf(48, 60, 72)) {
            add("before-$note", "Earlier SING · C${note / 12 - 1}", 4, note)
            add("after-$note", "Moving SING · C${note / 12 - 1}", 5, note)
        }
        for (value in listOf(0f, .5f, 1f)) add("groove-${(value * 100).roundToInt()}", "GROOVE ${(value * 100).roundToInt()}%", macros = base + ("GROOVE" to value))
        for (value in listOf(.1f, .9f)) add("body-${(value * 100).roundToInt()}", "BODY ${(value * 100).roundToInt()}%", macros = base + ("BODY" to value))
        add("soft", "Soft touch", velocity = .35f)
        for (value in listOf(0f, 1f)) add("edge-${value.toInt()}", "Controls ${value.toInt()} · C3", note = 48,
            macros = base.mapValues { if (it.key in listOf("TUNE", "HOLD")) it.value else value })
        for (model in listOf(4, 5)) add(if (model == 4) "finite-before" else "finite-after",
            "Finite SING · model $model", model, macros = base + ("HOLD" to 0f))
        val rows = cases.map { c ->
            val p = c.patch; val held = Cairn.isLoop(p.macros)
            start = System.nanoTime(); val r = Cairn.inspect(p.voice, p.macros, p.midi, p.velocity, p.startAtContact, model = p.model)
            val ms = (System.nanoTime() - start) / 1e6; val d = r.diagnostics
            val wav = file(root, "audio/${c.id}.wav"); WavWriter.write(wav, Snip(r.samples, 1, Cairn.RATE))
            file(root, "recipes/${c.id}.json").writeText(p.toJsonText())
            val warm = if (held) {
                WavWriter.write(file(root, "continuation/${c.id}.wav"), Snip(r.full, 1, Cairn.RATE))
                val reference = Cairn.inspect(p.voice, p.macros, p.midi, p.velocity, p.startAtContact,
                    Cairn.Options(fullLoopPreroll = true), p.model)
                relative(r.samples, reference.samples).also { check(it < 1e-5) }
            } else null
            val pitch = Pitch.detect(Snip(r.samples, 1, Cairn.RATE), fromSec = .6f, windowSec = .3f)
            check(r.samples.all { it.isFinite() } && d.rawPeak < 2 && abs(r.samples.average()) < .002)
            check(Classifier.classify(Snip(r.samples, 1, Cairn.RATE)).drumClass.name == "LOOP")
            if (held) check(d.seamError!! < Keys.MAX_SEAM_ERROR)
            println("${c.id} model ${p.model} seam=${d.seamError} warm=$warm")
            mapOf("id" to c.id, "label" to c.label, "audio" to "audio/${c.id}.wav", "sha256" to sha(wav.readBytes()),
                "held" to held, "midi" to p.midi, "model" to p.model, "macros" to p.macros, "velocity" to p.velocity,
                "seconds" to r.samples.size.toDouble() / Cairn.RATE, "loopStartFrame" to d.loopStartFrame,
                "seamError" to d.seamError, "warmReferenceRelativeRms" to warm, "renderMs" to ms,
                "rawPeak" to d.rawPeak, "peak" to r.samples.maxOf { abs(it) }, "dc" to r.samples.average(),
                "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
                "pitchCents" to pitch?.let { 1200 * log2(it.hz / d.requestedHz) },
                "movement" to if (held) CairnSingMotionMeasure.measure(r.samples, p.midi) else null)
        }
        check(File(root, "audio/finite-before.wav").readBytes().contentEquals(File(root, "audio/finite-after.wav").readBytes()))
        repeat(6) { Cairn.inspect(voice, base) }
        val benchmark = mutableListOf<Map<String, Any>>()
        for (model in listOf(4, 5)) for (i in 0..11) {
            val m = base + ("SLOPE" to (.311f + i * .0113f)); Cairn.clearMotionCache()
            start = System.nanoTime(); val r = Cairn.inspect(voice, m, model = model)
            benchmark += mapOf("model" to model, "uncachedMs" to (System.nanoTime() - start) / 1e6,
                "mechanicsMs" to r.diagnostics.mechanicsMs, "audioMs" to r.diagnostics.audioMs)
        }
        val a = Cairn.inspect(voice, base, 72); val b = Cairn.inspect(voice, base, 72, options = Cairn.Options(oversample = 8))
        val highRate = relative(a.samples, b.samples).also { check(it < .04) }
        val legacy = mutableListOf<Map<String, Any>>()
        for (i in args.indices) if (args[i] == "--legacy-root") {
            val source = File(args[i + 1]); val old = Json.parse(File(source, "manifest.json").readText()).obj()
            var wavs = 0; var current = 0
            for (row in old.getValue("clips").arr()) {
                val c = row.obj(); val id = c.getValue("id").str()
                val p = CairnPatch.fromJsonText(File(source, "recipes/$id.json").readText())
                val isolated = (c["isolated"] ?: c["uncoupled"])?.bool() ?: false
                val r = Cairn.inspect(p.voice, p.macros, p.midi, p.velocity, p.startAtContact,
                    Cairn.Options(disableCoupling = isolated), p.model)
                for ((folder, samples, rate) in listOf(Triple("audio", r.samples, Cairn.RATE),
                    Triple("full", r.full, Cairn.RATE), Triple("raw", r.raw, r.diagnostics.internalRate),
                    Triple("continuation", r.full, Cairn.RATE))) {
                    val original = File(source, "$folder/$id.wav"); if (!original.exists()) continue
                    val replay = file(root, "legacy-replay/${source.name}/$folder/$id.wav")
                    WavWriter.write(replay, Snip(samples, 1, rate), allowNonMpcRate = rate != Cairn.RATE)
                    check(sha(replay.readBytes()) == sha(original.readBytes())) { "Earlier audio changed: ${source.name}/$folder/$id" }; wavs++
                }
                if (p.model >= 2 && !(p.voice == voice && Cairn.isLoop(p.macros))) {
                    val latest = Cairn.inspect(p.voice, p.macros, p.midi, p.velocity, p.startAtContact,
                        Cairn.Options(disableCoupling = isolated), 5)
                    check(latest.samples.contentEquals(r.samples)) { "Unchanged gesture moved in model 5: ${source.name}/$id" }; current++
                }
            }
            legacy += mapOf("source" to source.name, "cases" to old.getValue("clips").arr().size,
                "wavs" to wavs, "changed" to 0, "unchangedThroughModel5" to current)
        }
        file(root, "manifest.json").writeText(Json.write(json(mapOf("title" to "Cairn / A moving rim",
            "generatedAt" to Instant.now().toString(), "sourceRevision" to revision, "sourceSha256" to hashes,
            "model" to 5, "coldFirstHoldMs" to cold, "benchmark" to benchmark,
            "relativeRms4x8x" to highRate, "clips" to rows, "legacy" to legacy))))
        println("SING motion wrote ${rows.size} comparisons; cold HOLD $cold ms")
    }
    private fun relative(a: FloatArray, b: FloatArray) = sqrt(a.indices.sumOf { (a[it] - b[it]).toDouble().pow(2) } / b.sumOf { it.toDouble().pow(2) })
    private fun file(root: File, name: String) = File(root, name).apply { parentFile.mkdirs() }
    private fun sha(a: ByteArray) = MessageDigest.getInstance("SHA-256").digest(a).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(a: Any?): JsonValue = when (a) {
        null -> JsonValue.Null; is String -> JsonValue.Str(a); is Boolean -> JsonValue.Bool(a)
        is Number -> JsonValue.Num(a.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(a.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(a.map(::json)); else -> error("Unsupported manifest value")
    }
}
