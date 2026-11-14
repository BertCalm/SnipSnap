package com.snipsnap.synth

/**
 * Exact KEEP performances of the single finite TONGUE voice.
 * Population is an internal mechanism count, never a voice identity. No control
 * law, acoustic model, saved name or native input is changed for registration.
 * [SplayPresets] remains the original, separately addressable provisional bank.
 */
object SplayFactoryPresets {
    private val presets: List<SplayPatch> = SplayAcceptedBank.entries.map { it.patch }

    fun forVoice(voice: SplayVoice): List<SplayPatch> = presets.filter { it.voice == voice }
    fun all(): List<SplayPatch> = presets
}
