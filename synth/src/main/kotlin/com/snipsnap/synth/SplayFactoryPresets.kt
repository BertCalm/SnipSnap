package com.snipsnap.synth

/**
 * Exact KEEP performances. TONGUE's five are its accepted population settings; population is an
 * internal mechanism count, never a voice identity. Each of the five model-3 bodies has one: the
 * 16-mechanism setting at C4 the owner kept (rounds splay-voices-1 to -3), which is also that body's
 * default. No control law, acoustic model, saved name or native input is changed for registration.
 * [SplayPresets] remains the original, separately addressable provisional bank.
 */
object SplayFactoryPresets {
    /** The kept body takes used TONGUE's 16-mechanism setting, unchanged, at model 3. */
    private val keptBodies: List<SplayPatch> = SplayAcceptedBank.entries.single { it.mechanisms == 16 }.patch.let { pc16 ->
        SplayVoice.entries.filter { it.body != null }.map { voice ->
            pc16.copy(voice = voice, model = Splay.BODY_MODEL)
        }
    }

    private val presets: List<SplayPatch> = SplayAcceptedBank.entries.map { it.patch } + keptBodies

    fun forVoice(voice: SplayVoice): List<SplayPatch> = presets.filter { it.voice == voice }
    fun all(): List<SplayPatch> = presets
}
