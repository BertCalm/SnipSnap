package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.PadRecipe
import com.snipsnap.xpm.PadNoteMap

/**
 * DO IT AGAIN: COPY LAST TREATMENT off one pad, PASTE it on another —
 * "do to this snare what I did to that one". Every rewritten pad carries
 * [KitPad.recipe], opaque JSON the kit layer round-trips verbatim; the
 * engines have always promised it makes the sound "re-treatable". This
 * cashes the part of that promise that can be cashed, and refuses the
 * rest BY NAME, never by silently skipping a step.
 *
 * [plan] decides first, from the recipe's top-level key, before any byte
 * moves; [apply] then walks through the model's own doors (`eraPad`,
 * `characterPad`, `keyedPad`, `smearPad`, `replaceAudio`, `Robin.apply`)
 * so every destination guard — layered pads, chained pads, the keyed
 * family's own refusals — stays exactly where it already lives.
 *
 * The recipe is the LAST step, not the stack: `treatPad` and
 * `rewriteEveryFile` both overwrite it, and the undo doors set it null
 * even while the bin still holds earlier takes. A pad that was crushed
 * *then* washed copies as "washed" alone, and the button says so
 * (COPY LAST TREATMENT, [Copy.REPLAY_LAST_ONLY]).
 */
object RecipeReplay {

    /** What a copy holds: the recipe itself, its one-line name (the way SINCE T3 names it), and the pad it came off. */
    data class Clip(val recipe: JsonValue.Obj, val word: String, val from: String)

    /** COPY: [recipe] as a [Clip] labelled "KIT A02", or null when the pad carries nothing to copy. */
    fun clip(recipe: JsonValue.Obj?, kitName: String, slot: Int): Clip? =
        recipe?.let { Clip(it, KitDiff.recipeName(it), "${kitName.uppercase()} ${PadNoteMap.labelForPad(slot)}") }

    /** What replaying a recipe would do — decided before anything is touched. */
    sealed class Plan {
        data class Era(val era: String, val amount: Float) : Plan()
        data class Character(val name: String, val amount: Float) : Plan()
        /** [sourceKey] is the key label the recipe was written in — a label, not an input: the destination kit's own key is what plays. */
        data class KeyedPlan(val name: String, val amount: Float, val seed: Long, val dials: Keyed.Dials, val sourceKey: String?) : Plan()
        data class Smear(val amount: Float) : Plan()
        /** DUST: replayable when [tape]'s print can be found — [apply] needs a way to that print. */
        data class Dust(val amount: Float, val tape: String) : Plan()
        /** A synth patch: replaying it REPLACES the destination's sound with the patch's own render. */
        data class Patch(val recipe: PadRecipe) : Plan()
        /** Round robin: complete, but the deal mixes the seed with the slot — same recipe, new deal. */
        data class RobinDeal(val takes: Int, val seed: Int, val zones: Int?) : Plan()
        /** Refused, in the words the toast says. */
        data class Refused(val reason: String) : Plan()
    }

