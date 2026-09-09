package com.snipsnap.shell

/**
 * What the app's shared `AudioFocus` owner (`:app`) should do about one of
 * `AudioManager.OnAudioFocusChangeListener`'s callback values. Kept here,
 * not in `:app`, so the three-way distinction Android actually sends —
 * `AUDIOFOCUS_LOSS` vs. the two `TRANSIENT` flavors vs. `AUDIOFOCUS_GAIN` —
 * is unit-tested without an emulator, exactly the way this codebase already
 * pulls other pure policy (`PadHit`, `Layers`) out of the Android layer.
 */
enum class AudioFocusAction { STOP, PAUSE, RESUME, IGNORE }

object AudioPolicy {

    // `AudioManager`'s own int constants, copied rather than referenced:
    // `:shell` is a JVM-only module with no Android dependency (see its
    // build.gradle.kts), and these five values are long-stable platform
    // API — pinned by `AudioPolicyTest`'s own constants test so a typo here
    // can't silently mismatch what the real `AudioManager` sends.
    const val AUDIOFOCUS_GAIN = 1
    const val AUDIOFOCUS_LOSS = -1
    const val AUDIOFOCUS_LOSS_TRANSIENT = -2
    const val AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3

    /**
     * `AUDIOFOCUS_LOSS` and both `TRANSIENT` flavors all map to [AudioFocusAction.PAUSE]/[AudioFocusAction.STOP]
     * territory — the distinction between them is [AudioFocusAction.STOP] (a
     * plain, indefinite [AUDIOFOCUS_LOSS]) vs. [AudioFocusAction.PAUSE] (either
     * `TRANSIENT` flavor, which Android's contract says is worth holding the
     * focus request open for). Both silence every registered voice
     * immediately and identically; what differs is what `AudioFocus` itself
     * does with its *own* focus grant afterward — see its KDoc for the full
     * reasoning, including why `TRANSIENT_CAN_DUCK` pauses rather than
     * ducks in this app.
     */
    fun actionFor(focusChange: Int): AudioFocusAction = when (focusChange) {
        AUDIOFOCUS_LOSS -> AudioFocusAction.STOP
        AUDIOFOCUS_LOSS_TRANSIENT, AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusAction.PAUSE
        AUDIOFOCUS_GAIN -> AudioFocusAction.RESUME
        else -> AudioFocusAction.IGNORE
    }
}
