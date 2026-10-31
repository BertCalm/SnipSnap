package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * The per-pad effects rack. Order is fixed and not negotiable:
 *
 *    PITCH → SWELL → REVERSE → SMEAR → GHOST → SPIKE → EQ → CONTOUR → WAH → COMPRESSOR → SQUASH → FORGE → VALVE → CRUNCH → RING → DUB → VINYL → TAPE → ENSEMBLE → PHASE → ECHO → SPRING → ROOM → MOTION
 *
 * contour after EQ and before the dynamics, so SQUASH tames the resonant
 * peak rather than the peak riding over the squash; ensemble after tape and
 * before phase, because the copies are made of the finished tone and the
 * sweep and the repeats then carry all three.
 *
 * wah after contour and before squash: a wah is the first pedal on the
 * board, a filter in front of the compressor and the amp, and the
 * compressor then levels what the sweep did to the note.
 *
 * compressor after wah and before squash: the sustain pedal sits where a
 * guitar board puts it (wah, compressor, drive, amp), and SQUASH, the
 * sampler's punch, still has the last word on the hit before the amp.
 *
 * forge after squash and before valve: the hit is formed once its dynamics
 * are set (FORGE has no gain envelope of its own, so SQUASH's punch is what it
 * coins), and the amp then colours the formed blank. Its ceiling is relative
 * to the pad's peak, so the order against SQUASH changes what is coined, not
 * how loud it lands.
 *
 * valve after forge and before crunch: a compressor feeds an amp, and the
 * sampler's damage happens to a sound that already existed.
 *
 * room after spring and before motion: a spring tank is a device, and the
 * room is where everything, the tank included, is heard. ROOM is the last
 * space, and the tape still stops with the room on it.
 *
 * Pitch before everything, because in a sampler pitch *is* the transport:
 * SWELL's stretched head, REVERSE's flip and the whole rack all see the
 * pitched sound, since that is what a sampler plays. Swell next, so the
 * rack sees the arrival and the hit as one sound; reverse after that
 * because you effect the flipped sample, not flip the effected one (the
 * sampling-era way); smear and ghost after that because they decide what
 * the hit *is* before anything decides how it sounds (anatomy before
 * tone); dynamics before character (crunch and dub are the converter's
 * own damage, quantized and honest; ring sits between them as damage of
 * a different species, inharmonic rather than coarse); then vinyl,
 * because a record dubbed to tape needs its surface noise to exist
 * before tape can process it along with everything else; then time,
 * space always last — echoes belong *in* the room — and motion after
 * even that, because the tape stops with the reverb still on it. A fixed
 * order is a playability rule wearing an architecture hat: no routing
 * screen, no wrong answers.
 *
 * A `null` section is a hard bypass. Serializes next to the pad's WAV in
 * `kit.json` so the recipe stays editable forever, same as synth patches.
 *
 * A section may widen a mono snip to stereo (ENSEMBLE does, above its
 * `WIDTH_OFF`) and never narrows one; every section after it in the order runs per
 * channel, so the pair it made reaches the WAV intact.
 */
