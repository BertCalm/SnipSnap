package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/** Same scores and controls in frozen model 3 and hardened model 4. No production registry edits. */
object OtisHardeningStudy {
    data class Case(val id: String, val title: String, val description: String, val request: OtisPrototypeRequest)

    fun cases(): List<Case> = buildList {
        for (voice in OtisPrototypeVoice.entries) {
            val name = when (voice) {
                OtisPrototypeVoice.HARP -> "Blues harmonica"
                else -> voice.name.lowercase().replaceFirstChar { it.uppercase() }
            }
            for ((root, label) in listOf(48 to "C3", 60 to "C4", 72 to "C5")) {
                add(Case("${voice.name.lowercase()}_${label.lowercase()}", "$name · $label",
                    "Same $label note, 1.5-second gate and .80 playing strength. Compare the body and the end of the note.",
                    OtisPrototypeRequest(voice, listOf(OtisPrototypeNote(midi = root)), model = 4)))
            }
        }
        for (case in OtisPresetStudy.cases().filter { it.group == "Note" }) {
            add(Case(case.id, case.title, case.description, case.request.copy(model = 4)))
        }
        for (voice in listOf(OtisPrototypeVoice.ORGAN, OtisPrototypeVoice.SQUEEZE)) {
            add(Case("${voice.name.lowercase()}_chord", "${voice.name.lowercase().replaceFirstChar { it.uppercase() }} · Cm7",
                "One simultaneous C minor seventh; the same four notes feed one continuous render.",
                OtisPrototypeRequest(voice, listOf(60, 63, 67, 70).map { OtisPrototypeNote(midi = it, velocity = .68f) }, model = 4)))
        }
        val phrase = listOf(60, 63, 67, 65).mapIndexed { index, midi ->
            OtisPrototypeNote(midi, startSeconds = index * .52, gateSeconds = .4)
        }
        add(Case("organ_rotary_phrase", "Organ · turning phrase",
            "The same phrase moves through one cabinet, accelerating from slow toward fast.",
            OtisPrototypeRequest(OtisPrototypeVoice.ORGAN, phrase,
                organ = OtisOrganSettings(rotary = true, rotaryChangeSeconds = .2), model = 4)))
        val front = OtisPresetStudy.cases().first { it.presetId == "organ_front_pew" && it.group == "Note" }.request
        add(Case("organ_delayed", "Organ · space before the note",
            "Half a second of silence before Front Pew. Compare the beginning and the final release.",
            front.copy(notes = listOf(OtisPrototypeNote(startSeconds = .5)), model = 4)))
        add(Case("organ_short_tap", "Organ · short tap, slow attack",
            "A 40 ms tap with a two-second attack and three-second release. The refined version releases from the level reached at key-up.",
            OtisPrototypeRequest(OtisPrototypeVoice.ORGAN, listOf(OtisPrototypeNote(gateSeconds = .04)),
                organ = OtisOrganSettings(drawbars = List(9) { if (it == 2) 1f else 0f }, click = 0f,
                    percussionLevel = 0f, leakage = 0f, drive = 0f, attackSeconds = 2.0, releaseSeconds = 3.0,
                    cabinetHz = 20_000.0), model = 4)))
    }

