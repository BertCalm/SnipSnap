package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.WavWriter.BitDepth
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Actual Kotlin DSP, with pre-output floats for independent prototype comparison. */
object ApsisPopulationAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/apsis-populations").apply { mkdirs() }
        listOf("audio", "recipes", "internal").forEach { File(root, it).mkdirs() }
        val clips = mutableListOf<Map<String, Any?>>()
        for (voice in listOf(ApsisVoice.GRAIN, ApsisVoice.ANCHOR)) {
            for (kind in listOf("c4", "c2", "c6", "soft", "hard", "held")) {
                val id = "kotlin_${voice.name.lowercase()}_$kind"
                val midi = when (kind) { "c2" -> 36; "c6" -> 84; else -> 60 }
                val vel = when (kind) { "soft" -> .2f; "hard" -> .9f; else -> .65f }
                val patch = ApsisPatch("$voice $kind", voice,
                    Apsis.defaults(voice) + ("HOLD" to if (kind == "held") 1f else 0f), midi, vel)
                File(root, "recipes/$id.json").writeText(patch.toJsonText())
                var internal = FloatArray(0)
                val p = ApsisPopulation.probe(voice, patch.macros, midi, vel, patch.config,
                    Apsis.ProbeOptions(normalize = true, recordStems = false, recordTraces = false),
                    cancelled = { false }, onInternalMix = { internal = it })
                val bytes = ByteBuffer.allocate(internal.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                internal.forEach { bytes.putFloat(it) }
                File(root, "internal/$id.f32").writeBytes(bytes.array())
                val listening = if (kind == "held") p.rawMix + p.rawMix else p.rawMix
                val peak = listening.maxOf { abs(it).toDouble() }
                val window = Dsp.RATE / 5
                var sum = 0.0; var loudest = 0.0
                for (i in listening.indices) {
                    sum += listening[i].toDouble() * listening[i]
                    if (i >= window) sum -= listening[i - window].toDouble() * listening[i - window]
                    if (i >= window - 1) loudest = maxOf(loudest, sqrt(sum.coerceAtLeast(0.0) / window))
                }
                val matchedGain = minOf(.12 / loudest.coerceAtLeast(1e-20), .85 / peak.coerceAtLeast(1e-20))
                for ((suffix, gain) in listOf("raw" to .054653905539097276, "matched" to matchedGain)) {
                    File(root, "audio/${id}_$suffix.wav").outputStream().use {
                        WavWriter.write(it, Snip(FloatArray(listening.size) { i -> (listening[i] * gain).toFloat() }, 1, Dsp.RATE), BitDepth.PCM_16)
                    }
                }
                File(root, "audio/${id}_native.wav").outputStream().use { WavWriter.write(it, Snip(p.samples, 1, Dsp.RATE)) }
                val d = p.diagnostics
                clips += linkedMapOf("id" to id, "voice" to voice.name, "kind" to kind,
                    "midi" to midi, "velocity" to vel, "frames" to listening.size,
                    "rawFile" to "audio/${id}_raw.wav", "matchedFile" to "audio/${id}_matched.wav",
                    "recipe" to "recipes/$id.json", "effectiveHz" to p.effectiveHz,
                    "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
                    "stateError" to p.stateError, "seamError" to p.seamError,
                    "prerollCycles" to d.prerollCycles, "maxUnforcedEnergyRise" to d.maxUnforcedEnergyRise,
                    "solverResidual" to d.solverResidual, "matchedGain" to matchedGain,
                    "preparationMs" to d.preparationNanos / 1e6,
                    "synthesisMs" to d.synthesisNanos / 1e6, "outputMs" to d.outputNanos / 1e6,
                    "preparationCacheHit" to d.preparationCacheHit,
                    "cacheBytes" to d.preparationCacheRetainedBytes)
                println("$id seam=${p.seamError} state=${p.stateError} renderMs=${(d.preparationNanos + d.synthesisNanos + d.outputNanos) / 1e6}")
            }
            val score = FloatArray((Dsp.RATE * 9.6).toInt())
            val events = listOf(Triple(0.0, 48, .65f), Triple(1.8, 55, .45f), Triple(3.6, 51, .85f))
            for ((at, note, velocity) in events) {
                val s = Apsis.probe(voice, Apsis.defaults(voice), note, velocity,
                    options = Apsis.ProbeOptions(recordStems = false, recordTraces = false)).rawMix
                val offset = (at * Dsp.RATE).toInt()
                for (i in s.indices) if (i + offset < score.size) score[i + offset] += s[i] * .35f
            }
            val id = "kotlin_${voice.name.lowercase()}_phrase"
            val peak = score.maxOf { abs(it).toDouble() }
            val window = Dsp.RATE / 5
            var sum = 0.0; var loudest = 0.0
            for (i in score.indices) {
                sum += score[i].toDouble() * score[i]
                if (i >= window) sum -= score[i - window].toDouble() * score[i - window]
                if (i >= window - 1) loudest = maxOf(loudest, sqrt(sum.coerceAtLeast(0.0) / window))
            }
            val match = minOf(.12 / loudest.coerceAtLeast(1e-20), .85 / peak.coerceAtLeast(1e-20))
            for ((suffix, gain) in listOf("raw" to .054653905539097276, "matched" to match)) {
                File(root, "audio/${id}_$suffix.wav").outputStream().use {
                    WavWriter.write(it, Snip(FloatArray(score.size) { i -> (score[i] * gain).toFloat() }, 1, Dsp.RATE), BitDepth.PCM_16)
                }
            }
            clips += linkedMapOf("id" to id, "voice" to voice.name, "kind" to "phrase",
                "frames" to score.size, "rawFile" to "audio/${id}_raw.wav", "matchedFile" to "audio/${id}_matched.wav",
                "score" to events.map { (at, note, velocity) -> mapOf("atSeconds" to at, "midi" to note,
                    "velocity" to velocity, "patch" to ApsisPatch("Score note", voice, Apsis.defaults(voice), note, velocity).toJsonText()) })
        }
        fun json(value: Any?): JsonValue = when(value) {
            null -> JsonValue.Null
            is String -> JsonValue.Str(value)
            is Boolean -> JsonValue.Bool(value)
            is Number -> JsonValue.Num(value.toDouble())
            is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
            is List<*> -> JsonValue.Arr(value.map(::json))
            else -> error("Unsupported metadata $value")
        }
        File(root, "manifest.json").writeText(Json.write(json(mapOf(
            "schema" to "snipsnap.apsis.population-port.v1", "sampleRate" to Dsp.RATE,
            "internalRate" to Dsp.RATE * 4, "clips" to clips))))
    }
}
