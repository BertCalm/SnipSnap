package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A board: the pedals on one pad, each with its own AMT and a side. Saved
 * under `pedals` on a [PadRecipe] (never `board`, which is WIDE's) and
 * rendered as up to three [FxChain] passes around the amp: the PRE picks
 * folded into one chain in the rack's order, then the pre-amp end fade and
 * the AMP pick, then the POST picks folded likewise. Within a pass a later
 * pick wins a section both set. The board owns one tail budget over the
 * original ([FxChain.MAX_CHAIN_TAIL_SECONDS]), as WIDE does, because each
 * pass caps only its own.
 *
 * A board with every pick on one side renders sample for sample as
 * [Treatments.apply] of the folded chain: a one-pick board is exactly a
 * chip, which ties this type to every existing character golden.
 *
 * Picks are saved in the order they were made. This round renders by side
 * then rack order and ignores that order within a side; the format carries
 * it so a later round can honour it without a migration.
 */
data class PedalBoard(val picks: List<Pick>) {

    /** One pedal on the board. */
    data class Pick(val key: String, val amount: Float, val side: PedalSide) {
        val entry: Pedals.Entry get() = Pedals.byKey(key)

        init {
            require(amount.isFinite() && amount in 0f..1f) { "a pick's amount is 0..1, got $amount" }
            val e = Pedals.byKey(key)
            require(side in e.sides) { "${e.word} cannot sit on the ${side.id} side - it sits ${e.sides.joinToString(" or ") { it.id }}" }
        }

        /** A pick at an entry's default side. */
        constructor(key: String, amount: Float = 1f) : this(key, amount, Pedals.byKey(key).side)

        fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
            linkedMapOf(
                "key" to JsonValue.Str(key),
                "amount" to JsonValue.Num(amount.toDouble()),
                "side" to JsonValue.Str(side.id),
            ),
        )
    }

    init {
        require(picks.isNotEmpty()) { "a board holds at least one pedal" }
        require(picks.size <= MAX_PICKS) { "a board holds at most $MAX_PICKS pedals, got ${picks.size}" }
        require(picks.map { it.key }.distinct().size == picks.size) { "a pedal is on the board twice" }
    }

    /** Every pick at AMT 0: the board does nothing. */
    val isBypass: Boolean get() = picks.all { it.amount <= 0f }

    /** The picks on [side] that do something, in the order they were made. */
    fun on(side: PedalSide): List<Pick> = picks.filter { it.side == side && it.amount > 0f }

    /**
     * One pass: every pick on [side] folded into one chain, rack order
     * inside it, a later pick winning a shared section. Null when the side
     * is empty.
     */
    fun chainFor(side: PedalSide): FxChain? {
        val live = on(side)
        if (live.isEmpty()) return null
        var out = FxChain()
        for (pick in live) {
            val c = pick.entry.chainAt(pick.amount)
            if (c.reverse) out = out.copy(reverse = true)
            for (name in FxChain.SECTION_NAMES) c.section(name)?.let { out = out.withSection(name, it) }
        }
        return out
    }

    /** The pick that owns a section in [side]'s pass (the later of those that set it), or null. */
    fun owner(side: PedalSide, section: String): Pick? = on(side).lastOrNull { it.entry.chain.section(section) != null }

    fun process(snip: Snip): Snip {
        if (isBypass || snip.frameCount == 0) return snip
        var out = snip
        chainFor(PedalSide.PRE)?.let { out = it.process(out) }
        chainFor(PedalSide.AMP)?.let { amp ->
            val full = owner(PedalSide.AMP, "valve")?.entry?.chain ?: amp
            out = amp.process(Treatments.endFaded(out, amp, full))
        }
        chainFor(PedalSide.POST)?.let { out = it.process(out) }
        return capTail(snip, out)
    }

    /**
     * The board around a synth pad's own landing chain, rendered from its [dry] patch: the PRE picks,
     * then the pre-amp end fade and the AMP pick (a drive pedal into the amp), then [landing] (the
     * pad's amp and whatever it lands through), then the POST picks. This is the rig way: on a guitar
     * pad the wah and the compressor reach the amp instead of following it
     * (docs/superpowers/specs/2026-10-27-pedals-in-front-of-the-amp-design.md). With no landing chain
     * it is [process] on the dry sound, and a bypassed board is the landing chain alone.
     */
    fun processAround(dry: Snip, landing: FxChain?): Snip {
        if (landing == null || landing.isBypass) return process(dry)
        if (isBypass || dry.frameCount == 0) return landing.process(dry)
        var out = dry
        chainFor(PedalSide.PRE)?.let { out = it.process(out) }
        chainFor(PedalSide.AMP)?.let { amp ->
            val full = owner(PedalSide.AMP, "valve")?.entry?.chain ?: amp
            out = amp.process(Treatments.endFaded(out, amp, full))
        }
        out = landing.process(out)
        chainFor(PedalSide.POST)?.let { out = it.process(out) }
        return capTail(dry, out)
    }

    /** The board's own budget: never more than [FxChain.MAX_CHAIN_TAIL_SECONDS] over the original, faded so the cut never clicks. */
    private fun capTail(input: Snip, output: Snip): Snip {
        val maxFrames = input.frameCount + (FxChain.MAX_CHAIN_TAIL_SECONDS * output.sampleRate).toInt()
        if (output.frameCount <= maxFrames) return output
        val out = output.samples.copyOfRange(0, maxFrames * output.channels)
        Dsp.fadeTail(out, ms = 10f, rate = output.sampleRate, channels = output.channels)
        return Snip(out, output.channels, output.sampleRate)
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "pedals" to JsonValue.Num(VERSION.toDouble()),
            "picks" to JsonValue.Arr(picks.map { it.toJsonValue() }),
        ),
    )

    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        const val VERSION = 1

        /** The vision's board: five or six pedals. A refusal in words above it. */
        const val MAX_PICKS = 6

        /** A one-pick board for an old `treatment` recipe whose name is a pedal's key, on its default side; null for any other name. */
        fun fromCharacter(name: String, amount: Float): PedalBoard? {
            val entry = Pedals.entries.firstOrNull { it.key == name } ?: return null
            return PedalBoard(listOf(Pick(entry.key, amount, entry.side)))
        }

        fun fromJsonValue(value: JsonValue): PedalBoard {
            val obj = value.obj()
            val fields = setOf("pedals", "picks")
            if (obj.keys != fields) throw JsonException(
                "a pedal board requires exactly pedals and picks; missing ${fields - obj.keys}, unknown ${obj.keys - fields}",
            )
            val version = obj.getValue("pedals").int()
            if (version != VERSION) throw JsonException("unsupported pedals version $version")
            val picks = (obj.getValue("picks") as? JsonValue.Arr)?.items
                ?: throw JsonException("a pedal board's picks are a list")
            return try {
                PedalBoard(
                    picks.map { p ->
                        val o = p.obj()
                        val pickFields = setOf("key", "amount", "side")
                        if (o.keys != pickFields) throw JsonException(
                            "a pick requires exactly key, amount and side; missing ${pickFields - o.keys}, unknown ${o.keys - pickFields}",
                        )
                        val amount = o.getValue("amount").num()
                        if (!amount.isFinite() || amount !in 0.0..1.0) throw JsonException("a pick's amount is 0..1, got $amount")
                        Pick(o.getValue("key").str(), amount.toFloat(), PedalSide.fromId(o.getValue("side").str()))
                    },
                )
            } catch (e: IllegalArgumentException) {
                throw JsonException(e.message ?: "bad pedal board")
            }
        }

        fun fromJsonText(text: String): PedalBoard = fromJsonValue(Json.parse(text))
    }
}
