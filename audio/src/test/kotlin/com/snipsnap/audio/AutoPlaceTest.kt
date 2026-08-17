package com.snipsnap.audio

import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.PadNoteMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoPlaceTest {

    private fun arrange(vararg classes: DrumClass, padCount: Int = 16): List<DrumClass?> =
        AutoPlace.arrange(classes.toList(), padCount) { it }

    @Test
    fun `the core four land where hands expect them`() {
        val pads = arrange(
            DrumClass.HAT_OPEN,
            DrumClass.SNARE,
            DrumClass.KICK,
            DrumClass.HAT_CLOSED,
        )

        assertEquals(DrumClass.KICK, pads[0], "kick belongs on A01")
        assertEquals(DrumClass.SNARE, pads[1], "snare belongs on A02")
        assertEquals(DrumClass.HAT_CLOSED, pads[2], "closed hat belongs on A03")
        assertEquals(DrumClass.HAT_OPEN, pads[3], "open hat belongs on A04")
    }

    @Test
    fun `capture order does not decide placement`() {
        // The whole point: hits arrive in whatever order they were sampled, and
        // still come out playable.
        val forwards = arrange(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED)
        val backwards = arrange(DrumClass.HAT_CLOSED, DrumClass.SNARE, DrumClass.KICK)

        assertEquals(forwards.take(3), backwards.take(3))
    }

    @Test
    fun `a second kick takes the fallback pad rather than being exiled`() {
        val pads = arrange(DrumClass.KICK, DrumClass.KICK)

        assertEquals(DrumClass.KICK, pads[0])
        assertEquals(DrumClass.KICK, pads[4], "second kick should land on A05")
    }

    @Test
    fun `a third of a kind falls through to the first free pad`() {
        val pads = arrange(DrumClass.KICK, DrumClass.KICK, DrumClass.KICK)
        assertEquals(3, pads.count { it == DrumClass.KICK })
    }

    @Test
    fun `the core four displace a shaker, not the other way round`() {
        // PERC prefers pad 12, so this is really about priority: whatever gets
        // bumped, it must not be the kick.
        val pads = arrange(
            DrumClass.PERC, DrumClass.PERC, DrumClass.PERC, DrumClass.PERC,
            DrumClass.KICK, DrumClass.SNARE,
        )

        assertEquals(DrumClass.KICK, pads[0])
        assertEquals(DrumClass.SNARE, pads[1])
    }

    @Test
    fun `a clap prefers the snare's neighbour`() {
        val pads = arrange(DrumClass.SNARE, DrumClass.CLAP)
        assertEquals(DrumClass.SNARE, pads[1])
        assertEquals(DrumClass.CLAP, pads[5], "clap belongs on A06 beside the snare")
    }

    @Test
    fun `a clap takes the snare pad when there is no snare`() {
        val pads = arrange(DrumClass.CLAP)
        assertEquals(DrumClass.CLAP, pads[5])
    }

    @Test
    fun `a loop goes to the far corner`() {
        val pads = arrange(DrumClass.LOOP)
        assertEquals(DrumClass.LOOP, pads[15], "loops belong out of the way on A16")
    }

    @Test
    fun `returns exactly one entry per pad`() {
        assertEquals(16, arrange(DrumClass.KICK).size)
        assertEquals(8, arrange(DrumClass.KICK, padCount = 8).size)
    }

    @Test
    fun `empty pads are null`() {
        val pads = arrange(DrumClass.KICK)
        assertEquals(15, pads.count { it == null })
        assertNull(pads[1])
    }

    @Test
    fun `nothing in, nothing placed`() {
        assertTrue(AutoPlace.arrange(emptyList<DrumClass>(), 16) { it }.all { it == null })
    }

    @Test
    fun `drops what will not fit`() {
        val items = List(40) { DrumClass.PERC }
        val pads = AutoPlace.arrange(items, padCount = 16) { it }

        assertEquals(16, pads.size)
        assertEquals(16, pads.count { it != null })
    }

    @Test
    fun `respects a smaller grid`() {
        // Preferences pointing past the end of the grid must not be honoured.
        val pads = AutoPlace.arrange(listOf(DrumClass.LOOP, DrumClass.KICK), padCount = 4) { it }

        assertEquals(4, pads.size)
        assertEquals(DrumClass.KICK, pads[0])
        assertTrue(DrumClass.LOOP in pads)
    }

    @Test
    fun `rejects an impossible grid`() {
        assertFailsWith<IllegalArgumentException> { arrange(DrumClass.KICK, padCount = 0) }
    }

    @Test
    fun `hats share a mute group so one chokes the other`() {
        assertEquals(AutoPlace.HAT_MUTE_GROUP, AutoPlace.muteGroupFor(DrumClass.HAT_CLOSED))
        assertEquals(AutoPlace.HAT_MUTE_GROUP, AutoPlace.muteGroupFor(DrumClass.HAT_OPEN))
        assertEquals(0, AutoPlace.muteGroupFor(DrumClass.KICK))
        assertEquals(0, AutoPlace.muteGroupFor(DrumClass.SNARE))
    }

    @Test
    fun `names are filename safe`() {
        for (drumClass in DrumClass.entries) {
            val name = AutoPlace.nameFor(drumClass)
            assertTrue(name.isNotBlank())
            assertTrue(name.all { it.isLetterOrDigit() }, "'$name' must be filename safe")
        }
    }

    @Test
    fun `a synthetic break becomes a playable kit`() {
        // The whole auto-place path: real-ish hits, classified, placed, named,
        // mute-grouped, and turned into a program.
        val hits = listOf(
            DrumSynth.openHat(),
            DrumSynth.kick(),
            DrumSynth.closedHat(),
            DrumSynth.snare(),
        )

        val classified = hits.map { it to Classifier.classify(it).drumClass }
        val placed = AutoPlace.arrange(classified, padCount = 16) { it.second }

        assertEquals(DrumClass.KICK, placed[0]?.second)
        assertEquals(DrumClass.SNARE, placed[1]?.second)
        assertEquals(DrumClass.HAT_CLOSED, placed[2]?.second)
        assertEquals(DrumClass.HAT_OPEN, placed[3]?.second)

        val pads = placed.map { entry ->
            entry?.let { (snip, drumClass) ->
                Pad(
                    sampleName = "SS_${AutoPlace.nameFor(drumClass)}_01",
                    frameCount = snip.frameCount.toLong(),
                    muteGroup = AutoPlace.muteGroupFor(drumClass),
                )
            }
        }

        val program = DrumProgram("Auto Kit", pads)
        assertEquals("SS_Kick_01", program.pads[0]?.sampleName)
        assertEquals("A01", PadNoteMap.labelForPad(1))

        // Both hats in one mute group is what makes closed choke open.
        assertEquals(AutoPlace.HAT_MUTE_GROUP, program.pads[2]?.muteGroup)
        assertEquals(AutoPlace.HAT_MUTE_GROUP, program.pads[3]?.muteGroup)
        assertEquals(0, program.pads[0]?.muteGroup)
    }
}
