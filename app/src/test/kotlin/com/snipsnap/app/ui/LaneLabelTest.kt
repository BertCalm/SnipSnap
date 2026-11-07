package com.snipsnap.app.ui

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import kotlin.test.Test
import kotlin.test.assertEquals

class LaneLabelTest {
    private fun kit(vararg pads: KitPad) = Kit(name = "T", pads = pads.toList())

    @Test
    fun a_lane_is_named_after_the_pad_it_plays() {
        val k = kit(KitPad(slot = 1, sampleFile = "pluck_c3.wav", displayName = "Pluck C3"))
        assertEquals("PLUCK C3", laneLabel(GrooveEdit.Lane.KICK, k))
    }

    @Test
    fun an_empty_slot_keeps_the_drum_word() {
        val k = kit(KitPad(slot = 1, sampleFile = "a.wav"))
        assertEquals("SNARE", laneLabel(GrooveEdit.Lane.SNARE, k))
        for (lane in GrooveEdit.Lane.entries) assertEquals(LANE_DRUM_WORD.getValue(lane), laneLabel(lane, kit()))
    }

    @Test
    fun every_lane_reads_its_own_slot() {
        val pads = GrooveEdit.Lane.entries.map {
            KitPad(slot = GrooveEdit.LANE_SLOT.getValue(it), sampleFile = "${it.name}.wav", displayName = "p${it.ordinal}")
        }
        val k = Kit(name = "T", pads = pads)
        for (lane in GrooveEdit.Lane.entries) assertEquals("P${lane.ordinal}", laneLabel(lane, k))
    }

    @Test
    fun colour_follows_the_pads_class_unless_it_is_undecided() {
        val k = kit(
            KitPad(slot = 1, sampleFile = "a.wav", drumClass = DrumClass.TOM),
            KitPad(slot = 2, sampleFile = "b.wav", drumClass = DrumClass.UNKNOWN),
        )
        assertEquals(DrumClass.TOM, laneClass(GrooveEdit.Lane.KICK, k, DrumClass.KICK))
        assertEquals(DrumClass.SNARE, laneClass(GrooveEdit.Lane.SNARE, k, DrumClass.SNARE))
        assertEquals(DrumClass.CLAP, laneClass(GrooveEdit.Lane.CLAP, k, DrumClass.CLAP))
    }
}
