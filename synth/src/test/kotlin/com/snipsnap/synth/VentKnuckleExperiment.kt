package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A listening experiment, deliberately outside the saved VENT models and factory roster.
 * The optional envelope acts on the already conditioned, decimated raw readout, before
 * the same shared level and tail stages as model 2. The ODE is never rerun with a new force.
 */
internal object VentKnuckleExperiment {
    const val FORMAT = "VENT_KNUCKLE_ENVELOPE_EXPERIMENT"
    const val VERSION = 1
    const val START_SECONDS = .006
    const val T60_SECONDS = .18
    const val BPM = 92.0
    const val PAD_LEVEL = .6f
    const val STAGE = "conditioned-decimated-raw-before-shared-level"

    enum class Variant(val id: String, val title: String) {
        ORIGINAL("original", "Original"), TIGHT_DECAY("tight-decay", "Tight decay"),
    }

    /** Both variants use this complete factory patch without changing its controls or seed. */
    val patch: VentPatch get() = VentKitPresets.entries.single { it.id == "pulse-hit" }.patch

    data class Event(val beat: Double, val velocity: Float) {
        val gain: Float get() = PAD_LEVEL * (.35f + .65f * velocity)
    }

    val events = listOf(1f, .7f, .85f, .7f, 1f, .7f, .85f, .7f)
        .mapIndexed { i, velocity -> Event(i.toDouble(), velocity) }

    fun source(patch: VentPatch = this.patch): VentRender =
        Vent.renderDetailed(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seed, patch.model)

    fun gainAt(seconds: Double): Double {
        require(seconds.isFinite() && seconds >= 0.0)
        return if (seconds <= START_SECONDS) 1.0 else exp(-ln(1000.0) * (seconds - START_SECONDS) / T60_SECONDS)
    }

    fun render(source: VentRender, variant: Variant): Snip = when (variant) {
        Variant.ORIGINAL -> source.snip
        Variant.TIGHT_DECAY -> {
            val out = source.raw.copyOf()
            for (i in out.indices) out[i] = (out[i] * gainAt(i.toDouble() / Dsp.RATE)).toFloat()
            Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(out)
            Snip(out, 1, Dsp.RATE)
        }
    }

    /** Repeated fixed PCM hits: the event gain changes, while synthesis touch stays saved. */
    fun rhythm(hit: Snip): Snip {
        require(hit.channels == 1 && hit.sampleRate == Dsp.RATE)
        val frames = (8.0 * 60.0 / BPM * Dsp.RATE).roundToInt()
        val out = FloatArray(frames)
        for (event in events) {
            val start = (event.beat * 60.0 / BPM * Dsp.RATE).roundToInt()
            check(start + hit.samples.size <= out.size)
            for (i in hit.samples.indices) out[start + i] += hit.samples[i] * event.gain
        }
        return Snip(out, 1, Dsp.RATE)
    }

    fun processing(variant: Variant): Map<String, Any?> = when (variant) {
        Variant.ORIGINAL -> linkedMapOf("type" to "identity", "version" to VERSION)
        Variant.TIGHT_DECAY -> linkedMapOf(
            "type" to "exponential-amplitude-decay", "version" to VERSION,
            "startSeconds" to START_SECONDS, "t60Seconds" to T60_SECONDS, "stage" to STAGE,
            "formula" to "gain = 1 until start; then exp(-ln(1000) * (t - start) / t60)",
        )
    }

    fun recipe(patch: VentPatch, source: VentRender, variant: Variant): String = Json.write(json(linkedMapOf(
        "format" to FORMAT, "version" to VERSION, "variant" to variant.id,
        "sourcePatch" to patch.toJsonValue(), "processing" to processing(variant),
        "output" to linkedMapOf(
            "sampleRate" to Dsp.RATE, "channels" to 1,
            "sharedLevelTarget" to Dsp.MELODIC_LOUDNESS_TARGET, "samplePeakGuard" to .99,
            "tailFadeMilliseconds" to 4.0,
        ),
        "sourceRawFloat32leSha256" to samplesHash(source.raw),
        "sourceTrajectoryFloat64leSha256" to traceHash(source.trace),
        "rhythm" to rhythmRecipe(),
        "note" to "Experimental audition processing, not a loadable PadRecipe or a new VENT model. The output is a finite hit; rhythm mixes eight copies of that hit.",
    )))

    /** Strict replay refuses a recipe that silently changes or invents an envelope stage. */
    fun replay(text: String): Snip {
        val o = Json.parse(text).obj()
        require(o.getValue("format").str() == FORMAT && o.getValue("version").int() == VERSION)
        val variant = Variant.entries.single { it.id == o.getValue("variant").str() }
        require(o.getValue("processing") == json(processing(variant))) { "Unsupported experiment processing" }
        require(o.getValue("rhythm") == json(rhythmRecipe())) { "Unsupported experiment rhythm" }
        val expectedOutput = json(linkedMapOf(
            "sampleRate" to Dsp.RATE, "channels" to 1,
            "sharedLevelTarget" to Dsp.MELODIC_LOUDNESS_TARGET, "samplePeakGuard" to .99,
            "tailFadeMilliseconds" to 4.0,
        ))
        require(o.getValue("output") == expectedOutput) { "Unsupported experiment output stage" }
        val patch = VentPatch.fromJsonValue(o.getValue("sourcePatch")) as VentPatch
        require(patch.voice == VentVoice.PULSE && patch.model == Vent.CLEAN_MODEL)
        val source = source(patch)
        check(samplesHash(source.raw) == o.getValue("sourceRawFloat32leSha256").str()) { "Source raw audio differs" }
        check(traceHash(source.trace) == o.getValue("sourceTrajectoryFloat64leSha256").str()) { "Source trajectory differs" }
        return render(source, variant)
    }

    fun rhythmRecipe(): Map<String, Any?> = linkedMapOf(
        "bpm" to BPM, "bars" to 2, "beatsPerBar" to 4, "durationBeats" to 8,
        "padLevel" to PAD_LEVEL, "eventGainLaw" to "padLevel * (0.35 + 0.65 * velocity)",
        "framePlacement" to "round(beat * 60 / bpm * sampleRate)",
        "events" to events.map { linkedMapOf("beat" to it.beat, "velocity" to it.velocity, "gain" to it.gain) },
        "note" to "Both variants use the identical pattern and event gains; each event plays its variant's one fixed rendered hit.",
    )

    fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return hash(bytes.array())
    }

    fun traceHash(trace: List<VentTrace>): String {
        val bytes = ByteBuffer.allocate(trace.size * 32).order(ByteOrder.LITTLE_ENDIAN)
        for (point in trace) bytes.putLong(point.seconds.toRawBits()).putLong(point.x.toRawBits())
            .putLong(point.y.toRawBits()).putLong(point.z.toRawBits())
        return hash(bytes.array())
    }

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> {
            check(value.toDouble().isFinite())
            JsonValue.Num(value.toDouble())
        }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported experiment JSON value ${value.javaClass.name}")
    }
}
