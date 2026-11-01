package com.snipsnap.kit

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pad's explicit choke group wins over the class rule, and the class
 * rule still holds when nothing is said: KIT FROM ANYTHING's ride and
 * crash wear HAT_OPEN's tag for colour and balance but must never be
 * choked by the closed hat.
 */
class MuteGroupOverrideTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("mutegroup").toFile()

    private fun tone(): Snip = Snip(FloatArray(4410) { i -> (0.5 * sin(2.0 * PI * 220 * i / 44_100)).toFloat() }, 1, 44_100)

    @Test
    fun `an explicit choke group overrides the class default and null keeps it`() {
        val kit = KitAssembler.assembleArranged(
            "Choke",
            listOf(
                ArrangedPad(tone(), DrumClass.HAT_OPEN),                // the class rule: the hats' group
                ArrangedPad(tone(), DrumClass.HAT_OPEN, muteGroup = 0), // a ride: tagged HAT_OPEN, never choked
                ArrangedPad(tone(), DrumClass.KICK, muteGroup = 3),     // any explicit group is honoured
            ),
            temp,
        )
        assertEquals(AutoPlace.HAT_MUTE_GROUP, kit.pads[0].muteGroup)
        assertEquals(0, kit.pads[1].muteGroup)
        assertEquals(3, kit.pads[2].muteGroup)
        // and it survives the sidecar
        val back = KitStore.load(temp)
        assertEquals(listOf(AutoPlace.HAT_MUTE_GROUP, 0, 3), back.pads.map { it.muteGroup })
    }
}
