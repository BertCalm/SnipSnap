package com.snipsnap.shell

import com.snipsnap.audio.Pcm
import com.snipsnap.audio.SilenceWatch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.audio.DrumClass
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The hardening matrix, round two (wave BBB): every door the phone grew
 * since the SS2 matrix meets every degenerate shape a tape can arrive in.
 * The contract is the same binary, named one: a door either returns a
 * valid result - finite audio, a selection inside the tape, a sheet whose
 * loop is inside its sample - or refuses with an IllegalArgumentException
 * that says why. No other throwable, and silence never invents a beat.
 */
class DegenerateDoorsTest {

    private val rate = 44_100
    private val temp: File = java.nio.file.Files.createTempDirectory("doors").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun fixtures(): List<Pair<String, Snip>> {
        val square = FloatArray(rate / 2) { i -> if ((i / 100) % 2 == 0) 1f else -1f }
        return listOf(
            "one sample" to Snip(floatArrayOf(0.5f), 1, rate),
            "tiny" to Snip(FloatArray(44) { i -> (0.5 * Math.sin(2.0 * Math.PI * 440 * i / rate)).toFloat() }, 1, rate),
            "silence" to Snip(FloatArray(rate / 2), 1, rate),
            "pure DC" to Snip(FloatArray(rate / 2) { 0.5f }, 1, rate),
            "full-scale square" to Snip(square, 1, rate),
            "low rate" to Snip(FloatArray(2400) { i -> (0.5 * Math.sin(2.0 * Math.PI * 200 * i / 8000)).toFloat() }, 1, 8000),
            "stereo" to Snip(
                FloatArray(rate) { i -> (0.4 * Math.sin(2.0 * Math.PI * (if (i % 2 == 0) 300.0 else 500.0) * (i / 2) / rate)).toFloat() },
                2, rate,
            ),
            "white noise" to Snip(Random(7).let { r -> FloatArray(rate) { (r.nextFloat() * 2 - 1) * 0.8f } }, 1, rate),
        )
    }

    private val kit = Kit(
        "Doors",
        listOf(
            KitPad(slot = 1, sampleFile = "k.wav", drumClass = DrumClass.KICK),
            KitPad(slot = 2, sampleFile = "s.wav", drumClass = DrumClass.SNARE),
            KitPad(slot = 3, sampleFile = "h.wav", drumClass = DrumClass.HAT_CLOSED),
        ),
    )

    /** Each door: a valid result, checked in words, or an IllegalArgumentException with a reason. */
    private fun doors(): List<Pair<String, (Snip) -> Unit>> = listOf(
        "ReadGroove.read" to { s ->
            val r = ReadGroove.read(s, kit, "x")
            assertTrue(r.hits > 0 && r.bars in 1..64 && r.bpm.isFinite(), "a reading with hits, bars and a tempo")
        },
        "ReadGroove.feel" to { s ->
            val dir = File(temp, "feel-${s.hashCode()}").apply { mkdirs() }
            // A straight base to pour on, so the door's own refusal is what is tested.
            val base = com.snipsnap.mpc3.Mpc3Clip("Base", 1, listOf(com.snipsnap.mpc3.Mpc3Note(36, 0, 0.9f)))
            com.snipsnap.kit.GrooveStore.save(dir, listOf(base))
            val f = ReadGroove.feel(s, dir, "x")
            assertTrue(f.covered in 2..16)
        },
        "Dig.best" to { s ->
            val found = Dig.best(s)
            if (found != null) {
                assertTrue(found.startFrame >= 0 && found.endFrame <= s.frameCount && found.startFrame < found.endFrame, "a find inside the tape")
                assertTrue(found.score.isFinite())
            }
        },
        "SnipStore.import" to { s ->
            val dir = File(temp, "import-${s.hashCode()}").apply { mkdirs() }
            val landed = SnipStore.import(s, dir, 1_000L)
            val back = WavReader.read(landed.file)
            assertTrue(back.frameCount > 0 && back.samples.all { it.isFinite() })
        },
        "InstantKit.build" to { s ->
            val dir = File(temp, "instant-${s.hashCode()}")
            val r = InstantKit.build(s, "Instant", dir)
            assertTrue(r.sliceCount > 0 && r.kit.pads.isNotEmpty())
        },
        "SilenceWatch.feed" to { s ->
            val watch = SilenceWatch.forSeconds(0.1, s.sampleRate)
            var ticks = 0
            val block = FloatArray(512)
            var i = 0
            while (i < s.samples.size) {
                val n = minOf(512, s.samples.size - i)
                System.arraycopy(s.samples, i, block, 0, n)
                if (watch.feed(block, n)) ticks++
                i += n
            }
            val silent = s.samples.all { it == 0f }
            if (!silent) assertTrue(ticks == 0 || s.frameCount > watch.holdFrames, "sound never ticks the watch by itself")
        },
    )

