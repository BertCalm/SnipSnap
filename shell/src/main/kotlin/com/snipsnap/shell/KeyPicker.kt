package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Scales
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad

/**
 * The KIT screen's KEY panel, as data (F5.3): twelve roots, five scales,
 * OFF, and the readout of what IN KEY did to each tonal pad. The panel
 * drives [KitBuilderModel.setKey] and [KitBuilderModel.retuneTonalPads];
 * nothing here touches audio — a key is metadata, and IN KEY moves the
 * pad's own tune fields, the way the CLI's `--key` does.
 */
object KeyPicker {

    /** The roots, C first, as the chips draw them. */
    val ROOTS: List<String> = Scales.NOTE_NAMES.toList()

    /** The scales, in the words the chips use. */
    val SCALES: List<String> = listOf("MAJOR", "MINOR", "MAJ PENT", "MIN PENT", "CHROMATIC")

    /** Where a fresh panel opens when the kit has no key: the sampler's home. */
    const val DEFAULT_ROOT = 9 // A
    const val DEFAULT_SCALE = "MINOR"

    private val SCALE_FOR: Map<String, Scale> = mapOf(
        "MAJOR" to Scale.MAJOR,
        "MINOR" to Scale.MINOR,
        "MAJ PENT" to Scale.MAJOR_PENTATONIC,
        "MIN PENT" to Scale.MINOR_PENTATONIC,
        "CHROMATIC" to Scale.CHROMATIC,
    )

    /** The chip's word for a scale. */
    fun scaleLabel(scale: Scale): String = SCALE_FOR.entries.first { it.value == scale }.key

    /** A key from the chips; throws on a word the panel does not draw. */
    fun key(rootIndex: Int, scaleLabel: String): KeySpec {
        require(rootIndex in ROOTS.indices) { "root is 0..11, got $rootIndex" }
        val scale = SCALE_FOR[scaleLabel]
            ?: throw IllegalArgumentException("unknown scale '$scaleLabel' - the panel draws: ${SCALES.joinToString(", ")}")
        return KeySpec(rootIndex, scale)
    }

    /** "A MINOR", "F# MAJ PENT", or "OFF". */
    fun label(key: KeySpec?): String = key?.let { "${ROOTS[it.rootSemitone]} ${scaleLabel(it.scale)}" } ?: "OFF"

    /** One tonal pad's tune readout: "A03 · BASS 01 · +2 ST −13¢", or "… · AS CAPTURED" when nothing moved it. */
    fun readout(pad: KitPad): String {
        val tune = if (pad.tuneCoarse == 0 && pad.tuneFine == 0) {
            "AS CAPTURED"
        } else {
            "%+d ST %+d¢".format(java.util.Locale.ROOT, pad.tuneCoarse, pad.tuneFine).replace("-", "−")
        }
        // Kotlin's uppercase() is already invariant-locale; Locale.ROOT makes that visible.
        return "${MutateSheet.padTag(pad.slot)} · ${pad.displayName.uppercase(java.util.Locale.ROOT)} · $tune"
    }

    /** The readouts for every tonal pad, slot order — the pads IN KEY may move; empty when the kit has none. */
    fun readouts(kit: Kit): List<String> =
        kit.pads.filter { it.drumClass == DrumClass.TONAL }.sortedBy { it.slot }.map { readout(it) }
}
