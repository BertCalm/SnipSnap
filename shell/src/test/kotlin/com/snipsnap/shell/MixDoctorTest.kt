package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixDoctorTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("doctor").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100

    private fun tone(hz: Double, seconds: Float, amp: Float, dc: Float = 0f): Snip =
        Snip(
            FloatArray((seconds * rate).toInt()) { i ->
                (amp * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat() + dc
            },
            1, rate,
        )

    /** A sub-heavy but real loop: a low sine over a whisper of noise. */
    private fun boomyLoop(hz: Double, seconds: Float, seed: Int): Snip {
        val rnd = kotlin.random.Random(seed)
        return Snip(
            FloatArray((seconds * rate).toInt()) { i ->
                (0.5 * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat() + (rnd.nextFloat() * 2 - 1) * 0.01f
            },
            1, rate,
        )
    }

    /** Boomy kick loop + boomy bass loop, ungrouped hats, a screamer, a DC pad. */
    private fun sickKit(): KitBuilderModel {
        val m = KitBuilderModel.create("Sick", File(temp, "Sick"))
        m.assign(1, boomyLoop(60.0, 0.8f, seed = 1), DrumClass.LOOP)
        m.assign(2, boomyLoop(55.0, 0.9f, seed = 2), DrumClass.LOOP)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.assign(4, DrumSynth.openHat(), DrumClass.HAT_OPEN)
        m.update(3) { it.copy(muteGroup = 0) }
        m.update(4) { it.copy(muteGroup = 0) }
        m.assign(5, tone(5000.0, 0.3f, 0.9f), DrumClass.PERC)
        m.update(5) { it.copy(level = 1f) }
        // Quiet company so slot 5 is an outlier against a real median.
        m.update(1) { it.copy(level = 0.3f) }
        m.update(2) { it.copy(level = 0.3f) }
        m.assign(6, tone(200.0, 0.3f, 0.3f, dc = 0.08f), DrumClass.PERC)
        m.update(6) { it.copy(level = 0.3f) }
        m.save()
        return m
    }

    @Test
    fun `a deliberately sick kit draws exactly its diseases`() {
        val m = sickKit()
        val findings = MixDoctor.examine(m.kit, m.kitDir)
        val codes = findings.map { it.code }.toSet()

        assertTrue(MixDoctor.Code.LOW_MASKING in codes, "the two boomy loops fight for the sub: $findings")
        assertTrue(MixDoctor.Code.CLASHING_HATS in codes, "ungrouped hats named: $findings")
        assertTrue(MixDoctor.Code.LOUD_OUTLIER in codes, "the screamer named: $findings")
        assertTrue(MixDoctor.Code.DC_RUMBLE in codes, "the DC pad named: $findings")

        val masking = findings.first { it.code == MixDoctor.Code.LOW_MASKING }
        assertEquals(listOf(1, 2), masking.slots)
        assertTrue(findings.first { it.code == MixDoctor.Code.LOUD_OUTLIER }.slots == listOf(5))
        assertTrue(findings.first { it.code == MixDoctor.Code.DC_RUMBLE }.slots == listOf(6))
        assertEquals(findings, MixDoctor.examine(m.kit, m.kitDir), "the diagnosis is deterministic")
    }

    @Test
    fun `a pile of bright tonal pads is advice, not surgery - and hats never count`() {
        val m = KitBuilderModel.create("Bright", File(temp, "Bright"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        for (slot in 2..4) {
            m.assign(slot, tone(5000.0, 0.3f, 0.5f), DrumClass.LOOP)
        }
        m.save()
        val findings = MixDoctor.examine(m.kit, m.kitDir)
        val harsh = findings.firstOrNull { it.code == MixDoctor.Code.HARSH_BUILDUP }
        assertTrue(harsh != null && !harsh.fixable, "three bright loops are a buildup, flagged as advice: $findings")

        val bytes = m.kit.pads.associate { it.sampleFile to File(m.kitDir, it.sampleFile).readBytes() }
        MixDoctor.fix(m)
        for ((f, b) in bytes) {
            assertTrue(File(m.kitDir, f).readBytes().contentEquals(b), "advice never touches audio: $f")
        }

        // The same brightness worn by hats and snares is their job, not a finding.
        val drums = KitBuilderModel.create("Drums", File(temp, "Drums"))
        drums.assign(1, DrumSynth.kick(), DrumClass.KICK)
        drums.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        drums.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        drums.assign(4, DrumSynth.openHat(), DrumClass.HAT_OPEN)
        drums.save()
        assertTrue(
            MixDoctor.examine(drums.kit, drums.kitDir).none { it.code == MixDoctor.Code.HARSH_BUILDUP },
            "bright-by-trade classes never count against the kit",
        )
    }

    @Test
    fun `a healthy kit draws no findings`() {
        val m = KitBuilderModel.create("Healthy", File(temp, "Healthy"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.save()
        assertEquals(emptyList(), MixDoctor.examine(m.kit, m.kitDir))
    }

    @Test
    fun `--fix cures the fixable, keeps the originals in the bin, and leaves taste alone`() {
        val m = sickKit()
        val before = MixDoctor.examine(m.kit, m.kitDir)
        val carvedCandidate = m.pad(1)!!.sampleFile to File(m.kitDir, m.pad(1)!!.sampleFile).readBytes()

        val fixed = MixDoctor.fix(m)
        m.save()
        assertTrue(fixed.isNotEmpty())

        val after = MixDoctor.examine(m.kit, m.kitDir)
        val afterCodes = after.map { it.code }.toSet()
        assertTrue(MixDoctor.Code.CLASHING_HATS !in afterCodes, "hats grouped: $after")
        assertTrue(MixDoctor.Code.LOUD_OUTLIER !in afterCodes, "level trimmed: $after")
        assertTrue(MixDoctor.Code.DC_RUMBLE !in afterCodes, "DC gone: $after")
        assertTrue(MixDoctor.Code.LOW_MASKING !in afterCodes, "one of the pair carved: $after")
        assertTrue(
            after.all { it.code == MixDoctor.Code.HARSH_BUILDUP },
            "only taste advice may remain, got $after",
        )

        // The hats now share one nonzero group.
        assertEquals(m.pad(3)!!.muteGroup, m.pad(4)!!.muteGroup)
        assertTrue(m.pad(3)!!.muteGroup != 0)
        // The screamer came down, not off.
        assertTrue(m.pad(5)!!.level < 1f && m.pad(5)!!.level > 0.05f)
        // The DC pad is centred now.
        val cured = WavReader.read(File(m.kitDir, m.pad(6)!!.sampleFile))
        val mean = cured.samples.sumOf { it.toDouble() } / cured.samples.size
        assertTrue(abs(mean) < 0.005, "DC removed, mean now $mean")
        // A carve is a real, bin-backed audio edit with a recipe.
        val carvedPad = listOf(1, 2).first { m.pad(it)!!.recipe != null }
        assertTrue(
            !File(m.kitDir, m.pad(carvedPad)!!.sampleFile).readBytes()
                .contentEquals(if (carvedPad == 1) carvedCandidate.second else File(m.kitDir, carvedCandidate.first).readBytes()),
            "the carved pad's audio actually changed",
        )
        assertTrue(m.binContents().isNotEmpty(), "originals wait in the bin")
        assertTrue(before.size > after.size)
    }
}
