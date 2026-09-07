package com.snipsnap.shell

import com.snipsnap.audio.Body
import com.snipsnap.audio.Eternal
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Retune
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Tuner
import com.snipsnap.synth.Wobble
import java.util.Locale

/**
 * The keyed family: treatments that read the kit itself — its key, its
 * tempo — where the rack's characters read nothing but the sound. One
 * door for the pad sheet's fifth row and the CLI verbs alike:
 *
 * - **retuned** — every partial talked into the key ([Retune]); a drum
 *   is refused as unpitched;
 * - **bodied** — a bank of resonators tuned to the key, struck by the
 *   hit ([Body]); with no key, the hit's own note or C, never refused;
 * - **wobbled** — a filter sweep synced to a note division at the kit's
 *   tempo ([Wobble]);
 * - **eternal** — the attack kept bit for bit, the tail slowed toward a
 *   frozen instant ([Eternal]): reads nothing of the kit, but lives here
 *   because its tail is the point and the rack's tail budget would cut it.
 *
 * Each is `Snip → Snip` with AMOUNT how far, peak matched, and its own
 * honest refusal in words.
 */
object Keyed {

    val NAMES: List<String> = listOf("retuned", "bodied", "wobbled", "eternal")

    /** The honest refusal: the sound is not what the treatment wants. */
    class Refused(message: String) : IllegalArgumentException(message)

    /** What the kit knows about itself: a key when one was chosen, and its tempo. */
    data class Context(val key: KeySpec?, val bpm: Float)

    /** The dials the phone does not draw — the CLI's flags, at their defaults on the card. */
    data class Dials(
        /** BODY: the modes' T60, seconds. */
        val decay: Float = Body.DECAY_DEFAULT,
        /** WOBBLE: the note division the sweep is synced to. */
        val division: String = Wobble.DEFAULT_DIVISION,
        /** ETERNAL: the tail in seconds; null takes it from AMOUNT ([Eternal.tailFor]). */
        val tail: Float? = null,
        /** ETERNAL: how much of the attack passes untouched, seconds. */
        val knee: Float = Eternal.KNEE_DEFAULT_SEC,
    )

    /** What the treatment did: the sound, and the key or tempo it read, in the toast's words. */
    data class Result(val snip: Snip, val keyLabel: String)

    /** The retune's target when the kit has no key: every semitone. */
    val NO_KEY: KeySpec = KeySpec(0, Scale.CHROMATIC)

    const val NO_KEY_LABEL = "THE NEAREST SEMITONES"

    /** A name the family knows, or an [IllegalArgumentException] naming the ones it does. */
    fun require(name: String) {
        require(name in NAMES) { "unknown keyed treatment '$name' - one of: ${NAMES.joinToString(", ")}" }
    }

    /**
     * Why [name] would refuse [snip] in [context], in words, or null when
     * it will go ahead — asked before anything is touched.
     */
    fun refusal(name: String, snip: Snip, context: Context, amount: Float = 1f, dials: Dials = Dials()): String? {
        require(name)
        return when (name) {
            "retuned" -> Retune.analyze(snip, context.key ?: NO_KEY).refusal
            "eternal" -> if (amount <= 0f) null else Eternal.refusal(snip, dials.tail ?: Eternal.tailFor(amount), dials.knee)
            else -> null
        }
    }

    /** [name] over [snip] in [context]; [seed] the retune's phases; [dials] the CLI's extra flags. */
    fun apply(name: String, snip: Snip, context: Context, amount: Float = 1f, seed: Long = 0, dials: Dials = Dials()): Result {
        require(name)
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        val key = context.key
        return when (name) {
            "retuned" -> {
                val k = key ?: NO_KEY
                val tuned = Retune.retune(snip, k, amount, seed)
                    ?: throw Refused(Retune.analyze(snip, k).refusal ?: "not a note")
                Result(tuned.snip, key?.label?.uppercase() ?: NO_KEY_LABEL)
            }
            "bodied" -> {
                val root = Body.rootFor(snip, key)
                val label = key?.label?.uppercase()
                    ?: (Scales.NOTE_NAMES[root] + if (isPitched(snip)) ", THE HIT'S OWN NOTE" else "")
                Result(Body.ring(snip, root, key?.scale ?: Scale.CHROMATIC, amount, dials.decay), label)
            }
            "wobbled" -> Result(
                Wobble.sweep(snip, context.bpm, dials.division, amount),
                "%s AT %d BPM".format(Locale.ROOT, dials.division, Math.round(context.bpm)),
            )
            "eternal" -> {
                if (amount <= 0f) return Result(snip, "")
                val tail = dials.tail ?: Eternal.tailFor(amount)
                Eternal.refusal(snip, tail, dials.knee)?.let { throw Refused(it) }
                Result(Eternal.stretch(snip, tail, dials.knee, seed), "A %.1f S TAIL".format(Locale.ROOT, tail))
            }
            else -> throw IllegalStateException(name)
        }
    }

    private fun isPitched(snip: Snip): Boolean {
        val est = Pitch.detect(snip) ?: return false
        return est.confidence >= Tuner.MIN_CONFIDENCE
    }
}
