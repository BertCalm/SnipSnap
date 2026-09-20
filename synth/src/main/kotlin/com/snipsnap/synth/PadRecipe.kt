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
) {
    init {
        require(patch != null || fx != null) { "an empty recipe records nothing" }
    }

    /** Regenerate a synth pad's audio from scratch. */
    fun render(): Snip {
        val p = requireNotNull(patch) { "recipe has no patch to render; use process() on the captured audio" }
        val dry = p.render()
        return fx?.process(dry) ?: dry
    }

    /** Re-treat captured audio with the stored chain. */
    fun process(snip: Snip): Snip = fx?.process(snip) ?: snip

    fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap<String, JsonValue>()
        obj["recipe"] = JsonValue.Num(VERSION.toDouble())
        patch?.let { obj["patch"] = it.toJsonValue() }
        fx?.let { obj["fx"] = it.toJsonValue() }
        treatment?.let { obj["treatment"] = JsonValue.Str(it) }
        amount?.let { obj["amount"] = JsonValue.Num(it.toDouble()) }
        obj["alias"] = JsonValue.Bool(alias)
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
         * WAVs were regenerated alongside this bump.
         */
        const val VERSION = 2

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
            if (version != VERSION) throw JsonException("unsupported recipe version $version")
            val patch = obj["patch"]?.let { Patches.fromJsonValue(it) }
            val fx = obj["fx"]?.let { FxChain.fromJsonValue(it) }
            return PadRecipe(
                patch = patch,
                fx = fx,
                treatment = obj["treatment"]?.str(),
                amount = obj["amount"]?.num()?.toFloat(),
                alias = obj["alias"]?.bool() ?: impliesAlias(patch, fx),
            )
        }

        fun fromJsonText(text: String): PadRecipe = fromJsonValue(Json.parse(text))
    }
}
