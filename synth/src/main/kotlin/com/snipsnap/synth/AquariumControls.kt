package com.snipsnap.synth

import com.snipsnap.json.Json
import java.util.Collections
import java.util.Locale
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.roundToLong

data class AquariumPreset(val id: String, val name: String, val settings: AquariumRecipe)
data class AquariumGroup(val id: String, val name: String, val effects: List<AquariumEffect>)
data class AquariumEffect(val id: String, val name: String, val mixId: String, val primary: List<AquariumControl>, val details: List<AquariumControl>)
enum class AquariumControlScale { LINEAR, LOG, LOG_ZERO }

/** UI mapping only. Bounds, defaults and selector steps come from the canonical native registry. */
class AquariumControl internal constructor(
    val spec: AquariumParameterSpec,
    val label: String,
    val unit: String,
    val scale: AquariumControlScale,
    options: List<String> = emptyList(),
) {
    val id: String get() = spec.id
    val options: List<String> = Collections.unmodifiableList(options.toList())

    /** Strict normalized input; selectors snap to their native step before leaving the editor. */
    fun fromNormalized(position: Float): Float {
        require(position.isFinite() && position in 0f..1f) { "Aquarium slider position must be finite and in 0..1: $position" }
        if (position == 0f) return spec.minimum
        if (position == 1f) return spec.maximum
        val low = spec.minimum.toDouble()
        val high = spec.maximum.toDouble()
        val raw = when (scale) {
            AquariumControlScale.LINEAR -> low + (high - low) * position
            AquariumControlScale.LOG -> low * exp(ln(high / low) * position)
            // A 1 ms knee gives zero-based delay controls useful travel near short delays.
            AquariumControlScale.LOG_ZERO -> expm1(ln1p(high) * position)
        }
        val stepped = if (spec.isDiscrete) low + ((raw - low) / spec.step).roundToLong() * spec.step.toDouble() else raw
        return stepped.toFloat().coerceIn(spec.minimum, spec.maximum).also(spec::validate)
    }

    fun toNormalized(value: Float): Float {
        spec.validate(value)
        val low = spec.minimum.toDouble()
        val high = spec.maximum.toDouble()
        return when (scale) {
            AquariumControlScale.LINEAR -> ((value - low) / (high - low)).toFloat()
            AquariumControlScale.LOG -> (ln(value / low) / ln(high / low)).toFloat()
            AquariumControlScale.LOG_ZERO -> (ln1p(value.toDouble()) / ln1p(high)).toFloat()
        }.coerceIn(0f, 1f)
    }

    /** Readout always describes stored physical units; selectors use their native enum order. */
    fun format(value: Float): String {
        spec.validate(value)
        if (options.isNotEmpty()) return options[((value - spec.minimum) / spec.step).toInt()]
        return when (unit) {
            "Hz" -> if (value >= 1_000f) "${decimal(value / 1_000.0, 2)} kHz" else "${decimal(value.toDouble(), if (value < 1f) 4 else 2)} Hz"
            "%" -> "${decimal(value * 100.0, 1)}%"
            "voices" -> "${value.toInt()} voices"
            else -> decimal(value.toDouble(), 2) + if (unit.isEmpty()) "" else " $unit"
        }
    }

    private fun decimal(value: Double, places: Int): String = String.format(Locale.ROOT, "%.${places}f", value).trimEnd('0').trimEnd('.').let {
        if (it == "-0" || it.isEmpty()) "0" else it
    }
}

/** The accepted listening presets and the complete pad panel surface. This changes no DSP law. */
object AquariumControls {
    val presets: List<AquariumPreset> = frozen(loadPresets())
    private val specs = AquariumRecipe.PARAMETERS.associateBy { it.id }
    private val choices = mapOf(
        "aqua_tide_mode" to listOf("Amplitude", "Filter", "Both"),
        "aqua_tide_shape" to listOf("Sine", "Triangle", "S&H"),
        "aqua_tide_syncEnabled" to listOf("Off", "On"),
        // Native cycles per beat are 1/32, 1/16, 1/8, 1/4, 1/2, 1, 2 and 4.
        "aqua_tide_syncDiv" to listOf("32 beats", "16 beats", "8 beats", "4 beats", "2 beats", "1 beat", "1/2 beat", "1/4 beat"),
        "aqua_surface_mode" to listOf("High shelf", "Low shelf", "Bell"),
        "aqua_surface_sweepRange" to listOf("1 octave", "4 octaves"),
        "aqua_biolume_satMode" to listOf("Soft", "Hard", "Fold"),
    )
    private val labels = mapOf(
        "lowGain" to "Low gain", "midGain" to "Mid gain", "highGain" to "High gain", "lowXover" to "Low crossover", "highXover" to "High crossover",
        "stereoSpread" to "Stereo spread", "syncEnabled" to "Tempo sync", "syncDiv" to "Cycle length", "filterFreq" to "Filter cutoff", "filterRes" to "Filter resonance",
        "stereoPhase" to "Stereo phase", "freq" to "Frequency", "sweepRange" to "Sweep range", "hpFreq" to "High-pass cutoff", "satMode" to "Saturation",
    )
    private val descriptors: Map<String, AquariumControl> = specs.mapValues { (id, spec) ->
        val key = id.substringAfterLast('_')
        val hz = key in setOf("rate", "lowXover", "highXover", "filterFreq", "freq", "hpFreq")
        val milliseconds = id == "aqua_drift_depth" || key == "predelay"
        val unit = when {
            hz -> "Hz"
            milliseconds -> "ms"
            id == "aqua_surface_gain" || key == "threshold" -> "dB"
            key in setOf("lowGain", "midGain", "highGain") -> "×"
            key == "stereoPhase" -> "°"
            key == "voices" -> "voices"
            spec.isDiscrete -> ""
            else -> "%"
        }
        AquariumControl(spec, labels[key] ?: key.replaceFirstChar { it.uppercase() }, unit,
            when { hz -> AquariumControlScale.LOG; milliseconds -> AquariumControlScale.LOG_ZERO; else -> AquariumControlScale.LINEAR }, choices[id] ?: emptyList())
    }

