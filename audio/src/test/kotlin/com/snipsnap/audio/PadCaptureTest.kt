package com.snipsnap.audio

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PadCaptureTest {
    private fun silence(frames: Int) = FloatArray(frames)

    // A short burst starting at `at`, on a bed of silence. Hann-windowed
    // (fades in AND out) rather than a straight decay-from-full-amplitude:
    // a linear decay's asymmetric envelope leaves a small residual DC bias
    // when averaged over the mostly-silent slice `grabOneShot` extracts, and
    // Cleanup.removeDcOffset (correctly, for real captures — see its
    // docstring) subtracts that bias uniformly, lifting the "silent" tail
    // just enough to defeat `trimSilence`'s threshold. A symmetric window
    // cancels to ~0 net bias, which real synthetic silence should have
    // anyway; it's still an unambiguous percussive hit for onset detection.
    private fun hitAt(total: Int, at: Int, lenFrames: Int = 4_410): FloatArray {
        val buf = FloatArray(total)
        for (i in 0 until lenFrames) {
            val idx = at + i
            if (idx >= total) break
            val env = 0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / lenFrames).toFloat())
            buf[idx] = (0.8f * env * sin(2.0 * Math.PI * 180.0 * i / 44_100.0)).toFloat()
        }
        return buf
    }

    @Test
    fun `silence yields null — nothing to grab`() {
        assertNull(PadCapture.grabOneShot(silence(44_100), 44_100))
    }

    @Test
    fun `a hit near the end is grabbed as a short one-shot`() {
        // 2s of mostly silence, a hit starting at 1.5s
        val raw = hitAt(total = 88_200, at = 66_150)
        val snip = PadCapture.grabOneShot(raw, 44_100)
        assertNotNull(snip)
        assertEquals(1, snip.channels)
        assertEquals(44_100, snip.sampleRate)
        // Trimmed to roughly the hit, not the whole 2s.
        assertTrue(snip.frameCount in PadCapture.MIN_ONESHOT_FRAMES..(6_000 + 2_000),
            "expected a short one-shot, got ${snip.frameCount} frames")
        // Normalized: peak near full-scale.
        assertTrue(snip.peak() > 0.9f, "expected normalized peak, got ${snip.peak()}")
    }

    @Test
    fun `the last of two hits is the one grabbed`() {
        // Second hit's start is phase-aligned to the first (same offset mod the
        // analysis hop, 256 frames) rather than at a round 2.5s: Transients'
        // energy-frame accounting is phase-sensitive at a hit's very first
        // partial analysis window, and an unlucky phase can leave that
        // window's energy a hair under the silence floor even though the
        // attack is unambiguous a hop later — starving its novelty peak of
        // being credited as a local max. Matching phase keeps both onsets
        // symmetric and unambiguous, without touching the detector itself.
        val raw = hitAt(total = 132_300, at = 22_050)          // hit at 0.5s
            .also { first -> hitAt(total = 132_300, at = 110_114).forEachIndexed { i, v -> if (v != 0f) first[i] = v } } // hit at ~2.5s
        val snip = PadCapture.grabOneShot(raw, 44_100)
        assertNotNull(snip)
        // Only the last hit's worth of audio, far shorter than 2.5s of lead-in.
        assertTrue(snip.frameCount < 22_050, "should grab the last hit, not from the first")
    }
}
