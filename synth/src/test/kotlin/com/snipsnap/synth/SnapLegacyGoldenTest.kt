package com.snipsnap.synth

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A SNAP pad saved before SNAP SCAN (no portrait on the patch) must render bit for bit as it did.
 * The hashes were taken from the render at the merge commit before any SNAP SCAN code existed
 * (docs/superpowers/specs/2026-11-21-snap-scan-design.md, §10 round 1a tests).
 */
class SnapLegacyGoldenTest {

    private fun sha(samples: FloatArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = java.nio.ByteBuffer.allocate(samples.size * 4)
        for (s in samples) bytes.putInt(java.lang.Float.floatToIntBits(s))
        return md.digest(bytes.array()).joinToString("") { "%02x".format(it) }.take(16)
    }

    private val ramp = IntArray(Snap.TABLE_SIZE) { (it * 255) / (Snap.TABLE_SIZE - 1) }
    private val wave = IntArray(Snap.TABLE_SIZE) { Math.round(127.5 + 120 * kotlin.math.sin(it * 0.07) * kotlin.math.cos(it * 0.013)).toInt() }

    @Test
    fun aRampOrbitPadRendersAsBefore() {
        val patch = SnapPatch("Door", SnapVoice.ORBIT, mapOf("GRIT" to 0.4f, "TUNE" to 0.3f), ramp)
        assertEquals(LEGACY_RAMP, sha(patch.render().samples))
    }

    @Test
    fun aDrawnPadWithAShapeRendersAsBefore() {
        val patch = SnapPatch("Pen", SnapVoice.DRAWN, mapOf("DECAY" to 0.8f, "BRIGHT" to 0.3f), wave, Draw.shape(Draw.Shape.BOUNCE))
        assertEquals(LEGACY_DRAWN, sha(patch.render().samples))
    }

    companion object {
        const val LEGACY_RAMP = "bc27b9c48c381177"
        const val LEGACY_DRAWN = "ee2fbc6cc6e8eff9"
    }
}
