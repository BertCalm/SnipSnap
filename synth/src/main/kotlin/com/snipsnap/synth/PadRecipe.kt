package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A pad's whole recipe: how its WAV came to be, and how to make it again.
 *
 * Two shapes, one format:
 * - a **synth pad** stores a [Patch] (and optionally an [FxChain]) —
 *   [render] regenerates the WAV from scratch, bit-for-bit;
 * - a **captured pad** stores only the [FxChain] — the raw capture is the
 *   source of truth, [process] re-treats it.
 *
 * This is what goes into `kit.json`'s per-pad `recipe` field (opaque to the
 * `:kit` layer, typed here): the reason a kit stays editable forever instead
 * of freezing into a folder of anonymous WAVs.
 */
data class PadRecipe(
    val patch: Patch? = null,
    val fx: FxChain? = null,
    /**
     * Which named treatment produced [fx], when one did.
     *
     * Stored rather than derived: [Treatments.chain] scales a chain's macros
     * by AMT, so a treated pad's chain stops equalling the table entry it
     * came from as soon as the amount leaves 1.0. Bank B's treatment tag and
     * the pad sheet's selected segment both read this.
     */
    val treatment: String? = null,
    /**
     * The AMT that produced [fx], 0..1, when a named treatment did.
     *
     * Stored beside the name for the same reason: both `Treatments.chain` and
     * `Eras.process` scale by this, so the resulting chain cannot be run
     * backwards to recover the amount that made it. This field serves the
     * `Treatments` path (`treatPad`, and bank-B twins) — the pad sheet's
     * TREATMENT card goes through `Eras`, which keeps its own `{"era",
     * "amount"}` recipe.
     */
    val amount: Float? = null,
    /**
     * Whether this pad's aliasing is deliberate (docs/SYNTH_UPGRADE.md, U6).
     *
     * Every engine already renders through the U6 oversample/decimate path
     * regardless of this flag - it changes nothing about [render]. It is
     * pure metadata: a record of whether the grit in this pad's sound is
     * the point (VELVET `CHIP`, anything feeding [Crunch]) rather than an
     * artifact nobody chose. Defaults to that computation so kit generators
     * get it right without having to say so explicitly at every call site;
     * pass it directly to override.
     */
    val alias: Boolean = impliesAlias(patch, fx),
    /** An ordered compound board after the source's existing finishing rack. */
    val board: BoardRecipe? = null,
    /**
     * Optional delivery fade for a periodic source placed on a one-shot pad. This
     * changes only the end of the delivered audio, after the source and rack have
     * rendered; the saved instrument and its seamless HOLD buffer stay untouched.
     * Null preserves existing recipes and their exact audio. Duration is milliseconds.
     */
    val terminalFadeMs: Float? = null,
    /**
     * The pedals on this pad, each with its own AMT and a side of the amp
     * ([PedalBoard]): the pad sheet's library board. Saved under `pedals`,
     * never `board` (WIDE's). Enough to re-render on its own, so unlike
     * [treatment] and [amount] nothing is stored twice. Runs after [fx] and
     * before [board]. Null preserves existing recipes and their exact audio.
     */
    val pedals: PedalBoard? = null,
    /**
     * A velocity-layer bank, bottom to top, DRY first: one pad sample turned into
     * several layers, each its own finishing chain ([LayerRecipe]). The source is the
     * DRY layer, so [processBank] never runs [process] on it first. Null preserves
     * existing recipes and their exact audio.
     */
    val layers: List<LayerRecipe>? = null,
) {
    init {
        require(patch != null || fx != null || board != null || pedals != null || !layers.isNullOrEmpty()) {
            "an empty recipe records nothing"
        }
        layers?.let { require(it.isNotEmpty()) { "a bank has at least one layer" } }
        terminalFadeMs?.let {
            require(it.isFinite() && it in 1f..100f) { "terminal fade must be finite and within 1..100 ms" }
        }
    }

    /**
     * Regenerate a synth pad's audio from scratch. With [pedals], the board is rendered around the pad's own
     * landing chain ([PedalBoard.processAround]): the pedals in front of the amp, the POST picks after it. A
     * recipe holds a patch and pedals together only since the pedal door kept the patch (2026-10-27), so no
     * recipe saved before renders differently.
     */
    fun render(): Snip {
        val p = requireNotNull(patch) { "recipe has no patch to render; use process() on the captured audio" }
        val dry = p.render()
        val board = pedals ?: return process(dry)
        val pedalled = board.processAround(dry, fx)
        return terminalFade(this.board?.process(pedalled) ?: pedalled)
    }

    /** Re-treat captured audio with the stored chain. */
    fun process(snip: Snip): Snip {
        val finished = fx?.process(snip) ?: snip
        val pedalled = pedals?.process(finished) ?: finished
        return terminalFade(board?.process(pedalled) ?: pedalled)
    }

    /** The bank's layers from [render]'s audio, bottom to top. */
    fun renderBank(): List<Snip> = processBank(render())

    /** One snip per layer, bottom to top, DRY first. The [source] is the DRY layer. */
    fun processBank(source: Snip): List<Snip> = layers.orEmpty().map { l ->
        val wet = l.fx?.process(source) ?: source
        l.terminalFadeMs?.let { ms -> fadeTail(wet, ms) } ?: wet
    }

    private fun terminalFade(snip: Snip): Snip = terminalFadeMs?.let { fadeTail(snip, it) } ?: snip

    private fun fadeTail(snip: Snip, ms: Float): Snip {
        val samples = snip.samples.copyOf()
        Dsp.fadeTail(samples, ms = ms, rate = snip.sampleRate, channels = snip.channels)
        return snip.copy(samples = samples)
    }

    fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap<String, JsonValue>()
        obj["recipe"] = JsonValue.Num(VERSION.toDouble())
        patch?.let { obj["patch"] = it.toJsonValue() }
        fx?.let { obj["fx"] = it.toJsonValue() }
        treatment?.let { obj["treatment"] = JsonValue.Str(it) }
        amount?.let { obj["amount"] = JsonValue.Num(it.toDouble()) }
        obj["alias"] = JsonValue.Bool(alias)
        board?.let { obj["board"] = it.toJsonValue() }
        terminalFadeMs?.let { obj["terminalFadeMs"] = JsonValue.Num(it.toDouble()) }
        pedals?.let { obj["pedals"] = it.toJsonValue() }
        layers?.let { obj["layers"] = JsonValue.Arr(it.map(LayerRecipe::toJsonValue)) }
        return JsonValue.Obj(obj)
    }

    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        /**
         * U3+U5+U6 (docs/SYNTH_UPGRADE.md): Punch, `Dsp.Env`, filter
         * saturation and 4x oversampling all change what every engine
         * renders for the same patch, and [alias] is new — there is no
         * migration path for a v1 recipe, by design (see the doc's own
         * "take the clean break now" recommendation). `testkit/`'s 369
         * WAVs were regenerated alongside this bump. Version 3 added [layers]
         * and is a pure superset: version 2 files still read.
         */
        const val VERSION = 3

        /**
         * The [alias] default: true where grit is deliberately the point
         * (docs/SYNTH_UPGRADE.md's U6 alias-flag section) - VELVET `CHIP`,
         * or any chain feeding [Crunch]. `Eras`-produced audio keeps its
         * own separate `{"era","amount"}` recipe (see [amount]'s doc), so
         * it never reaches this shape at all.
         */
        private fun impliesAlias(patch: Patch?, fx: FxChain?): Boolean =
            (patch is VelvetPatch && patch.voice == VelvetVoice.CHIP) || fx?.crunch != null

        fun fromJsonValue(value: JsonValue): PadRecipe {
            val obj = value.obj()
            val version = obj["recipe"]?.int() ?: throw JsonException("not a pad recipe: no recipe version")
            if (version != 2 && version != VERSION) throw JsonException("unsupported recipe version $version")
            val patch = obj["patch"]?.let { Patches.fromJsonValue(it) }
            val fx = obj["fx"]?.let { FxChain.fromJsonValue(it) }
            return PadRecipe(
                patch = patch,
                fx = fx,
                treatment = obj["treatment"]?.str(),
                amount = obj["amount"]?.num()?.toFloat(),
                alias = obj["alias"]?.bool() ?: impliesAlias(patch, fx),
                board = obj["board"]?.let { BoardRecipe.fromJsonValue(it) },
                terminalFadeMs = obj["terminalFadeMs"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat(),
                pedals = obj["pedals"]?.let { PedalBoard.fromJsonValue(it) },
                layers = obj["layers"]?.takeUnless { it is JsonValue.Null }?.arr()?.map { LayerRecipe.fromJsonValue(it) },
            )
        }

        fun fromJsonText(text: String): PadRecipe = fromJsonValue(Json.parse(text))
    }
}

/** Whether this recipe carries a layer bank. */
val PadRecipe.isBanked: Boolean get() = !layers.isNullOrEmpty()

/** One layer of a [PadRecipe] bank: a name, an optional finishing chain, an optional tail fade (ms). */
data class LayerRecipe(
    val name: String,
    val fx: FxChain? = null,
    val terminalFadeMs: Float? = null,
) {
    init {
        require(name.isNotBlank()) { "a layer needs a name" }
    }

    fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap<String, JsonValue>()
        obj["name"] = JsonValue.Str(name)
        fx?.let { obj["fx"] = it.toJsonValue() }
        terminalFadeMs?.let { obj["terminalFadeMs"] = JsonValue.Num(it.toDouble()) }
        return JsonValue.Obj(obj)
    }

    companion object {
        fun fromJsonValue(value: JsonValue): LayerRecipe {
            val obj = value.obj()
            return LayerRecipe(
                name = obj["name"]?.str() ?: throw JsonException("not a layer: no name"),
                fx = obj["fx"]?.let { FxChain.fromJsonValue(it) },
                terminalFadeMs = obj["terminalFadeMs"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat(),
            )
        }
    }
}
