package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Finite source-based listening prototype. These are arrangement descriptions, not cultural styles. */
enum class ConfluenceProfile { UNISON, RESPONSE, DRONE }
enum class ConfluenceSourceMode { PITCHED, TEXTURE }

/** A resolved canonical asset. The caller's mutable PCM never becomes rendering state. */
class ConfluenceSource(val hash: String, samples: FloatArray, val sampleRate: Int = Confluence.RATE) {
    internal val pcm: FloatArray = samples.copyOf()
    val frameCount: Int get() = pcm.size

    init {
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Confluence asset identity must be a SHA-256 hash" }
        require(sampleRate == Confluence.RATE) { "Confluence sources must be canonical mono 44.1 kHz assets" }
        require(pcm.size in 256..Confluence.RATE * 30) { "Confluence source must contain 256 frames to 30 seconds" }
        require(pcm.all { it.isFinite() }) { "Confluence source contains nonfinite audio" }
        require(hash == canonicalHash(pcm, sampleRate)) { "Confluence source hash does not match canonical PCM; reassign and reanalyze changed material" }
    }

    companion object {
        /** Identity of the canonical little-endian float PCM, including its rate and length. */
        fun fromSnip(snip: Snip): ConfluenceSource {
            require(snip.channels == 1 && snip.sampleRate == Confluence.RATE) { "resolve/downmix/resample the source before Confluence" }
            return ConfluenceSource(canonicalHash(snip.samples, snip.sampleRate), snip.samples, snip.sampleRate)
        }

        private fun canonicalHash(samples: FloatArray, rate: Int): String {
            val bytes = ByteBuffer.allocate(8 + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.putInt(rate).putInt(samples.size)
            for (sample in samples) bytes.putFloat(sample)
            return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

/** Markers use absolute frames in the canonical asset; pitch is a manually confirmed MIDI reference. */
data class ConfluenceRegion(
    val assetHash: String,
    val startFrame: Int,
    val endFrame: Int,
    val mode: ConfluenceSourceMode = ConfluenceSourceMode.PITCHED,
    val referenceMidi: Float? = null,
    val sustainStartFrame: Int? = null,
    val sustainEndFrame: Int? = null,
) {
    init {
        require(assetHash.matches(Regex("[0-9a-f]{64}"))) { "region requires a content hash" }
        require(startFrame >= 0 && endFrame - startFrame >= 11_025) { "select at least 0.25 seconds of source" }
        require(referenceMidi == null || referenceMidi.isFinite() && referenceMidi in 0f..127f) { "invalid reference pitch" }
        require(mode != ConfluenceSourceMode.PITCHED || referenceMidi != null) { "confirm a reference note for a pitched source" }
        require((sustainStartFrame == null) == (sustainEndFrame == null)) { "sustain requires both markers" }
        if (sustainStartFrame != null && sustainEndFrame != null) {
            require(sustainStartFrame >= startFrame && sustainEndFrame <= endFrame && sustainEndFrame - sustainStartFrame >= 11_025) {
                "sustain must be a marked region of at least 0.25 seconds inside the phrase"
            }
        }
    }
}

/** Dedicated recipe: no Patch registration, implicit file lookup, PCM, or cached analysis in JSON. */
data class ConfluenceRecipe(
    val name: String,
    val profile: ConfluenceProfile,
    val regions: List<ConfluenceRegion>,
    val macros: Map<String, Float> = emptyMap(),
    val seed: Int = 1,
    val midi: Int? = null,
    val seconds: Float = 8f,
    val hold: Boolean = false,
    val model: Int = Confluence.MODEL,
) {
    init {
        require(name.isNotBlank()) { "Confluence recipe needs a name" }
        require(model == Confluence.MODEL) { "unsupported Confluence model $model" }
        require(regions.size in 1..8) { "assign one to eight source regions" }
        require(seconds.isFinite() && seconds in 2f..30f) { "Confluence finite render must be 2..30 seconds" }
        require(midi == null || midi in 0..127) { "invalid requested MIDI note" }
        require(!hold) { "Confluence prototype supports finite arrangements; HOLD is not implemented" }
        require(macros.keys.all { it in Confluence.MACRO_NAMES }) { "unknown Confluence macro" }
        require(macros.values.all { it.isFinite() && it in 0f..1f }) { "Confluence macros must be finite and in 0..1" }
        require(profile != ConfluenceProfile.DRONE || regions.all { it.sustainStartFrame != null }) {
            "add a marked sustained region for every DRONE source"
        }
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "engine" to JsonValue.Str("CONFLUENCE_PROTOTYPE"), "model" to num(model),
        "analysis" to num(Confluence.ANALYSIS_VERSION), "name" to JsonValue.Str(name),
        "profile" to JsonValue.Str(profile.name), "seed" to num(seed), "seconds" to num(seconds),
        "hold" to JsonValue.Bool(hold), "midi" to (midi?.let(::num) ?: JsonValue.Null),
        "macros" to JsonValue.Obj(macros.mapValues { num(it.value) }),
        "regions" to JsonValue.Arr(regions.map { r -> JsonValue.Obj(linkedMapOf(
            "assetHash" to JsonValue.Str(r.assetHash), "startFrame" to num(r.startFrame), "endFrame" to num(r.endFrame),
            "mode" to JsonValue.Str(r.mode.name), "referenceMidi" to (r.referenceMidi?.let(::num) ?: JsonValue.Null),
            "sustainStartFrame" to (r.sustainStartFrame?.let(::num) ?: JsonValue.Null),
            "sustainEndFrame" to (r.sustainEndFrame?.let(::num) ?: JsonValue.Null),
        )) }),
    ))
    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        private fun num(n: Number) = JsonValue.Num(n.toDouble())
        fun fromJsonText(text: String): ConfluenceRecipe {
            try {
                val o = Json.parse(text).obj()
                require(o["engine"]?.str() == "CONFLUENCE_PROTOTYPE") { "not a Confluence prototype recipe" }
                require(o["analysis"]?.int() == Confluence.ANALYSIS_VERSION) { "unsupported Confluence analysis version" }
                fun optional(v: JsonValue?) = v?.takeUnless { it == JsonValue.Null }
                return ConfluenceRecipe(
                    name = o.getValue("name").str(), profile = ConfluenceProfile.valueOf(o.getValue("profile").str()),
                    regions = o.getValue("regions").arr().map { value -> val r = value.obj()
                        ConfluenceRegion(r.getValue("assetHash").str(), r.getValue("startFrame").int(), r.getValue("endFrame").int(),
                            ConfluenceSourceMode.valueOf(r.getValue("mode").str()), optional(r["referenceMidi"])?.num()?.toFloat(),
                            optional(r["sustainStartFrame"])?.int(), optional(r["sustainEndFrame"])?.int()) },
                    macros = o.getValue("macros").obj().mapValues { it.value.num().toFloat() },
                    seed = o.getValue("seed").int(), midi = optional(o["midi"])?.int(), seconds = o.getValue("seconds").num().toFloat(),
                    hold = o.getValue("hold").bool(), model = o.getValue("model").int(),
                )
            } catch (e: IllegalArgumentException) { throw JsonException(e.message ?: "invalid Confluence recipe") }
        }
    }
}
