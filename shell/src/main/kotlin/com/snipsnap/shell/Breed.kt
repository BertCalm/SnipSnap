package com.snipsnap.shell

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Patches
import java.io.File
import java.util.Random

/**
 * BREEDING (XX2) — two kits' recipes crossed into a child kit. Pad by
 * pad, kit A's pad meets kit B's pad on the same slot (or B's first pad
 * of the same class) and their recipes cross: every synth macro and
 * every rack macro is, by a seeded coin, A's, B's, or the average of
 * both; a rack section only one parent has comes along half the time;
 * the reverse flag is one parent's. A synth pad re-renders from the
 * crossed patch through the crossed rack; a captured pad is A's own
 * audio through the crossed rack. Pads with nothing to cross come over
 * as they are.
 *
 * **Classifier-audited**: a child must classify as its parent's own
 * audio does — a kick stays a kick — or the coin is thrown again, up to
 * [MAX_TRIES]; a child that never passes is A's pad kept verbatim, and
 * the report says so. Deterministic: same parents, same seed, same kit.
 * The child is a new folder; both parents stay untouched.
 */
object Breed {

    const val MAX_TRIES = 6

    /** What breeding did per slot. */
    data class Report(
        val kit: Kit,
        /** Slots whose recipes crossed. */
        val crossed: List<Int>,
        /** Slots kept as A's own: no partner, or nothing to cross. */
        val kept: List<Int>,
        /** Slots the audit sent back to A's own after every try changed the class. */
        val audited: List<Int>,
    )

    fun breed(aDir: File, bDir: File, destDir: File, name: String = destDir.name, seed: Int = 0): Report {
        require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
        require(!File(destDir, KitStore.FILE_NAME).exists()) { "destination is already a kit: $destDir" }
        val a = KitStore.load(aDir)
        val b = KitStore.load(bDir)
        require(a.pads.isNotEmpty()) { "'${a.name}' has no pads to breed from" }
        require(b.pads.isNotEmpty()) { "'${b.name}' has no pads to breed with" }

        val rng = Random(seed.toLong())
        val model = KitBuilderModel.create(name, destDir)
        model.setKey(a.key)
        model.setTempo(a.tempoBpm)
        val crossed = mutableListOf<Int>()
        val kept = mutableListOf<Int>()
        val audited = mutableListOf<Int>()
        val stamp = mapOf("bredFrom" to "${a.name} x ${b.name}")

        for (pad in a.pads.sortedBy { it.slot }) {
            val aSnip = WavReader.read(File(aDir, pad.sampleFile))
            val partner = partnerOf(pad, b)
            val aRecipe = recipeOf(pad)
            val bRecipe = partner?.let { recipeOf(it) }
            val canCross = partner != null && crossable(aRecipe, bRecipe)
            if (!canCross) {
                place(model, pad, aSnip, pad.recipe, stamp)
                kept += pad.slot
                continue
            }
            val parentClass = Classifier.classify(aSnip).drumClass
            var child: Pair<Snip, PadRecipe>? = null
            repeat(MAX_TRIES) {
                if (child != null) return@repeat
                val recipe = cross(aRecipe, bRecipe, rng)
                val rendered = render(recipe, aSnip) ?: return@repeat
                if (Classifier.classify(rendered).drumClass == parentClass) child = rendered to recipe
            }
            val c = child
            if (c == null) {
                place(model, pad, aSnip, pad.recipe, stamp)
                audited += pad.slot
            } else {
                place(model, pad, c.first, c.second.toJsonValue(), stamp)
                crossed += pad.slot
            }
        }
        model.save()
        return Report(model.kit, crossed, kept, audited)
    }

    /** B's partner for [pad]: the pad on the same slot, else B's first pad of the same class. */
    fun partnerOf(pad: KitPad, b: Kit): KitPad? =
        b.pad(pad.slot) ?: b.pads.sortedBy { it.slot }.firstOrNull { it.drumClass == pad.drumClass }

    /**
     * Whether the coin has anything to throw for: A's own patch or rack,
     * or B's rack over A's audio. B's patch alone is not enough — a
     * captured pad has no engine to render B's patch through.
     */
    private fun crossable(aRecipe: PadRecipe?, bRecipe: PadRecipe?): Boolean =
        (aRecipe != null || bRecipe != null) && (aRecipe?.patch != null || aRecipe?.fx != null || bRecipe?.fx != null)

    /**
     * The slots of [a] that would cross with [b] — what [breed] will do,
     * before it does it, so the KIT screen can refuse a pick that would
     * only copy A. The audit may still send some of these back; every
     * slot [breed] reports as crossed or audited is in this list, and
     * nothing else is.
     */
    fun crossable(a: Kit, b: Kit): List<Int> =
        a.pads.sortedBy { it.slot }
            .filter { pad -> partnerOf(pad, b)?.let { crossable(recipeOf(pad), recipeOf(it)) } == true }
            .map { it.slot }

