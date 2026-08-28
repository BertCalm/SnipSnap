package com.snipsnap.shell

import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.Kit
import com.snipsnap.mpc3.Mpc3Clip
import java.io.File
import java.util.Random

/**
 * The Arranger (KK1): songs, not loops. The kit already owns every
 * ingredient — the captured groove, its tight/half/sparse variations,
 * the fill, the ghost grammar — and this lays them into a structure a
 * beat tape would recognize:
 *
 * ```
 * intro (sparse) → theme (captured) → variation (ghosted or tight) →
 * the turn (fill) → reprise (captured) → outro (half)
 * ```
 *
 * Every section's clip is one of the kit's own variations, derived
 * fresh from the stored base groove so the plan never depends on what
 * happened to be saved. Seeded and deterministic: the same (kit, seed)
 * always plans the same song. Honest refusals and skips: no groove
 * refuses outright; a kit with nothing to roll on skips the turn; no
 * snare to whisper on means the variation falls back to tight.
 */
object Arranger {

    /** One section: a variation clip played [repeats] times through. */
    data class Section(val name: String, val clip: Mpc3Clip, val repeats: Int) {
        init {
            require(repeats >= 1) { "a section plays at least once" }
        }

        val bars: Int get() = clip.bars * repeats
    }

    data class Arrangement(val name: String, val seed: Int, val sections: List<Section>) {
        val totalBars: Int get() = sections.sumOf { it.bars }
    }

    /** Section target lengths in bars — the classic beat-tape proportions. */
    private const val INTRO_BARS = 2
    private const val BODY_BARS = 4
    private const val OUTRO_BARS = 2

    fun arrange(kit: Kit, kitDir: File, seed: Int = 0): Arrangement {
        val base = GrooveStore.load(kitDir).firstOrNull()
            ?: throw IllegalArgumentException(
                "no groove to arrange - chop with --groove, or import a .mid",
            )
        val std = GrooveVariations.standard(base)
        val captured = std[0]
        val tight = std[1]
        val half = std[2]
        val sparse = std[3]

        // Draw before deriving, so the rng stream never depends on what
        // the kit happens to support.
        val rng = Random(seed.toLong())
        val preferGhosts = rng.nextFloat() < 0.7f
        val ghosted = try {
            GrooveVariations.ghosted(base, kit, seed = seed + 1)
        } catch (e: IllegalArgumentException) {
            null
        }
        val variation = if (preferGhosts && ghosted != null) ghosted else tight
        val turn = try {
            GrooveVariations.fill(base, kit, seed = seed + 2)
        } catch (e: IllegalArgumentException) {
            null
        }

        fun repeats(clip: Mpc3Clip, target: Int) = maxOf(1, target / clip.bars)
        val sections = buildList {
            add(Section("intro", sparse, repeats(sparse, INTRO_BARS)))
            add(Section("theme", captured, repeats(captured, BODY_BARS)))
            add(Section("variation", variation, repeats(variation, BODY_BARS)))
            turn?.let { add(Section("the turn", it, repeats(it, BODY_BARS))) }
            add(Section("reprise", captured, repeats(captured, BODY_BARS)))
            add(Section("outro", half, repeats(half, OUTRO_BARS)))
        }
        return Arrangement("${kit.name} Song", seed, sections)
    }
}
