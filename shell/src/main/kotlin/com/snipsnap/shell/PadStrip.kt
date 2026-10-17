package com.snipsnap.shell

import com.snipsnap.kit.KitPad

/**
 * The pad strip (`docs/SCREEN_PLAN_2026_10.md` §3.3): one line under the
 * grid for the selected pad, and the tap door into the pad sheet the
 * reviews asked for (W#22). Tap a pad to hear it and select it; the strip
 * reads what it carries and offers OPEN ▸. An empty pad's strip offers
 * what can fill it.
 *
 * Built on [PadSheetBoxes]' own closed-box strings so the strip and the
 * sheet never disagree about what a pad carries: the strip is those
 * summaries, minus the UNTOUCHED ones, plus the two facts the sheet's
 * everyday row shows (choke group, layers).
 */
object PadStrip {

    data class Strip(
        /** `A02`. */
        val id: String,
        /** The pad's name, or null for an empty pad. */
        val name: String?,
        /** `CRUSH · 35% · CHOKE 1`, or [RAW], or [EMPTY]. */
        val facts: String,
        /** The strip's doors, in order. */
        val doors: List<String>,
    ) {
        /** The whole strip as one line: `A02 · snare · CRUSH · 35%`. */
        val line: String get() = listOfNotNull(id, name, facts).joinToString(" · ")
    }

    const val RAW = "RAW"
    const val EMPTY = "EMPTY"

    const val DOOR_OPEN = "OPEN ▸"
    const val DOOR_CAPTURE = "CAPTURE ▸"
    const val DOOR_FROM_SNIPS = "FROM SNIPS ▸"

    fun line(pad: KitPad?, slot: Int, outsideStage: String? = null): Strip {
        val id = PadBanks.tag(slot)
        if (pad == null) return Strip(id, null, EMPTY, listOf(DOOR_CAPTURE, DOOR_FROM_SNIPS))
        // `touched`, not "any strip that is not UNTOUCHED": MAKE's strip
        // advertises what the box can do and is never UNTOUCHED, so only
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
        return Strip(id, pad.displayName, if (facts.isEmpty()) RAW else facts.joinToString(" · "), listOf(DOOR_OPEN))
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
