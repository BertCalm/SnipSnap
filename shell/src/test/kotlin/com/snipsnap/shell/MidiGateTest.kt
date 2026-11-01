package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class MidiGateTest {

    @Test
    fun `one step at 120 bpm is an eighth of a second`() = assertEquals(125_000_000L, MidiGate.stepNanos(120f))

    @Test
    fun `one step at 60 bpm is a quarter second`() = assertEquals(250_000_000L, MidiGate.stepNanos(60f))
}
