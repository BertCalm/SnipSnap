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
     * came from as soon as AMT leaves 100. Bank B's treatment tag and the pad
     * sheet's selected segment both read this.
     */
    val treatment: String? = null,
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
        return JsonValue.Obj(obj)
    }

    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        const val VERSION = 1

        fun fromJsonValue(value: JsonValue): PadRecipe {
            val obj = value.obj()
            val version = obj["recipe"]?.int() ?: throw JsonException("not a pad recipe: no recipe version")
            if (version != VERSION) throw JsonException("unsupported recipe version $version")
            return PadRecipe(
                patch = obj["patch"]?.let { Patches.fromJsonValue(it) },
                fx = obj["fx"]?.let { FxChain.fromJsonValue(it) },
                treatment = obj["treatment"]?.str(),
            )
        }

        fun fromJsonText(text: String): PadRecipe = fromJsonValue(Json.parse(text))
    }
}
