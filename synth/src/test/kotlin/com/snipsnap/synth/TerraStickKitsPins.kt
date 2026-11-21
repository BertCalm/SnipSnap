package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.security.MessageDigest

/**
 * The pin test's grid hash: SHA-256 over every zone sample as the 24-bit PCM a kit saves (each sample scaled by
 * 2^23 - 1, rounded and clamped), touch by touch, take by take, each preceded by its length. 24-bit, as TERRA's preset
 * pins hash PCM24 WAVs, so a last-bit floating-point difference between machines does not move a pin.
 */
internal object TerraStickKitsPins {
    fun gridHash(grid: List<List<Snip>>): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = java.nio.ByteBuffer.allocate(4)
        for (zone in grid) for (snip in zone) {
            md.update(buf.clear().putInt(snip.samples.size).array())
            for (x in snip.samples) md.update(buf.clear().putInt(Math.round(x.coerceIn(-1f, 1f) * 8388607f)).array())
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
