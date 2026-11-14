package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/** Owner-approved six-voice bank, with native excitation layers and one playback velocity gain. */
object OsteriaKits {
    fun sixVoice(): List<ArrangedPad?> = OsteriaPerformanceBank.render().map { it.arranged() }
}
