package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest

/** Four focused comparisons for the reported loss of chord vibrato and bellows draw. */
object OtisSqueezeDrawStudy {
    data class Case(val id: String, val title: String, val description: String, val request: OtisPrototypeRequest,
        val reference: Boolean = false)

    fun cases(): List<Case> {
        val chord = OtisCharacterStudy.cases().first { it.id == "squeeze_chord" }.request.copy(model = 6)
        return listOf(
            Case("chord", "The same C minor seventh", "Start here. Does Restored bring back the vibrato and bellows draw while keeping the new reed character?",
                chord, reference = true),
            Case("long_chord", "A longer chord draw", "The same four notes held for 3.6 seconds. Listen for one breathing movement through the whole chord.",
                chord.copy(notes = chord.notes.map { it.copy(gateSeconds = 3.6) })),
            Case("joining_key", "A second key joins", "Hold C4, add E-flat4, then release it. Shared pressure still responds to the second key; the moving reed body stays present.",
                OtisPrototypeRequest(OtisPrototypeVoice.SQUEEZE, OtisPhrasePressureStudy.notes(), model = 6)),
            Case("single", "Single-note reference", "This reference-strength C4 is preserved exactly. The revision targets the loss of movement under load.",
                OtisPrototypeRequest(OtisPrototypeVoice.SQUEEZE, model = 6)))
    }

