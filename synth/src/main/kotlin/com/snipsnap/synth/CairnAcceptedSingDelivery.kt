package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos

/**
 * Explicit delivery candidate over genuine settled native context, not new synthesis.
 * The shared players start at full gain, so only the first 220 predecessor frames
 * receive the same endpoint-inclusive raised-cosine intro as the kept audition.
 * The accepted loop body and its actual preceding join window stay bit-exact.
 * Instrument packaging, keyboard range and sampler note-off are separate work.
 */
object CairnAcceptedSingDelivery {
    const val INTRO_FRAMES = 220
    const val POLICY_JSON = """{"schema":1,"id":"cairn-settled-prefix-intro-v1","onset":"settled-native-predecessor","introFrames":220,"introAt44100Hz":"approximately 5 ms, matching kept audition","curve":"raised cosine, endpoint-inclusive","modifyLoopBody":false,"naturalAttack":false,"naturalRelease":false,"release":"separate sampler envelope, not specified here"}"""
    val policySha256: String get() = MessageDigest.getInstance("SHA-256")
        .digest(POLICY_JSON.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    class IntroCandidate internal constructor(
        val sourceId: String,
        val sourceRecipeSha256: String,
        val deliveryPolicySha256: String,
        val fullLoopStartFrame: Int,
        private val full: FloatArray,
    ) {
        val fullEndFrameExclusive: Int get() = full.size
        val hasNaturalAttack: Boolean get() = false
        val hasNaturalRelease: Boolean get() = false
        fun samples(): Snip = Snip(full.copyOf(), 1, Cairn.RATE)
    }

    /** Explicit opt-in; the source context and its original full/cycle remain unchanged. */
    fun withSettledPrefixIntro(context: CairnAcceptedSingContext.Context): IntroCandidate {
        require(INTRO_FRAMES <= context.fullLoopStartFrame - 256) {
            "CAIRN delivery intro must finish before the genuine predecessor join window"
        }
        val full = context.fullNativeContext().samples
        for (i in 0 until INTRO_FRAMES) {
            val gain = (.5 - .5 * cos(PI * i / (INTRO_FRAMES - 1))).toFloat()
            full[i] *= gain
        }
        return IntroCandidate(context.sourceId, context.recipeSha256, policySha256,
            context.fullLoopStartFrame, full)
    }
}
