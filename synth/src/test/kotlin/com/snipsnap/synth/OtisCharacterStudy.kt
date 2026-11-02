package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/** Listener-led model5 air character, compared at the same notes/controls against frozen model4. */
object OtisCharacterStudy {
    data class Case(val id: String, val title: String, val description: String,
        val request: OtisPrototypeRequest, val independent: Boolean = false)

    fun cases(): List<Case> = buildList {
        fun note(midi: Int, gate: Double = 1.5, velocity: Float = .8f) = OtisPrototypeNote(midi, gateSeconds = gate, velocity = velocity)
        fun request(voice: OtisPrototypeVoice, notes: List<OtisPrototypeNote>, air: OtisAirSettings = OtisAirSettings()) =
            OtisPrototypeRequest(voice, notes, air = air, model = 5, sharedPressure = .85f)
        val steam = OtisPrototypeVoice.STEAM
        add(Case("steam_c4", "Steam · held C4", "Does the held body carry more weight and pressure at the same listening level?",
            request(steam, listOf(note(60)))))
        add(Case("steam_c3", "Steam · low C3", "Check whether the extra body stays full in the low register.",
            request(steam, listOf(note(48)))))
        add(Case("steam_chord", "Steam · open fifth and octave", "C3, C4 and G4 open together. Compare New Shared against New Independent to hear the player budget.",
            request(steam, listOf(48, 60, 67).map { note(it, velocity = .7f) }), independent = true))
        add(Case("steam_pushed", "Steam · pushed phrase", "Higher pressure, less breath noise. Is this enough power, or does the pipe become too brassy?",
            request(steam, listOf(60, 63, 67, 60).mapIndexed { i, midi -> OtisPrototypeNote(midi, i * .42, .34, .9f) },
                OtisAirSettings(pressure = .95f, breath = .18f, steamInstability = .65f))))

        val harp = OtisPrototypeVoice.HARP
        add(Case("harp_blow_chord", "Harp · C major chord", "C4, E4 and G4 in a sustained blow-style gesture. Does the reed and mouth colour read as harmonica now?",
            request(harp, listOf(60, 64, 67).map { note(it, velocity = .78f) })))
        add(Case("harp_draw_chord", "Harp · G7 chord", "G4, B4, D5 and F5 in a draw-style gesture. Listen for the bright chord body and the shared breath.",
            request(harp, listOf(67, 71, 74, 77).map { note(it, velocity = .78f) }), independent = true))
        add(Case("harp_single", "Harp · one G4", "One reed line after the chords. Does its identity survive on its own?",
            request(harp, listOf(note(67)))))
        add(Case("harp_stabs", "Harp · tongue-shaped chord stabs", "Three short C-major chord stabs. Judge the front of each chord and whether it releases cleanly.",
            request(harp, listOf(0.0, .45, .9).flatMap { start -> listOf(60, 64, 67).map { OtisPrototypeNote(it, start, .19, .82f) } })))
        add(Case("harp_blues", "Harp · short blues phrase", "A G-centred blues phrase with stronger bend and breath. The instrument likeness is yours to judge.",
            request(harp, listOf(67, 70, 72, 73, 74, 77, 67).mapIndexed { i, midi -> OtisPrototypeNote(midi, i * .30, .25, .84f) },
                OtisAirSettings(harpBend = .8f, breath = .5f, pressure = .8f, harpVibrato = .45f))))

        val squeeze = OtisPrototypeVoice.SQUEEZE
        add(Case("squeeze_dry", "Squeeze · dry C4", "Detuning is off and the pulse is low. Does the new nasal bite and rasp have an identity without shimmer?",
            request(squeeze, listOf(note(60)), OtisAirSettings(musette = 0f, bellowsPulse = .1f))))
        add(Case("squeeze_c4", "Squeeze · moving C4", "The same default controls as before. Compare the attack, the held reed colour and the close.",
            request(squeeze, listOf(note(60)))))
        add(Case("squeeze_chord", "Squeeze · C minor seventh", "C4, E-flat4, G4 and B-flat4 share one player budget. Does the character hold together as a chord?",
            request(squeeze, listOf(60, 63, 67, 70).map { note(it, velocity = .68f) })))
        add(Case("squeeze_shared", "Squeeze · two keys, one breath", "Hold C4; E-flat4 joins, then leaves. Shared is the preferred direction; Independent isolates the interaction from the new reed character.",
            request(squeeze, OtisPhrasePressureStudy.notes()), independent = true))
    }

