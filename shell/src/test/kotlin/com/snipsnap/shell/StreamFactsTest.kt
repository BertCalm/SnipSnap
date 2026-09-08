package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamFactsTest {

    @Test
    fun `a reported latency reads as whole milliseconds`() {
        assertEquals("9 MS", StreamFacts.latency(8.6))
        assertEquals("8 MS", StreamFacts.latency(8.4))
        assertEquals("23 MS", StreamFacts.latency(23.0))
        assertEquals("1 MS", StreamFacts.latency(0.6))
    }

    @Test
    fun `anything the device will not say is an em dash, not a zero`() {
        assertEquals("— MS", StreamFacts.latency(null))
        assertEquals("— MS", StreamFacts.latency(0.0))
        assertEquals("— MS", StreamFacts.latency(-1.0))
        assertEquals("— MS", StreamFacts.latency(Double.NaN))
        assertEquals("— MS", StreamFacts.latency(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `the shared path is named whether or not the number is`() {
        assertEquals("23 MS SHARED", StreamFacts.latency(23.0, shared = true))
        assertEquals("— MS SHARED", StreamFacts.latency(null, shared = true))
        assertEquals("9 MS", StreamFacts.latency(9.0, shared = false))
    }
}
