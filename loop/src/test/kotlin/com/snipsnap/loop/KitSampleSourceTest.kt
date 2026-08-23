package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KitSampleSourceTest {

    private fun tempDir(): File =
        File.createTempFile("snipsnap-src", "").let {
            it.delete(); it.mkdirs(); it.deleteOnExit(); it
        }

    /**
     * Writes a distinguishable stereo WAV: left and right hold different
     * constant values, and the pair is unique per fixture. A fixture where
     * every file (or every channel) held the same content would make a
     * "returned the wrong file" or "swapped the channels" bug invisible no
     * matter what the test asserts, so every call site below picks its own
     * left/right pair.
     */
    private fun writeWav(dir: File, name: String, frames: Int, left: Float, right: Float): File {
        val samples = FloatArray(frames * 2)
        for (i in 0 until frames) {
            samples[i * 2] = left
            samples[i * 2 + 1] = right
        }
        val snip = Snip(samples, 2, 44_100)
        return WavWriter.write(File(dir, name), snip, WavWriter.BitDepth.PCM_24)
    }

    /** 24-bit round-trip quantization is tiny; this tolerance is generous relative to it. */
    private fun assertChannelValues(snip: Snip, left: Float, right: Float) {
        val eps = 1e-4f
        for (i in 0 until snip.frameCount) {
            assertTrue(
                abs(snip.samples[i * 2] - left) < eps,
                "frame $i left: expected $left got ${snip.samples[i * 2]}",
            )
            assertTrue(
                abs(snip.samples[i * 2 + 1] - right) < eps,
                "frame $i right: expected $right got ${snip.samples[i * 2 + 1]}",
            )
        }
    }

    @Test
    fun `resolves a loop by bare filename inside the session folder`() {
        val dir = tempDir()
        // Two distinct loops share the folder. A bug that answers with "the
        // first WAV in the directory" instead of the one actually named must
        // fail here, since aardvark.wav sorts first and holds different audio.
        writeWav(dir, "aardvark.wav", 1_000, left = 0.5f, right = -0.25f)
        writeWav(dir, "break.wav", 600, left = 0.1f, right = 0.75f)

        val snip = KitSampleSource(dir).loop("break.wav")
        assertNotNull(snip)
        assertEquals(600, snip.frameCount)
        assertEquals(2, snip.channels)
        assertChannelValues(snip, left = 0.1f, right = 0.75f)
    }

    @Test
    fun `returns null for a loop that is not there`() {
        assertNull(KitSampleSource(tempDir()).loop("gone.wav"))
    }

    @Test
    fun `resolves a pad through the kit's own sidecar`() {
        val dir = tempDir()
        val kitDir = File(dir, "Thump Kit").also { it.mkdirs() }
        // Two pads with distinct content and distinct lengths. A bug that
        // always answers with the kit's first pad regardless of slot must
        // fail on the slot-2 assertion below.
        writeWav(kitDir, "kick.wav", 800, left = 0.4f, right = -0.4f)
        writeWav(kitDir, "snare.wav", 500, left = -0.6f, right = 0.6f)
        KitStore.save(
            Kit(
                "Thump Kit",
                listOf(
                    KitPad(slot = 1, sampleFile = "kick.wav"),
                    KitPad(slot = 2, sampleFile = "snare.wav"),
                ),
            ),
            kitDir,
        )
        // A second kit whose folder name sorts BEFORE "Thump Kit". A bug that
        // ignores the `kit` argument and indexes whichever kit directory it
        // finds first must fail on the Thump-kit assertions below, since a
        // first-directory bug would answer with Clang Kit's slot-1 audio.
        val clangDir = File(dir, "Clang Kit").also { it.mkdirs() }
        writeWav(clangDir, "clap.wav", 300, left = 0.2f, right = -0.9f)
        KitStore.save(Kit("Clang Kit", listOf(KitPad(slot = 1, sampleFile = "clap.wav"))), clangDir)

        val source = KitSampleSource(dir)

        val kick = source.pad("Thump Kit", 1)
        assertNotNull(kick)
        assertEquals(800, kick.frameCount)
        assertChannelValues(kick, left = 0.4f, right = -0.4f)

        val snare = source.pad("Thump Kit", 2)
        assertNotNull(snare)
        assertEquals(500, snare.frameCount)
        assertChannelValues(snare, left = -0.6f, right = 0.6f)

        val clap = source.pad("Clang Kit", 1)
        assertNotNull(clap)
        assertEquals(300, clap.frameCount)
        assertChannelValues(clap, left = 0.2f, right = -0.9f)

        // Same cache contract as loop(): baking asks for the same pad
        // repeatedly across a long cycle, so a second read must be the same
        // decoded instance, not a fresh WavReader.read.
        assertEquals(true, kick === source.pad("Thump Kit", 1), "second pad read should be cached")
    }

    @Test
    fun `returns null for a slot the kit does not fill`() {
        val dir = tempDir()
        val kitDir = File(dir, "Thump Kit").also { it.mkdirs() }
        writeWav(kitDir, "kick.wav", 800, left = 0.4f, right = -0.4f)
        KitStore.save(Kit("Thump Kit", listOf(KitPad(slot = 1, sampleFile = "kick.wav"))), kitDir)

        assertNull(KitSampleSource(dir).pad("Thump Kit", 9))
    }

    @Test
    fun `returns null for a kit that is not there`() {
        assertNull(KitSampleSource(tempDir()).pad("No Such Kit", 1))
    }

    @Test
    fun `refuses a path that escapes the session folder`() {
        // sampleFile is meant to be a bare filename. A traversal attempt must
        // not read outside the session, whatever wrote loop.json. Plant a
        // recognizable file one level up, inside its own isolated parent
        // directory (not the shared system temp root) so a removed guard
        // doesn't merely "pass" by coincidence of nothing being there to
        // find, and nothing is left loose in shared temp on an abnormal exit.
        val parent = tempDir()
        val dir = File(parent, "session").also { it.mkdirs() }
        val sentinelName = "sentinel.wav"
        writeWav(parent, sentinelName, 100, left = 0.9f, right = 0.9f)

        assertNull(KitSampleSource(dir).loop("../$sentinelName"))
        assertNull(KitSampleSource(dir).loop("../../etc/passwd"))
    }

    @Test
    fun `a kit created after a failed lookup is not permanently poisoned`() {
        val dir = tempDir()
        val source = KitSampleSource(dir)

        // First ask: the kit folder doesn't exist yet. This miss must not be
        // cached forever, since Task 2 wires a live session where a kit can
        // be written mid-session and needs to be found on the next ask.
        assertNull(source.pad("Late Kit", 1))

        val kitDir = File(dir, "Late Kit").also { it.mkdirs() }
        writeWav(kitDir, "clave.wav", 400, left = 0.3f, right = -0.7f)
        KitStore.save(Kit("Late Kit", listOf(KitPad(slot = 1, sampleFile = "clave.wav"))), kitDir)

        val snip = source.pad("Late Kit", 1)
        assertNotNull(snip)
        assertEquals(400, snip.frameCount)
        assertChannelValues(snip, left = 0.3f, right = -0.7f)
    }

    @Test
    fun `decodes each file once`() {
        val dir = tempDir()
        writeWav(dir, "break.wav", 1_000, left = 0.5f, right = 0.5f)
        val source = KitSampleSource(dir)

        val first = source.loop("break.wav")
        val second = source.loop("break.wav")
        // Baking asks repeatedly across a long cycle; decoding a WAV every time
        // would put file I/O on the path a prefetch is trying to keep short.
        assertNotNull(first)
        assertEquals(true, first === second, "second read should be cached")
    }
}
