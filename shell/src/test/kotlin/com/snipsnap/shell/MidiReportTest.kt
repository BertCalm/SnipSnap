package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MidiReportTest {
    private val minilab = MidiDeviceId(
        "MiniLab 3",
        MidiFingerprint.of(MidiFingerprint.Transport.USB, 0x1C75, 0x020B, 4, 4, "Arturia", "MiniLab 3", "MiniLab 3"),
    )
    private val endpoint = MidiEndpoint(minilab, listOf("MiniLab 3 MIDI", "MiniLab DIN THRU"), listOf("MiniLab 3 MIDI"))
    private val phone = MidiReport.Phone("0.1-BRASS-GROW", "15", 35, "Google", "Pixel 8")

    @Test fun `a phone with no MIDI says so first`() =
        assertEquals(MidiReport.NO_MIDI, MidiReport.status(supported = false, emptyList(), emptyList()).first())

    @Test fun `nothing plugged in names the cable, the power and Bluetooth`() {
        val lines = MidiReport.status(supported = true, emptyList(), emptyList())
        assertEquals(MidiReport.NOTHING, lines.first())
        assertTrue(lines.any { "POWERED USB HUB" in it })
        assertTrue(lines.any { "BLUETOOTH" in it })
    }

    @Test fun `a device that would not open is named, connected or not`() {
        assertTrue(MidiReport.refusedLine("MiniLab 3") in MidiReport.status(true, emptyList(), listOf("MiniLab 3")))
        assertTrue(MidiReport.refusedLine("Other") in MidiReport.status(true, listOf(endpoint), listOf("Other")))
    }

    @Test fun `connected counts devices`() {
        assertEquals("1 DEVICE CONNECTED", MidiReport.status(true, listOf(endpoint), emptyList()).first())
        assertEquals("2 DEVICES CONNECTED", MidiReport.connected(2))
    }

    @Test fun `the transport prints USB ids in hex, and alone when there are none`() {
        assertEquals("USB 1C75:020B", MidiReport.transport(minilab.fingerprint))
        assertEquals("VIRTUAL", MidiReport.transport(MidiFingerprint.of(MidiFingerprint.Transport.VIRTUAL, 0, 0, 1, 1, null, "X", null)))
    }

    @Test fun `the report carries the phone, each device's ports and key, and the last lines oldest first`() {
        val m = MidiMonitor()
        m.deliver("MiniLab 3 · MiniLab 3 MIDI", byteArrayOf(0x99.toByte(), 36, 100), timestampNanos = 1_000_000)
        m.deliver("MiniLab 3 · MiniLab 3 MIDI", byteArrayOf(0x89.toByte(), 36, 0), timestampNanos = 101_000_000)
        val text = MidiReport.text(phone, true, listOf(endpoint), emptyList(), m.snapshot())
        assertTrue("ANDROID 15 (API 35) · Google Pixel 8" in text, text)
        assertTrue("MIDI SUPPORT: YES" in text)
        assertTrue("- MiniLab 3 · USB 1C75:020B · KEY ${minilab.fingerprint.key}" in text, text)
        assertTrue("SENDS: MiniLab 3 MIDI | MiniLab DIN THRU" in text)
        assertTrue("RECEIVES: MiniLab 3 MIDI" in text)
        val on = text.indexOf("NOTE ON 36 V100 CH10")
        val off = text.indexOf("NOTE OFF 36 CH10")
        assertTrue(on in 0 until off, "oldest first: the note-on before its note-off\n$text")
        assertTrue("+100.0 ms  MiniLab 3 · MiniLab 3 MIDI  89 24 00  NOTE OFF 36 CH10" in text, text)
    }

    @Test fun `the report carries at most REPORT_LINES lines`() {
        val m = MidiMonitor()
        repeat(MidiReport.REPORT_LINES + 10) { m.deliver("A", byteArrayOf(0xB0.toByte(), 1, (it % 128).toByte()), timestampNanos = 0) }
        val text = MidiReport.text(phone, true, emptyList(), emptyList(), m.snapshot())
        assertEquals(MidiReport.REPORT_LINES, text.lines().count { it.startsWith("first  A") })
        assertTrue("LAST ${MidiReport.REPORT_LINES} MESSAGES" in text)
    }

    @Test fun `a port list that is empty says NONE`() {
        val text = MidiReport.text(phone, true, listOf(MidiEndpoint(minilab, emptyList(), emptyList())), emptyList(), MidiMonitor().snapshot())
        assertTrue("SENDS: NONE" in text && "RECEIVES: NONE" in text, text)
    }

    private val clockResult = ClockProbe.Result(
        verdict = ClockProbe.Verdict.SLOW_START, samples = 372, validSamples = 340,
        startupGapMs = 812.4, longestStallMs = 0.0, stallAtMs = null, rateErrorPpm = 9.0, lagMs = 23.0,
    )
    private fun clock(shared: Boolean = false, api: Int = 2) = MidiReport.ClockReport(
        line = ClockProbe.describe(clockResult), result = clockResult, sampleRate = 48_000, shared = shared,
        framesPerBurst = 192, audioApi = api, latencyMillis = 31.5, model = "Pixel 8", sdk = 35,
        route = MidiReport.Route.BLUETOOTH,
    )
    private fun withClock(c: MidiReport.ClockReport?) =
        MidiReport.text(phone, true, listOf(endpoint), emptyList(), MidiMonitor().snapshot(), clock = c)

    @Test fun `without a clock check there is no CLOCK section`() {
        val text = withClock(null)
        assertTrue("CLOCK CHECK" !in text && "Answers S1" !in text, text)
        assertEquals(text, MidiReport.text(phone, true, listOf(endpoint), emptyList(), MidiMonitor().snapshot()))
    }

    @Test fun `a clock check adds a CLOCK section with the line, counts, stream facts, route and the S1 sentence`() {
        val text = withClock(clock())
        assertTrue("CLOCK CHECK" in text, text)
        assertTrue(ClockProbe.describe(clockResult) in text, text)
        assertTrue("SAMPLES 372 · WITH POSITION 340" in text, text)
        assertTrue("START-UP GAP 812 MS" in text, text)
        assertTrue("LONGEST STALL 0 MS" in text, text)
        assertTrue("SAMPLE RATE 48000" in text, text)
        assertTrue("EXCLUSIVE" in text && "SHARED" !in text, text)
        assertTrue("BURST 192 FRAMES" in text, text)
        assertTrue("API AAUDIO" in text, text)
        assertTrue("ROUTE BLUETOOTH" in text, text)
        assertTrue("LATENCY 31.5 MS" in text, text)
        assertTrue("Pixel 8" in text && "SDK 35" in text, text)
        assertTrue("Answers S1 for the pad engine's Oboe stream, the one clock out uses. The AudioTrack path is not probed." in text, text)
    }

    @Test fun `the clock section says SHARED, names every API, and survives missing figures`() {
        assertTrue("SHARED" in withClock(clock(shared = true)))
        assertEquals("OPENSL ES", MidiReport.apiWord(1))
        assertEquals("UNSPECIFIED", MidiReport.apiWord(0))
        assertEquals("NO STREAM", MidiReport.apiWord(-1))
        val none = clockResult.copy(startupGapMs = null, stallAtMs = 2000.0, longestStallMs = 500.0)
        val text = withClock(clock().copy(result = none, latencyMillis = null))
        assertTrue("START-UP GAP NONE" in text && "STALL 500 MS AT 2000 MS" in text && "LATENCY UNKNOWN" in text, text)
    }
}