    @JvmStatic fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/otis-hardening/refinements").canonicalFile.also { it.mkdirs() }
        val cases = cases()
        check(cases.map { it.id }.distinct().size == cases.size)
        val renders = cases.associate { case ->
            println("OTIS hardening ${case.id}")
            case.id to listOf(case.request.copy(model = 3).render(), case.request.copy(model = 4).render())
        }
        val peak = renders.values.flatten().maxOf { it.peak() }
        val rawGain = minOf(1f, .98f / peak.coerceAtLeast(.00001f))
        val records = cases.map { case ->
            val variants = renders.getValue(case.id).mapIndexed { index, source ->
                val model = index + 3
                val stem = "${case.id}.model$model"
                val raw = Snip(FloatArray(source.samples.size) { source.samples[it] * rawGain }, 1, Dsp.RATE)
                val matched = AuditionLevel.level(source)
                val rawFile = WavWriter.write(File(out, "$stem.raw.wav"), raw)
                val matchedFile = WavWriter.write(File(out, "$stem.matched.wav"), matched)
                val request = case.request.copy(model = model)
                val recipeFile = File(out, "$stem.json").also { it.writeText(OtisPrototypeRecipe.toJsonText(request) + "\n") }
                linkedMapOf("id" to "model$model", "label" to if (model == 3) "Previous" else "Refined",
                    "raw" to rawFile.name, "matched" to matchedFile.name, "recipe" to recipeFile.name,
                    "rawSha256" to sha256(rawFile), "matchedSha256" to sha256(matchedFile),
                    "measurements" to metrics(source), "model" to model)
            }
            linkedMapOf("id" to case.id, "title" to case.title, "family" to case.request.voice.name,
                "description" to case.description, "variants" to variants)
        }
        val special = renders.getValue("organ_delayed").map { x -> mapOf(
            "leadRms" to rms(x.samples, .05, .45),
            "tailRms" to rms(x.samples, x.durationSeconds - .036, x.durationSeconds - .004)) }
        val release = renders.getValue("organ_short_tap").map { x -> mapOf(
            "beforeKeyUpRms" to rms(x.samples, .025, .04), "laterRms" to rms(x.samples, .95, 1.05)) }
        val timings = if ("--timings" in args) benchmark() else emptyMap<String, Any>()
        val files = listOf("OtisPrototype.kt", "OtisPrototypeLegacy.kt", "OtisPrototypeV4.kt", "OtisOrganPrototype.kt",
            "OtisOrganPrototypeV4.kt", "OtisAirPrototype.kt", "OtisAirPrototypeV4.kt", "OtisPrototypeRecipe.kt", "OtisHardeningStudy.kt")
        val hashes = files.associateWith { sha256(File("src/test/kotlin/com/snipsnap/synth", it)) }
        val manifest = mapOf("format" to "snipsnap-otis-hardening", "models" to listOf(3, 4), "cases" to records,
            "rawPcmGain" to rawGain, "sourceHashes" to hashes,
            "matching" to "AuditionLevel matches the loudest 200ms window to .03 with a .99 peak guard.",
            "dcDiagnostic" to special, "releaseDiagnostic" to release, "timings" to timings,
            "limits" to listOf("Finite research renderer; no production registry or HOLD claim.",
                "Model4 uses causal20Hz DC removal and120ms settling instead of whole-file mean subtraction.",
                "Organ chord percussion still follows its historical simultaneous-note order policy.",
                "Raw source peaks may exceed unity; all exported raw WAVs share one disclosed gain."))
        File(out, "manifest.json").writeText(Json.write(value(manifest)) + "\n")
    }

    private fun benchmark(): Map<String, Any> = buildMap {
        for (voice in OtisPrototypeVoice.entries) for (chord in listOf(false, true)) {
            val notes = if (chord) listOf(60, 63, 67, 70).map { OtisPrototypeNote(midi = it) } else listOf(OtisPrototypeNote())
            for (model in listOf(3, 4)) {
                val request = OtisPrototypeRequest(voice, notes, model = model)
                repeat(2) { request.render() }
                val elapsed = List(5) {
                    val start = System.nanoTime()
                    val x = request.render()
                    check(x.samples.all { it.isFinite() })
                    (System.nanoTime() - start) / 1e6
                }.sorted()
                put("${voice.name}_${if (chord) "chord4" else "single"}_model$model", mapOf("medianMs" to elapsed[2],
                    "minMs" to elapsed.first(), "maxMs" to elapsed.last(), "repeats" to 5))
            }
        }
        put("environment", mapOf("java" to System.getProperty("java.version"), "scope" to "Warmed serial JVM, not Android", "gateSeconds" to 1.5))
    }

    private fun rms(x: FloatArray, from: Double, until: Double): Double {
        val start = (from * Dsp.RATE).toInt().coerceAtLeast(0)
        val end = (until * Dsp.RATE).toInt().coerceAtMost(x.size)
        return sqrt((start until end).sumOf { x[it].toDouble() * x[it] } / maxOf(1, end - start))
    }
    private fun metrics(x: Snip) = mapOf("frames" to x.frameCount, "seconds" to x.durationSeconds,
        "peak" to x.peak(), "mean" to x.samples.map { it.toDouble() }.average(), "finite" to x.samples.all { it.isFinite() })
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Number -> JsonValue.Num(v.toDouble())
        is Boolean -> JsonValue.Bool(v)
        is Map<*, *> -> JsonValue.Obj(v.entries.associateTo(linkedMapOf()) { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map { value(it) })
        else -> error("Unsupported study value ${v.javaClass}")
    }
}
