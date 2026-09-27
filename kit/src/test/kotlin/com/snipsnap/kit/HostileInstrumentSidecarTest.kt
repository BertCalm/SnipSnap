package com.snipsnap.kit

import com.snipsnap.json.JsonException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An `.instrument.json` a person edited, or a disk mangled. A sidecar is
 * what the phone plays a held instrument from, so a value no writer here
 * produces is refused at the door, where [InstrumentStore.list] skips it,
 * rather than reaching a voice: a release of `1e39` reads as an infinite
 * Float, and an infinite fade is a looped key that never stops.
 */
class HostileInstrumentSidecarTest {

    private fun sidecar(release: String = "0.6", zone: String = zone()): String =
        """{"version":1,"name":"Pad","release":$release,"zones":[$zone]}"""

    private fun zone(low: String = "40", high: String = "50", root: String = "45", sample: String = "\"Pad_[TrackData]/Pad_A2.wav\"", frames: String = "44100", loop: String = "22050") =
        """{"low":$low,"high":$high,"root":$root,"sample":$sample,"frames":$frames,"loopStart":$loop}"""

    private fun withFile(text: String, block: (File) -> Unit) {
        val dir = Files.createTempDirectory("hostile-instrument").toFile()
        try {
            val f = File(dir, "Pad${InstrumentStore.SUFFIX}").apply { writeText(text) }
            block(f)
        } finally {
            dir.deleteRecursively()
        }
    }

    private val refused = mapOf(
        "a release past a Float" to sidecar(release = "1e39"),
        "a negative release" to sidecar(release = "-0.5"),
        "a zone above MIDI" to sidecar(zone = zone(high = "128")),
        "a zone below MIDI" to sidecar(zone = zone(low = "-1")),
        "an upside-down zone" to sidecar(zone = zone(low = "60", high = "50")),
        "a root outside MIDI" to sidecar(zone = zone(root = "200")),
        "a sample with no frames" to sidecar(zone = zone(frames = "0")),
        "a negative loop start" to sidecar(zone = zone(loop = "-1")),
        "a sample that climbs out" to sidecar(zone = zone(sample = "\"../../secret.wav\"")),
        "a sample that climbs out mid-path" to sidecar(zone = zone(sample = "\"Pad_[TrackData]/../../secret.wav\"")),
        "an absolute sample" to sidecar(zone = zone(sample = "\"/etc/passwd\"")),
        "a backslashed climb" to sidecar(zone = zone(sample = "\"..\\\\secret.wav\"")),
        "a blank sample" to sidecar(zone = zone(sample = "\"\"")),
    )

    @Test
    fun `a sidecar no writer here could have written is refused, and skipped on the shelf`() {
        for ((what, text) in refused) withFile(text) { f ->
            assertFailsWith<JsonException>(what) { InstrumentStore.read(f) }
            assertTrue(InstrumentStore.list(f.parentFile).isEmpty(), "$what: the shelf listed it")
        }
    }

    @Test
    fun `a merely long release is capped, not refused`() {
        withFile(sidecar(release = "45")) { f ->
            assertEquals(InstrumentStore.MAX_RELEASE_SECONDS, InstrumentStore.read(f).release)
        }
    }

    @Test
    fun `what the shop writes still reads`() {
        withFile(sidecar()) { f ->
            val i = InstrumentStore.read(f)
            assertEquals(0.6f, i.release)
            assertEquals("Pad_[TrackData]/Pad_A2.wav", i.zones.single().sample)
        }
    }

    @Test
    fun `an instrument built in code with a release no file could hold still plays one that ends`() {
        val zone = InstrumentStore.Zone(0, 127, 60, "x.wav", 10, 0)
        for (r in listOf(Float.POSITIVE_INFINITY, Float.NaN, -1f, 1e9f)) {
            val p = InstrumentStore.Instrument("X", r, listOf(zone)).playableRelease
            assertTrue(p.isFinite() && p in 0f..InstrumentStore.MAX_RELEASE_SECONDS, "release $r plays as $p")
        }
    }
}