    /**
     * The slots of [kit] carrying something BREED can cross from this
     * side: a synth patch or a rack. The BREED button's readout — a
     * kit of plain captures counts zero here, which is the one
     * number that explains "0 PADS CROSSED" before it happens.
     */
    fun recipePads(kit: Kit): List<Int> =
        kit.pads.sortedBy { it.slot }
            .filter { pad -> recipeOf(pad)?.let { it.patch != null || it.fx != null } == true }
            .map { it.slot }

    /** A pad's recipe as the synth's own shape, or null for anything else (an era, a mutate, a keyed treatment, none). */
    fun recipeOf(pad: KitPad): PadRecipe? {
        val r = pad.recipe ?: return null
        return try {
            PadRecipe.fromJsonValue(r)
        } catch (e: JsonException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The coin: A's, B's, or the average. */
    private fun pick(x: Float?, y: Float?, rng: Random): Float? {
        if (x == null) return y
        if (y == null) return x
        return when (rng.nextInt(3)) {
            0 -> x
            1 -> y
            else -> (x + y) / 2f
        }
    }

    private fun crossMacros(x: Map<String, Float>?, y: Map<String, Float>?, rng: Random): Map<String, Float>? {
        if (x == null && y == null) return null
        // A section only one parent has comes along half the time.
        if (x == null || y == null) return if (rng.nextBoolean()) (x ?: y) else null
        val names = (x.keys + y.keys).toList().sorted()
        return names.associateWith { n -> pick(x[n], y[n], rng)!!.coerceIn(0f, 1f) }
    }

    /** The child's recipe: A's patch with crossed macros when the engines agree, and the crossed rack. */
    internal fun cross(a: PadRecipe?, b: PadRecipe?, rng: Random): PadRecipe {
        val patch: Patch? = a?.patch?.let { ap ->
            val bp = b?.patch
            if (bp != null && bp.engine == ap.engine && bp.voiceName == ap.voiceName) {
                val names = (ap.macros.keys + bp.macros.keys).toList().sorted()
                val macros = names.associateWith { n -> pick(ap.macros[n], bp.macros[n], rng)!!.coerceIn(0f, 1f) }
                withMacros(ap, macros)
            } else {
                ap
            }
        }
        val fa = a?.fx
        val fb = b?.fx
        val fx = if (fa == null && fb == null) {
            null
        } else {
            FxChain(
                reverse = if (fa != null && fb != null) (if (rng.nextBoolean()) fa.reverse else fb.reverse) else (fa?.reverse ?: fb!!.reverse),
                eq = crossMacros(fa?.eq, fb?.eq, rng),
                squash = crossMacros(fa?.squash, fb?.squash, rng),
                crunch = crossMacros(fa?.crunch, fb?.crunch, rng),
                tape = crossMacros(fa?.tape, fb?.tape, rng),
                echo = crossMacros(fa?.echo, fb?.echo, rng),
                spring = crossMacros(fa?.spring, fb?.spring, rng),
                smear = crossMacros(fa?.smear, fb?.smear, rng),
                ghost = crossMacros(fa?.ghost, fb?.ghost, rng),
                motion = crossMacros(fa?.motion, fb?.motion, rng),
                dub = crossMacros(fa?.dub, fb?.dub, rng),
                swell = crossMacros(fa?.swell, fb?.swell, rng),
            )
        }
        return PadRecipe(patch = patch, fx = fx ?: if (patch == null) FxChain() else null)
    }

    /** The same patch with other macros, through its own JSON so every engine is spoken to alike. */
    private fun withMacros(patch: Patch, macros: Map<String, Float>): Patch {
        val json = patch.toJsonValue()
        val entries = LinkedHashMap(json.entries)
        entries["macros"] = JsonValue.Obj(macros.entries.associateTo(LinkedHashMap()) { (k, v) -> k to JsonValue.Num(v.toDouble()) })
        return Patches.fromJsonValue(JsonValue.Obj(entries))
    }

    /** The child's audio: a synth patch re-rendered through the rack, or A's audio through it. */
    private fun render(recipe: PadRecipe, aSnip: Snip): Snip? =
        if (recipe.patch != null) recipe.render() else recipe.process(aSnip)

    private fun place(model: KitBuilderModel, pad: KitPad, snip: Snip, recipe: JsonValue.Obj?, stamp: Map<String, String>) {
        model.assign(pad.slot, snip, pad.drumClass, pad.displayName)
        model.update(pad.slot) {
            it.copy(
                colorHex = pad.colorHex,
                level = pad.level,
                pan = pad.pan,
                tuneCoarse = pad.tuneCoarse,
                tuneFine = pad.tuneFine,
                muteGroup = pad.muteGroup,
                oneShot = pad.oneShot,
                recipe = recipe,
                source = pad.source + stamp,
            )
        }
    }
}
