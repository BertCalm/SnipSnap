package com.snipsnap.shell

import com.snipsnap.audio.Outside
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import java.io.File
import kotlin.math.roundToInt

/**
 * The PAD SHEET's OUTSIDE card, as data — the phone's door onto
 * [Outside]: the world as an effect.
 *
 * Two moves. **REAMP** sends the pad itself out of the jack (a pedal, an
 * amp, the room) and the return *becomes* the pad, lined up on the
 * arrival, MIX dry to wet. **ROOM** sends the sweep instead, turns what
 * comes back into the room's impulse response, and hands that to ROOM OF
 * ITSELF as the parent — so the pad is played inside the actual room the
 * phone is in, WET how much. Both are bin-backed rewrites through the
 * same doors as every treatment: the recipe rides the pad, undo is the
 * original back out of the bin.
 *
 * The timing is the card's business too, because the Android layer has
 * no idea when its own audio stack will actually start playing: the
 * phone starts *listening* first, waits [PRE_ROLL_SEC] (that quiet is
 * also how the room's noise floor gets measured), then plays the send,
 * and keeps listening for the send's length plus [Outside.TAIL_MAX_SEC]
 * of tail plus [LATENCY_ALLOWANCE_SEC] for the trip. Whatever the real
 * latency turns out to be, [Outside.align] finds it in the return.
 */
object OutsideSheet {

    enum class Move { REAMP, ROOM }

    /** Move order, left to right, as the card draws it. */
    val MOVES: List<String> = Move.values().map { it.name }

    fun moveFor(name: String): Move =
        Move.values().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("unknown outside move '$name' - the card draws: ${MOVES.joinToString(", ")}")

    private val KNOBS: Map<Move, Knob> = mapOf(
        // The whole point of a reamp is the return; MIX opens all the way wet.
        Move.REAMP to Knob("MIX", 0f, 1f, 1f, exponential = false),
        // ROOM OF ITSELF's own default.
        Move.ROOM to Knob("WET", 0f, 1f, 0.5f, exponential = false),
    )

    fun knobFor(move: Move): Knob = KNOBS.getValue(move)

    /** Stepper fraction 0..1 → the knob's value. */
    fun value(knob: Knob, fraction: Float): Float = knob.value(fraction)

    /** The knob's value → stepper fraction; the inverse of [value]. */
    fun fraction(knob: Knob, value: Float): Float = knob.fraction(value)

    /** What the value column reads: "50%". */
    fun label(knob: Knob, value: Float): String = "${(value * 100).roundToInt()}%"

    /** The phone listens this long before the send starts: the room's floor, and a margin for an early start. */
    const val PRE_ROLL_SEC = 0.25f

    /** How late the send may come back and still be waited for — Android's round trip plus a USB interface's. */
    const val LATENCY_ALLOWANCE_SEC = 1f

    /** The room's label as ROOM OF ITSELF's parent, in the recipe and the lineage. */
    const val ROOM_LABEL = "outside:room"

    /** What goes out of the jack for [move] on [slot]: the pad's own audio, or the sweep at the pad's rate. */
    fun send(model: KitBuilderModel, slot: Int, move: Move): Snip {
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        val own = WavReader.read(File(model.kitDir, pad.sampleFile))
        return when (move) {
            Move.REAMP -> own
            Move.ROOM -> Outside.probe(own.sampleRate)
        }
    }

    /** The pre-roll in frames at [sampleRate]. */
    fun preRollFrames(sampleRate: Int): Int = (PRE_ROLL_SEC * sampleRate).toInt()

    /** How many frames the phone records, at the send's rate, for the whole trip to land inside. */
    fun listenFrames(send: Snip): Int =
        preRollFrames(send.sampleRate) + send.frameCount +
            ((Outside.TAIL_MAX_SEC + LATENCY_ALLOWANCE_SEC) * send.sampleRate).toInt()

    /** What the trip did: the pad as rewritten, and how the return was found. */
    data class Outcome(
        val pad: KitPad,
        val move: Move,
        /** The trip's latency — the arrival's lag past the pre-roll — in ms. */
        val lagMs: Float,
        /** How surely the return was found, 0..1. */
        val confidence: Float,
        /** The return came back upside down (REAMP restores it). */
        val inverted: Boolean,
    )

