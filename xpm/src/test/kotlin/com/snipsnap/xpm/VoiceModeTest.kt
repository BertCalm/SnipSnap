package com.snipsnap.xpm

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceModeTest {

    private fun layer(name: String = "S") = VelocityLayer(name, 1_000L, 1, 127)

    private fun kg(low: Int, high: Int, root: Int = high) = Keygroup(low, high, root, listOf(layer("S$low")))

    @Test
    fun `mapping table`() {
        VoiceMode.POLY.let {
            assertEquals(0, it.polyphony)
            assertEquals(0f, it.portamentoSeconds)
            assertEquals(false, it.mono)
            assertEquals(false, it.legato)
            assertEquals(false, it.retrigger)
        }
        VoiceMode.LEAD.let {
            assertEquals(1, it.polyphony)
            assertEquals(0f, it.portamentoSeconds)
            assertEquals(true, it.mono)
            assertEquals(false, it.legato)
            assertEquals(true, it.retrigger)
        }
        VoiceMode.GLIDE.let {
            assertEquals(1, it.polyphony)
            assertEquals(VoiceMode.GLIDE_PORTAMENTO, it.portamentoSeconds)
            assertEquals(true, it.mono)
            assertEquals(true, it.legato)
            assertEquals(false, it.retrigger)
        }
    }

    @Test
    fun `parse falls back to POLY`() {
        assertEquals(VoiceMode.LEAD, VoiceMode.parse("LEAD"))
        assertEquals(VoiceMode.GLIDE, VoiceMode.parse("GLIDE"))
        assertEquals(VoiceMode.POLY, VoiceMode.parse("lead"))
        assertEquals(VoiceMode.POLY, VoiceMode.parse(null))
        assertEquals(VoiceMode.POLY, VoiceMode.parse(""))
    }

    @Test
    fun `fullKeyboard stretches the edges only`() {
        val program = KeygroupProgram(
            name = "Edges",
            keygroups = listOf(kg(40, 42), kg(43, 45), kg(46, 48)),
            volumeRelease = 0.9f,
            voiceMode = VoiceMode.GLIDE,
        )
        val full = program.fullKeyboard()
        assertEquals(listOf(0 to 42, 43 to 45, 46 to 127), full.keygroups.map { it.lowNote to it.highNote })
        assertEquals(program.keygroups.map { it.rootNote }, full.keygroups.map { it.rootNote })
        assertEquals(program.keygroups.map { it.layers }, full.keygroups.map { it.layers })
        assertEquals(program.keygroups[1], full.keygroups[1])
        assertEquals("Edges", full.name)
        assertEquals(VoiceMode.GLIDE, full.voiceMode)
        assertEquals(0.9f, full.volumeRelease)
    }

    @Test
    fun `fullKeyboard stretches every tied edge and is idempotent`() {
        val program = KeygroupProgram(name = "Ties", keygroups = listOf(kg(40, 50), kg(40, 60)))
        val full = program.fullKeyboard()
        assertEquals(listOf(0 to 50, 0 to 127), full.keygroups.map { it.lowNote to it.highNote })
        assertEquals(full, full.fullKeyboard())

        val whole = KeygroupProgram(name = "Whole", keygroups = listOf(kg(0, 127)))
        assertEquals(whole, whole.fullKeyboard())
    }
}
