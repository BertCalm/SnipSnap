package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.loop.Bouncer
import com.snipsnap.loop.DroneFit
import com.snipsnap.loop.SampleSource
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `loop.json` a person edited by hand, or a disk corrupted, with drones in
 * it. Every file either loads or is refused with an exception the app's
 * `runCatching` turns into LOOP's "won't read" line; nothing loads into a
 * grid that then crashes. What loads must refit and play a full bounce of
 * finite audio: a drone the renderer can't read is silence, not a stall.
 */
class HostileDroneSessionTest {

    private object Nothing : SampleSource {
        override fun loop(sampleFile: String): Snip? = null
        override fun pad(kit: String, slot: Int): Snip? = null
    }

    private val good = """{"engine":"RESIN","voice":"LEAD","macros":{"CUTOFF":0.5},"motion":0.5,"rate":1}"""

    /** A whole sidecar: one hostile track, five silent ones, a short cheap interval. */
    private fun sidecar(track: String, bpm: String = "220", bars: String = "1", rate: String = "22050"): String {
        val silent = """{"name":"-","engaged":false,"level":1,"pan":0,"chain":[{"type":"silence"}]}"""
        return """{"version":1,"bpm":$bpm,"barsPerInterval":$bars,"sampleRate":$rate,"tracks":[$track,${List(5) { silent }.joinToString(",")}]}"""
    }

    private fun drone(recipe: String = good, root: String = "57", slice: String = "0", of: String = "1") =
        """{"type":"drone","recipe":$recipe,"rootMidi":$root,"slice":$slice,"of":$of}"""

    private fun track(vararg blocks: String) =
        """{"name":"D","engaged":true,"level":1,"pan":0,"chain":[${blocks.joinToString(",")}]}"""

    /** Files that must be refused outright: the block itself is not a drone this build can hold. */
    private val refused = mapOf(
        "no recipe" to sidecar(track("""{"type":"drone","rootMidi":57,"slice":0,"of":1}""")),
        "no root" to sidecar(track("""{"type":"drone","recipe":$good,"slice":0,"of":1}""")),
        "fractional root" to sidecar(track(drone(root = "57.5"))),
        "root past MIDI" to sidecar(track(drone(root = "200"))),
        "negative root" to sidecar(track(drone(root = "-1"))),
        "span of three" to sidecar(track(drone(of = "3"))),
        "slice past its span" to sidecar(track(drone(slice = "2", of = "2"))),
        "negative slice" to sidecar(track(drone(slice = "-1", of = "2"))),
        "nine slices" to sidecar(track(*Array(9) { drone(slice = "0", of = "1") })),
        "root as text" to sidecar(track(drone(root = "\"A1\""))),
    )

    /** Files that must load, refit, and play: odd, but nothing a grid can't hold. */
    private val loaded = mapOf(
        "half a drone" to sidecar(track(drone(slice = "0", of = "4"), drone(slice = "1", of = "4"))),
        "two drones in one track" to sidecar(track(drone(root = "57"), drone(root = "60"))),
        "another engine" to sidecar(track(drone(recipe = """{"engine":"THEREMIN","voice":"AIR"}"""))),
        "recipe as text" to sidecar(track(drone(recipe = "\"RESIN\""))),
        "recipe as null" to sidecar(track(drone(recipe = "null"))),
        "motion past its range" to sidecar(track(drone(recipe = """{"engine":"RESIN","voice":"LEAD","macros":{},"motion":5,"rate":1}"""))),
        "breaths of three" to sidecar(track(drone(recipe = """{"engine":"RESIN","voice":"LEAD","macros":{},"motion":0.5,"rate":3}"""))),
        "unknown voice" to sidecar(track(drone(recipe = """{"engine":"RESIN","voice":"KAZOO","macros":{},"motion":0.5,"rate":1}"""))),
        "macros as text" to sidecar(track(drone(recipe = """{"engine":"RESIN","voice":"LEAD","macros":{"CUTOFF":"high"},"motion":0.5,"rate":1}"""))),
        "macros out of range" to sidecar(track(drone(recipe = """{"engine":"RESIN","voice":"LEAD","macros":{"CUTOFF":1e300,"CREAM":-5},"motion":0.5,"rate":1}"""))),
        "root at MIDI's floor" to sidecar(track(drone(root = "0"))),
        "root at MIDI's ceiling" to sidecar(track(drone(root = "127"))),
        "a drone beside a loop" to sidecar(track(drone(), """{"type":"loop","sampleFile":"gone.wav"}""")),
    )

    private fun write(text: String): File =
        Files.createTempDirectory("hostile-loop").toFile().also { File(it, SessionStore.FILE_NAME).writeText(text) }

    @Test
    fun `a sidecar whose drone blocks can't be drones is refused, not half-loaded`() {
        for ((what, text) in refused) {
            val dir = write(text)
            try {
                val outcome = runCatching { SessionStore.load(dir) }
                assertTrue(outcome.isFailure, "$what: loaded ${outcome.getOrNull()}")
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `an odd but whole sidecar loads, refits, and plays finite audio`() {
        for ((what, text) in loaded) {
            val dir = write(text)
            try {
                val session: Session = runCatching { SessionStore.load(dir) }.getOrElse { throw AssertionError("$what: refused: $it") }
                val fit = DroneFit.refit(session)
                assertEquals(fit, DroneFit.refit(fit), "$what: refit didn't settle")
                val out = Bouncer.render(fit, DroneSource(Nothing), intervals = 2)
                assertEquals(2 * fit.intervalFrames, out.frameCount, "$what: the bounce is the wrong length")
                assertTrue(out.samples.all { it.isFinite() }, "$what: the bounce has non-finite samples")
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
