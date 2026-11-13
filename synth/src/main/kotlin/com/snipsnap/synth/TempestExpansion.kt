package com.snipsnap.synth

/** Compatibility entry points for the original full-kit audition. All recipes are now factory entries. */
object TempestExpansion {
    fun forVoice(voice: TempestVoice): List<TempestPatch> =
        if (voice in Tempest.EXPANSION_VOICES) TempestPresets.forVoice(voice) else emptyList()

    fun fullKitPatches(): List<TempestPatch> = TempestPresets.fullKitPatches()
}
