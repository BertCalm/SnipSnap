package com.snipsnap.audio

/**
 * Puts classified snips on the pads people expect them on.
 *
 * The convention this targets — kick A01, snare A02, closed hat A03, open hat
 * A04, everything else filling upward — is the finger-drumming layout most MPC
 * users already have in their hands. Landing there means a generated kit is
 * playable immediately; landing in capture order means it isn't.
 *
 * Placement is a starting point, not a verdict. Every assignment must stay
 * trivially draggable in the UI.
 */
object AutoPlace {

    /**
     * Preferred pads per class, best first, 1-based.
     *
     * A second choice exists for the core four so a kit with two kicks doesn't
     * exile the second one to the far end of the grid.
     */
    val PREFERENCES: Map<DrumClass, List<Int>> = mapOf(
        DrumClass.KICK to listOf(1, 5),
        DrumClass.SNARE to listOf(2, 6),
        DrumClass.HAT_CLOSED to listOf(3, 7),
        DrumClass.HAT_OPEN to listOf(4, 8),
        DrumClass.CLAP to listOf(6, 2),
        DrumClass.TOM to listOf(9, 10, 11),
        DrumClass.PERC to listOf(12, 13, 14),
        DrumClass.TONAL to listOf(13, 14, 15),
        DrumClass.LOOP to listOf(16, 15),
        DrumClass.UNKNOWN to emptyList(),
    )

    /**
     * Order in which classes get to claim their preferred pads.
     *
     * The core four go first: if something has to be displaced, it should be a
     * shaker, not the kick.
     */
    private val PRIORITY: List<DrumClass> = listOf(
        DrumClass.KICK,
        DrumClass.SNARE,
        DrumClass.HAT_CLOSED,
        DrumClass.HAT_OPEN,
        DrumClass.CLAP,
        DrumClass.TOM,
        DrumClass.PERC,
        DrumClass.TONAL,
        DrumClass.LOOP,
        DrumClass.UNKNOWN,
    )

    /** Mute group hats share so closed chokes open. 0 means no group. */
    const val HAT_MUTE_GROUP = 1

    /**
     * Arrange [items] across [padCount] pads.
     *
     * Returns a list of that length; index `i` holds whatever landed on pad
     * `i + 1`, or null for an empty pad. Items beyond the available pads are
     * dropped — a bank is 16 pads and a busy break yields more slices than that.
     */
    fun <T> arrange(
        items: List<T>,
        padCount: Int = 16,
        classOf: (T) -> DrumClass,
    ): List<T?> {
        require(padCount > 0) { "padCount must be positive: $padCount" }

        val pads = arrayOfNulls<Any?>(padCount)
        val unplaced = mutableListOf<T>()

        // Stable within a class, so equal candidates keep capture order.
        val ordered = items.sortedBy { item ->
            PRIORITY.indexOf(classOf(item)).let { if (it < 0) PRIORITY.size else it }
        }

        for (item in ordered) {
            val preferred = PREFERENCES[classOf(item)].orEmpty()
            val slot = preferred.firstOrNull { it in 1..padCount && pads[it - 1] == null }
            if (slot != null) {
                pads[slot - 1] = item
            } else {
                unplaced += item
            }
        }

        // Anything displaced falls into the first free pad, in priority order.
        for (item in unplaced) {
            val free = pads.indexOfFirst { it == null }
            if (free < 0) break
            pads[free] = item
        }

        @Suppress("UNCHECKED_CAST")
        return pads.toList() as List<T?>
    }

    /**
     * Frames a choked voice takes to reach silence.
     *
     * A cut mid-cycle is a click, so a choke ramps instead. It lives here,
     * beside the mute group that causes it, because every renderer has to
     * choke the same way or one pattern sounds different depending on which
     * of them played it: `KitPreview` offline, `OrbitEngine` live. About
     * 2.7 ms at 48 kHz - short enough to still read as a choke.
     */
    const val CHOKE_FADE = 128

    /**
     * Mute group for a class.
     *
     * Only hats get one by default: closed and open sharing a group is what
     * makes a closed hat choke a ringing open one, and its absence is the kind
     * of wrong that's hard to place by ear.
     */
    fun muteGroupFor(drumClass: DrumClass): Int = when (drumClass) {
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN -> HAT_MUTE_GROUP
        else -> 0
    }

    /**
     * TapeOS class colour for a class, as #rrggbb.
     *
     * These are the tokens from docs/UI_DESIGN.md — shell colour, pad colour
     * and (eventually) MPC pad colour are one language, so they live in code
     * exactly once.
     */
    fun colorFor(drumClass: DrumClass): String = when (drumClass) {
        DrumClass.KICK -> "#e8542e"
        DrumClass.SNARE -> "#ffc41f"
        DrumClass.CLAP -> "#e8409f"
        DrumClass.HAT_CLOSED -> "#1fc6cf"
        DrumClass.HAT_OPEN -> "#7adfe4"
        DrumClass.TOM -> "#9a6cf0"
        DrumClass.PERC -> "#8fd424"
        DrumClass.TONAL -> "#b06cf0"
        DrumClass.LOOP -> "#3f8cf0"
        DrumClass.UNKNOWN -> "#b9bdc1"
    }

    /** Suggested sample-name stem for a class, for generated filenames. */
    fun nameFor(drumClass: DrumClass): String = when (drumClass) {
        DrumClass.KICK -> "Kick"
        DrumClass.SNARE -> "Snare"
        DrumClass.CLAP -> "Clap"
        DrumClass.HAT_CLOSED -> "HatClosed"
        DrumClass.HAT_OPEN -> "HatOpen"
        DrumClass.TOM -> "Tom"
        DrumClass.PERC -> "Perc"
        DrumClass.TONAL -> "Tonal"
        DrumClass.LOOP -> "Loop"
        DrumClass.UNKNOWN -> "Snip"
    }
}
