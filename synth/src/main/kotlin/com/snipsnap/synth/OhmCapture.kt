package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A short excitation recording kept inside its OHM recipe. PCM16 is the canonical
 * source, so editing or deleting the original pad cannot change a later render.
 * Construction quantizes once; all reads return fresh mono 44.1 kHz samples.
 */
class OhmCapture private constructor(private val pcm: ByteArray, val label: String?) {
    constructor(samples: FloatArray, label: String? = null) : this(encode(samples), checkedLabel(label))

    val frameCount: Int get() = pcm.size / 2
    val sha256: String = hash(pcm)
    private val encoded: String = Base64.getEncoder().encodeToString(pcm)

    fun samples(): FloatArray = FloatArray(frameCount) { i ->
        val value = (pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)
        value.toShort().toInt() / 32768f
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf<String, JsonValue>(
        "encoding" to JsonValue.Str(ENCODING),
        "sampleRate" to JsonValue.Num(SAMPLE_RATE.toDouble()),
        "frames" to JsonValue.Num(frameCount.toDouble()),
        "pcm16" to JsonValue.Str(encoded),
        "sha256" to JsonValue.Str(sha256),
    ).also { fields -> label?.let { fields["label"] = JsonValue.Str(it) } })

    fun toJsonText(): String = Json.write(toJsonValue())

    override fun equals(other: Any?): Boolean =
        other is OhmCapture && label == other.label && pcm.contentEquals(other.pcm)

    override fun hashCode(): Int = 31 * pcm.contentHashCode() + (label?.hashCode() ?: 0)

    override fun toString(): String = "OhmCapture(frames=$frameCount, label=$label, sha256=$sha256)"