data class FxChain(
    /** The transport: pitch is speed, and it runs before everything. */
    val speed: Map<String, Float>? = null,
    val reverse: Boolean = false,
    val eq: Map<String, Float>? = null,
    val squash: Map<String, Float>? = null,
    val crunch: Map<String, Float>? = null,
    val ring: Map<String, Float>? = null,
    val tape: Map<String, Float>? = null,
    val phase: Map<String, Float>? = null,
    val echo: Map<String, Float>? = null,
    val spring: Map<String, Float>? = null,
    /** Later in the parameter list (they arrived later) than in the rack: see the order above. */
    val smear: Map<String, Float>? = null,
    val ghost: Map<String, Float>? = null,
    val spike: Map<String, Float>? = null,
    val motion: Map<String, Float>? = null,
    val dub: Map<String, Float>? = null,
    val vinyl: Map<String, Float>? = null,
    val swell: Map<String, Float>? = null,
    val contour: Map<String, Float>? = null,
    val ensemble: Map<String, Float>? = null,
    val valve: Map<String, Float>? = null,
    val wah: Map<String, Float>? = null,
    val compressor: Map<String, Float>? = null,
    val forge: Map<String, Float>? = null,
    val room: Map<String, Float>? = null,
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

    fun process(snip: Snip): Snip = finish(head(snip))

    /** [snip] through the TRANSPORT and ARRIVAL stages only: the sound the tail budget is measured from. */
    internal fun head(snip: Snip): Snip {
        var head = snip
        for (sec in SECTIONS) {
            if (sec.stage == Stage.RACK) continue
            sec.get(this)?.let { head = sec.run(head, it) }
        }
        return head
    }

    /** The rack over a [head] (what [head] left), then the tail budget measured from it. */
    internal fun finish(head: Snip): Snip = capTail(head, processRest(head))

    private fun processRest(snip: Snip): Snip {
        var s = snip
        if (reverse) s = reversed(s)
        for (sec in SECTIONS) {
            if (sec.stage != Stage.RACK) continue
            sec.get(this)?.let { s = sec.run(s, it) }
        }
        return s
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

        /**
         * Most tail the whole rack may add over its input, seconds —
         * measured from the *pitched, swelled* sound, not the original. A
         * hit pitched down an octave is twice as long by instruction, and
         * that length is not tail; the budget exists to stop ECHO and
         * SPRING running away, not to truncate a note.
         */
        const val MAX_CHAIN_TAIL_SECONDS = 1.0f

        /**
         * How long the budget's cut fades, seconds, on a raised cosine. A trail still sounding at the cut fades away
         * over its last 300 ms instead of stopping in 10 ms: the owner heard DELAY's trail stop on held notes at the DELAY confirm
         * listen (2026-10-26) and chose this fade for every pad (2026-10-27; docs/superpowers/specs/2026-10-22-delay-head-design.md).
         * The fade is the last 300 ms of the one-second budget, measured from the sound as TRANSPORT and ARRIVAL leave
         * it, so it lands after the sound ends unless a RACK section moves the sound later than 0.7 s: MOTION's START
         * can, by up to 0.75 s, which puts at most the sound's last 50 ms in the fade, under 1 dB down.
         */
        const val CAP_FADE_SECONDS = 0.3f

        /** [capTail] measured from [input]'s length. */
        internal fun capTail(input: Snip, output: Snip): Snip = capTail(input.frameCount, output)

        /**
         * The tail budget, one for the rack and the pedal board. ECHO and SPRING each bound their own tail, but stacked
         * they add up: a hit through the full rack grew past the loop threshold and stopped reading as a hit at all. So
         * whatever the sections did, [output] never runs more than [MAX_CHAIN_TAIL_SECONDS] past [soundFrames], the
         * sound's own length, and when it would, its last [CAP_FADE_SECONDS] fade on a raised cosine (per frame, so a
         * stereo frame keeps one gain). An output already inside the budget comes back untouched.
         */
        internal fun capTail(soundFrames: Int, output: Snip): Snip {
            val maxFrames = soundFrames + (MAX_CHAIN_TAIL_SECONDS * output.sampleRate).toInt()
            if (output.frameCount <= maxFrames) return output
            val out = output.samples.copyOfRange(0, maxFrames * output.channels)
            Dsp.fadeTailCosine(out, CAP_FADE_SECONDS, rate = output.sampleRate, channels = output.channels)
            return Snip(out, output.channels, output.sampleRate)
        }

        /** Which pass a section belongs to: the two before the tail budget is measured, and the rack. */
        enum class Stage { TRANSPORT, ARRIVAL, RACK }

        /**
         * One rack section, described rather than hand-written. The list below
         * IS the order — in the signal path and in JSON alike — so the two can
         * no longer drift apart. `process()` follows this declaration order
         * for the pre-[Stage.RACK] stages too, so a [Stage.TRANSPORT] entry
         * must be declared ahead of the [Stage.ARRIVAL] one.
         */
        internal class Section(
            val name: String,
            val macros: List<MacroSpec>,
            val get: (FxChain) -> Map<String, Float>?,
            val with: (FxChain, Map<String, Float>?) -> FxChain,
            val run: (Snip, Map<String, Float>) -> Snip,
            val stage: Stage = Stage.RACK,
            /** The gain law of each [FadeLaw.LOG] macro, by name: what `Treatments.fade` walks in decibels. */
            val gains: Map<String, (Float) -> Float> = emptyMap(),
        )

        internal val SECTIONS: List<Section> = listOf(
            Section("speed", Speed.MACROS, { it.speed }, { c, m -> c.copy(speed = m) }, Speed::process, Stage.TRANSPORT),
            Section("swell", Swell.MACROS, { it.swell }, { c, m -> c.copy(swell = m) }, Swell::process, Stage.ARRIVAL),
            Section("smear", Smear.MACROS, { it.smear }, { c, m -> c.copy(smear = m) }, Smear::process),
            Section("ghost", Ghost.MACROS, { it.ghost }, { c, m -> c.copy(ghost = m) }, Ghost::process),
            Section("spike", Spike.MACROS, { it.spike }, { c, m -> c.copy(spike = m) }, Spike::process),
            Section("eq", Eq.MACROS, { it.eq }, { c, m -> c.copy(eq = m) }, Eq::process),
            Section("contour", Contour.MACROS, { it.contour }, { c, m -> c.copy(contour = m) }, Contour::process),
            Section("wah", Wah.MACROS, { it.wah }, { c, m -> c.copy(wah = m) }, Wah::process),
            Section("compressor", Compressor.MACROS, { it.compressor }, { c, m -> c.copy(compressor = m) }, Compressor::process),
            Section("squash", Squash.MACROS, { it.squash }, { c, m -> c.copy(squash = m) }, Squash::process),
            Section("forge", Forge.MACROS, { it.forge }, { c, m -> c.copy(forge = m) }, Forge::process),
            Section("valve", Valve.MACROS, { it.valve }, { c, m -> c.copy(valve = m) }, Valve::process, gains = mapOf("DRIVE" to Valve::gainFor)),
            Section("crunch", Crunch.MACROS, { it.crunch }, { c, m -> c.copy(crunch = m) }, Crunch::process),
            Section("ring", Ring.MACROS, { it.ring }, { c, m -> c.copy(ring = m) }, Ring::process),
            Section("dub", Dub.MACROS, { it.dub }, { c, m -> c.copy(dub = m) }, Dub::process),
            Section("vinyl", Vinyl.MACROS, { it.vinyl }, { c, m -> c.copy(vinyl = m) }, Vinyl::process),
            Section("tape", Tape.MACROS, { it.tape }, { c, m -> c.copy(tape = m) }, Tape::process),
            Section("ensemble", Ensemble.MACROS, { it.ensemble }, { c, m -> c.copy(ensemble = m) }, Ensemble::process),
            Section("phase", Phase.MACROS, { it.phase }, { c, m -> c.copy(phase = m) }, Phase::process),
            Section("echo", Echo.MACROS, { it.echo }, { c, m -> c.copy(echo = m) }, Echo::process),
            Section("spring", Spring.MACROS, { it.spring }, { c, m -> c.copy(spring = m) }, Spring::process),
            Section("room", Room.MACROS, { it.room }, { c, m -> c.copy(room = m) }, Room::process),
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

        /** The gain law [section] declares for its [FadeLaw.LOG] macro [macro], or null where it declares none. */
        fun gainOf(section: String, macro: String): ((Float) -> Float)? = sectionOf(section).gains[macro]

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
