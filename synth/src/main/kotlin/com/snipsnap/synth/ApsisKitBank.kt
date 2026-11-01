package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import java.util.Locale

enum class ApsisPadRole { BASS, KEY, CHORD_TONE, COLOR, HELD }

/** A native sample zone. Performance velocity changes excitation before the house output stage. */
data class ApsisBankLayer(val velStart: Int, val velEnd: Int, val patch: ApsisPatch)

/** The shared source of pad recipes for the MPC bank and its musical scores. */
data class ApsisBankEntry(
    val slot: Int,
    val id: String,
    val role: ApsisPadRole,
    val presetName: String,
    val patch: ApsisPatch,
) {
    val label: String get() = String.format(Locale.ROOT, "A%02d", slot)
    val note: String get() = Scales.nameOf(patch.midi)
    val held: Boolean get() = Apsis.isLoop(patch.macros.getValue("HOLD"))
    val level: Float get() = 1f
    // A separate group per pad makes retriggers replace that pad's previous voice only.
    val muteGroup: Int get() = slot
    fun render(): Snip = patch.render()

    /** These windows are the three-zone split implemented by KitAssembler.assembleArranged. */
    fun layers(): List<ApsisBankLayer> = if (role in setOf(ApsisPadRole.BASS, ApsisPadRole.KEY, ApsisPadRole.CHORD_TONE))
        listOf(ApsisBankLayer(1, 41, patch.copy(velocity = patch.velocity * .35f)),
            ApsisBankLayer(42, 84, patch.copy(velocity = patch.velocity * .65f)),
            ApsisBankLayer(85, 127, patch))
        else listOf(ApsisBankLayer(1, 127, patch))
}

/**
 * Four basses, four keys, four independently playable chord tones, two timing colors,
 * and two genuine held cycles. All six established presets remain represented.
 * Held cycles gate off immediately; no new attack, release fade or effects are baked in.
 */
object ApsisKitBank {
    const val NOTE_OFF_RELEASE_CONTROL = 0f
    private val entries: List<ApsisBankEntry> by lazy {
        fun entry(slot: Int, id: String, role: ApsisPadRole, preset: String, midi: Int,
            velocity: Float): ApsisBankEntry {
            val source = ApsisPresets.forVoice(ApsisVoice.PASSAGE).single { it.name == preset }
            val name = if (role == ApsisPadRole.COLOR) source.name else "${source.name} ${Scales.nameOf(midi)}"
            return ApsisBankEntry(slot, id, role, preset, source.copy(name = name, midi = midi, velocity = velocity))
        }
        buildList {
            for ((index, midi) in listOf(36, 39, 43, 46).withIndex())
                add(entry(index + 1, "bass-$midi", ApsisPadRole.BASS, "Close Rush", midi, .9f))
            for ((index, midi) in listOf(48, 51, 55, 58).withIndex())
                add(entry(index + 5, "key-$midi", ApsisPadRole.KEY, "Slow Apse", midi, .8f))
            for ((index, midi) in listOf(60, 63, 67, 70).withIndex())
                add(entry(index + 9, "chord-$midi", ApsisPadRole.CHORD_TONE, "Ringing Bodies", midi, .75f))
            add(entry(13, "gathered-color", ApsisPadRole.COLOR, "Gathered Bloom", 60, .8f))
            add(entry(14, "spaced-color", ApsisPadRole.COLOR, "Spaced Replies", 60, .8f))
            add(entry(15, "held-root", ApsisPadRole.HELD, "Held Return", 48, .65f))
            add(entry(16, "held-fifth", ApsisPadRole.HELD, "Held Return", 55, .65f))
        }
    }

    fun all(): List<ApsisBankEntry> = entries
    fun pad(slot: Int): ApsisBankEntry = entries.single { it.slot == slot }

    fun arrangedPads(): List<ArrangedPad?> = entries.map { entry ->
        val layers = entry.layers()
        val recipe = PadRecipe(entry.patch)
        ArrangedPad(entry.render(), Apsis.drumClassFor(entry.patch.voice, entry.patch.macros),
            recipe.toJsonValue(), level = entry.level, oneShot = !entry.held,
            softVariants = layers.dropLast(1).map { it.patch.render() }, displayName = entry.patch.name,
            source = mapOf("engine" to ApsisPatch.ENGINE, "bankId" to entry.id, "role" to entry.role.name,
                "preset" to entry.presetName, "midi" to entry.patch.midi.toString(),
                "velocity" to entry.patch.velocity.toString(), "muteGroup" to entry.muteGroup.toString(),
                "retrigger" to "Restart at frame 0; replace the preceding voice of this pad only.",
                "playback" to if (entry.held) "NOTE_ON_FORWARD_LOOP" else "ONE_SHOT",
                "nativeExport" to "generateApsisKit saves full-cycle loop flags and WAV metadata; shared kit exporters preserve gated loop playback.",
                "loopStartFrame" to "0", "loopEnd" to if (entry.held) "sample frame count (exclusive)" else "none",
                "releaseControl" to NOTE_OFF_RELEASE_CONTROL.toString(),
                "noteOff" to if (entry.held) "Immediate gate off; no added release fade." else "Ignored; complete finite sample."))
    }
}
