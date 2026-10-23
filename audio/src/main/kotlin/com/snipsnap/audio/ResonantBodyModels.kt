package com.snipsnap.audio

import java.util.Collections
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ideal filter spectra, not recordings or measured material responses. */
enum class BodyFamily { HARMONIC, BAR }

/** Model 1 controls. A body is tuned explicitly; source pitch is never analyzed. */
data class BodySettings(
    val tune: Float = 1f / 3f,
    val body: Float = 0f,
    val ring: Float = 0.5f,
    val damp: Float = 0.5f,
    val mix: Float = 0.5f,
    val from: BodyFamily = BodyFamily.HARMONIC,
    val to: BodyFamily = BodyFamily.BAR,
    val modelVersion: Int = ResonantBody.MODEL_VERSION,
) {
    init {
        require(modelVersion == ResonantBody.MODEL_VERSION) { "unsupported body model version $modelVersion" }
        for ((name, value) in listOf("TUNE" to tune, "BODY" to body, "RING" to ring, "DAMP" to damp, "MIX" to mix)) {
            require(value.isFinite() && value in 0f..1f) { "$name must be finite and in 0..1" }
        }
        require(from != to) { "body families must differ" }
    }

    val rootMidi: Int get() = (36 + 36 * tune.toDouble()).roundToInt()
    val referenceHz: Double get() = 440.0 * 2.0.pow((rootMidi - 69) / 12.0)
    // Multiplication instead of pow(12) at the upper endpoint avoids a spurious extra tail frame.
    val rootT60: Double get() = if (ring == 1f) 0.6 else 0.05 * 12.0.pow(ring.toDouble())
}

internal data class BodyMode(val ratio: Double, val weight: Double)
internal data class BodyTableId(val version: Int, val from: BodyFamily, val to: BodyFamily, val modes: List<BodyMode>)

internal object BodyModels {
    // Authored shape, not measured excitation strengths. Normalize once, never against rendered PCM.
    private val weights = listOf(1.0, 0.65, 0.4, 0.25).let { w -> w.map { it / w.sum() } }
    private fun modes(family: BodyFamily): List<BodyMode> {
        val ratios = when (family) {
            BodyFamily.HARMONIC -> listOf(1.0, 2.0, 3.0, 4.0) // ideal string's zero-stiffness harmonic series
            // Existing Modes.METAL_BAR: rounded free-free Euler–Bernoulli ratios,
            // attributed there to Fletcher & Rossing / Blevins. No strike renderer is reused.
            BodyFamily.BAR -> listOf(1.0, 2.756, 5.404, 8.933)
        }
        return ratios.mapIndexed { i, ratio -> BodyMode(ratio, weights[i]) }
    }
    private val tables = BodyFamily.entries.associateWith(::modes)
    private val identities = BodyFamily.entries.associateWith { from ->
        val to = BodyFamily.entries.first { it != from }
        BodyTableId(ResonantBody.MODEL_VERSION, from, to,
            Collections.unmodifiableList(tables.getValue(from) + tables.getValue(to)))
    }
    fun identity(settings: BodySettings): BodyTableId = identities.getValue(settings.from)
}

internal data class BodyCoefficientKey(
    val table: BodyTableId,
    val algorithmVersion: Int,
    val rate: Int,
    val referenceHz: Double,
    val rootT60: Double,
    val damping: Double,
)

internal data class BodyModeCoefficients(
    val hz: Double, val t60: Double, val radius: Double,
    val rotateReal: Double, val rotateImaginary: Double,
    val injectReal: Double, val injectImaginary: Double, val weight: Double,
)

internal fun bodyCoefficients(key: BodyCoefficientKey): List<BodyModeCoefficients> = key.table.modes.map { mode ->
    val hz = key.referenceHz * mode.ratio
    require(hz > 0 && hz < key.rate / 2.0) { "body mode $hz Hz outside Nyquist" }
    val t60 = max(0.01, key.rootT60 / (1 + 4 * key.damping * log2(mode.ratio)))
    val r = exp(-ln(1000.0) / (t60 * key.rate))
    require(r > 0 && r < 1) { "invalid body decay radius" }
    val theta = 2 * PI * hz / key.rate
    val c = cos(theta)
    val s = sin(theta)
    BodyModeCoefficients(hz, t60, r, r * c, r * s, (1 - r) * c, (1 - r) * s, mode.weight)
}

/** Bounded per-processor coefficients only. PCM and mutable ringing state never enter this cache. */
internal class BodyCoefficientCache(private val enabled: Boolean) {
    private val entries = LinkedHashMap<BodyCoefficientKey, List<BodyModeCoefficients>>(8, 0.75f, true)

    @Synchronized
    fun get(key: BodyCoefficientKey): Pair<List<BodyModeCoefficients>, Boolean> {
        if (enabled) entries[key]?.let { return it to true }
        val coefficients = bodyCoefficients(key)
        if (enabled) {
            entries[key] = coefficients
            if (entries.size > 8) entries.remove(entries.keys.first())
        }
        return coefficients to false
    }

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int = entries.size
}
