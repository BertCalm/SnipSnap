package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonException
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.AcvsException
import com.snipsnap.mpc3.MpcDiff
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The hardening contract, exercised: feed every reader thousands of
 * mutations of a *valid* file and assert each one ends in a **typed
 * refusal or a valid parse** — never an `ArrayIndexOutOfBounds`, an NPE, a
 * `StackOverflow`, an `OutOfMemory`, or a hang. Bugs in a byte-walker show
 * up here as the wrong kind of exception, and the resource ceilings (BB2)
 * show up as the batch finishing quickly.
 *
 * Deterministic: a fixed seed per reader, so a failure reproduces exactly.
 */
class FuzzTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("fuzz").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** Exceptions a reader is *allowed* to throw for bad input. */
    private fun isTypedRefusal(t: Throwable): Boolean = when (t) {
        is IllegalArgumentException, // includes BadPathException, TooLargeException
        is java.io.IOException,
        is AcvsException,
        is JsonException,
        -> true
        else -> false
    }

    /**
     * Run [read] over [rounds] seeded mutations of [valid]; every call must
     * end in a valid parse or a typed refusal, and the whole batch must
     * finish well under a hang bound.
     */
    private fun fuzz(name: String, valid: ByteArray, seed: Int, rounds: Int, read: (ByteArray) -> Unit) {
        val rnd = Random(seed)
        val start = System.nanoTime()
        for (i in 0 until rounds) {
            val mutant = mutate(valid, rnd)
            try {
                read(mutant)
            } catch (t: Throwable) {
                if (!isTypedRefusal(t)) {
                    fail("$name round $i: untyped ${t::class.simpleName}: ${t.message}")
                }
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 30_000, "$name: $rounds rounds took ${ms}ms - a reader may be hanging")
    }

    private fun mutate(valid: ByteArray, rnd: Random): ByteArray {
        val bytes = valid.copyOf()
        when (rnd.nextInt(4)) {
            0 -> { // flip a handful of bytes
                repeat(1 + rnd.nextInt(8)) {
                    if (bytes.isNotEmpty()) bytes[rnd.nextInt(bytes.size)] = rnd.nextInt(256).toByte()
                }
            }
            1 -> return bytes.copyOf(if (bytes.isEmpty()) 0 else rnd.nextInt(bytes.size)) // truncate
            2 -> { // zero a run
                if (bytes.isNotEmpty()) {
                    val at = rnd.nextInt(bytes.size)
                    for (j in at until minOf(bytes.size, at + rnd.nextInt(32))) bytes[j] = 0
                }
            }
            3 -> { // set high bytes (0xFF), the AIOOBE-via-huge-size shape
                if (bytes.isNotEmpty()) {
                    val at = rnd.nextInt(bytes.size)
                    for (j in at until minOf(bytes.size, at + 4)) bytes[j] = 0xFF.toByte()
                }
            }
        }
        return bytes
    }

    private val ROUNDS = 2_000

    @Test
    fun `WavReader survives mutation`() {
        val valid = ByteArrayValid.wav()
        fuzz("WavReader", valid, seed = 1, rounds = ROUNDS) { WavReader.read(it) }
    }

    @Test
    fun `WavReader readSmpl survives mutation and never hands back a loop outside the audio`() {
        val valid = ByteArrayValid.wavWithSheet()
        fuzz("WavReader.readSmpl", valid, seed = 6, rounds = ROUNDS) { bytes ->
            val sheet = WavReader.readSmpl(bytes) ?: return@fuzz
            val loop = sheet.loop ?: return@fuzz
            // A sheet that comes back must be one the audio can honour.
            val frames = runCatching { WavReader.read(bytes).frameCount }.getOrNull() ?: return@fuzz
            assertTrue(loop.endFrameExclusive <= frames, "readSmpl returned a loop past the audio: $loop vs $frames frames")
        }
        // And the audio path is indifferent to the sheet's presence.
        fuzz("WavReader.read (with sheet)", valid, seed = 7, rounds = ROUNDS) { WavReader.read(it) }
    }

    @Test
    fun `Acvs survives mutation`() {
        val valid = ByteArrayValid.acvs()
        fuzz("Acvs", valid, seed = 2, rounds = ROUNDS) { Acvs.read(it) }
    }

    @Test
    fun `MpcDiff load survives mutation`() {
        val valid = ByteArrayValid.acvs()
        val f = File(temp, "diff.xtd")
        fuzz("MpcDiff", valid, seed = 3, rounds = ROUNDS) {
            f.writeBytes(it)
            MpcDiff.load(f)
        }
    }

    @Test
    fun `MidiGroove survives mutation`() {
        val valid = MidiGroove.write(
            com.snipsnap.mpc3.Mpc3Clip("Fuzz", 1, listOf(com.snipsnap.mpc3.Mpc3Note(36, 0, 0.9f))),
            92f,
        )
        fuzz("MidiGroove", valid, seed = 4, rounds = ROUNDS) { MidiGroove.read(it, "fuzz") }
    }

    @Test
    fun `XpnImporter survives mutation`() {
        val valid = ByteArrayValid.xpn(temp)
        val f = File(temp, "fuzz.xpn")
        fuzz("XpnImporter", valid, seed = 5, rounds = 500) {
            f.writeBytes(it)
            XpnImporter.import(f, File(temp, "fuzz-out"), overwrite = true)
        }
    }

    /** Valid fixtures the mutations start from. */
    private object ByteArrayValid {
        fun wav(): ByteArray {
            val tmp = File.createTempFile("valid", ".wav")
            try {
                WavWriter.write(tmp, Snip(FloatArray(2_000) { (it % 100) / 100f }, 1, 44_100))
                return tmp.readBytes()
            } finally {
                tmp.delete()
            }
        }

        fun wavWithSheet(): ByteArray = java.io.ByteArrayOutputStream().apply {
            WavWriter.write(
                this,
                Snip(FloatArray(2_000) { (it % 100) / 100f }, 1, 44_100),
                smpl = com.snipsnap.audio.SmplChunk(60, com.snipsnap.audio.SmplChunk.Loop(500, 2_000)),
            )
        }.toByteArray()

        fun acvs(): ByteArray = Acvs.write(
            com.snipsnap.mpc3.AcvsHeader("1.0.0.0", "SerialisableTrackData", "json", "Linux"),
            """{"data":{"version":1,"nested":{"a":[1,2,3],"b":"x"}}}""",
        )

        fun xpn(dir: File): ByteArray {
            val wav = File(dir, "seed.wav").also {
                WavWriter.write(it, Snip(FloatArray(500) { 0.2f }, 1, 44_100))
            }
            val program = """
                <?xml version="1.0"?><MPCVObject><Program type="Drum">
                <ProgramName>Fuzz</ProgramName><Instruments><Instrument number="1">
                <Layers><Layer number="1"><VelStart>0</VelStart><VelEnd>127</VelEnd>
                <SampleName>seed</SampleName></Layer></Layers></Instrument></Instruments>
                </Program></MPCVObject>
            """.trimIndent()
            val out = File(dir, "seed.xpn")
            ZipOutputStream(out.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("Fuzz.xpm")); zip.write(program.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("seed.wav")); zip.write(wav.readBytes()); zip.closeEntry()
            }
            return out.readBytes()
        }
    }
}