    /**
     * Read [recipe] the way its writer wrote it. Refusals come first,
     * because the recipes that only *name* something the replay would
     * need (a parent pad, two bin takes, a room, a measurement of that
     * exact sound) must never fall through to a door that would happily
     * do something else with their `amount`.
     */
    fun plan(recipe: JsonValue.Obj?): Plan {
        if (recipe == null) return Plan.Refused(Copy.REPLAY_NOTHING)
        val e = recipe.entries
        MutateSheet.read(recipe)?.let { m ->
            return Plan.Refused(Copy.replayNeedsParent(m.word, m.parents.map { PadSheetBoxes.parentName(it) }))
        }
        OutsideSheet.read(recipe)?.let { return Plan.Refused(Copy.REPLAY_OUTSIDE) }
        if ("splice" in e) return Plan.Refused(Copy.REPLAY_SPLICE)
        if ("clean" in e) return Plan.Refused(Copy.replayMeasured("CLEAN"))
        if ("doctor" in e) return Plan.Refused(Copy.replayMeasured("THE DOCTOR"))
        if ("sculpt" in e) return Plan.Refused(Copy.replayMeasured("SCULPT"))
        (e["verb"] as? JsonValue.Str)?.value?.let { verb ->
            PadSheet.readSmear(recipe)?.let { if (verb == "smear") return Plan.Smear(it) }
            PadSheet.readDust(recipe)?.let { if (verb == "dust") return Plan.Dust(it.amount, it.tape) }
            return Plan.Refused(Copy.REPLAY_NO_DOOR)
        }
        if ("patch" in e) {
            val parsed = runCatching { PadRecipe.fromJsonValue(recipe) }.getOrNull()
            return if (parsed?.patch != null) Plan.Patch(parsed) else Plan.Refused(Copy.REPLAY_NO_DOOR)
        }
        PadSheet.read(recipe)?.let { applied ->
            return when (val t = applied.treatment) {
                is PadSheet.Treatment.Era -> Plan.Era(t.name, applied.amount)
                is PadSheet.Treatment.Character -> Plan.Character(t.name, applied.amount)
                is PadSheet.Treatment.Keyed -> {
                    val defaults = Keyed.Dials()
                    Plan.KeyedPlan(
                        name = t.name,
                        amount = applied.amount,
                        seed = (e["seed"] as? JsonValue.Num)?.value?.toLong() ?: 0L,
                        dials = Keyed.Dials(
                            decay = (e["decay"] as? JsonValue.Num)?.value?.toFloat() ?: defaults.decay,
                            division = (e["division"] as? JsonValue.Str)?.value ?: defaults.division,
                            tail = (e["tail"] as? JsonValue.Num)?.value?.toFloat() ?: defaults.tail,
                            knee = (e["knee"] as? JsonValue.Num)?.value?.toFloat() ?: defaults.knee,
                        ),
                        sourceKey = (e["key"] as? JsonValue.Str)?.value,
                    )
                }
            }
        }
        (e["robin"] as? JsonValue.Obj)?.let { r ->
            val takes = (r.entries["takes"] as? JsonValue.Num)?.value?.toInt() ?: return Plan.Refused(Copy.REPLAY_NO_DOOR)
            val seed = (r.entries["seed"] as? JsonValue.Num)?.value?.toInt() ?: 0
            val zones = (r.entries["zones"] as? JsonValue.Num)?.value?.toInt()
            return Plan.RobinDeal(takes, seed, zones)
        }
        return Plan.Refused(Copy.REPLAY_NO_DOOR)
    }

    /** What PASTE did: the pad as it is now, and the toast that says so. */
    data class Done(val pad: KitPad, val toast: String)

    /**
     * PASTE: replay [recipe] on [slot] through the model's own doors.
     * Saves are the caller's job, as everywhere in the model. A
     * [Plan.Refused] recipe throws an [IllegalArgumentException] carrying
     * its reason; the doors' own refusals (`Unpitched`, a layered pad)
     * propagate untouched. [padName] is only for the toast. [prints]
     * finds a tape's dust by name for a [Plan.Dust] (the app hands in
     * `DustPrints.forTape` over its shelf); without one, or when the tape
     * is gone, a dust recipe refuses with [Copy.dustTapeGone].
     */
    fun apply(
        model: KitBuilderModel,
        slot: Int,
        recipe: JsonValue.Obj,
        padName: String,
        prints: ((String) -> com.snipsnap.audio.Dust.Print?)? = null,
    ): Done {
        val word = KitDiff.recipeName(recipe)
        return when (val p = plan(recipe)) {
            is Plan.Refused -> throw IllegalArgumentException(p.reason)
            is Plan.Era -> Done(model.eraPad(slot, p.era, p.amount), Copy.replayed(word, padName))
            is Plan.Character -> Done(model.characterPad(slot, p.name, p.amount), Copy.replayed(word, padName))
            is Plan.KeyedPlan -> {
                val pad = model.keyedPad(slot, p.name, p.amount, p.seed, p.dials)
                val key = model.lastKeyLabel
                // The stored key is a label of what the SOURCE rang in; the
                // destination kit's own key is what this pad rings in now.
                // Named when they differ, because that is the one surprise.
                val toast = if (p.sourceKey != null && p.sourceKey != key) {
                    Copy.replayedInKey(word, padName, key, p.sourceKey)
                } else {
                    Copy.replayedInKey(word, padName, key, null)
                }
                Done(pad, toast)
            }
            is Plan.Smear -> Done(model.smearPad(slot, p.amount), Copy.replayed(word, padName))
            is Plan.Dust -> {
                val print = prints?.invoke(p.tape) ?: throw IllegalArgumentException(Copy.dustTapeGone(p.tape))
                Done(model.dustPad(slot, p.amount, p.tape, print), Copy.replayed(word, padName))
            }
            is Plan.Patch -> Done(
                model.replaceAudio(slot, p.recipe.toJsonValue()) { p.recipe.render() },
                Copy.replayedPatch(padName),
            )
            is Plan.RobinDeal -> Done(
                Robin.apply(model, slot, takes = p.takes, seed = p.seed, zones = p.zones),
                Copy.replayedRobin(p.takes, padName),
            )
        }
    }
}
