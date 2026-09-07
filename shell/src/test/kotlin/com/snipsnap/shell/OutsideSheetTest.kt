package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Outside
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutsideSheetTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("outside-sheet").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100

    /** A hit: 200 ms of decaying seeded noise. */
    private fun hit(seed: Long = 7): Snip {
        val rng = Random(seed)
        val frames = (0.2f * rate).toInt()
        return Snip(FloatArray(frames) { f -> (0.6 * rng.nextGaussian() * 0.3 * Math.exp(-f / (0.05 * rate))).toFloat() }, 1, rate)
    }

    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, hit(), DrumClass.KICK)
        m.assign(2, hit(11), DrumClass.SNARE)
        m.save()
        return m
    }

    /**
     * The room the phone is in, as the mic would hear it: [preRoll] of
     * hiss, then [send] arriving [trip] frames late at [gain], with an
     * echo [echoAt] frames after it at [echoGain], out to [listen] frames.
     */
    private fun heard(send: Snip, listen: Int, preRoll: Int, trip: Int, gain: Float, echoAt: Int = 0, echoGain: Float = 0f): Snip {
        val rng = Random(5)
        val out = FloatArray(listen) { (rng.nextGaussian() * 1e-3).toFloat() }
        val mono = send.samples
        for (i in mono.indices) {
            val at = preRoll + trip + i
            if (at in out.indices) out[at] += mono[i] * gain
            if (echoGain != 0f) {
                val e = at + echoAt
                if (e in out.indices) out[e] += mono[i] * gain * echoGain
            }
        }
        return Snip(out, 1, rate)
    }

    private fun correlation(a: FloatArray, b: FloatArray, n: Int): Double {
        var dot = 0.0
        var ea = 0.0
        var eb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i].toDouble()
            ea += a[i] * a[i].toDouble()
            eb += b[i] * b[i].toDouble()
        }
        return if (ea < 1e-12 || eb < 1e-12) 0.0 else dot / sqrt(ea * eb)
    }

    @Test
    fun `two moves, each with one knob - MIX opens wet, WET opens halfway`() {
        assertEquals(listOf("REAMP", "ROOM"), OutsideSheet.MOVES)
        val mix = OutsideSheet.knobFor(OutsideSheet.Move.REAMP)
        assertEquals("MIX", mix.label)
        assertEquals(1f, mix.default)
        val wet = OutsideSheet.knobFor(OutsideSheet.Move.ROOM)
        assertEquals("WET", wet.label)
        assertEquals(0.5f, wet.default)
        assertEquals("50%", OutsideSheet.label(wet, 0.5f))
        assertEquals(0.25f, OutsideSheet.value(mix, 0.25f), 1e-6f)
        assertEquals(0.25f, OutsideSheet.fraction(mix, 0.25f), 1e-6f)
        assertFailsWith<IllegalArgumentException> { OutsideSheet.moveFor("FEEDBACK") }
    }

    @Test
    fun `REAMP sends the pad itself, ROOM sends the sweep at the pad's rate, and the listen covers the whole trip`() {
        val m = model("Send")
        val own = WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile))
        val reamp = OutsideSheet.send(m, 1, OutsideSheet.Move.REAMP)
        assertEquals(own.frameCount, reamp.frameCount)
        assertTrue(own.samples.contentEquals(reamp.samples))

        val room = OutsideSheet.send(m, 1, OutsideSheet.Move.ROOM)
        assertEquals((Outside.PROBE_SECONDS * rate).toInt(), room.frameCount)
        assertEquals(rate, room.sampleRate)

        val listen = OutsideSheet.listenFrames(reamp)
        val least = OutsideSheet.preRollFrames(rate) + reamp.frameCount +
            ((Outside.TAIL_MAX_SEC + OutsideSheet.LATENCY_ALLOWANCE_SEC) * rate).toInt()
        assertEquals(least, listen)
        assertEquals((0.25f * rate).toInt(), OutsideSheet.preRollFrames(rate))
        assertFailsWith<IllegalArgumentException> { OutsideSheet.send(m, 9, OutsideSheet.Move.REAMP) }
    }

    @Test
    fun `REAMP - the return becomes the pad, lined up, the trip in the recipe, undo byte-identical`() {
        val m = model("Reamp")
        val padFile = File(m.kitDir, m.pad(1)!!.sampleFile)
        val before = padFile.readBytes()
        val send = OutsideSheet.send(m, 1, OutsideSheet.Move.REAMP)
        val preRoll = OutsideSheet.preRollFrames(rate)
        val trip = 900
        // A slap off the far wall, landing after the hit itself has ended.
        val echoAt = send.frameCount + 2000
        val back = heard(send, OutsideSheet.listenFrames(send), preRoll, trip, gain = 0.3f, echoAt = echoAt, echoGain = 0.4f)

        val outcome = OutsideSheet.apply(m, 1, OutsideSheet.Move.REAMP, back, fraction = 1f, preRollFrames = preRoll)
        m.save()
        assertEquals(OutsideSheet.Move.REAMP, outcome.move)
        assertEquals(trip * 1000f / rate, outcome.lagMs, 0.1f, "the trip, not the wait")
        assertTrue(outcome.confidence > 0.9f)
        assertTrue(!outcome.inverted)
        assertNull(outcome.impulse, "REAMP measures no room")
        assertFailsWith<IllegalArgumentException> { OutsideSheet.keep(temp, outcome, "Reamp") }

        val now = WavReader.read(padFile)
        // The head before the echo lands: the return, lined up on the hit.
        assertTrue(correlation(now.samples, send.samples, 2500) > 0.95, "the pad is the return, on the hit")
        assertTrue(now.frameCount > echoAt + 1000, "and it kept the echo: ${now.frameCount}")
        assertEquals(send.peak(), now.peak(), 1e-3f, "peak matched")

        val applied = OutsideSheet.read(m.pad(1)!!.recipe)!!
        assertEquals("REAMP", applied.move)
        assertEquals(outcome.lagMs, applied.lagMs, 0.01f)
        assertEquals("REAMP", m.pad(1)!!.source["outside"])
        assertTrue(OutsideSheet.statusLine(applied).startsWith("REAMP · 20 MS LATE · "), OutsideSheet.statusLine(applied))
        assertNull(MutateSheet.read(m.pad(1)!!.recipe), "a reamp is not a mutate")

        OutsideSheet.undo(m, 1)
        m.save()
        assertTrue(padFile.readBytes().contentEquals(before), "undo is the original, byte for byte")
        assertNull(m.pad(1)!!.recipe)
        assertNull(m.pad(1)!!.source["outside"])
    }

    @Test
    fun `ROOM - the sweep comes back, the room becomes the parent, ROOM OF ITSELF plays the pad inside it`() {
        val m = model("Room")
        val padFile = File(m.kitDir, m.pad(1)!!.sampleFile)
        val before = padFile.readBytes()
        val original = WavReader.read(padFile)
        val send = OutsideSheet.send(m, 1, OutsideSheet.Move.ROOM)
        val preRoll = OutsideSheet.preRollFrames(rate)
        val trip = 1200
        val echoAt = 4000
        val back = heard(send, OutsideSheet.listenFrames(send), preRoll, trip, gain = 0.4f, echoAt = echoAt, echoGain = 0.5f)

        val outcome = OutsideSheet.apply(m, 1, OutsideSheet.Move.ROOM, back, fraction = 1f, preRollFrames = preRoll)
        m.save()
        assertEquals(OutsideSheet.Move.ROOM, outcome.move)
        assertTrue(abs(outcome.lagMs - trip * 1000f / rate) < 0.1f, "the trip: ${outcome.lagMs}")
        assertTrue(outcome.confidence > 0.1f)

        val now = WavReader.read(padFile)
        assertTrue(now.frameCount >= original.frameCount + echoAt, "the pad plus the room's echo: ${now.frameCount}")
        // Fully wet through the room, the head before the echo lands is the
        // pad itself — 2 ms late, the impulse's own pre-roll (Outside cuts
        // the response from just before its peak so the arrival is whole).
        val pre = (0.002f * rate).toInt()
        val left = FloatArray(3000) { now.samples[(it + pre) * 2] }
        val head = correlation(left, original.samples, 3000)
        assertTrue(head > 0.9, "the direct path is the pad: $head")

        val recipe = m.pad(1)!!.recipe!!
        val mutate = recipe.entries["mutate"] as JsonValue.Obj
        assertEquals("room", (mutate.entries["mode"] as JsonValue.Str).value)
        assertEquals(listOf(OutsideSheet.ROOM_LABEL), (mutate.entries["with"] as JsonValue.Arr).items.map { (it as JsonValue.Str).value })
        val applied = OutsideSheet.read(recipe)!!
        assertEquals("ROOM", applied.move)
        val asMutate = MutateSheet.read(recipe)!!
        assertEquals("ROOM", asMutate.mode)
        assertEquals(listOf(OutsideSheet.ROOM_LABEL), asMutate.parents)
        assertEquals("ROOM", m.pad(1)!!.source["outside"])

        // The room as measured rides the outcome, so KEEP can put it on the shelf...
        val impulse = outcome.impulse!!
        assertTrue(impulse.frameCount > echoAt, "the impulse holds the echo: ${impulse.frameCount}")
        val shelf = m.kitDir.parentFile!!
        val kept = OutsideSheet.keep(shelf, outcome, m.kit.name, nowMillis = 77L)
        assertEquals("ROOM ROOM", kept.name, "the kit is called Room; its room is ROOM ROOM")
        assertEquals(outcome.lagMs, kept.lagMs, 1e-3f)
        assertEquals(outcome.confidence, kept.confidence, 1e-3f)
        assertEquals("Room:A01", kept.from)
        assertEquals(impulse.frameCount, WavReader.read(kept.file).frameCount)
        // ...where another pad takes it as a MUTATE parent, no trip needed.
        val otherFile = File(m.kitDir, m.pad(2)!!.sampleFile)
        val otherBefore = WavReader.read(otherFile)
        MutateSheet.apply(m, 2, Rooms.partner(kept), Mutate.Mode.ROOM, fraction = 1f)
        m.save()
        assertTrue(WavReader.read(otherFile).frameCount >= otherBefore.frameCount + echoAt, "pad 2 plays in the kept room")
        assertEquals(listOf("room:ROOM ROOM"), MutateSheet.read(m.pad(2)!!.recipe)!!.parents)

        OutsideSheet.undo(m, 1)
        m.save()
        assertTrue(padFile.readBytes().contentEquals(before))
        assertNull(m.pad(1)!!.recipe)
        assertNull(m.pad(1)!!.source["outside"])
        assertNull(m.pad(1)!!.source["mutatedWith"])
    }

    @Test
    fun `a room that says nothing is refused in words, and nothing is touched`() {
        val m = model("Silent")
        val padFile = File(m.kitDir, m.pad(1)!!.sampleFile)
        val before = padFile.readBytes()
        val send = OutsideSheet.send(m, 1, OutsideSheet.Move.REAMP)
        val silence = Snip(FloatArray(OutsideSheet.listenFrames(send)), 1, rate)

        val e = assertFailsWith<Outside.Refused> { OutsideSheet.apply(m, 1, OutsideSheet.Move.REAMP, silence, 1f) }
        assertTrue(e.message!!.contains("said nothing"), e.message)
        assertTrue(padFile.readBytes().contentEquals(before))
        assertNull(m.pad(1)!!.recipe)
        assertTrue(m.binContents().isEmpty(), "a refusal never reaches the bin")

        val e2 = assertFailsWith<Outside.Refused> { OutsideSheet.apply(m, 1, OutsideSheet.Move.ROOM, silence, 1f) }
        assertTrue(e2.message!!.contains("said nothing"), e2.message)
        assertTrue(m.binContents().isEmpty())
    }

    @Test
    fun `the recipe reads defensively and the copy shouts`() {
        assertNull(OutsideSheet.read(null))
        assertNull(OutsideSheet.read(JsonValue.Obj(linkedMapOf("fx" to JsonValue.Str("tape")))))
        val line = Copy.outside("REAMP", "KICK", 23.4f, 0.87f)
        assertEquals(line.uppercase(), line)
        assertTrue("23 MS LATER" in line && "87% SURE" in line, line)
        assertEquals("OUTSIDE REFUSED: THE ROOM SAID NOTHING BACK.", Copy.outsideRefused("the room said nothing back."))
        for (l in listOf(Copy.OUTSIDE_UNDONE, Copy.OUTSIDE_NEEDS_MIC, Copy.OUTSIDE_TAPE_ROLLING, Copy.OUTSIDE_NEEDS_ONE, Copy.OUTSIDE_SENDING, Copy.OUTSIDE_LISTENING)) {
            assertEquals(l.uppercase(), l, "TapeOS shouts: '$l'")
        }
    }
}