    val effects: List<AquariumEffect> = frozen(listOf(
        stage("fathom", "Fathom", "mix", "depth", "character", "lowGain"),
        stage("drift", "Drift", "mix", "depth", "rate", "stereoSpread"),
        stage("tide", "Tide", "mix", "depth", "rate", "mode"),
        stage("reef", "Reef", "mix", "size", "damping", "density"),
        stage("surface", "Surface", "mix", "gain", "freq", "sweep"),
        stage("biolume", "Biolume", "mix", "drive", "shimmer", "hpFreq"),
    ))
    val groups: List<AquariumGroup> = frozen(listOf(
        AquariumGroup("depth", "Depth", frozen(listOf(effect("fathom"), effect("surface")))),
        AquariumGroup("motion", "Motion", frozen(listOf(effect("drift"), effect("tide")))),
        AquariumGroup("color", "Color", frozen(listOf(effect("reef"), effect("biolume")))),
    ))

    fun control(id: String): AquariumControl = descriptors[id] ?: throw IllegalArgumentException("unknown Aquarium control $id")
    fun effect(id: String): AquariumEffect = effects.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown Aquarium effect $id")
    fun preset(id: String): AquariumPreset = presets.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown Aquarium preset $id")

    private fun stage(id: String, name: String, vararg primary: String): AquariumEffect {
        val primaryControls = primary.map { control("aqua_${id}_$it") }
        val primaryIds = primaryControls.map { it.id }.toSet()
        return AquariumEffect(id, name, "aqua_${id}_mix", frozen(primaryControls), frozen(descriptors.values.filter { it.id.startsWith("aqua_${id}_") && it.id !in primaryIds }))
    }

    private fun loadPresets(): List<AquariumPreset> {
        val resource = "/com/snipsnap/synth/aquarium/presets.json"
        val text = AquariumControls::class.java.getResourceAsStream(resource)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("missing accepted Aquarium preset metadata: $resource")
        val specs = AquariumRecipe.PARAMETERS.associateBy { it.id }
        return Json.parse(text).obj().map { (id, value) ->
            val controls = value.obj().mapValues { (key, number) ->
                (specs[key] ?: error("unknown accepted Aquarium preset control $key")).decode(number.num())
            }
            AquariumPreset(id, id.uppercase(Locale.ROOT), AquariumRecipe(controls))
        }
    }

    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
}

/** Immutable panel state. Bypass memory belongs to this edit session and never changes the saved DSP recipe. */
class AquariumEditor private constructor(val render: AquariumRenderRecipe, private val rememberedMixes: Map<String, Float>) {
    constructor(render: AquariumRenderRecipe = AquariumRenderRecipe()) : this(render, initialMixes(render))

    val presetId: String? get() = AquariumControls.presets.firstOrNull { it.settings.parameters == render.settings.parameters }?.id

    fun set(id: String, value: Float): AquariumEditor {
        AquariumControls.control(id).spec.validate(value)
        val settings = AquariumRecipe(render.settings.parameters + (id to value), render.settings.seed, render.settings.model)
        return withRender(render.copy(settings = settings))
    }

    fun setNormalized(id: String, position: Float): AquariumEditor = set(id, AquariumControls.control(id).fromNormalized(position))

    fun toggle(effectId: String): AquariumEditor {
        val id = AquariumControls.effect(effectId).mixId
        return set(id, if (render.settings.parameters.getValue(id) > 0f) 0f else rememberedMixes.getValue(effectId))
    }

    /** Replaces only the 52 sound controls; seed and every render-context field remain the caller's. */
    fun selectPreset(id: String): AquariumEditor {
        val settings = AquariumRecipe(AquariumControls.preset(id).settings.parameters, render.settings.seed, render.settings.model)
        return withRender(render.copy(settings = settings))
    }

    /** Lets render-policy controls change context without discarding the session's bypass memory. */
    fun withRender(next: AquariumRenderRecipe): AquariumEditor {
        val mixes = rememberedMixes.toMutableMap()
        for (effect in AquariumControls.effects) {
            val mix = next.settings.parameters.getValue(effect.mixId)
            if (mix > 0f) mixes[effect.id] = mix
        }
        return AquariumEditor(next, Collections.unmodifiableMap(mixes))
    }

    override fun equals(other: Any?): Boolean = other is AquariumEditor && render == other.render && rememberedMixes == other.rememberedMixes
    override fun hashCode(): Int = 31 * render.hashCode() + rememberedMixes.hashCode()

    companion object {
        private fun initialMixes(render: AquariumRenderRecipe): Map<String, Float> = Collections.unmodifiableMap(AquariumControls.effects.associate { effect ->
            val current = render.settings.parameters.getValue(effect.mixId)
            val accepted = AquariumControls.preset(effect.id).settings.parameters.getValue(effect.mixId)
            require(accepted > 0f) { "accepted Aquarium stage preset ${effect.id} must have a nonzero mix" }
            effect.id to if (current > 0f) current else accepted
        })
    }
}
