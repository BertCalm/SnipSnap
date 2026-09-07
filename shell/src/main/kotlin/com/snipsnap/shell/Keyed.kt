package com.snipsnap.shell

import com.snipsnap.audio.Body
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Retune
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Snip

/**
 * The keyed family: treatments that read the kit itself — its key today,
 * its tempo next — where the rack's characters read nothing but the
 * sound. One door for the pad sheet's row five and the CLI verbs alike:
 *
 * - **retuned** — every partial talked into the key ([Retune]); a drum
 *   is refused as unpitched;
 * - **bodied** — a bank of resonators tuned to the key, struck by the
 *   hit ([Body]); with no key, the hit's own note or C, never refused.
 *
 * Each is `Snip → Snip` with AMOUNT how far, peak matched, and its own
 * honest refusal in words.
 */
object Keyed {

    val NAMES: List<String> = listOf("retuned", "bodied")

    /** The honest refusal: the sound is not what the treatment wants. */
    class Refused(message: String) : IllegalArgumentException(message)

    /** What the treatment did, in the toast's words: the key it read, and a note. */
    data class Result(val snip: Snip, val keyLabel: String)

    /** The retune's target when the kit has no key: every semitone. */
    val NO_KEY: KeySpec = KeySpec(0, Scale.CHROMATIC)

    const val NO_KEY_LABEL = "THE NEAREST SEMITONES"

    /** A name the family knows, or an [IllegalArgumentException] naming the ones it does. */
    fun require(name: String) {
        require(name in NAMES) { "unknown keyed treatment '$name' - one of: ${NAMES.joinToString(", ")}" }
    }

    /**
     * Why [name] would refuse [snip] under [key], in words, or null when
     * it will go ahead — asked before anything is touched.
     */
    fun refusal(name: String, snip: Snip, key: KeySpec?): String? {
        require(name)
        return when (name) {
            "retuned" -> Retune.analyze(snip, key ?: NO_KEY).refusal
            else -> null
        }
    }

    /**
     * [name] over [snip] against [key] (null: the kit has none). [decay]
     * is BODY's ring, seconds; [seed] the retune's phases.
     */
    fun apply(name: String, snip: Snip, key: KeySpec?, amount: Float = 1f, seed: Long = 0, decay: Float = Body.DECAY_DEFAULT): Result {
        require(name)
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        return when (name) {
            "retuned" -> {
                val k = key ?: NO_KEY
                val tuned = Retune.retune(snip, k, amount, seed)
                    ?: throw Refused(Retune.analyze(snip, k).refusal ?: "not a note")
                Result(tuned.snip, key?.label?.uppercase() ?: NO_KEY_LABEL)
            }
            "bodied" -> {
                val root = Body.rootFor(snip, key)
                val scale = key?.scale ?: Scale.CHROMATIC
                val label = key?.label?.uppercase() ?: (com.snipsnap.audio.Scales.NOTE_NAMES[root] + (if (Body.rootFor(snip, null) == root && Pitchy.isPitched(snip)) ", THE HIT'S OWN NOTE" else ""))
                Result(Body.ring(snip, root, scale, amount, decay), label)
            }
            else -> throw IllegalStateException(name)
        }
    }

    /** The toast's word for the key BODY rang at when the kit has none. */
    private object Pitchy {
        fun isPitched(snip: Snip): Boolean {
            val est = com.snipsnap.audio.Pitch.detect(snip) ?: return false
            return est.confidence >= com.snipsnap.audio.Tuner.MIN_CONFIDENCE
        }
    }
}