    @JvmStatic fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/otis-squeeze-draw").canonicalFile.also { it.mkdirs() }
        val cases = cases()
        val recipes = cases.associate { c -> c.id to buildList {
            add(c.request.copy(model = 5))
            add(c.request)
            if (c.reference) add(c.request.copy(model = 4))
        } }
        val renders = recipes.mapValues { (id, requests) ->
            println("OTIS Squeeze draw $id")
            requests.map { it.render() }
        }
        val maxPeak = renders.values.flatten().maxOf { it.peak() }
        val gain = minOf(1f, .98f / maxPeak.coerceAtLeast(.00001f))
        val records = cases.map { c ->
            val variants = recipes.getValue(c.id).mapIndexed { index, request ->
                val source = renders.getValue(c.id)[index]
                val stem = "${c.id}.model${request.model}"
                check(source.samples.all { it.isFinite() })
                val recipe = File(out, "$stem.json").also { it.writeText(OtisPrototypeRecipe.toJsonText(request) + "\n") }
                val replay = OtisPrototypeRecipe.fromJsonText(recipe.readText()).render()
                check(source.samples.contentEquals(replay.samples)) { "$stem lost its exact recipe replay" }
                val raw = WavWriter.write(File(out, "$stem.raw.wav"), Snip(FloatArray(source.samples.size) { source.samples[it] * gain }, 1, Dsp.RATE))
                val matched = WavWriter.write(File(out, "$stem.matched.wav"), AuditionLevel.level(source))
                val rate = Dsp.RATE * Dsp.OVERSAMPLE
                val frames = OtisAirCharacterV5.frameCount(request.voice, request.notes, request.air, rate)
                val trace = OtisAirCharacterV5.trace(request.voice, request.notes, request.air, frames,
                    if (request.model >= 5) request.sharedPressure else 0f, rate)
                linkedMapOf("id" to "model${request.model}", "label" to when (request.model) {
                    5 -> "Before · Shared"
                    6 -> "Restored · Shared"
                    else -> "Earlier feel · Independent"
                }, "model" to request.model, "sharedPressure" to if (request.model >= 5) request.sharedPressure else 0f,
                    "raw" to raw.name, "matched" to matched.name, "recipe" to recipe.name,
                    "rawSha256" to sha256(raw), "matchedSha256" to sha256(matched), "recipeSha256" to sha256(recipe),
                    "minimumPressure" to trace.pressure.min(), "maximumDemand" to trace.demand.max(),
                    "measurements" to mapOf("frames" to source.frameCount, "sourcePeak" to source.peak(),
                        "mean" to source.samples.sumOf { it.toDouble() } / source.samples.size))
            }
            mapOf("id" to c.id, "title" to c.title, "description" to c.description, "family" to "SQUEEZE",
                "initialVariant" to "model6", "variants" to variants,
                "details" to "Before and Restored have identical notes, controls, seed and .85 Shared pressure. " +
                    "Restored retains more upper-reed body when the chord draws pressure down, with a common swell and small vibrato. " +
                    "The optional earlier-feel reference is model4, with its independent pressure and older reed colour.")
        }
        val sourceNames = listOf("OtisPrototype.kt", "OtisPrototypeRecipe.kt", "OtisPrototypeV4.kt", "OtisPrototypeV5.kt",
            "OtisPrototypeV6.kt", "OtisAirCharacterV5.kt", "OtisAirPrototypeV4.kt", "OtisAirPrototype.kt",
            "OtisSqueezeCharacterV5.kt", "OtisSqueezeCharacterV6.kt", "OtisSqueezeDrawStudy.kt", "OtisCharacterStudy.kt")
        val manifest = mapOf("format" to "snipsnap-otis-squeeze-draw-study", "model" to 6,
            "baseCommit" to "310bee383bcd17a7c8f7a5bda06500e4b30e8922", "cases" to records,
            "rawPcmGain" to gain, "sourcePeakBeforeGain" to maxPeak,
            "sourceHashes" to sourceNames.associateWith { sha256(File("src/test/kotlin/com/snipsnap/synth", it)) },
            "matching" to "House Loudness.of target .03 after gentle 120 Hz weighting, loudest 200 ms; .99 peak guard. Source WAVs share one headroom gain.",
            "replay" to "All nine variants rendered twice with exact Float sample equality",
            "timings" to timings(cases.first().request),
            "heard" to "Josh reports loss of Squeeze chord vibrato/draw in model5; the other sounds improved.",
            "preserved" to "Models1–5, model5 Steam/Harp/Organ, and the unloaded reference Squeeze remain exact.",
            "limits" to listOf("Shape constants and restored feel await listening", "Finite prototype, no HOLD or production integration",
                "A musical bellows analogy, not physical reed or air simulation", "Loaded pitch vibrato is intentional and bounded by PULSE*(8+14*MUSETTE) cents"))
        File(out, "manifest.json").writeText(Json.write(value(manifest)) + "\n")
        val study = mapOf("title" to "Squeeze, with room to breathe.",
            "description" to "The chord should keep its vibrato and draw. Compare the previous Shared take with the restored motion.",
            "groups" to listOf(mapOf("id" to "squeeze", "title" to "Squeeze", "description" to "Start with the same chord, then try the longer draw. Shared remains selected.", "cases" to records)),
            "summary" to mapOf("scope" to "Squeeze chord motion only; the improved Steam and Harp stay on model5.",
                "matching" to manifest.getValue("matching"), "manifest" to "manifest.json"))
        File(out, "study.json").writeText(Json.write(value(study)) + "\n")
        check(renders.getValue("single")[0].samples.contentEquals(renders.getValue("single")[1].samples))
        println("OTIS Squeeze draw: 4 comparisons, 9 variants, 18 WAVs; single reference unchanged")
    }

    private fun timings(request: OtisPrototypeRequest): Map<String, Any> = buildMap {
        for (model in listOf(5, 6)) {
            val r = request.copy(model = model)
            repeat(2) { r.render() }
            val elapsed = List(3) {
                val start = System.nanoTime()
                val output = r.render()
                check(output.samples.all { it.isFinite() })
                (System.nanoTime() - start) / 1e6
            }.sorted()
            put("model$model", mapOf("medianMs" to elapsed[1], "minMs" to elapsed.first(), "maxMs" to elapsed.last()))
        }
        put("scope", "Warmed serial JVM, 2 warmups and 3 repeats; same four-note 1.5-second chord; not Android")
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
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
