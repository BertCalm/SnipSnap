package com.snipsnap.kit

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.util.Locale

/**
 * One slot of input for [KitAssembler.assemble]: the rendered audio, the
 * class the kit layer names and colours it by, and optionally the recipe
 * that regenerates it (opaque here; written by `:synth`), a mixer level
 * (set by [Balance]), and tuning offsets (set by [InKey]).
 */
data class ArrangedPad(
    val snip: Snip,
    val drumClass: DrumClass,
    val recipe: JsonValue.Obj? = null,
    val level: Float? = null,
    val tuneCoarse: Int = 0,
    val tuneFine: Int = 0,
    /** false = gate: PLAY holds the note while pressed, instead of one-shot. */
    val oneShot: Boolean = true,
    /**
     * Softer renderings of the same hit, softest first, [snip] being the
     * hardest. Up to three; each becomes a velocity zone under the main
     * sample, so soft pad hits *sound* soft on the hardware.
     */
    val softVariants: List<Snip> = emptyList(),
    /**
     * Provenance, carried through to [KitPad.source]: where this hit came
     * from ("file", "sourceFrame", "lengthFrames", …). A chopped kit
     * should remember its origins.
     */
    val source: Map<String, String> = emptyMap(),
) {
    init {
        require(softVariants.size <= 3) { "at most 3 soft variants (4 zones total)" }
    }
}

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
        /** The kit's key, when in-key treatment chose one — persisted in kit.json. */
        key: com.snipsnap.audio.KeySpec? = null,
        /** The source's detected tempo — persisted, and stamped into LOOP stems. */
        tempoBpm: Float? = null,
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
            // A loop's tempo is the fact you browse for; it rides in the stem.
            // Locale.ROOT on both: `%d` follows the default locale, so on an
            // ar-EG or fa-IR device the tempo and the counter would arrive as
            // Eastern-Arabic digits and the filename would stop being
            // MPC-safe. The bpm stem is a new path onto the same rake.
            val stemClass = if (pad.drumClass == DrumClass.LOOP && tempoBpm != null) {
                String.format(Locale.ROOT, "%s_%dbpm", className, Math.round(tempoBpm))
            } else {
                className
            }
            val stem = String.format(Locale.ROOT, "%s_%s_%02d", label, stemClass, n)

            WavWriter.write(File(dir, "$stem.wav"), pad.snip)

            // Soft variants become velocity zones: the band 1..127 splits
            // evenly, softest zone first, the main sample on top. The first
            // zone starts at 1, not 0 — velocity 0 is note-off, and a layer
            // window that includes it can ghost-trigger (Rex Rule #3, via
            // the XO_OX XPN toolchain).
            val layers = if (pad.softVariants.isEmpty()) emptyList() else buildList {
                val zoneCount = pad.softVariants.size + 1
                pad.softVariants.forEachIndexed { v, variant ->
                    val file = "${stem}_v${v + 1}.wav"
                    WavWriter.write(File(dir, file), variant)
                    add(
                        KitLayer(
                            file,
                            velStart = if (v == 0) 1 else 128 * v / zoneCount,
                            velEnd = 128 * (v + 1) / zoneCount - 1,
                        ),
                    )
                }
                add(KitLayer("$stem.wav", velStart = 128 * pad.softVariants.size / zoneCount, velEnd = 127))
            }

            pads += KitPad(
                slot = slot,
                sampleFile = "$stem.wav",
                displayName = String.format(Locale.ROOT, "%s %02d", className, n),
                drumClass = pad.drumClass,
                colorHex = AutoPlace.colorFor(pad.drumClass),
                level = pad.level ?: 0.707946f,
                tuneCoarse = pad.tuneCoarse,
                tuneFine = pad.tuneFine,
                oneShot = pad.oneShot,
                muteGroup = AutoPlace.muteGroupFor(pad.drumClass),
                source = pad.source,
                recipe = pad.recipe,
                velocityLayers = layers,
            )
        }

        val kit = Kit(name, pads, key, tempoBpm)
        KitStore.save(kit, dir)
        return kit
    }
}