    @Test
    fun `every door returns a valid result or refuses by name - across every degenerate shape`() {
        for ((fixtureName, snip) in fixtures()) {
            for ((doorName, door) in doors()) {
                val result = runCatching { door(snip) }
                result.exceptionOrNull()?.let { e ->
                    if (e is AssertionError) throw e
                    assertTrue(
                        e is IllegalArgumentException,
                        "$doorName on $fixtureName threw ${e::class.simpleName}: ${e.message}",
                    )
                    assertTrue(!e.message.isNullOrBlank(), "$doorName on $fixtureName refused without saying why")
                }
            }
        }
    }

    @Test
    fun `silence never becomes a beat, a break, or a kit`() {
        val silence = Snip(FloatArray(rate * 2), 1, rate)
        assertTrue(runCatching { ReadGroove.read(silence, kit, "s") }.isFailure, "the ear hears no beat in silence")
        assertTrue(Dig.best(silence) == null, "no break in silence")
        assertTrue(runCatching { InstantKit.build(silence, "S", File(temp, "silent-kit")) }.isFailure, "no hits, no kit")
    }

    @Test
    fun `PadPeaks shrugs at junk, truncated and missing pad files`() {
        val dir = File(temp, "peaks").apply { mkdirs() }
        val rnd = Random(11)
        File(dir, "junk.wav").writeBytes(ByteArray(300) { rnd.nextInt(256).toByte() })
        val good = File(dir, "good.wav").also { WavWriter.write(it, Snip(FloatArray(2000) { 0.3f }, 1, rate)) }
        File(dir, "trunc.wav").writeBytes(good.readBytes().copyOf(40))
        File(dir, "empty.wav").writeBytes(ByteArray(0))
        val k = Kit(
            "P",
            listOf(
                KitPad(slot = 1, sampleFile = "junk.wav"),
                KitPad(slot = 2, sampleFile = "good.wav"),
                KitPad(slot = 3, sampleFile = "trunc.wav"),
                KitPad(slot = 4, sampleFile = "empty.wav"),
                KitPad(slot = 5, sampleFile = "gone.wav"),
            ),
        )
        val peaks = PadPeaks.forKit(k, dir)
        assertTrue(2 in peaks.keys, "the good pad draws")
        assertTrue(peaks.values.all { cols -> cols.all { it.min.isFinite() && it.max.isFinite() } })
    }

    @Test
    fun `Pcm turns any bytes into finite samples inside the rails`() {
        val rnd = Random(5)
        val bytes = ByteArray(4_000) { rnd.nextInt(256).toByte() }
        val out16 = FloatArray(2_000)
        val n16 = Pcm.int16ToFloat(bytes, 0, bytes.size, out16, 0)
        assertTrue(n16 == 2_000 && out16.all { it.isFinite() && it >= -1f && it <= 1f })
        val out32 = FloatArray(1_000)
        val n32 = Pcm.floatToFloat(bytes, 0, bytes.size, out32, 0)
        assertTrue(n32 == 1_000 && out32.all { it.isFinite() && it >= -1f && it <= 1f })
    }
}