    /**
     * The return becomes the pad. [returned] is what the mic heard for
     * [listenFrames] — pre-roll included, [preRollFrames] of it, so the
     * recipe's latency is the trip's and not the wait's. [fraction] is
     * the stepper's position on the move's knob. REAMP rewrites through
     * [KitBuilderModel.replaceAudio] with an `outside` recipe; ROOM
     * deconvolves the impulse and runs [Mutate.apply]'s ROOM with it as
     * the one parent, the `outside` block riding inside the mutate recipe
     * — so the MUTATE card reads it as a ROOM whose parent is the room.
     * Refusals ([Outside.Refused], layered pads, chains) come before any
     * byte is touched.
     */
    fun apply(
        model: KitBuilderModel,
        slot: Int,
        move: Move,
        returned: Snip,
        fraction: Float,
        preRollFrames: Int = 0,
    ): Outcome {
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.velocityLayers.isEmpty()) { "pad $slot is velocity-layered - clear the layers before sending it outside" }
        val knob = knobFor(move)
        val amount = value(knob, fraction)
        val base = WavReader.read(File(model.kitDir, pad.sampleFile))
        val rate = base.sampleRate

        return when (move) {
            Move.REAMP -> {
                // Align and cut first, so a refusal never reaches the bin.
                val reamped = Outside.reamp(base, returned, amount)
                val a = reamped.alignment
                val lagMs = (a.lagFrames - preRollFrames) * 1000f / rate
                val recipe = JsonValue.Obj(
                    linkedMapOf<String, JsonValue>(
                        "outside" to outsideBlock("reamp", lagMs, a.confidence, a.inverted, "mix", amount),
                    ),
                )
                model.replaceAudio(slot, recipe) { reamped.snip }
                val stamped = model.update(slot) { it.copy(source = it.source + mapOf("outside" to Move.REAMP.name)) }
                Outcome(stamped, move, lagMs, a.confidence, a.inverted)
            }
            Move.ROOM -> {
                val impulse = Outside.impulse(Outside.probe(rate), returned)
                val lagMs = (impulse.lagFrames - preRollFrames) * 1000f / rate
                // The deconvolution's standout, squashed to 0..1 the way a
                // confidence reads: over eight is found at all, over
                // eighty is unmistakable.
                val confidence = (impulse.standout / 80f).coerceIn(0f, 1f)
                val outcome = Mutate.apply(
                    model, slot, listOf(Mutate.Source(ROOM_LABEL, impulse.snip)), Mutate.Mode.ROOM,
                    roomMix = amount,
                    extraRecipe = mapOf("outside" to outsideBlock("room", lagMs, confidence, false, "wet", amount)),
                )
                val stamped = model.update(slot) { it.copy(source = it.source + mapOf("outside" to Move.ROOM.name)) }
                Outcome(stamped.copy(recipe = outcome.pad.recipe), move, lagMs, confidence, false)
            }
        }
    }

    private fun outsideBlock(move: String, lagMs: Float, confidence: Float, inverted: Boolean, knob: String, amount: Float) =
        JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "move" to JsonValue.Str(move),
                "lagMs" to JsonValue.Num(lagMs.toDouble()),
                "confidence" to JsonValue.Num(confidence.toDouble()),
                "inverted" to JsonValue.Bool(inverted),
                knob to JsonValue.Num(amount.toDouble()),
            ),
        )

    /** What an outside pad carries, read from the recipe. */
    data class Applied(val move: String, val lagMs: Float, val confidence: Float, val inverted: Boolean)

    /**
     * Read defensively: REAMP leaves `{"outside": {…}}` at the top, ROOM
     * leaves it inside `{"mutate": {…, "outside": {…}}}`; any other recipe
     * reads as never having been outside.
     */
    fun read(recipe: JsonValue.Obj?): Applied? {
        val top = recipe?.entries?.get("outside") as? JsonValue.Obj
        val inMutate = (recipe?.entries?.get("mutate") as? JsonValue.Obj)?.entries?.get("outside") as? JsonValue.Obj
        val o = top ?: inMutate ?: return null
        val move = (o.entries["move"] as? JsonValue.Str)?.value ?: return null
        val lag = (o.entries["lagMs"] as? JsonValue.Num)?.value?.toFloat() ?: 0f
        val confidence = (o.entries["confidence"] as? JsonValue.Num)?.value?.toFloat() ?: 0f
        val inverted = (o.entries["inverted"] as? JsonValue.Bool)?.value == true
        return Applied(move.uppercase(), lag, confidence, inverted)
    }

    /** "23 MS LATE · 87% SURE", with the flip named when there was one — the card's status line. */
    fun statusLine(applied: Applied): String {
        val flip = if (applied.inverted) " · FLIPPED BACK" else ""
        return "${applied.move} · ${applied.lagMs.roundToInt()} MS LATE · ${(applied.confidence * 100).roundToInt()}% SURE$flip"
    }

    /** Back inside: the original out of the bin, the recipe and the stamps cleared. */
    fun undo(model: KitBuilderModel, slot: Int): KitPad {
        model.untreatPad(slot)
        return model.update(slot) { it.copy(source = it.source - "outside" - "mutatedWith") }
    }
}
