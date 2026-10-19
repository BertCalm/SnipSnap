package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ScannedChainShapeSource { FACTORY, DRAWN, SAMPLE }
enum class ScannedChainSampleMode { TONAL_CYCLE, TIMBRAL_WINDOW }

/** Provenance only. The saved points, rather than an external recording, regenerate the voice. */
data class ScannedChainSampleInfo(
    val mode: ScannedChainSampleMode,
    val sampleRate: Int,
    val channels: Int,
    val startFrame: Int,
    val frames: Int,
    val windowSha256: String,
    val detectedHz: Float? = null,
    val confidence: Float? = null,
) {
    init {
        require(sampleRate > 0 && channels in 1..2 && startFrame >= 0 && frames >= 2) { "invalid scanned-chain source window" }
        require(windowSha256.matches(Regex("[0-9a-f]{64}"))) { "invalid scanned-chain window hash" }
        require(detectedHz == null || detectedHz.isFinite() && detectedHz > 0f) { "invalid scanned-chain source pitch" }
        require(confidence == null || confidence.isFinite() && confidence in 0f..1f) { "invalid scanned-chain pitch confidence" }
        if (mode == ScannedChainSampleMode.TONAL_CYCLE) {
            require(detectedHz != null && confidence != null && confidence >= .7f) { "a tonal-cycle shape needs a confident source pitch" }
        }
    }
}

