package com.snipsnap.synth

/** Audition references from the two rendered kits; deliberately outside the factory [Presets]. */
object DrumlinePresets {
    fun all(): List<DrumlinePatch> = DrumlineKits.Kit.entries.flatMap { kit ->
        DrumlineKits.patches(kit).map { patch ->
            patch.copy(name = "${kit.title.removePrefix("DRUMLINE ")} ${patch.name}")
        }
    }

    fun forVoice(voice: DrumlineVoice): List<DrumlinePatch> = all().filter { it.voice == voice }
}
