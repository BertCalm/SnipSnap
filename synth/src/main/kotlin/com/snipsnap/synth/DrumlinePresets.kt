package com.snipsnap.synth

/** Factory sounds from the two kits approved on 2026-11-15; no extra knob settings. */
object DrumlinePresets {
    fun all(): List<DrumlinePatch> = DrumlineKits.Kit.entries.flatMap { kit ->
        DrumlineKits.patches(kit).map { patch ->
            patch.copy(name = "${kit.id.removePrefix("drumline-")} ${patch.name}".uppercase())
        }
    }

    fun forVoice(voice: DrumlineVoice): List<DrumlinePatch> = all().filter { it.voice == voice }
}
