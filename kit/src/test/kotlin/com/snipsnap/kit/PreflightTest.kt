package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreflightTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("preflight").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun wav(name: String, rate: Int = 44_100, frames: Int = 500): File =
        WavWriter.write(
            File(temp, name),
            Snip(FloatArray(frames) { 0.3f }, 1, rate),
            allowNonMpcRate = true,
        )

    private fun kitOf(vararg pads: KitPad) = Kit("Test Kit", pads.toList())

    private fun failures(findings: List<Finding>) =
        findings.filter { it.severity == Severity.FAIL }.map { it.message }

    @Test
    fun `a clean kit passes with the wizard's OK lines`() {
        wav("A01_Kick_01.wav"); wav("A03_Hat_01.wav"); wav("A04_HatOpen_01.wav")
        val kit = kitOf(
            KitPad(1, "A01_Kick_01.wav", drumClass = DrumClass.KICK),
            KitPad(3, "A03_Hat_01.wav", drumClass = DrumClass.HAT_CLOSED, muteGroup = 1),
            KitPad(4, "A04_HatOpen_01.wav", drumClass = DrumClass.HAT_OPEN, muteGroup = 1),
        )
        val findings = Preflight.check(kit, temp)

        assertTrue(!findings.blocked(), "clean kit should not be blocked: $findings")
        val oks = findings.filter { it.severity == Severity.OK }.map { it.message }
        assertTrue(oks.any { "3 pads assigned" in it })
        assertTrue(oks.any { "44.1" in it })
        assertTrue(oks.any { "choke group 1" in it })
    }

    @Test
    fun `an empty kit is a single clear failure`() {
        val findings = Preflight.check(Kit("x", emptyList()), temp)
        assertTrue(findings.blocked())
        assertEquals(1, findings.size)
    }

    @Test
    fun `a missing sample file blocks with the slot named`() {
        val findings = Preflight.check(kitOf(KitPad(5, "gone.wav")), temp)
        assertTrue(findings.blocked())
        assertTrue(findings.any { it.severity == Severity.FAIL && it.slot == 5 })
    }

    @Test
    fun `a wrong sample rate blocks`() {
        wav("a.wav", rate = 48_000)
        val findings = Preflight.check(kitOf(KitPad(1, "a.wav")), temp)
        assertTrue(findings.blocked())
        assertTrue(failures(findings).any { "48000" in it && "44.1" in it })
    }

    @Test
    fun `an unreadable wav blocks instead of exporting garbage`() {
        File(temp, "junk.wav").writeText("this is not audio")
        val findings = Preflight.check(kitOf(KitPad(1, "junk.wav")), temp)
        assertTrue(findings.blocked())
    }

    @Test
    fun `colliding export names block`() {
        // Different files whose sanitized stems collide would overwrite each
        // other on the card.
        wav("Kick?.wav"); wav("Kick_.wav")
        val findings = Preflight.check(
            kitOf(KitPad(1, "Kick?.wav"), KitPad(2, "Kick_.wav")),
            temp,
        )
        assertTrue(findings.blocked())
        assertTrue(failures(findings).any { "collide" in it })
    }

    @Test
    fun `a name needing sanitizing warns but does not block`() {
        wav("Kick?.wav")
        val findings = Preflight.check(kitOf(KitPad(1, "Kick?.wav")), temp)
        assertTrue(!findings.blocked())
        assertTrue(findings.any { it.severity == Severity.WARN && "renamed" in it.message })
    }

    @Test
    fun `an unsafe kit name blocks`() {
        wav("a.wav")
        val findings = Preflight.check(Kit("Kit: Vol. 1?", listOf(KitPad(1, "a.wav"))), temp)
        assertTrue(findings.blocked())
    }

    @Test
    fun `unpaired hats warn about ringing`() {
        wav("c.wav"); wav("o.wav")
        val kit = kitOf(
            KitPad(3, "c.wav", drumClass = DrumClass.HAT_CLOSED, muteGroup = 1),
            KitPad(4, "o.wav", drumClass = DrumClass.HAT_OPEN, muteGroup = 0),
        )
        val findings = Preflight.check(kit, temp)
        assertTrue(!findings.blocked())
        assertTrue(findings.any { it.severity == Severity.WARN && "choke" in it.message })
    }
}
