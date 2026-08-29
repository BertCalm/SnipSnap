package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.Names
import com.snipsnap.synth.Shuffle
import com.snipsnap.synth.SynthKits
import com.snipsnap.synth.ThumpKits
import com.snipsnap.synth.Velocity
import java.io.File

/**
 * The FRESH TAPE menu as data — every "new kit from nothing" the app
 * offers, wrapping the main-source synth builders so the menu is a table,
 * not a when-expression in a screen.
 *
 * This is the cold-start answer: the app is useful in the first thirty
 * seconds, before anything has been captured, and the empty grid never
 * looks empty. Every starter renders offline through the same pipeline as
 * captured audio (and carries per-pad recipes, so a starter kit remains
 * editable and regenerable forever). Seeded starters reroll; fixed ones
 * always land the same beloved kit.
 */
object StarterKits {

    class Starter internal constructor(
        /** Stable machine id. */
        val id: String,
        /** Menu line, in voice. */
        val displayName: String,
        /** One sentence under it. */
        val blurb: String,
        /** True = REROLL changes the result; false = a fixed recipe. */
        val seeded: Boolean,
        /** A key the kit's melodic content is in, when it has one. */
        val key: KeySpec? = null,
        private val build: (seed: Int) -> List<ArrangedPad?>,
    ) {
        /**
         * Render into [dir] as a full kit folder (WAVs + `kit.json`),
         * exactly the shape every other kit source produces.
         */
        fun render(name: String, dir: File, seed: Int = 0): Kit {
            require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
            return KitAssembler.assembleArranged(name, build(seed), dir, key)
        }
    }

    val ALL: List<Starter> = listOf(
        Starter(
            "factory", "FACTORY",
            "The house kit. Kick, snare, hats that choke, the works.",
            seeded = false,
        ) { ThumpKits.classic() },
        Starter(
            "lucky-dip", "LUCKY DIP",
            "Dice-rolled drums. The dice are audited - they can't roll a bad kit.",
            seeded = true,
        ) { Shuffle.kit(it) },
        Starter(
            "lucky-dip-ab", "LUCKY DIP A/B",
            "The dice kit plus a remixed bank B - every pad's evil twin.",
            seeded = true,
        ) { Shuffle.withRemixBank(Shuffle.kit(it), it) },
        Starter(
            "melodic", "MELODIC",
            "Plucks and organ stabs in A minor pentatonic. Runs, not just hits.",
            seeded = false,
            key = KeySpec.parse("Aminpent"),
        ) { SynthKits.melodic() },
        Starter(
            "chip", "CHIP",
            "Everything through a ~9-bit converter. 1987 in a kit.",
            seeded = false,
        ) { SynthKits.chip() },
        Starter(
            "cloud", "CLOUD",
            "Choirs and grain clouds. Atmosphere, not drums.",
            seeded = false,
        ) { SynthKits.cloud() },
        Starter(
            "velocity", "VELOCITY",
            "The house kit with ghost notes. Soft hits sound soft, not just quiet.",
            seeded = false,
        ) {
            ThumpKits.classic().map { pad ->
                pad?.copy(softVariants = Velocity.variants(pad.snip, count = 2))
            }
        },
    )

    fun byId(id: String): Starter? = ALL.firstOrNull { it.id == id }
}