    companion object {
        const val SAMPLE_RATE = 44_100
        const val MAX_FRAMES = SAMPLE_RATE / 2
        const val MAX_LABEL_CHARS = 64
        const val DEFAULT_SECONDS = .25f
        const val MAX_SECONDS = .5f
        const val SILENT_PEAK = 1e-4f
        private const val ENCODING = "PCM16LE"
        private const val MAX_BASE64_CHARS = ((MAX_FRAMES * 2 + 2) / 3) * 4

        /**
         * Take the audible onset, including 1 ms of lead, rather than a file's
         * leading silence. Only this bounded neighbourhood is resampled. Source
         * gain is removed once so the instrument's own excitation law owns force.
         */
        fun fromSnip(source: Snip, label: String? = null, seconds: Float = DEFAULT_SECONDS): OhmCapture {
            checkedLabel(label)
            require(seconds.isFinite() && seconds in .001f..MAX_SECONDS) { "OHM capture duration must be .001..$MAX_SECONDS seconds" }
            require(source.frameCount > 1) { "OHM capture needs audible source audio" }
            fun mono(frame: Int): Double {
                var sum = 0.0
                for (channel in 0 until source.channels) {
                    val sample = source.samples[frame * source.channels + channel]
                    if (sample.isFinite()) sum += sample.toDouble()
                }
                return sum / source.channels
            }
            var peak = 0.0
            for (frame in 0 until source.frameCount) peak = maxOf(peak, abs(mono(frame)))
            require(peak >= SILENT_PEAK) { "OHM capture source is silent" }
            val threshold = maxOf(SILENT_PEAK.toDouble(), .01 * peak)
            var onset = 0
            while (onset < source.frameCount && abs(mono(onset)) < threshold) onset++
            val lead = (source.sampleRate * .001).roundToInt()
            val start = (onset - lead).coerceAtLeast(0)
            val keep = ceil((seconds.toDouble() + .005) * source.sampleRate).toLong()
            val end = minOf(source.frameCount.toLong(), start.toLong() + keep).toInt()
            val head = FloatArray(end - start) { i -> (mono(start + i) / peak).toFloat() }
            val resampled = Resampler.resample(Snip(head, 1, source.sampleRate), SAMPLE_RATE).samples
            val size = min((seconds * SAMPLE_RATE).roundToInt(), resampled.size)
            require(size >= 2) { "OHM capture is too short after resampling" }
            val samples = resampled.copyOf(size)
            val mean = samples.sumOf { it.toDouble() } / size
            var capturedPeak = 0.0
            for (i in samples.indices) {
                samples[i] = (samples[i] - mean).toFloat()
                capturedPeak = maxOf(capturedPeak, abs(samples[i].toDouble()))
            }
            require(capturedPeak >= SILENT_PEAK) { "OHM capture has no audible varying signal" }
            val fadeIn = min(size / 2, (SAMPLE_RATE * .001).roundToInt()).coerceAtLeast(2)
            val fadeOut = min(size / 2, (SAMPLE_RATE * .005).roundToInt()).coerceAtLeast(2)
            for (i in samples.indices) {
                var weight = 1.0
                if (i < fadeIn) weight *= .5 - .5 * cos(Math.PI * i / (fadeIn - 1))
                val remaining = size - 1 - i
                if (remaining < fadeOut) weight *= .5 - .5 * cos(Math.PI * remaining / (fadeOut - 1))
                samples[i] = (samples[i] * (.9 / capturedPeak) * weight).toFloat()
            }
            return OhmCapture(samples, label)
        }

        fun fromJsonText(text: String): OhmCapture = fromJsonValue(Json.parse(text))

        fun fromJsonValue(value: JsonValue): OhmCapture {
            val fields = value.obj()
            fun field(key: String) = fields[key] ?: throw JsonException("OHM capture has no $key")
            try {
                require(field("encoding").str() == ENCODING) { "unsupported OHM capture encoding" }
                require(field("sampleRate").int() == SAMPLE_RATE) { "OHM capture must be mono $SAMPLE_RATE Hz PCM16" }
                val frames = field("frames").int()
                require(frames in 2..MAX_FRAMES) { "OHM capture frames must be 2..$MAX_FRAMES" }
                val text = field("pcm16").str()
                require(text.length <= MAX_BASE64_CHARS && text.length == ((frames * 2 + 2) / 3) * 4) {
                    "OHM capture PCM length differs from its frame count"
                }
                val bytes = Base64.getDecoder().decode(text)
                require(bytes.size == frames * 2 && Base64.getEncoder().encodeToString(bytes) == text) { "OHM capture PCM is not canonical Base64" }
                require(bytes.any { it.toInt() != 0 }) { "OHM capture is silent" }
                fields["sha256"]?.takeUnless { it is JsonValue.Null }?.let {
                    val expected = it.str()
                    require(expected.matches(Regex("[0-9a-fA-F]{64}")) && expected.equals(hash(bytes), ignoreCase = true)) {
                        "OHM capture checksum differs from its PCM"
                    }
                }
                val label = fields["label"]?.takeUnless { it is JsonValue.Null }?.str()
                return OhmCapture(bytes, checkedLabel(label))
            } catch (failure: IllegalArgumentException) {
                throw JsonException(failure.message ?: "invalid OHM capture")
            }
        }

        private fun checkedLabel(label: String?): String? {
            require(label == null || (label.length <= MAX_LABEL_CHARS && label.none { it.isISOControl() })) {
                "OHM capture label must be at most $MAX_LABEL_CHARS characters without controls"
            }
            return label
        }

        private fun encode(samples: FloatArray): ByteArray {
            require(samples.size in 2..MAX_FRAMES) { "OHM capture needs 2..$MAX_FRAMES samples" }
            require(samples.all { it.isFinite() && it in -1f..1f }) { "OHM capture samples must be finite and within -1..1" }
            require(samples.any { abs(it) >= SILENT_PEAK }) { "OHM capture is silent" }
            return ByteArray(samples.size * 2).also { out ->
                for (i in samples.indices) {
                    val value = (samples[i] * 32768f).roundToInt().coerceIn(-32768, 32767)
                    out[2 * i] = value.toByte()
                    out[2 * i + 1] = (value shr 8).toByte()
                }
            }
        }

        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
