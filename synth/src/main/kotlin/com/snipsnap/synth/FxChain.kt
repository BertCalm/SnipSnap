package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * The per-pad effects rack. Order is fixed and not negotiable:
 *
 *    SWELL → REVERSE → SMEAR → GHOST → EQ → SQUASH → CRUNCH → DUB → TAPE → ECHO → SPRING → MOTION
 *
 * Swell before everything, so the rack sees the arrival and the hit as
 * one sound; reverse next because you effect the flipped sample, not
 * flip the effected one (the sampling-era way); smear and ghost after
 * that because they decide what the hit *is* before anything decides
 * how it sounds (anatomy before tone); dynamics before character (dub
 * beside crunch: both are the converter's own damage) before time, space
 * always last — echoes belong *in* the room — and motion after even
 * that, because the tape stops with the reverb still on it. A fixed order is
 * a playability rule wearing an architecture hat: no routing screen, no
 * wrong answers.
 *
 * A `null` section is a hard bypass. Serializes next to the pad's WAV in
 * `kit.json` so the recipe stays editable forever, same as synth patches.
 */
data class FxChain(
    val reverse: Boolean = false,
    val eq: Map<String, Float>? = null,
    val squash: Map<String, Float>? = null,
    val crunch: Map<String, Float>? = null,
    val tape: Map<String, Float>? = null,
    val echo: Map<String, Float>? = null,
    val spring: Map<String, Float>? = null,
    /** Later in the parameter list (they arrived later) than in the rack: see the order above. */
    val smear: Map<String, Float>? = null,
    val ghost: Map<String, Float>? = null,
    val motion: Map<String, Float>? = null,
    val dub: Map<String, Float>? = null,
    val swell: Map<String, Float>? = null,
) {
    init {
        for ((name, macros, known) in listOf(
            Triple("smear", smear, Smear.MACROS),
            Triple("ghost", ghost, Ghost.MACROS),
            Triple("motion", motion, Motion.MACROS),
            Triple("dub", dub, Dub.MACROS),
            Triple("swell", swell, Swell.MACROS),
            Triple("eq", eq, Eq.MACROS),
            Triple("squash", squash, Squash.MACROS),
            Triple("crunch", crunch, Crunch.MACROS),
            Triple("tape", tape, Tape.MACROS),
            Triple("echo", echo, Echo.MACROS),
            Triple("spring", spring, Spring.MACROS),
        )) {
            if (macros == null) continue
            val names = known.map { it.name }.toSet()
            for ((k, v) in macros) {
                require(k in names) { "unknown $name macro $k (knows $names)" }
                require(v in 0f..1f) { "$name macro $k out of 0..1: $v" }
            }
        }
    }

    val isBypass: Boolean
        get() = !reverse && swell == null && smear == null && ghost == null && eq == null && squash == null &&
            crunch == null && dub == null && tape == null && echo == null && spring == null && motion == null

    fun process(snip: Snip): Snip {
        // The swell is an arrival, not a tail: the tail budget is measured from the swelled sound.
        val swelled = swell?.let { Swell.process(snip, it) } ?: snip
        return capTail(swelled, processRest(swelled))
    }

    private fun processRest(snip: Snip): Snip {
        var s = snip
        if (reverse) s = reversed(s)
        smear?.let { s = Smear.process(s, it) }
        ghost?.let { s = Ghost.process(s, it) }
        eq?.let { s = Eq.process(s, it) }
        squash?.let { s = Squash.process(s, it) }
        crunch?.let { s = Crunch.process(s, it) }
        dub?.let { s = Dub.process(s, it) }
        tape?.let { s = Tape.process(s, it) }
        echo?.let { s = Echo.process(s, it) }
        spring?.let { s = Spring.process(s, it) }
        motion?.let { s = Motion.process(s, it) }
        return s
    }

    /**
     * ECHO and SPRING each bound their own tail, but stacked they add up —
     * a hit through the full rack grew past the loop threshold and stopped
     * reading as a hit at all. The chain owns the total budget: whatever the
     * sections did, the result never gains more than [MAX_CHAIN_TAIL_SECONDS]
     * over the input, faded so the cut never clicks.
     */
    private fun capTail(input: Snip, output: Snip): Snip {
        val maxFrames = input.frameCount + (MAX_CHAIN_TAIL_SECONDS * output.sampleRate).toInt()
        if (output.frameCount <= maxFrames) return output
        val out = output.samples.copyOfRange(0, maxFrames * output.channels)
        val fade = (0.01f * output.sampleRate).toInt() * output.channels
        for (i in 0 until minOf(fade, out.size)) {
            out[out.size - 1 - i] *= i.toFloat() / fade
        }
        return Snip(out, output.channels, output.sampleRate)
    }

    private fun reversed(snip: Snip): Snip {
        val out = FloatArray(snip.samples.size)
        val frames = snip.frameCount
        for (f in 0 until frames) {
            val src = (frames - 1 - f) * snip.channels
            val dst = f * snip.channels
            for (ch in 0 until snip.channels) out[dst + ch] = snip.samples[src + ch]
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap<String, JsonValue>()
        obj["fx"] = JsonValue.Num(VERSION.toDouble())
        obj["reverse"] = JsonValue.Bool(reverse)
        for ((name, macros) in listOf(
            "swell" to swell, "smear" to smear, "ghost" to ghost, "eq" to eq, "squash" to squash, "crunch" to crunch,
            "dub" to dub, "tape" to tape, "echo" to echo, "spring" to spring, "motion" to motion,
        )) {
            if (macros != null) {
                obj[name] = JsonValue.Obj(
                    macros.entries.associateTo(LinkedHashMap()) { (k, v) -> k to JsonValue.Num(v.toDouble()) },
                )
            }
        }
        return JsonValue.Obj(obj)
    }

    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        const val VERSION = 1

        /** Most tail the whole rack may add over its input, seconds. */
        const val MAX_CHAIN_TAIL_SECONDS = 1.0f

        fun fromJsonValue(value: JsonValue): FxChain {
            val obj = value.obj()
            val version = obj["fx"]?.int() ?: throw JsonException("not an fx chain: no fx version")
            if (version != VERSION) throw JsonException("unsupported fx version $version")
            fun section(name: String): Map<String, Float>? =
                (obj[name] as? JsonValue.Obj)?.entries?.mapValues { (_, v) -> v.num().toFloat() }
            return FxChain(
                reverse = (obj["reverse"] as? JsonValue.Bool)?.value ?: false,
                eq = section("eq"),
                squash = section("squash"),
                crunch = section("crunch"),
                tape = section("tape"),
                echo = section("echo"),
                spring = section("spring"),
                smear = section("smear"),
                ghost = section("ghost"),
                motion = section("motion"),
                dub = section("dub"),
                swell = section("swell"),
            )
        }

        fun fromJsonText(text: String): FxChain = fromJsonValue(Json.parse(text))
    }
}
