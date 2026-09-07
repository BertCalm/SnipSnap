package com.snipsnap.app

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes every open→mutate→save on a kit folder across ALL screens.
 * `KitBuilderModel.save()` is an unconditional whole-file overwrite with no
 * version check, so two unsynchronized writers on one `kit.json` silently
 * lose whichever landed first (QA finding, two hunters independently).
 *
 * Every app-side `KitBuilderModel` open→mutate→save sequence — PadCapture's
 * GRAB/HOLD, PadSheet's debounced metadata flush and every treatment/mutate/
 * outside/desample/era/smear commit, SynthScreen's SEND TO PAD, TakesBin's
 * restore — acquires [mutex] around its own IO-side block. Only the mutation
 * itself belongs inside the lock; read-only/DSP work above it stays
 * concurrent.
 */
object KitWrites {
    val mutex = Mutex()
}