    @JvmStatic fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/otis-character-v5").canonicalFile.also { it.mkdirs() }
        val cases = cases()
        check(cases.size == 13 && cases.map { it.id }.distinct().size == cases.size)
        val recipes = cases.associate { case -> case.id to buildList {
            add("model4" to case.request.copy(model = 4))
            add("model5_shared" to case.request)
            if (case.independent) add("model5_independent" to case.request.copy(sharedPressure = 0f))
        } }
        val renders = cases.associate { case ->
            println("OTIS character ${case.id}")
            case.id to recipes.getValue(case.id).map { (_, request) -> request.render() }
        }
        val largestPeak = renders.values.flatten().maxOf { it.peak() }
        val rawGain = minOf(1f, .98f / largestPeak.coerceAtLeast(.00001f))
        val records = cases.map { case ->
            val variants = recipes.getValue(case.id).mapIndexed { index, (id, request) ->
                val source = renders.getValue(case.id)[index]
                check(source.samples.all { it.isFinite() } && source.channels == 1 && source.sampleRate == Dsp.RATE)
                val raw = Snip(FloatArray(source.samples.size) { source.samples[it] * rawGain }, 1, Dsp.RATE)
                val matched = AuditionLevel.level(source)
                val stem = "${case.id}.$id"
                val rawFile = WavWriter.write(File(out, "$stem.raw.wav"), raw)
                val matchedFile = WavWriter.write(File(out, "$stem.matched.wav"), matched)
                val recipeFile = File(out, "$stem.json").also { it.writeText(OtisPrototypeRecipe.toJsonText(request) + "\n") }
                val restored = OtisPrototypeRecipe.fromJsonText(recipeFile.readText()).render()
                check(source.samples.contentEquals(restored.samples)) { "$stem recipe did not replay its PCM" }
                linkedMapOf("id" to id, "label" to when (id) {
                    "model4" -> "Earlier"
                    "model5_shared" -> "New · Shared"
                    else -> "New · Independent"
                }, "model" to request.model, "sharedPressure" to if (request.model == 5) request.sharedPressure else 0f,
                    "raw" to rawFile.name, "matched" to matchedFile.name, "recipe" to recipeFile.name,
                    "rawSha256" to sha256(rawFile), "matchedSha256" to sha256(matchedFile),
                    "recipeSha256" to sha256(recipeFile), "measurements" to metrics(source),
                    "pressure" to pressureDiagnostics(request))
            }
            linkedMapOf("id" to case.id, "title" to case.title, "family" to case.request.voice.name,
                "description" to case.description, "initialVariant" to "model5_shared", "variants" to variants,
                "details" to "Earlier uses model4; New uses model5. The score, controls and seed match. " +
                    "New Shared uses the preferred .85 player-budget strength; new single reference notes draw no excess pressure. " +
                    "These are source sketches and musical gestures, not an acoustic instrument simulation.")
        }
        val sourceNames = listOf("OtisPrototype.kt", "OtisPrototypeRecipe.kt", "OtisPrototypeV4.kt", "OtisPrototypeV5.kt",
            "OtisAirCharacterV5.kt", "OtisSteamCharacterV5.kt", "OtisHarpCharacterV5.kt", "OtisSqueezeCharacterV5.kt",
            "OtisCharacterStudy.kt", "OtisPhrasePressureStudy.kt")
        val manifest = linkedMapOf<String, Any>("format" to "snipsnap-otis-character-study", "model" to 5,
            "baseCommit" to "310bee383bcd17a7c8f7a5bda06500e4b30e8922", "cases" to records,
            "rawPcmGain" to rawGain, "rawPeakBeforeGain" to largestPeak, "replay" to "All 29 variant recipes rendered twice with exact sample equality",
            "matching" to "House Loudness.of target .03: loudest 200 ms after gentle 120 Hz weighting, peak guard .99. Source WAVs share one headroom gain.",
            "sourceHashes" to (sourceNames + listOf("OtisAirPrototype.kt", "OtisAirPrototypeV4.kt"))
                .associateWith { sha256(File("src/test/kotlin/com/snipsnap/synth", it)) },
            "timings" to benchmark(),
            "pressureController" to mapOf("strength" to .85, "floor" to .4, "drawSeconds" to .045,
                "recoverSeconds" to .20, "requestedControlRateHz" to 1000, "scope" to "Reference key envelopes control source level and upper harmonic opening"),
            "heard" to listOf("Josh hears models 3 and 4 as the same", "Steam needs more power", "Harp does not yet read as harmonica; audition chords",
                "Squeeze can gain further character", "Shared pressure preferred"),
            "limits" to listOf("Model 1–4 audio and Organ model 4 are preserved", "No HOLD, registry or Android integration",
                "Feedforward reed and pipe approximations; no physical authenticity claim", "Shape constants and new timbres await listening"))
        File(out, "manifest.json").writeText(Json.write(value(manifest)) + "\n")
        val groups = listOf(OtisPrototypeVoice.STEAM, OtisPrototypeVoice.HARP, OtisPrototypeVoice.SQUEEZE).map { voice ->
            mapOf("id" to voice.name.lowercase(), "title" to if (voice == OtisPrototypeVoice.HARP) "Harp" else voice.name.lowercase().replaceFirstChar { it.uppercase() },
                "description" to when (voice) {
                    OtisPrototypeVoice.STEAM -> "More body and pressure-driven edge. Compare at Even level before judging Source level."
                    OtisPrototypeVoice.HARP -> "Start with the chords, then the single note and phrase. Does it read as blues harmonica?"
                    else -> "A drier nasal reed, uneven bite and pressure-shaped rasp. Judge whether it earns its own place."
                }, "cases" to records.filter { it["family"] == voice.name })
        }
        val study = mapOf("title" to "Otis, more character.", "description" to "Steam power, harmonica chords and a more distinct Squeeze. Shared breath is the new starting point.",
            "groups" to groups, "summary" to mapOf("manifest" to "manifest.json", "sharedPressure" to .85,
                "earlier" to "Model 4 preserved; Organ kept unchanged", "matching" to manifest.getValue("matching"),
                "scope" to "Finite prototype; character listening acceptance is open"))
        File(out, "study.json").writeText(Json.write(value(study)) + "\n")
        println("OTIS character: ${records.size} comparisons, ${renders.values.sumOf { it.size }} variants, raw gain=$rawGain")
    }

    private fun pressureDiagnostics(request: OtisPrototypeRequest): Map<String, Any> {
        if (request.model < 5) return mapOf("strength" to 0, "minimum" to 1, "maximum" to 1)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val frames = OtisAirCharacterV5.frameCount(request.voice, request.notes, request.air, rate)
        val trace = OtisAirCharacterV5.trace(request.voice, request.notes, request.air, frames, request.sharedPressure, rate)
        return mapOf("strength" to request.sharedPressure, "minimum" to trace.pressure.min(), "maximum" to trace.pressure.max(),
            "final" to trace.pressure.last(), "maximumDemand" to trace.demand.max(), "controlRateHz" to trace.controlRateHz)
    }

    private fun benchmark(): Map<String, Any> = buildMap {
        for (voice in listOf(OtisPrototypeVoice.STEAM, OtisPrototypeVoice.HARP, OtisPrototypeVoice.SQUEEZE)) {
            for (chord in listOf(false, true)) for (model in listOf(4, 5)) {
                val notes = (if (chord) listOf(60, 64, 67) else listOf(60)).map {
                    OtisPrototypeNote(it, gateSeconds = 1.5)
                }
                val request = OtisPrototypeRequest(voice, notes, model = model)
                repeat(2) { request.render() }
                val times = List(3) {
                    val start = System.nanoTime()
                    val output = request.render()
                    check(output.samples.all { it.isFinite() })
                    (System.nanoTime() - start) / 1e6
                }.sorted()
                put("${voice.name}_${if (chord) "chord3" else "single"}_model$model",
                    mapOf("medianMs" to times[1], "minMs" to times.first(), "maxMs" to times.last()))
            }
        }
        put("scope", "Warmed serial JVM; 2 warmups, 3 timed renders, 1.5-second gates; not Android")
    }

    private fun metrics(x: Snip): Map<String, Any> = mapOf("frames" to x.frameCount, "seconds" to x.durationSeconds,
        "peak" to x.peak(), "mean" to x.samples.sumOf { it.toDouble() } / x.samples.size,
        "rms" to sqrt(x.samples.sumOf { it.toDouble() * it } / x.samples.size),
        "lastSample" to x.samples.last(), "maximumStep" to (1 until x.samples.size).maxOf { abs(x.samples[it] - x.samples[it - 1]) })

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Number -> JsonValue.Num(v.toDouble())
        is Map<*, *> -> JsonValue.Obj(v.entries.associateTo(linkedMapOf()) { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map { value(it) })
        else -> error("unsupported manifest value $v")
    }
}
