package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * First dry JVM listening gate, not the six-voice factory roster. Every file is finite:
 * HOLD lengthens the maintained gesture here and supplies no sampler loop marker.
 * Native files retain the engine's ordinary output stage; matched files use the shared
 * audition gain so a longer tail does not win an A/B merely by being louder.
 */
object MeridianAuditionGenerator {
    private const val SEED = 4810
    private const val MODEL = 1
    private val voice = MeridianVoice.SWEEP
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)

    private data class Case(
        val id: String, val group: String, val title: String, val detail: String,
        val overrides: Map<String, Float> = emptyMap(),
        val midi: Int = Meridian.DEFAULT_MIDI, val velocity: Float = 1f,
    )

    private fun cases(): List<Case> = buildList {
        add(Case("default", "Contact and clocks", "The starting swing", "LATITUDE .80. Compare with opposite traversal and the exact equator."))
        add(Case("opposite", "Contact and clocks", "Opposite traversal", "LATITUDE .20; the initial orientation, material order and noise seed stay fixed.", mapOf("LATITUDE" to .2f)))
        add(Case("equator", "Contact and clocks", "Exact equator", "LATITUDE .50. The swing repeats at fixed alignment; this is the short static-contact control.", mapOf("LATITUDE" to .5f)))
        add(Case("journey-long", "Contact and clocks", "A longer material journey", "HOLD 1 extends this finite gesture. Compare with fixed-long; neither file is a held sampler loop.", mapOf("HOLD" to 1f)))
        add(Case("fixed-long", "Contact and clocks", "The same long gesture at fixed alignment", "LATITUDE .50 and HOLD 1. This removes precession from the long causal comparison.", mapOf("LATITUDE" to .5f, "HOLD" to 1f)))
        add(Case("near-equator", "Contact and clocks", "Nearly stationary", "LATITUDE .55 with the longer finite gesture. Judge how much material change a small rate reveals.", mapOf("LATITUDE" to .55f, "HOLD" to 1f)))
        for (control in listOf("LENGTH", "REACH", "SURFACE", "MEMORY")) for ((index, value) in steps.withIndex()) {
            add(Case("${control.lowercase()}-$index", "$control sweep", "$control ${number(value.toDouble(), 2)}",
                "The other controls remain at the same voice defaults. Listen across all five values.", mapOf(control to value)))
        }
        for (midi in listOf(36, 51, 84)) add(Case("note-$midi", "Register and touch", "${noteName(midi)} / same gesture",
            "Native MIDI changes the resonator tuning; the motion controls and noise seed stay fixed.", midi = midi))
        for (velocity in listOf(.25f, .6f)) add(Case("velocity-${(velocity * 100).toInt()}", "Register and touch", "Velocity ${number(velocity.toDouble(), 2)}",
            "Compare with the starting swing at velocity 1. Matched gain lets you judge contact hardness rather than overall level.", velocity = velocity))
        add(Case("latitude-surface", "Interactions", "Moving across stronger material contrast", "LATITUDE .80 × SURFACE 1; compare with the SURFACE sweep and fixed alignment.", mapOf("SURFACE" to 1f, "HOLD" to 1f)))
        add(Case("memory-latitude", "Interactions", "A long journey retaining earlier contacts", "LATITUDE .80 × MEMORY .85, finite HOLD 1. Listen for earlier surfaces behind current contact.", mapOf("MEMORY" to .85f, "HOLD" to 1f)))
        add(Case("length-reach", "Interactions", "Fast deep contact", "LENGTH .15 × REACH .90; the note is unchanged while the contact cadence and depth change.", mapOf("LENGTH" to .15f, "REACH" to .9f)))
        add(Case("surface-reach", "Interactions", "Deep contact on contrasting material", "SURFACE .90 × REACH .90; judge whether depth changes attack and material response beyond loudness.", mapOf("SURFACE" to .9f, "REACH" to .9f)))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/meridian-audition").absoluteFile
        check(root.mkdirs() || root.isDirectory) { "Cannot create ${root.path}" }
        val roster = cases()
        check(roster.size == 35 && roster.map { it.id }.distinct().size == roster.size)
        val clips = mutableListOf<Map<String, Any?>>()
        val assets = mutableListOf("manifest.json", "diagnostics.json", "index.html")
        val started = System.nanoTime()
        MeridianMotion.clearCache()

        for ((index, case) in roster.withIndex()) {
            val macros = Meridian.defaults(voice) + case.overrides
            val patch = MeridianPatch(case.title, voice, macros, case.midi, case.velocity, model = MODEL, seedContext = SEED)
            val began = System.nanoTime()
            // The probe shares the normal render pass and output arithmetic. It only adds
            // measurements, so replay below checks real recipes against these exact samples.
            val probe = Meridian.renderProbe(voice, macros, case.midi, case.velocity, SEED,
                normalize = true, taps = false)
            val native = Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE)
            val renderMs = (System.nanoTime() - began) / 1e6
            requireAudio(case.id, native)
            val matched = AuditionLevel.level(native)
            requireAudio(case.id, matched)
            val files = linkedMapOf(
                "native" to "audio/${case.id}-native.wav",
                "matched" to "audio/${case.id}-matched.wav",
                "recipe" to "recipes/${case.id}.json",
                "contacts" to "plots/${case.id}-contacts.svg",
            )
            WavWriter.write(File(root, files.getValue("native")), native, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, files.getValue("matched")), matched, WavWriter.BitDepth.PCM_16)
            val recipe = PadRecipe(patch = patch)
            val recipeText = recipe.toJsonText()
            File(root, files.getValue("recipe")).also { it.parentFile.mkdirs() }.writeText(recipeText)
            File(root, files.getValue("contacts")).also { it.parentFile.mkdirs() }.writeText(contactPlot(probe.motion))
            val restored = PadRecipe.fromJsonText(recipeText)
            check(restored.patch == patch) { "${case.id}: saved recipe changed settings" }
            // The two motion lengths and a second note exercise real render replay, not copied buffers.
            val replayChecked = case.id in setOf("default", "journey-long", "note-51")
            if (replayChecked) check(sameBits(native, restored.render())) { "${case.id}: recipe did not reproduce audio" }
            val decoded = WavReader.read(File(root, files.getValue("native")))
            requireAudio("${case.id} PCM16", decoded)
            val classification = Classifier.classify(decoded)
            val actualMidi = Meridian.midiFor(voice, macros.getValue("TUNE"), case.midi)
            val targetHz = 440.0 * 2.0.pow((actualMidi - 69) / 12.0)
            val bodyStart = minOf(.18f, native.durationSeconds / 4)
            val pitch = Pitch.detect(decoded, bodyStart, .35f)
            val tailStart = (probe.diagnostics.activeSeconds + .02).toFloat()
            val tailWindow = minOf(.35, probe.diagnostics.tailSeconds - .04).toFloat()
            val tailPitch = Pitch.detect(decoded, tailStart, tailWindow)
            val clip = linkedMapOf<String, Any?>(
                "id" to case.id, "group" to case.group, "title" to case.title, "detail" to case.detail,
                "voice" to voice.name, "model" to MODEL, "midi" to case.midi,
                "actualMidi" to actualMidi, "note" to noteName(actualMidi), "targetHz" to targetHz,
                "velocity" to case.velocity, "seedContext" to SEED, "macros" to macros,
                "files" to files, "durationSeconds" to native.durationSeconds,
                "native" to metrics(native), "matched" to metrics(matched), "decodedPcm16" to metrics(decoded),
                "diagnosticRenderMillis" to renderMs, "recipeRoundtrip" to true, "audioReplayChecked" to replayChecked,
                "nativeSamplesSha256" to samplesHash(native.samples),
                "rawEngine" to rawDiagnostics(probe.diagnostics),
                "motion" to linkedMapOf(
                    "fullSwingPeriodSeconds" to probe.motion.periodSeconds,
                    "precessionRadiansPerSecond" to probe.motion.precessionRate,
                    "amplitude" to probe.motion.amplitude,
                    "initialOrientationRadians" to probe.motion.initialOrientation,
                    "surfaceCount" to MeridianMotion.SURFACES,
                    "descriptorRate" to MeridianMotion.CONTROL_RATE,
                ),
                "pitch" to linkedMapOf(
                    "method" to "Pitch.detect on decoded native PCM16; autocorrelation with nearby spectral refinement",
                    "fromSeconds" to bodyStart, "windowSeconds" to .35f,
                    "hz" to pitch?.hz, "confidence" to pitch?.confidence,
                    "errorCents" to pitch?.let { 1200 * ln(it.hz / targetHz) / ln(2.0) },
                ),
                "unforcedTailPitch" to linkedMapOf(
                    "method" to "Pitch.detect after the new contact gate closes; nullable when the tail is too quiet",
                    "fromSeconds" to tailStart, "windowSeconds" to tailWindow,
                    "hz" to tailPitch?.hz, "confidence" to tailPitch?.confidence,
                    "errorCents" to tailPitch?.let { 1200 * ln(it.hz / targetHz) / ln(2.0) },
                ),
                "classifier" to linkedMapOf("class" to classification.drumClass.name, "confidence" to classification.confidence),
            )
            clips += clip
            assets += files.values
            println("MERIDIAN ${index + 1}/${roster.size}: ${case.id}, ${number(renderMs, 1)} ms, pitch ${pitch?.hz ?: "unresolved"} Hz")
        }

        val timing = benchmark()
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "MERIDIAN", "model" to MODEL, "voice" to voice.name,
            "sampleRate" to Dsp.RATE, "channels" to 1, "clipCount" to clips.size,
            "generatedAt" to Instant.now().toString(), "sourceCommit" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "sourceFilesSha256" to sourceHashes(), "clips" to clips,
            "warmPublicRenderBenchmark" to timing,
            "notes" to listOf(
                "One dry prototype voice. Every file is finite; HOLD controls active gesture duration and no sampler loop is exported.",
                "Native files use Meridian's shared output stage. Matched files additionally use AuditionLevel.level for listening comparisons.",
                "Native and matched WAVs are mono 44.1 kHz PCM16. Recipes preserve native note, velocity, model and seed context.",
                "Classifier LOOP is a duration category and does not imply a sampler loop marker or a seamless HOLD.",
                "Pitch.detect is a local body estimate. Contact recurrence and broad low-MEMORY resonances can alter spectral weighting; an estimate is not a whole-note tuning guarantee.",
                "Owner feedback applied to the earlier Python concept proof. This Kotlin implementation has no listening verdict yet.",
            ),
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        File(root, "diagnostics.json").writeText(Json.write(json(linkedMapOf(
            "engine" to "MERIDIAN", "model" to MODEL,
            "finiteAndUnclipped" to true, "recipeFieldsRoundtrip" to true,
            "audioRecipeReplayCases" to clips.filter { it["audioReplayChecked"] == true }.map { it["id"] },
            "benchmark" to timing, "generationSeconds" to (System.nanoTime() - started) / 1e9,
            "acceptance" to "Ready for owner listening. Prototype CPU, local pitch and classifier observations are measurements, not musical approval.",
        ))))
        File(root, "index.html").writeText(page(clips, timing))
        writeZip(root, assets)
        println("MERIDIAN wrote ${clips.size} dry comparisons, native/matched PCM16 WAVs, recipes, warm timings and audition.zip under ${root.path}")
    }

    private fun benchmark(): List<Map<String, Any?>> = listOf("default" to 0f, "long" to 1f).map { (name, hold) ->
        val macros = Meridian.defaults(voice) + ("HOLD" to hold)
        repeat(3) { Meridian.render(voice, macros, seedContext = SEED, model = MODEL) }
        var frames = 0
        val times = List(7) {
            val start = System.nanoTime()
            frames = Meridian.render(voice, macros, seedContext = SEED, model = MODEL).frameCount
            (System.nanoTime() - start) / 1e6
        }
        val sorted = times.sorted()
        linkedMapOf<String, Any?>(
            "case" to name, "warmupRenders" to 3, "measuredRenders" to times.size,
            "milliseconds" to times, "minMillis" to sorted.first(), "medianMillis" to sorted[sorted.size / 2],
            "maxMillis" to sorted.last(), "frames" to frames, "audioSeconds" to frames.toDouble() / Dsp.RATE,
            "scope" to "Warm public render on this JVM, including normal output conditioning, without diagnostic taps. Not a phone benchmark.",
        )
    }

    private fun requireAudio(id: String, snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE) { "$id: wrong output format" }
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$id: invalid samples" }
        check(snip.peak() > 1e-7f && snip.peak() < 1f) { "$id: silent or clipping output (${snip.peak()})" }
    }

    private fun metrics(snip: Snip): Map<String, Any?> = linkedMapOf(
        "peak" to snip.peak(),
        "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
        "loudness" to Loudness.of(snip),
    )

    private fun rawDiagnostics(d: Meridian.Diagnostics): Map<String, Any?> = linkedMapOf(
        "rawPeak" to d.rawPeak, "peakModalEnergySampled" to d.peakEnergy,
        "rootHz" to d.rootHz, "modalFrequenciesHz" to d.rootModalHz.toList(),
        "activeSeconds" to d.activeSeconds, "tailSeconds" to d.tailSeconds,
        "geometryCacheHit" to d.geometryCacheHit, "geometryPrepareMillis" to d.geometryPrepareNanos / 1e6,
        "perSurfaceSquaredInputSum" to d.perSurfaceInputEnergy.toList(),
        "perSurfaceInputPeak" to d.perSurfaceInputPeak.toList(),
        "perSurfaceOutputEnergy" to d.perSurfaceOutputEnergy.toList(),
        "inputHashes" to d.inputHashes.map { java.lang.Long.toUnsignedString(it, 16).padStart(16, '0') },
        "roughnessHashes" to d.roughnessHashes.map { java.lang.Long.toUnsignedString(it, 16).padStart(16, '0') },
        "triggerCounts" to d.triggerCounts.toList(), "maxContactWeightSum" to d.maxContactWeightSum,
        "note" to "Raw peak and modal energy precede output leveling. Modal energy is sampled every 256 internal frames. Input sums are squared discrete contact increments, not a mechanical work claim.",
    )

    private fun sameBits(a: Snip, b: Snip) = a.channels == b.channels && a.sampleRate == b.sampleRate &&
        a.samples.size == b.samples.size && a.samples.indices.all { a.samples[it].toRawBits() == b.samples[it].toRawBits() }

    /** Draw the exact cached descriptors used by the render, rather than a second illustrative model. */
    private fun contactPlot(plan: MeridianMotion.Plan): String = buildString {
        val palette = listOf("#ddb070", "#b8d5dc", "#6bd5cf", "#c79ce3")
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1000\" height=\"470\" viewBox=\"0 0 1000 470\"><rect width=\"1000\" height=\"470\" fill=\"#142330\"/><g fill=\"#e9e2d5\" font-family=\"sans-serif\"><text x=\"20\" y=\"29\" font-size=\"20\">Trajectory and actual per-surface contact</text><text x=\"20\" y=\"53\" font-size=\"13\">Full swing ${number(plan.periodSeconds, 3)} s · orientation rate ${number(plan.precessionRate, 3)} rad/s · gate ${number(plan.activeSeconds, 2)} s</text></g>")
        append("<circle cx=\"205\" cy=\"242\" r=\"150\" fill=\"none\" stroke=\"#526b79\"/>")
        for (j in 0 until MeridianMotion.SURFACES) {
            val angle = 2 * PI * j / MeridianMotion.SURFACES + plan.floorRotation
            val x = 205 + 150 * cos(angle)
            val y = 242 - 150 * sin(angle)
            append("<circle cx=\"${number(x, 2)}\" cy=\"${number(y, 2)}\" r=\"9\" fill=\"${palette[Meridian.materialFor(j).ordinal]}\"/><text x=\"${number(x + 12, 2)}\" y=\"${number(y + 4, 2)}\" fill=\"#e9e2d5\" font-family=\"sans-serif\" font-size=\"12\">$j</text>")
        }
        val count = 600
        val trajectory = (0..count).joinToString(" ") { i ->
            val point = plan.pointAt(i * plan.activeSeconds / count)
            "${number(205 + 150 * point.x, 2)},${number(242 - 150 * point.y, 2)}"
        }
        append("<polyline points=\"$trajectory\" fill=\"none\" stroke=\"#f5efe2\" stroke-opacity=\".45\" stroke-width=\"1\"/>")
        for (j in 0 until MeridianMotion.SURFACES) {
            val y = 112 + j * 25
            val trace = (0..count).joinToString(" ") { i ->
                val seconds = i * plan.activeSeconds / count
                val weight = plan.weightsAt(seconds)[j]
                "${number(465 + 500.0 * i / count, 2)},${number(y - 20 * weight, 2)}"
            }
            append("<line x1=\"465\" y1=\"$y\" x2=\"965\" y2=\"$y\" stroke=\"#344b59\"/><text x=\"438\" y=\"$y\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#ddd\">$j</text><polyline points=\"$trace\" fill=\"none\" stroke=\"${palette[Meridian.materialFor(j).ordinal]}\" stroke-width=\"1.5\"/>")
        }
        append("<text x=\"465\" y=\"435\" font-family=\"sans-serif\" font-size=\"13\" fill=\"#b8cad5\">Each stable surface ID, contact weight 0…1, time 0…${number(plan.activeSeconds, 2)} s →</text></svg>")
    }

    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    private fun noteName(midi: Int): String = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[Math.floorMod(midi, 12)] + (midi / 12 - 1)
    private fun number(value: Double, places: Int) = String.format(Locale.ROOT, "%.${places}f", value)
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(clips: List<Map<String, Any?>>, timing: List<Map<String, Any?>>): String = buildString {
        append("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Meridian · First Kotlin audition</title><style>body{font:16px/1.5 system-ui;background:#121e29;color:#eee7da;max-width:1000px;margin:36px auto;padding:0 22px}h1{font-size:34px}h2{margin-top:36px;font-size:24px}h3{font-size:18px;margin-bottom:6px}p{color:#bfccd4}article{border-top:1px solid #3f5360;padding:15px 0}audio{width:min(100%,650px)}a{color:#82d5d8}small{color:#aabac7}details{margin:12px 0}pre{overflow:auto;font-size:12px;background:#1b2d3b;padding:12px}.pair{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.pair span{width:80px}</style><h1>Meridian · First Kotlin audition</h1><p>A steady swing touches different fixed materials. Earlier surfaces keep ringing while new ones receive contact. One voice, dry mono, no support or effects.</p><p>These are actual Kotlin/JVM renders. The earlier Python proof received positive concept feedback; this implementation awaits listening. HOLD extends a finite gesture in this round. No seamless or indefinitely held loop is claimed.</p><p><strong>First comparison:</strong> starting swing → opposite traversal → exact equator. Then compare the two long gestures, with moving and fixed alignment. Judge whether the changing contact is clear within a useful sample, whether the requested note remains clear, and whether MEMORY preserves earlier materials.</p><p>Matched playback uses the same audition loudness rule across clips. Native playback retains the engine's ordinary conditioning and melodic leveling. Raw measurements, where provided, precede that output leveling. Files are 44.1 kHz mono PCM16.</p><p><a href="manifest.json">Complete recipes and measurements</a> · <a href="diagnostics.json">Diagnostics and warm benchmark</a> · <a href="audition.zip">Download this pack</a></p>""")
        var previous = ""
        for (clip in clips) {
            val group = clip.getValue("group").toString()
            if (group != previous) { append("<h2>${escape(group)}</h2>"); previous = group }
            @Suppress("UNCHECKED_CAST")
            val files = clip.getValue("files") as Map<String, String>
            append("<article id=\"${clip["id"]}\"><h3>${escape(clip.getValue("title").toString())}</h3><p>${escape(clip.getValue("detail").toString())}</p>")
            append("<div class=\"pair\"><span>Matched</span><audio controls preload=\"none\" src=\"${files["matched"]}\"></audio></div>")
            append("<div class=\"pair\"><span>Native</span><audio controls preload=\"none\" src=\"${files["native"]}\"></audio></div>")
            append("<small>${escape(clip.getValue("note").toString())} · velocity ${clip["velocity"]} · ${number((clip["durationSeconds"] as Number).toDouble(), 2)} s · <a href=\"${files["recipe"]}\">Saved recipe</a></small>")
            append("<details><summary>Trajectory and twelve surface contacts</summary><img style=\"width:100%\" src=\"${files["contacts"]}\" alt=\"Actual trajectory and per-surface contact-weight timelines\"></details>")
            append("<details><summary>Controls and measured evidence</summary><pre>${escape(Json.write(json(clip.filterKeys { it !in setOf("files", "nativeSamplesSha256") })))}</pre></details></article>")
        }
        append("<h2>Warm render timing</h2><p>Seven timed public renders follow three warmups per case. These are local JVM observations, with normal output conditioning and no diagnostic taps. No phone render budget is inferred.</p><pre>${escape(Json.write(json(timing)))}</pre>")
        append("<h2>Why it is built this way</h2><p>Fixed pickups make changes in contact responsible for the material journey. Each surface retains its own state so MEMORY carries the past. Separate clocks change articulation and traversal without directly multiplying oscillator pitch. The main risk remains time scale: a correct trajectory can move too little in a short sampler note.</p><script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})));</script></html>")
    }

    private fun sourceHashes(): Map<String, String> = listOf(
        "src/main/kotlin/com/snipsnap/synth/Meridian.kt",
        "src/main/kotlin/com/snipsnap/synth/MeridianMotion.kt",
        "src/main/kotlin/com/snipsnap/synth/MeridianPatch.kt",
        "src/main/kotlin/com/snipsnap/synth/Dsp.kt",
        "src/main/kotlin/com/snipsnap/synth/Modes.kt",
        "src/test/kotlin/com/snipsnap/synth/MeridianAuditionGenerator.kt",
    ).associateWith { path ->
        MessageDigest.getInstance("SHA-256").digest(File(path).readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Cannot record source provenance: $output" }
        return output
    }

    private fun writeZip(root: File, assets: List<String>) {
        ZipOutputStream(File(root, "audition.zip").outputStream().buffered()).use { zip ->
            for (name in assets.distinct().sorted()) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                File(root, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is Boolean -> JsonValue.Bool(value)
        is Number -> { check(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported audition JSON value ${value.javaClass.name}")
    }
}
