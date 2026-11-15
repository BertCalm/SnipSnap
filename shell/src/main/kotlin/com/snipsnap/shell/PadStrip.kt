package com.snipsnap.shell

import com.snipsnap.kit.KitPad

/**
 * The pad strip (`docs/SCREEN_PLAN_2026_10.md` §3.3): one line under the
 * grid for the selected pad, and the tap doors into the pad editor the
 * reviews asked for (W#22). Tap a pad to hear it and select it; the strip
 * reads what it carries and offers the two things a new user came to do with
 * it: [DOOR_CHANGE_SOUND], straight to its effects, and [DOOR_EDIT], the
 * editor. An empty pad's strip offers what can fill it. The owner's UX pass
 * (2026-11-15, "simple first, more later") put changing a pad's sound one tap
 * from the grid, where OPEN ▸ then EFFECTS ▸ made it two, and said nothing on
 * the way that the effects were there.
 *
 * Built on [PadSheetBoxes]' own closed-box strings so the strip and the
 * sheet never disagree about what a pad carries: the strip is those
 * summaries, minus the benches the pad carries nothing from, plus the two facts the sheet's
 * everyday row shows (choke group, layers).
 */
object PadStrip {

    data class Strip(
        /** `A02`. */
        val id: String,
        /** The pad's name, or null for an empty pad. */
        val name: String?,
        /** `CRUSH · 35% · CHOKE 1`, or [NO_EFFECTS], or [EMPTY]. */
        val facts: String,
        /** The strip's doors, in order. */
        val doors: List<String>,
    ) {
        /** The whole strip as one line: `A02 · snare · CRUSH · 35%`. */
        val line: String get() = listOfNotNull(id, name, facts).joinToString(" · ")
    }

    /** A pad nothing has been done to. Was RAW, which a new user could not read (UX pass, 2026-11-15). */
    const val NO_EFFECTS = "NO EFFECTS"
    const val EMPTY = "EMPTY"

    /** Opens the pad editor on its EFFECTS page, with the EFFECTS box open. */
    const val DOOR_CHANGE_SOUND = "CHANGE SOUND ▸"
    /** Opens the pad editor. Holding a pad is its shortcut. */
    const val DOOR_EDIT = "EDIT PAD ▸"
    /** Records a new sound into an empty pad. Holding an empty pad is its shortcut. */
    const val DOOR_RECORD = "RECORD INTO PAD ▸"
    const val DOOR_FROM_SAMPLES = "FROM MY SAMPLES ▸"

    fun line(pad: KitPad?, slot: Int, outsideStage: String? = null): Strip {
        val id = PadBanks.tag(slot)
        if (pad == null) return Strip(id, null, EMPTY, listOf(DOOR_RECORD, DOOR_FROM_SAMPLES))
        // `touched`, not "any strip with words": every strip reads words
        // (what the bench holds, when idle) and MAKE's always advertises what the box can do, so only
        // the benches the sheet itself counts as carrying something are
        // read, in the sheet's own order.
        val touched = PadSheetBoxes.touched(pad, outsideStage)
        val benches = PadSheetBoxes.summaries(pad, outsideStage)
            .filterKeys { it in touched }
            .values
        val facts = buildList {
            addAll(benches)
            if (pad.muteGroup > 0) add("CHOKE ${pad.muteGroup}")
            if (pad.velocityLayers.isNotEmpty()) add("${pad.velocityLayers.size} LAYERS")
            if (!pad.oneShot) add("HOLDS")
        }
        return Strip(id, pad.displayName, if (facts.isEmpty()) NO_EFFECTS else facts.joinToString(" · "), listOf(DOOR_CHANGE_SOUND, DOOR_EDIT))
    }

    /** The strip fitted to one line of [maxChars]: the facts give way first, the name never. */
    fun oneLine(strip: Strip, maxChars: Int = PadSheetBoxes.SUMMARY_CHARS): String {
        val full = strip.line
        if (full.length <= maxChars) return full
        val head = listOfNotNull(strip.id, strip.name).joinToString(" · ")
        val room = maxChars - head.length - 3 - 1
        return if (room <= 3) head else "$head · ${strip.facts.take(room)}…"
    }
}
