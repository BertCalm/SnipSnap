package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * A hand-built robin pad for :kit's own tests (the real deal lives in :shell): an original and its
 * takes - plain level steps of it - end to end in one 24-bit chain, plus a [dealer] that deals the
 * same steps again from whatever original it is handed.
 */
internal object RobinFixture {
    const val FRAMES = 2_000
    val SETTINGS = RobinSettings(0.6f, 6, 0, followsKit = true)
    val KIT = KitRobin(0.6f, 6)

    fun original(rate: Int): Snip = Snip(FloatArray(FRAMES) { (kotlin.math.sin(it * 0.07) * 0.5 * kotlin.math.exp(-it / 600.0)).toFloat() }, 1, rate)

    private fun deal(original: Snip, takes: Int): Pair<Snip, List<Long>> {
        val out = FloatArray(original.samples.size * takes)
        for (t in 0 until takes) for (i in original.samples.indices) out[t * original.samples.size + i] = original.samples[i] * (1f - 0.1f * t)
        return Snip(out, original.channels, original.sampleRate) to (0 until takes).map { it.toLong() * original.frameCount }
    }

    /** The original's bytes as WavWriter writes them - what take one must give back. */
    fun originalBytes(rate: Int): ByteArray = java.io.ByteArrayOutputStream().also { WavWriter.write(it, original(rate)) }.toByteArray()

    /** [kit] in [dir] plus a robin pad on [slot] (its chain dealt from [original]); saved. */
    fun addRobinPad(kit: Kit, dir: File, slot: Int, rate: Int): Kit {
        val name = "A%02d_Kick_%02d.wav".format(java.util.Locale.ROOT, slot, slot)
        val (chain, boundaries) = deal(WavReader.read(originalBytes(rate)), SETTINGS.takes)
        WavWriter.write(File(dir, name), chain)
        val pad = KitPad(slot, name, drumClass = DrumClass.KICK, chain = ChainInfo(boundaries, cycle = SETTINGS.takes), robin = SETTINGS)
        return kit.copy(pads = kit.pads + pad, robin = KIT).also { KitStore.save(it, dir) }
    }

    /** Deals every pad landed with settings and no chain the same way [addRobinPad] did; [seen] gets each original's bytes. */
    fun dealer(seen: MutableMap<Int, ByteArray> = HashMap()) = RobinDealer { kit, dir ->
        kit.copy(pads = kit.pads.map { pad ->
            val s = pad.robin
            if (s == null || pad.chain != null) return@map pad
            val file = File(dir, pad.sampleFile)
            seen[pad.slot] = file.readBytes()
            val (chain, boundaries) = deal(WavReader.read(file), s.takes)
            WavWriter.write(file, chain)
            pad.copy(chain = ChainInfo(boundaries, cycle = s.takes))
        })
    }
}
