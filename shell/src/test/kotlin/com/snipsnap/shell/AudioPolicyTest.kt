package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioPolicyTest {

    @Test
    fun `a permanent loss stops, and is not expected to come back on its own`() {
        assertEquals(AudioFocusAction.STOP, AudioPolicy.actionFor(AudioPolicy.AUDIOFOCUS_LOSS))
    }

    @Test
    fun `both transient flavors pause rather than duck`() {
        // See AudioPolicy's own KDoc: this app has no gain stage to duck
        // through, so CAN_DUCK collapses into the same action as a hard
        // transient loss rather than getting a distinct, unimplementable one.
        assertEquals(AudioFocusAction.PAUSE, AudioPolicy.actionFor(AudioPolicy.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(AudioFocusAction.PAUSE, AudioPolicy.actionFor(AudioPolicy.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
    }

    @Test
    fun `gain resumes`() {
        assertEquals(AudioFocusAction.RESUME, AudioPolicy.actionFor(AudioPolicy.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `an unrecognized value is ignored, never treated as silence`() {
        assertEquals(AudioFocusAction.IGNORE, AudioPolicy.actionFor(0))
        // AUDIOFOCUS_GAIN_TRANSIENT(_MAY_DUCK) - never requested by this app
        // (AudioFocus always asks for plain AUDIOFOCUS_GAIN), but a stray
        // value here must still fail safe rather than silence everything.
        assertEquals(AudioFocusAction.IGNORE, AudioPolicy.actionFor(2))
        assertEquals(AudioFocusAction.IGNORE, AudioPolicy.actionFor(3))
    }

    @Test
    fun `the loss and gain constants match AudioManager's real values`() {
        assertEquals(1, AudioPolicy.AUDIOFOCUS_GAIN)
        assertEquals(-1, AudioPolicy.AUDIOFOCUS_LOSS)
        assertEquals(-2, AudioPolicy.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(-3, AudioPolicy.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
    }
}
