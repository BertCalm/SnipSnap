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
        for (s in SECTIONS) {
            val macros = s.get(this) ?: continue
            val names = s.macros.map { it.name }.toSet()
            for ((k, v) in macros) {
                require(k in names) { "unknown ${s.name} macro $k (knows $names)" }
                require(v in 0f..1f) { "${s.name} macro $k out of 0..1: $v" }
            }
        }
    }

    val isBypass: Boolean
        get() = !reverse && SECTIONS.all { it.get(this) == null }

    fun process(snip: Snip): Snip {
        // TRANSPORT then ARRIVAL: the tail budget is measured from what they leave behind.
        var head = snip
        for (sec in SECTIONS) {
            if (sec.stage == Stage.RACK) continue
            sec.get(this)?.let { head = sec.run(head, it) }
        }
        return capTail(head, processRest(head))
    }

    private fun processRest(snip: Snip): Snip {
        var s = snip
        if (reverse) s = reversed(s)
        for (sec in SECTIONS) {
            if (sec.stage != Stage.RACK) continue
            sec.get(this)?.let { s = sec.run(s, it) }
        }
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
        for (sec in SECTIONS) {
            val macros = sec.get(this) ?: continue
            obj[sec.name] = JsonValue.Obj(
                macros.entries.associateTo(LinkedHashMap()) { (k, v) -> k to JsonValue.Num(v.toDouble()) },
            )
        }
        return JsonValue.Obj(obj)
    }

    fun toJsonText(): String = Json.write(toJsonValue())

    /** [name]'s macros on this chain, or null when the section is bypassed. */
    fun section(name: String): Map<String, Float>? = sectionOf(name).get(this)

    /** This chain with [name] set to [macros]; null bypasses the section. */
    fun withSection(name: String, macros: Map<String, Float>?): FxChain = sectionOf(name).with(this, macros)

    companion object {
        const val VERSION = 1

        /** Most tail the whole rack may add over its input, seconds. */
        const val MAX_CHAIN_TAIL_SECONDS = 1.0f

        /** Which pass a section belongs to: the two before the tail budget is measured, and the rack. */
        enum class Stage { TRANSPORT, ARRIVAL, RACK }

        /**
         * One rack section, described rather than hand-written. The list below
         * IS the order — in the signal path and in JSON alike — so the two can
         * no longer drift apart.
         */
        internal class Section(
            val name: String,
            val macros: List<MacroSpec>,
            val get: (FxChain) -> Map<String, Float>?,
            val with: (FxChain, Map<String, Float>?) -> FxChain,
            val run: (Snip, Map<String, Float>) -> Snip,
            val stage: Stage = Stage.RACK,
        )

        internal val SECTIONS: List<Section> = listOf(
            Section("swell", Swell.MACROS, { it.swell }, { c, m -> c.copy(swell = m) }, Swell::process, Stage.ARRIVAL),
            Section("smear", Smear.MACROS, { it.smear }, { c, m -> c.copy(smear = m) }, Smear::process),
            Section("ghost", Ghost.MACROS, { it.ghost }, { c, m -> c.copy(ghost = m) }, Ghost::process),
            Section("eq", Eq.MACROS, { it.eq }, { c, m -> c.copy(eq = m) }, Eq::process),
            Section("squash", Squash.MACROS, { it.squash }, { c, m -> c.copy(squash = m) }, Squash::process),
            Section("crunch", Crunch.MACROS, { it.crunch }, { c, m -> c.copy(crunch = m) }, Crunch::process),
            Section("dub", Dub.MACROS, { it.dub }, { c, m -> c.copy(dub = m) }, Dub::process),
            Section("tape", Tape.MACROS, { it.tape }, { c, m -> c.copy(tape = m) }, Tape::process),
            Section("echo", Echo.MACROS, { it.echo }, { c, m -> c.copy(echo = m) }, Echo::process),
            Section("spring", Spring.MACROS, { it.spring }, { c, m -> c.copy(spring = m) }, Spring::process),
            Section("motion", Motion.MACROS, { it.motion }, { c, m -> c.copy(motion = m) }, Motion::process),
        )

        /**
         * Every section's name, in rack order — the door other modules use.
         * [Section] itself stays internal: `internal` is module-scoped, and
         * `:shell`'s Treatments and Breed live outside this one.
         */
        val SECTION_NAMES: List<String> get() = SECTIONS.map { it.name }

        /** [name]'s macro specs, or an error naming the sections there are. */
        fun macrosOf(name: String): List<MacroSpec> = sectionOf(name).macros

        internal fun sectionOf(name: String): Section =
            SECTIONS.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException(
                    "unknown fx section '$name' - the rack has: ${SECTION_NAMES.joinToString(", ")}",
                )

        fun fromJsonValue(value: JsonValue): FxChain {
            val obj = value.obj()
            val version = obj["fx"]?.int() ?: throw JsonException("not an fx chain: no fx version")
            if (version != VERSION) throw JsonException("unsupported fx version $version")
            var chain = FxChain(reverse = (obj["reverse"] as? JsonValue.Bool)?.value ?: false)
            for (sec in SECTIONS) {
                val macros = (obj[sec.name] as? JsonValue.Obj)?.entries?.mapValues { (_, v) -> v.num().toFloat() }
                    ?: continue
                chain = sec.with(chain, macros)
            }
            return chain
        }

        fun fromJsonText(text: String): FxChain = fromJsonValue(Json.parse(text))
    }
}
