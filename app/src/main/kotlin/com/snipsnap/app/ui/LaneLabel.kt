package com.snipsnap.app.ui

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.Kit

/** The drum word each BEAT lane falls back to, and keeps for screen readers. */
internal val LANE_DRUM_WORD: Map<GrooveEdit.Lane, String> = mapOf(
    GrooveEdit.Lane.KICK to "KICK",
    GrooveEdit.Lane.SNARE to "SNARE",
    GrooveEdit.Lane.CLAP to "CLAP",
    GrooveEdit.Lane.HAT_CLOSED to "HAT C",
    GrooveEdit.Lane.HAT_OPEN to "HAT O",
    GrooveEdit.Lane.TOM to "TOM",
    GrooveEdit.Lane.PERC to "PERC",
)

/**
 * What a BEAT lane's row is called: the name of the pad it plays
 * ([GrooveEdit.DEFAULT_LANE_PADS] unless the pattern's lane was reassigned, then [pads]), so a synth or chop kit reads as what is in it and not as a drum word
 * that is not true. An empty slot, or a pad whose name is blank, falls back to the lane's drum
 * word ([LANE_DRUM_WORD]). Upper-cased because every label in the editor is.
 *
 * Screen readers keep the drum word ([LANE_DRUM_WORD]) in each step cell's name: the lane's job
 * does not change with the kit, and the name beside it is not unique (two lanes can play pads
 * called the same thing).
 */
internal fun laneLabel(lane: GrooveEdit.Lane, kit: Kit, pads: List<Int> = GrooveEdit.DEFAULT_LANE_PADS): String {
    val name = kit.pad(pads[lane.ordinal])?.displayName?.trim().orEmpty()
    return if (name.isEmpty()) LANE_DRUM_WORD.getValue(lane) else name.uppercase()
}

/**
 * The class a lane's row is coloured by: the pad's own when it has decided one, else the lane's
 * drum class (what the row has always used) so an unclassified pad does not go colourless.
 */
internal fun laneClass(lane: GrooveEdit.Lane, kit: Kit, fallback: DrumClass, pads: List<Int> = GrooveEdit.DEFAULT_LANE_PADS): DrumClass {
    val own = kit.pad(pads[lane.ordinal])?.drumClass
    return if (own == null || own == DrumClass.UNKNOWN) fallback else own
}
