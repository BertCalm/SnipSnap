package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.MpcFormat
import com.snipsnap.mpc3.MpcFormats
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.VoiceMode
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OneNoteTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("onenote").toFile()
    private val rate = 44_100

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** A clean decaying 220 Hz note — A3, MIDI 57 — with a few harmonics. */
    private fun note220(): Snip {
        val n = rate
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = Math.exp(-2.0 * t).toFloat()
            out[i] = (0.5 * sin(2 * PI * 220 * t) + 0.2 * sin(2 * PI * 440 * t) +
                0.08 * sin(2 * PI * 660 * t)).toFloat() * env
        }
        return Snip(out, 1, rate)
    }

    @Test
    fun `a pitched note becomes a full-range instrument rooted where it sounds`() {
        val result = OneNote.program("Test Bass", note220())
        assertEquals(57, result.rootMidi, "220 Hz is A3")
        assertEquals("A3", result.rootName)
        assertTrue(result.confidence >= OneNote.MIN_CONFIDENCE)

        val kg = result.program.keygroups.single()
        assertEquals(0, kg.lowNote)
        assertEquals(127, kg.highNote)
        assertEquals(57, kg.rootNote)
        assertEquals(result.sampleStem, kg.layers.single().sampleName)
        assertEquals(note220().frameCount.toLong(), kg.layers.single().frameCount)
    }

    @Test
    fun `export lands the dual-generation layout and both readers accept it`() {
        val card = File(temp, "card")
        val result = OneNote.export("Test Bass", note220(), card)

        val xty = File(card, "Test Bass.xty")
        val dataDir = File(card, "Test Bass_[TrackData]")
        assertTrue(xty.isFile)
        assertTrue(File(dataDir, "${result.sampleStem}.wav").isFile)
        assertTrue(File(dataDir, "Test Bass.xpm").isFile, "the MPC 2 twin sits beside the sample")

        assertEquals(MpcFormat.MPC3_ACVS, MpcFormats.detect(xty))
        assertEquals(MpcFormat.MPC2_XML, MpcFormats.detect(File(dataDir, "Test Bass.xpm")))
        val read = Mpc3Project.read(xty)
        assertTrue(read.isTrack)
        assertTrue("keygroup" in read.describe())

        // Overwrite discipline matches every other exporter.
        assertFailsWith<java.io.IOException> { OneNote.export("Test Bass", note220(), card) }
        OneNote.export("Test Bass", note220(), card, overwrite = true)
    }

    private fun tone(hz: Double): Snip {
        val n = rate
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            out[i] = ((0.5 * sin(2 * PI * hz * t) + 0.15 * sin(2 * PI * hz * 2 * t)) *
                Math.exp(-2.0 * t)).toFloat()
        }
        return Snip(out, 1, rate)
    }

    @Test
    fun `multisample - zones tile at the midpoints with real roots`() {
        // 110 / 220 / 440 Hz = A2(45) / A3(57) / A4(69), given scrambled.
        val result = OneNote.multiProgram(
            "Multi Bass",
            listOf("high.wav" to tone(440.0), "low.wav" to tone(110.0), "mid.wav" to tone(220.0)),
        )
        assertEquals(listOf(45, 57, 69), result.zones.map { it.rootMidi })
        val kgs = result.program.keygroups
        assertEquals(0, kgs[0].lowNote)
        assertEquals(51, kgs[0].highNote, "boundary at the midpoint of A2 and A3")
        assertEquals(52, kgs[1].lowNote)
        assertEquals(63, kgs[1].highNote)
        assertEquals(64, kgs[2].lowNote)
        assertEquals(127, kgs[2].highNote)
        kgs.forEach { assertTrue(it.rootNote in it.lowNote..it.highNote) }

        // Zones tile without gaps or overlaps.
        kgs.zipWithNext().forEach { (a, b) -> assertEquals(a.highNote + 1, b.lowNote) }
    }

    @Test
    fun `multisample export lands every zone's WAV and both programs`() {
        val card = File(temp, "multi-card")
        val result = OneNote.multiExport(
            "Multi Bass",
            listOf("a.wav" to tone(110.0), "b.wav" to tone(220.0)),
            card,
        )
        assertEquals(2, result.zones.size)
        val dataDir = File(card, "Multi Bass_[TrackData]")
        result.zones.forEach { assertTrue(File(dataDir, "${it.sampleStem}.wav").isFile, it.sampleStem) }
        assertTrue(File(card, "Multi Bass.xty").isFile)
        assertTrue(File(dataDir, "Multi Bass.xpm").isFile)
    }

    @Test
    fun `multisample refusals carry the offending labels`() {
        val rng = Random(9)
        val noise = Snip(FloatArray(rate) { (rng.nextFloat() * 2 - 1) * 0.5f }, 1, rate)
        val bad = assertFailsWith<IllegalArgumentException> {
            OneNote.multiProgram("X", listOf("good.wav" to tone(220.0), "hiss.wav" to noise))
        }
        assertTrue("hiss.wav" in bad.message!!)

        val dupe = assertFailsWith<IllegalArgumentException> {
            OneNote.multiProgram("X", listOf("one.wav" to tone(220.0), "two.wav" to tone(220.5)))
        }
        assertTrue("one.wav" in dupe.message!! && "two.wav" in dupe.message!!)
    }

    /** A genuinely sustained 220 Hz note (slow decay, light noise). */
    private fun heldNote(): Snip {
        val rng = Random(6)
        val n = rate * 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = (if (t < 0.01) t / 0.01 else Math.exp(-0.25 * (t - 0.01))).toFloat()
            out[i] = ((0.5 * sin(2 * PI * 220 * t) + 0.15 * sin(2 * PI * 440 * t)).toFloat() +
                (rng.nextFloat() * 2 - 1) * 0.002f) * env
        }
        return Snip(out, 1, rate)
    }

    @Test
    fun `sustain loops ride into the instrument when asked and found`() {
        val held = heldNote()
        val r = OneNote.program("Held", held, sustainLoop = true)
        assertTrue(r.loopStartFrame > 0, "a held note should loop")
        val layer = r.program.keygroups.single().layers.single()
        assertEquals(r.loopStartFrame, layer.loopStartFrame)
        assertEquals(r.sample.frameCount.toLong(), layer.frameCount)
        assertTrue(r.sample.frameCount < held.frameCount, "trimmed to the loop boundary")

        // A fast-decaying pluck honestly has no sustain: plays unlooped.
        val pluck = OneNote.program("Pluck", note220(), sustainLoop = true)
        assertEquals(0, pluck.loopStartFrame)

        // Multi path: the loop lands per zone and the written WAV is trimmed.
        val card = File(temp, "loop-card")
        val multi = OneNote.multiExport(
            "Held Keys", listOf("held.wav" to held), card, sustainLoop = true,
        )
        val zone = multi.zones.single()
        assertTrue(zone.loopStartFrame > 0)
        val written = com.snipsnap.audio.WavReader.read(
            File(card, "Held Keys_[TrackData]/${zone.sampleStem}.wav"),
        )
        assertEquals(multi.samples.getValue(zone.sampleStem).frameCount, written.frameCount)
    }

    @Test
    fun `unpitched material is refused, never guessed at`() {
        val rng = Random(3)
        val noise = Snip(FloatArray(rate) { (rng.nextFloat() * 2 - 1) * 0.5f }, 1, rate)
        val err = assertFailsWith<IllegalArgumentException> { OneNote.program("Noise", noise) }
        assertTrue("pitch" in err.message!!.lowercase())
        assertFailsWith<IllegalArgumentException> { OneNote.program("bad:name", note220()) }
    }

    /** Two zones, 40..42 and 43..45, the shape a keygroup export takes before the keyboard is spanned. */
    private fun twoZoneProgram(): Pair<KeygroupProgram, Map<String, Snip>> {
        val snip = note220()
        val frames = snip.frameCount.toLong()
        val program = KeygroupProgram(
            name = "Span",
            keygroups = listOf(
                Keygroup(40, 42, 41, listOf(VelocityLayer("Span_41", frames, 0, 127, 0L))),
                Keygroup(43, 45, 44, listOf(VelocityLayer("Span_44", frames, 0, 127, 0L))),
            ),
        )
        return program to mapOf("Span_41" to snip, "Span_44" to snip)
    }

    @Test
    fun `a package plays end to end`() {
        val (program, samples) = twoZoneProgram()
        OneNote.writePackage("Span", program, samples, temp, overwrite = false)
        val back = InstrumentStore.read(InstrumentStore.sidecar(temp, "Span"))
        assertEquals(listOf(0 to 42, 43 to 127), back.zones.map { it.lowNote to it.highNote })
        val xpm = File(temp, "Span_[TrackData]/Span.xpm").readText()
        assertTrue("<LowNote>0</LowNote>" in xpm)
        assertTrue("<HighNote>127</HighNote>" in xpm)
    }

    @Test
    fun `rewriting descriptors changes the mode and leaves the samples alone`() {
        val (program, samples) = twoZoneProgram()
        OneNote.writePackage("Span", program, samples, temp, overwrite = false)
        val dataDir = File(temp, "Span_[TrackData]")
        val wavs = dataDir.listFiles { f -> f.extension == "wav" }!!.sortedBy { it.name }
        assertEquals(2, wavs.size)
        val before = wavs.map { it.name to (it.lastModified() to it.length()) }
        OneNote.rewriteDescriptors(temp, "Span", program.copy(voiceMode = VoiceMode.LEAD))
        assertEquals(VoiceMode.LEAD, InstrumentStore.read(InstrumentStore.sidecar(temp, "Span")).voiceMode)
        assertTrue("<Mono>True</Mono>" in File(dataDir, "Span.xpm").readText())
        assertEquals(before, wavs.map { it.name to (it.lastModified() to it.length()) })
    }

    @Test
    fun `rewriting a package that is not there is refused`() {
        val (program, _) = twoZoneProgram()
        assertFailsWith<IllegalStateException> { OneNote.rewriteDescriptors(temp, "Ghost", program.copy(name = "Ghost")) }
        assertTrue(!File(temp, "Ghost.xty").exists())
        assertTrue(!InstrumentStore.sidecar(temp, "Ghost").exists())
    }

    @Test
    fun `a package can decline to span the keyboard`() {
        val snip = Snip(FloatArray(2_000) { 0.1f }, 1, rate)
        val program = KeygroupProgram(
            "Bed",
            listOf(Keygroup(48, 48, 48, listOf(VelocityLayer("Bed_48", 2_000L, 1, 127, 0L)))),
        )
        OneNote.writePackage("Bed", program, mapOf("Bed_48" to snip), temp, overwrite = false, spanKeyboard = false)
        val back = InstrumentStore.read(InstrumentStore.sidecar(temp, "Bed"))
        assertEquals(listOf(48 to 48), back.zones.map { it.lowNote to it.highNote })
        val xpm = File(temp, "Bed_[TrackData]/Bed.xpm").readText()
        assertTrue("<LowNote>48</LowNote>" in xpm)
        assertTrue("<HighNote>48</HighNote>" in xpm)
        assertTrue("<LowNote>0</LowNote>" !in xpm)
        assertTrue("<HighNote>127</HighNote>" !in xpm)

        OneNote.rewriteDescriptors(temp, "Bed", InstrumentStore.read(InstrumentStore.sidecar(temp, "Bed")).toProgram()
            .copy(voiceMode = VoiceMode.LEAD))
        val rewritten = InstrumentStore.read(InstrumentStore.sidecar(temp, "Bed"))
        assertEquals(listOf(48 to 48), rewritten.zones.map { it.lowNote to it.highNote })
        val rewrittenXpm = File(temp, "Bed_[TrackData]/Bed.xpm").readText()
        assertTrue("<LowNote>48</LowNote>" in rewrittenXpm)
        assertTrue("<HighNote>48</HighNote>" in rewrittenXpm)
    }

    /**
     * Nothing in the writers throws for a valid program, so this exercises the atomic path instead: the
     * `.xpm` path is made a non-empty directory, so its write must fail, and the files that would have
     * followed it must not have been touched.
     */
    @Test
    fun `a rewrite that cannot render leaves the package untouched`() {
        val (program, samples) = twoZoneProgram()
        OneNote.writePackage("Span", program, samples, temp, overwrite = false)
        val xty = File(temp, "Span.xty")
        val sidecar = InstrumentStore.sidecar(temp, "Span")
        val xpm = File(temp, "Span_[TrackData]/Span.xpm")
        val xtyBefore = xty.readBytes()
        val sidecarBefore = sidecar.readText()
        assertTrue(xpm.delete())
        assertTrue(xpm.mkdirs())
        File(xpm, "blocker").writeText("x")

        assertFailsWith<Exception> { OneNote.rewriteDescriptors(temp, "Span", program.copy(voiceMode = VoiceMode.LEAD)) }

        assertTrue(xtyBefore.contentEquals(xty.readBytes()), ".xty is byte-identical")
        assertEquals(sidecarBefore, sidecar.readText(), "sidecar is byte-identical")
        assertEquals(VoiceMode.POLY, InstrumentStore.read(sidecar).voiceMode)
    }

    @Test
    fun `the KEYS save path round-trips and then changes the mode in all three descriptors`() {
        val (program, samples) = twoZoneProgram()
        OneNote.writePackage("Span", program, samples, temp, overwrite = false)
        val xty = File(temp, "Span.xty")
        val xpm = File(temp, "Span_[TrackData]/Span.xpm")
        val xtyBefore = xty.readBytes()
        val xpmBefore = xpm.readText()

        val read = InstrumentStore.read(InstrumentStore.sidecar(temp, "Span"))
        OneNote.rewriteDescriptors(temp, "Span", read.toProgram())
        assertTrue(xtyBefore.contentEquals(xty.readBytes()), ".xty unchanged by a same-mode rewrite")
        assertEquals(xpmBefore, xpm.readText(), ".xpm unchanged by a same-mode rewrite")

        OneNote.rewriteDescriptors(temp, "Span", read.toProgram().copy(voiceMode = VoiceMode.LEAD))
        val xpmLead = xpm.readText()
        assertTrue("<Mono>True</Mono>" in xpmLead)
        assertTrue("<MonoRetrigger>True</MonoRetrigger>" in xpmLead)
        val payload = com.snipsnap.mpc3.Acvs.read(xty).payloadText.filterNot { it.isWhitespace() }
        assertTrue("\"monophonic\":true" in payload)
        assertTrue("\"monoRetrigger\":true" in payload)
    }

    @Test
    fun `a legacy trigger declaration survives a mode rewrite`() {
        val program = KeygroupProgram(
            "Triggered Bed",
            listOf(Keygroup(48, 48, 48, listOf(VelocityLayer("Triggered_Bed", 2_000, 0, 127)))),
            explicitNoteOn = true,
        )
        val samples = mapOf("Triggered_Bed" to Snip(FloatArray(2_000) { 0.1f }, 1, rate))
        OneNote.writePackage(program.name, program, samples, temp, overwrite = false, spanKeyboard = false)
        val sidecar = InstrumentStore.sidecar(temp, program.name)
        val legacySidecar = (Json.parse(sidecar.readText()) as JsonValue.Obj).entries.toMutableMap()
            .also { it.remove("explicitNoteOn") }
        sidecar.writeText(Json.write(JsonValue.Obj(legacySidecar)))

        val loaded = InstrumentStore.read(sidecar)
        assertTrue(loaded.explicitNoteOn, "the old sidecar recovers trigger mode from its XPM")
        OneNote.rewriteDescriptors(temp, program.name, loaded.toProgram().copy(voiceMode = VoiceMode.LEAD))

        val xpm = File(temp, "${program.name}_[TrackData]/${program.name}.xpm").readText()
        assertTrue("<TriggerMode>2</TriggerMode>" in xpm)
        assertEquals(true, (Json.parse(sidecar.readText()) as JsonValue.Obj).entries["explicitNoteOn"]?.let { (it as JsonValue.Bool).value })
        assertTrue("<LowNote>48</LowNote>" in xpm)
        assertTrue("<HighNote>48</HighNote>" in xpm)
    }

    @Test
    fun `a failure on the second descriptor restores the first`() {
        val (program, samples) = twoZoneProgram()
        OneNote.writePackage("Span", program, samples, temp, overwrite = false)
        val xpm = File(temp, "Span_[TrackData]/Span.xpm")
        val xty = File(temp, "Span.xty")
        val sidecar = InstrumentStore.sidecar(temp, "Span")
        val xpmBefore = xpm.readBytes()
        val sidecarBefore = sidecar.readBytes()
        val parked = File(temp, "parked.xty")
        assertTrue(xty.renameTo(parked))
        assertTrue(xty.mkdirs())
        File(xty, "blocker").writeText("x")

        try {
            assertFailsWith<Exception> {
                OneNote.rewriteDescriptors(temp, "Span", program.copy(voiceMode = VoiceMode.LEAD))
            }
            assertTrue(xpmBefore.contentEquals(xpm.readBytes()), "the first descriptor was rolled back")
            assertTrue(sidecarBefore.contentEquals(sidecar.readBytes()), "the last descriptor was untouched")
        } finally {
            xty.deleteRecursively()
            assertTrue(parked.renameTo(xty))
        }
    }

    @Test
    fun `a failure on the third descriptor restores the first two`() {
        val (program, samples) = twoZoneProgram()
        OneNote.writePackage("Span", program, samples, temp, overwrite = false)
        val xpm = File(temp, "Span_[TrackData]/Span.xpm")
        val xty = File(temp, "Span.xty")
        val sidecar = InstrumentStore.sidecar(temp, "Span")
        val xpmBefore = xpm.readBytes()
        val xtyBefore = xty.readBytes()
        val parked = File(temp, "parked-sidecar")
        assertTrue(sidecar.renameTo(parked))
        assertTrue(sidecar.mkdirs())
        File(sidecar, "blocker").writeText("x")

        try {
            assertFailsWith<Exception> {
                OneNote.rewriteDescriptors(temp, "Span", program.copy(voiceMode = VoiceMode.LEAD))
            }
            assertTrue(xpmBefore.contentEquals(xpm.readBytes()), "the first descriptor was rolled back")
            assertTrue(xtyBefore.contentEquals(xty.readBytes()), "the second descriptor was rolled back")
        } finally {
            sidecar.deleteRecursively()
            assertTrue(parked.renameTo(sidecar))
        }
    }
}
