package com.snipsnap.synth

import kotlin.math.exp
import kotlin.math.ln

/**
 * Audition-only passive contact loading, profile 1. This is a loss analogy, not a simulated
 * finger. A second nonnegative decay rate acts inside each played string before its body and
 * returns. Phase-bearing coefficients, excitation, event timing, and reference output gain stay
 * fixed. No factory preset, saved model, macro, layer policy, or extra voice selects this path.
 */
internal object NacreContactStudy {
    const val PROFILE_VERSION = 1
    // Proposed shape, not a measured or heard optimum: full added loss alone gives 0.65 s T60.
    const val LOSS_T60_SECONDS = .65

    data class Profile(val additionalLoss: Float, val version: Int = PROFILE_VERSION) {
        init {
            require(version == PROFILE_VERSION) { "unsupported NACRE contact study profile $version" }
            require(additionalLoss.isFinite() && additionalLoss in 0f..1f) {
                "NACRE contact study loss must be finite and in 0..1"
            }
        }
    }

    /** Per physical round trip; additional decay rate loss/0.65 s is independent of pitch. */
    internal fun perPeriodGain(loss: Float, hz: Double): Double {
        require(loss.isFinite() && loss in 0f..1f && hz.isFinite() && hz > 0.0)
        return if (loss == 0f) 1.0 else exp(-ln(1000.0) * loss / (LOSS_T60_SECONDS * hz))
    }

    fun inspect(profile: Profile, voice: NacreVoice = NacreVoice.PEARL,
        macros: Map<String, Float> = emptyMap(), midi: Int = Nacre.DEFAULT_MIDI,
        velocity: Float = 1f, seed: Long = Nacre.DEFAULT_SEED,
        options: Nacre.Options = Nacre.Options()): Nacre.Probe {
        val probe = Nacre.inspectContactStudy(voice, macros, midi, velocity, seed, options, profile.additionalLoss)
        return probe.copy(diagnostics = probe.diagnostics + linkedMapOf(
            "contactStudyProfileVersion" to profile.version.toDouble(),
            "contactStudyAdditionalLoss" to profile.additionalLoss.toDouble(),
            "contactStudyLossT60Seconds" to LOSS_T60_SECONDS,
            "contactStudyRegisteredOrSaved" to 0.0,
        ))
    }
}
