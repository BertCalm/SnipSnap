package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Exercises the real exhaustive Velocity dispatcher, not a replacement Patch interface. */
class SplayNativeVelocityTest {
    @Test fun `factory model two keeps its native velocity route and acoustic seed`() {
        val patch = SplayFactoryPresets.all().first().copy(midi = 57, seed = -41001)
        assertContentEquals(patch.copy(velocity = .37f).render().samples,
            Velocity.atVelocity(patch, .37f).samples, "model 2: native touch route")
        assertTrue(Velocity.atVelocity(patch, 0f).samples.all { it == 0f }, "model 2: silent zero touch")
    }
}
