package com.snipsnap.synth

/** Factory sounds from the two kits approved on 2026-11-15; no extra knob settings. */
object DrumlinePresets {
    /** Paste complete UserPresets roster lines here; the accepted kits stay fixed. */
    private val promoted: List<DrumlinePatch> = listOf()

    fun all(): List<DrumlinePatch> = DrumlineKits.Kit.entries.flatMap { kit ->
        DrumlineKits.patches(kit).map { patch ->
            patch.copy(name = "${kit.id.removePrefix("drumline-")} ${patch.name}".uppercase())
        }
    } + promoted

    fun forVoice(voice: DrumlineVoice): List<DrumlinePatch> = all().filter { it.voice == voice }
}
