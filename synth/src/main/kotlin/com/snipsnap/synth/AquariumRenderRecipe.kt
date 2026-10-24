package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.roundToLong

/**
 * Reusable finite Aquarium print settings, independent of the source asset or pad placement.
 *
 * Every render starts the effect clock at frame zero with [settings]' saved seed. BPM 0 means
 * no tempo is supplied. The host appends zero input for the saved tail, applies a final fade
 * capped at the output's length, then applies the fixed gain and rejects a peak above the
 * ceiling. Nothing here promises a sustain loop or performs normalization or limiting.
 *
 * The source supplies sample rate and channel layout. The effect adapter preserves stereo
 * information; this is not an instrument's mono, fixed-rate output stage. Native allocation
 * limits belong to the adapter, which queries its processor rather than duplicating them here.
 */
class AquariumRenderRecipe(
    val settings: AquariumRecipe = AquariumRecipe(),
    bpm: Double = 0.0,
    tailSeconds: Double = 2.0,
    fadeSeconds: Double = 0.005,
    val outputGain: Float = 0.5f,
    val peakCeiling: Float = 0.99f,
) {
    val bpm: Double = positiveZero(bpm)
    val tailSeconds: Double = positiveZero(tailSeconds)
    val fadeSeconds: Double = positiveZero(fadeSeconds)
    val clock: String get() = CLOCK
    val loopPolicy: String get() = LOOP_POLICY
    val peakPolicy: String get() = PEAK_POLICY
    /** Effect-state only: the offline renderer still honors the saved gain, tail and fade. */
    val isBypass: Boolean get() = settings.isBypass

    init {
        require(bpm.isFinite() && bpm in 0.0..1000.0) { "Aquarium render BPM must be finite and in 0..1000: $bpm" }
        require(tailSeconds.isFinite() && tailSeconds in 0.0..30.0) { "Aquarium render tail must be finite and in 0..30 seconds: $tailSeconds" }
        require(fadeSeconds.isFinite() && fadeSeconds in 0.0..0.1) { "Aquarium render fade must be finite and in 0..0.1 seconds: $fadeSeconds" }
        require(outputGain.isFinite() && outputGain > 0f && outputGain <= 1f) { "Aquarium render gain must be finite and in (0,1]: $outputGain" }
        require(peakCeiling.isFinite() && peakCeiling > 0f && peakCeiling <= 1f) { "Aquarium render peak ceiling must be finite and in (0,1]: $peakCeiling" }
    }

    /**
     * Seconds × source sample rate, rounded to the nearest frame with ties upward by roundToLong.
     * Nonnegative durations at 8..192 kHz fit Int; the host separately checks total output size.
     */
    fun tailFrames(sampleRate: Int): Int = frames(tailSeconds, sampleRate)

    /** Same rounding as [tailFrames]; the host caps this requested fade at total output frames. */
    fun fadeFrames(sampleRate: Int): Int = frames(fadeSeconds, sampleRate)

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "aquariumRender" to JsonValue.Num(VERSION.toDouble()),
        "settings" to settings.toJsonValue(),
        "clock" to JsonValue.Str(clock),
        "loopPolicy" to JsonValue.Str(loopPolicy),
        "peakPolicy" to JsonValue.Str(peakPolicy),
        "bpm" to JsonValue.Num(bpm),
        "tailSeconds" to JsonValue.Num(tailSeconds),
        "fadeSeconds" to JsonValue.Num(fadeSeconds),
        "outputGain" to JsonValue.Num(outputGain.toDouble()),
        "peakCeiling" to JsonValue.Num(peakCeiling.toDouble()),
    ))

    fun toJsonText(): String = Json.write(toJsonValue())

    fun copy(
        settings: AquariumRecipe = this.settings,
        bpm: Double = this.bpm,
        tailSeconds: Double = this.tailSeconds,
        fadeSeconds: Double = this.fadeSeconds,
        outputGain: Float = this.outputGain,
        peakCeiling: Float = this.peakCeiling,
    ): AquariumRenderRecipe = AquariumRenderRecipe(settings, bpm, tailSeconds, fadeSeconds, outputGain, peakCeiling)

    override fun equals(other: Any?): Boolean = other is AquariumRenderRecipe && settings == other.settings &&
        bpm == other.bpm && tailSeconds == other.tailSeconds && fadeSeconds == other.fadeSeconds &&
        outputGain == other.outputGain && peakCeiling == other.peakCeiling

    override fun hashCode(): Int {
        var result = settings.hashCode()
        result = 31 * result + bpm.hashCode()
        result = 31 * result + tailSeconds.hashCode()
        result = 31 * result + fadeSeconds.hashCode()
        result = 31 * result + outputGain.hashCode()
        return 31 * result + peakCeiling.hashCode()
    }

    override fun toString(): String = "AquariumRenderRecipe(settings=$settings, bpm=$bpm, tailSeconds=$tailSeconds, fadeSeconds=$fadeSeconds, outputGain=$outputGain, peakCeiling=$peakCeiling)"

    private fun frames(seconds: Double, sampleRate: Int): Int {
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "Aquarium source sample rate must be $MIN_SAMPLE_RATE..$MAX_SAMPLE_RATE: $sampleRate" }
        val count = (seconds * sampleRate.toDouble()).roundToLong()
        require(count in 0L..Int.MAX_VALUE.toLong()) { "Aquarium duration does not fit a frame count" }
        return count.toInt()
    }

    companion object {
        const val VERSION = 1
        const val CLOCK = "fresh"
        const val LOOP_POLICY = "finite"
        const val PEAK_POLICY = "reject"
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 192_000
        private val FIELDS = setOf("aquariumRender", "settings", "clock", "loopPolicy", "peakPolicy", "bpm", "tailSeconds", "fadeSeconds", "outputGain", "peakCeiling")

        fun fromJsonValue(value: JsonValue): AquariumRenderRecipe {
            val obj = value.obj()
            val unknown = obj.keys - FIELDS
            if (unknown.isNotEmpty()) throw JsonException("unknown Aquarium render fields: ${unknown.sorted().joinToString()}")
            val missing = FIELDS - obj.keys
            if (missing.isNotEmpty()) throw JsonException("missing Aquarium render fields: ${missing.sorted().joinToString()}")
            val version = obj.getValue("aquariumRender").int()
            if (version != VERSION) throw JsonException("unsupported Aquarium render version $version")
            for ((field, policy) in listOf("clock" to CLOCK, "loopPolicy" to LOOP_POLICY, "peakPolicy" to PEAK_POLICY)) {
                val saved = obj.getValue(field).str()
                if (saved != policy) throw JsonException("unsupported Aquarium $field $saved")
            }
            try {
                return AquariumRenderRecipe(
                    settings = AquariumRecipe.fromJsonValue(obj.getValue("settings")),
                    bpm = obj.getValue("bpm").num(),
                    tailSeconds = obj.getValue("tailSeconds").num(),
                    fadeSeconds = obj.getValue("fadeSeconds").num(),
                    outputGain = unitFloat("outputGain", obj.getValue("outputGain").num()),
                    peakCeiling = unitFloat("peakCeiling", obj.getValue("peakCeiling").num()),
                )
            } catch (e: IllegalArgumentException) {
                throw JsonException(e.message ?: "invalid Aquarium render recipe")
            }
        }

        fun fromJsonText(text: String): AquariumRenderRecipe = fromJsonValue(Json.parse(text))

        private fun unitFloat(field: String, number: Double): Float {
            require(number.isFinite() && number > 0.0 && number <= 1.0) { "Aquarium render $field must be finite and in (0,1]: $number" }
            val narrowed = number.toFloat()
            require(narrowed > 0f) { "Aquarium render $field is too small for native Float precision: $number" }
            return narrowed
        }

        private fun positiveZero(number: Double): Double = if (number == 0.0) 0.0 else number
    }
}

/**
 * Injectable finite effect adapter operating at the source rate without folding stereo to mono.
 * Always honor saved gain, tail and fade, including zero-mix settings; removal is a caller action.
 */
fun interface AquariumOfflineRenderer {
    fun render(source: Snip, recipe: AquariumRenderRecipe): Snip
}
