package com.snipsnap.synth

/** Model-6 articulation; legacy factors are exact identities, including noise depth. */
internal object RimeVoicing {
    fun attack(voice: RimeVoice): Double = when (voice) {
        RimeVoice.LOOP -> .006; RimeVoice.GLINT -> .003; RimeVoice.VESSEL -> .025
        RimeVoice.REED -> .012; RimeVoice.FILIGREE -> .018; RimeVoice.CRUST -> .004
    }
    fun decay(voice: RimeVoice): Double = when (voice) {
        RimeVoice.LOOP -> 1.0; RimeVoice.GLINT -> 2.1; RimeVoice.VESSEL -> .3
        RimeVoice.REED -> .45; RimeVoice.FILIGREE -> .35; RimeVoice.CRUST -> .75
    }
    fun contact(voice: RimeVoice): Double = when (voice) {
        RimeVoice.REED -> .80; RimeVoice.FILIGREE -> .90; RimeVoice.CRUST -> 1.18
        else -> 1.0
    }
    fun friction(voice: RimeVoice): Double = when (voice) {
        RimeVoice.REED -> .012; RimeVoice.FILIGREE -> .014; RimeVoice.CRUST -> .045
        else -> .018
    }
}
