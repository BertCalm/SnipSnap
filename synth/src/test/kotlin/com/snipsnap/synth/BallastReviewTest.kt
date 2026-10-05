package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals

class BallastReviewTest {
    @Test
    fun `held notes retain the supplied velocity through the public render`() {
        val m = Ballast.defaults(BallastVoice.ROOT) + ("HOLD" to 1f)
        val patch = BallastPatch("Held", BallastVoice.ROOT, m)
        val hard = patch.render()
        val soft = Velocity.atVelocity(patch, 0.2f)
        assertFalse(hard.samples.contentEquals(soft.samples), "HOLD discarded velocity")
        val expected = Ballast.renderLoopMeasured(BallastVoice.ROOT, m, velocity = 0.2f.toDouble())
        assertContentEquals(expected.loop, soft.samples)
    }
}
