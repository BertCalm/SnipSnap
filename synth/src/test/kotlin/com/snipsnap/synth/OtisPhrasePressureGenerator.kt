package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest

/** Standalone listening experiment; it never changes the model4 defaults or legacy sources. */
object OtisPhrasePressureGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/otis-hardening/pressure").canonicalFile.also { it.mkdirs() }
        val gentle = OtisPhrasePressureStudy.render(strength = .35f)
        val strong = OtisPhrasePressureStudy.render(strength = .85f)
        val bypass = OtisPhrasePressureStudy.render(strength = 0f)
        val studies = listOf(bypass, gentle, strong)
        val sources = listOf(bypass.independent, gentle.shared, strong.shared).map { OtisPrototypeV4.condition(it) }
        val gain = minOf(1f, .98f / sources.maxOf { it.peak() }.coerceAtLeast(.00001f))
        val ids = listOf("independent", "gentle", "shared")
        val labels = listOf("Independent", "Gentle sharing", "Shared pressure")
        val variants = sources.mapIndexed { index, source ->
            val id = ids[index]
            val raw = WavWriter.write(File(out, "$id.raw.wav"), Snip(FloatArray(source.samples.size) { source.samples[it] * gain }, 1, Dsp.RATE))
            val matched = WavWriter.write(File(out, "$id.matched.wav"), AuditionLevel.level(source))
            val recipe = studies[index].manifest()
            val chosen = recipe + mapOf("selectedVariant" to id, "conditioningModel" to 4,
                "strength" to listOf(0f, .35f, .85f)[index])
            File(out, "$id.json").writeText(Json.write(value(chosen)) + "\n")
            mapOf("id" to id, "label" to labels[index], "raw" to raw.name, "matched" to matched.name,
                "recipe" to "$id.json", "rawSha256" to hash(raw), "matchedSha256" to hash(matched))
        }
        val manifest = mapOf("format" to "snipsnap-otis-shared-pressure-audition", "rawPcmGain" to gain,
            "cases" to listOf(mapOf("id" to "held_reed_second_key", "title" to "One breath, two keys", "family" to "SQUEEZE",
                "description" to "C4 is held; E♭4 joins after .45 seconds, then leaves. Compare how the held reed responds and recovers.",
                "variants" to variants)), "gentle" to gentle.manifest(), "shared" to strong.manifest(),
            "sourceHashes" to listOf("OtisPhrasePressureGenerator.kt", "OtisPhrasePressureStudy.kt", "OtisPrototypeV4.kt", "OtisAirPrototype.kt").associateWith {
                hash(File("src/test/kotlin/com/snipsnap/synth", it)) },
            "limits" to "A bounded musical pressure analogy, not an acoustic energy simulation. Experimental and separate from model4 defaults.")
        File(out, "manifest.json").writeText(Json.write(value(manifest)) + "\n")
        println("OTIS shared pressure ${out.path}")
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Number -> JsonValue.Num(v.toDouble())
        is Boolean -> JsonValue.Bool(v)
        is Map<*, *> -> JsonValue.Obj(v.entries.associateTo(linkedMapOf()) { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map { value(it) })
        else -> error("Unsupported pressure study value ${v.javaClass}")
    }
}
