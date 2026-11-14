package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Splay
import com.snipsnap.synth.SplayAcceptedBank
import com.snipsnap.synth.SplayFactoryPresets
import com.snipsnap.synth.SplayPatch

/** Finite native delivery of one TONGUE voice; population settings do not create new voices. */
object SplayDelivery {
    const val STARTER_ID = "splay"
    const val PLAYING_NOTE = "ONE TONGUE VOICE · FIVE POPULATION SETTINGS · FINITE 8 SECONDS"

    /** The saved model owns missing-macro defaults, including legacy sparse model-1 recipes. */
    fun controls(patch: SplayPatch): Map<String, Float> = Splay.defaults(patch.voice, patch.model) + patch.macros

    /** Retains the complete saved root, velocity, signed seed and acoustic model on every edit. */
    fun edited(patch: SplayPatch, name: String, macros: Map<String, Float>): SplayPatch =
        Patches.edited(patch, name, macros) as SplayPatch

    fun starterPatches(): List<SplayPatch> = SplayFactoryPresets.all()

    /**
     * Five independent finite pads, using the existing native Patch.render/PadRecipe contract.
     * The listening room's raw × fixed matched gain remains a separate comparison format;
     * no matched-gain audio is put behind a recipe that would replay with native leveling.
     */
    fun starterPads(): List<ArrangedPad> = starterPatches().mapIndexed { index, patch ->
        val accepted = SplayAcceptedBank.entries[index]
        check(patch == accepted.patch)
        ArrangedPad(
            snip = patch.render(),
            drumClass = DrumClass.TONAL,
            recipe = PadRecipe(patch).toJsonValue(),
            displayName = "TONGUE ${patch.name}",
            oneShot = true,
            muteGroup = 0,
            source = mapOf(
                "engine" to SplayPatch.ENGINE,
                "voice" to patch.voice.name,
                "settingId" to accepted.id,
                "populationCount" to accepted.mechanisms.toString(),
                "rootMidi" to patch.midi.toString(),
                "rootOrigin" to "authored",
                "renderPolicy" to "native-patch-level",
            ),
        )
    }
}