/** Validated, immutable saved coordinates. Decode validates without changing any coordinate bits. */
class ScannedChainShape(
    points: FloatArray,
    val source: ScannedChainShapeSource,
    val label: String,
    val sample: ScannedChainSampleInfo? = null,
) {
    private val saved = points.copyOf()
    val points: FloatArray get() = saved.copyOf()

    init {
        require(saved.size == ScannedChain.SHAPE_SIZE) { "a scanned-chain shape needs 128 points" }
        require(saved.all { it.isFinite() && abs(it) <= .350001f }) { "scanned-chain points must be finite and within +/-0.35" }
        require(abs(saved.sumOf { it.toDouble() } / saved.size) <= 1e-5) { "a saved scanned-chain shape must be centered" }
        require(label.isNotBlank() && label.length <= 256) { "a scanned-chain shape needs a label of at most 256 characters" }
        require((source == ScannedChainShapeSource.SAMPLE) == (sample != null)) { "sample provenance must match the shape source" }
    }

    override fun equals(other: Any?): Boolean = other is ScannedChainShape && source == other.source && label == other.label &&
        sample == other.sample && saved.contentEquals(other.saved)
    override fun hashCode(): Int = (((saved.contentHashCode() * 31 + source.hashCode()) * 31 + label.hashCode()) * 31 + (sample?.hashCode() ?: 0))
    override fun toString(): String = "ScannedChainShape($source, $label, 128 points)"

    internal fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf<String, JsonValue>(
        "source" to JsonValue.Str(source.name), "label" to JsonValue.Str(label),
        "points" to JsonValue.Arr(saved.map { JsonValue.Num(it.toDouble()) }),
        // Json.write canonicalizes -0 to 0. Raw IEEE bits preserve even that distinction.
        "pointBits" to JsonValue.Arr(saved.map { JsonValue.Num(it.toRawBits().toDouble()) }),
    ).apply {
        sample?.let { s -> put("sample", JsonValue.Obj(linkedMapOf<String, JsonValue>(
            "mode" to JsonValue.Str(s.mode.name), "sampleRate" to JsonValue.Num(s.sampleRate.toDouble()),
            "channels" to JsonValue.Num(s.channels.toDouble()), "startFrame" to JsonValue.Num(s.startFrame.toDouble()),
            "frames" to JsonValue.Num(s.frames.toDouble()), "windowSha256" to JsonValue.Str(s.windowSha256),
        ).apply {
            s.detectedHz?.let { put("detectedHz", JsonValue.Num(it.toDouble())) }
            s.confidence?.let { put("confidence", JsonValue.Num(it.toDouble())) }
        })) }
    })

    companion object {
        fun factory(voice: ScannedChainVoice): ScannedChainShape {
            val points = if (voice == ScannedChainVoice.BLOOM) {
                condition(FloatArray(128) { i ->
                    val p = 2 * PI * i / 128
                    (.12 * sin(p) + sin(2 * p) + .65 * sin(3 * p + .3) + .4 * sin(5 * p)).toFloat()
                }, targetPeak = .12, scaleUp = true)
            } else ScannedChainModelV1.initialShape(ScannedChainModelV1.Config(voice.name,
                voice = ScannedChainModelV1.Voice.valueOf(voice.name)))
            return ScannedChainShape(points, ScannedChainShapeSource.FACTORY, "${voice.name} factory shape")
        }

        fun drawn(points: FloatArray, label: String = "Drawn shape"): ScannedChainShape =
            ScannedChainShape(condition(points), ScannedChainShapeSource.DRAWN, label)

        fun fromSample(
            snip: Snip, startFrame: Int = 0, mode: ScannedChainSampleMode = ScannedChainSampleMode.TIMBRAL_WINDOW,
            label: String = "Sample shape",
        ): ScannedChainShape {
            ScannedChain.checkCancellation()
            require(startFrame in 0 until snip.frameCount) { "the source window starts outside the sample" }
            val mono = Cleanup.toMono(snip)
            val pitch = if (mode == ScannedChainSampleMode.TONAL_CYCLE) {
                Pitch.detect(mono, fromSec = startFrame.toFloat() / snip.sampleRate + .05f, windowSec = .25f)
                    ?.takeIf { it.confidence >= .7f }
                    ?: throw IllegalArgumentException("No confident source pitch; choose a timbral window or another position")
            } else null
            val frames = if (pitch != null) (snip.sampleRate / pitch.hz).roundToInt().coerceAtLeast(2)
                else (snip.sampleRate * .02f).roundToInt().coerceAtLeast(2)
            require(frames <= mono.frameCount - startFrame) { "the selected source window runs beyond the sample" }
            val window = mono.samples.copyOfRange(startFrame, startFrame + frames)
            require(window.all { it.isFinite() }) { "the source window contains non-finite samples" }
            val mean = window.average()
            val values = FloatArray(128) { mass ->
                val value = if (frames >= 128) {
                    val from = mass * frames / 128
                    val to = (mass + 1) * frames / 128
                    var sum = 0.0
                    for (i in from until to) sum += window[i] - mean
                    sum / (to - from)
                } else {
                    val position = mass.toDouble() * frames / 128
                    val index = floor(position).toInt()
                    val fraction = position - index
                    (window[index] - mean) * (1 - fraction) + (window[(index + 1) % frames] - mean) * fraction
                }
                (value * (.5 - .5 * cos(2 * PI * mass / 127))).toFloat()
            }
            require(values.maxOf { abs(it) } > 1e-7f) { "the selected source window has no usable shape" }
            ScannedChain.checkCancellation()
            return ScannedChainShape(condition(values, scaleUp = true), ScannedChainShapeSource.SAMPLE, label,
                ScannedChainSampleInfo(mode, snip.sampleRate, snip.channels, startFrame, frames, hash(window), pitch?.hz, pitch?.confidence))
        }

        internal fun fromJsonValue(value: JsonValue): ScannedChainShape {
            val obj = value.obj()
            fun required(key: String) = obj[key] ?: throw JsonException("scanned-chain shape has no $key")
            val raw = required("points").arr()
            if (raw.size != 128) throw JsonException("a scanned-chain shape needs 128 points")
            var points = FloatArray(128) { raw[it].num().toFloat() }
            obj["pointBits"]?.let { valueBits ->
                val bits = valueBits.arr()
                if (bits.size != 128) throw JsonException("scanned-chain pointBits needs 128 values")
                val exact = FloatArray(128) { Float.fromBits(bits[it].int()) }
                if (exact.indices.any { !exact[it].isFinite() || exact[it] != points[it] }) throw JsonException("scanned-chain pointBits disagrees with points")
                points = exact
            }
            val source = ScannedChainShapeSource.entries.firstOrNull { it.name == required("source").str() }
                ?: throw JsonException("unknown scanned-chain shape source")
            val sample = obj["sample"]?.let { s ->
                val o = s.obj()
                fun r(key: String) = o[key] ?: throw JsonException("scanned-chain sample has no $key")
                val mode = ScannedChainSampleMode.entries.firstOrNull { it.name == r("mode").str() }
                    ?: throw JsonException("unknown scanned-chain sample mode")
                ScannedChainSampleInfo(mode, r("sampleRate").int(), r("channels").int(), r("startFrame").int(),
                    r("frames").int(), r("windowSha256").str(), o["detectedHz"]?.num()?.toFloat(), o["confidence"]?.num()?.toFloat())
            }
            return ScannedChainShape(points, source, required("label").str(), sample)
        }

        private fun condition(points: FloatArray, targetPeak: Double = .35, scaleUp: Boolean = false): FloatArray {
            require(points.size == 128 && points.all { it.isFinite() }) { "a scanned-chain shape needs 128 finite points" }
            val mean = points.average()
            val centered = FloatArray(128) { (points[it] - mean).toFloat() }
            val peak = centered.maxOf { abs(it.toDouble()) }
            val gain = if (peak > targetPeak || scaleUp && peak > 0) targetPeak / peak else 1.0
            return FloatArray(128) { (centered[it] * gain).toFloat() }
        }

        private fun hash(points: FloatArray): String {
            val bytes = ByteBuffer.allocate(points.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            points.forEach { bytes.putFloat(it) }
            return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
        }
    }
}
