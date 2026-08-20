package com.snipsnap.kit

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.PadNoteMap
import java.io.File

/**
 * One slot of input for [KitAssembler.assemble]: the rendered audio, the
 * class the kit layer names and colours it by, and optionally the recipe
 * that regenerates it (opaque here; written by `:synth`).
 */
data class ArrangedPad(
    val snip: Snip,
    val drumClass: DrumClass,
    val recipe: JsonValue.Obj? = null,
)

/**
 * Turns arranged snips into a kit folder — the last step of the auto-chop
 * pipeline. Input is what [AutoPlace.arrange] hands back: index `i` holds the
 * snip that landed on pad `i + 1`, or null.
 *
 * Writes each snip as a 24-bit WAV named for its pad and class
 * (`A01_Kick_01.wav`), saves the `kit.json` sidecar, and returns the [Kit].
 * The folder that comes out is one [KitExporter] call away from an SD card.
 */
object KitAssembler {

    fun assemble(
        name: String,
        arranged: List<Pair<Snip, DrumClass>?>,
        dir: File,
    ): Kit = assembleArranged(name, arranged.map { it?.let { (s, c) -> ArrangedPad(s, c) } }, dir)

    @JvmName("assembleArranged")
    fun assembleArranged(
        name: String,
        arranged: List<ArrangedPad?>,
        dir: File,
    ): Kit {
        require(arranged.size <= 128) { "at most 128 pad slots, got ${arranged.size}" }
        dir.mkdirs()
        require(dir.isDirectory) { "not a directory: $dir" }

        val perClassCount = HashMap<DrumClass, Int>()
        val pads = mutableListOf<KitPad>()

        arranged.forEachIndexed { i, entry ->
            val pad = entry ?: return@forEachIndexed
            if (pad.snip.frameCount == 0) return@forEachIndexed

            val slot = i + 1
            val n = perClassCount.merge(pad.drumClass, 1, Int::plus)!!
            val label = PadNoteMap.labelForPad(slot)
            val className = AutoPlace.nameFor(pad.drumClass)
            val stem = "%s_%s_%02d".format(label, className, n)

            WavWriter.write(File(dir, "$stem.wav"), pad.snip)

            pads += KitPad(
                slot = slot,
                sampleFile = "$stem.wav",
                displayName = "%s %02d".format(className, n),
                drumClass = pad.drumClass,
                colorHex = AutoPlace.colorFor(pad.drumClass),
                muteGroup = AutoPlace.muteGroupFor(pad.drumClass),
                recipe = pad.recipe,
            )
        }

        val kit = Kit(name, pads)
        KitStore.save(kit, dir)
        return kit
    }
}
