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
import com.snipsnap.synth.SnapPatch
import com.snipsnap.synth.crossTable
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
    fun recipeOf(pad: KitPad): PadRecipe? = recipeOf(pad.recipe)

    /**
     * Same parse, off the raw recipe JSON rather than a [KitPad] - shared
     * with `ArrangedPad.recipe` (`StarterKits`' VELOCITY starter reads its
     * patch from here too, Task 5b), which carries the identical opaque
     * shape before a pad ever lands in a kit.
     */
    fun recipeOf(recipe: JsonValue.Obj?): PadRecipe? {
        val r = recipe ?: return null
        return try {
            PadRecipe.fromJsonValue(r)
        } catch (e: JsonException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * [recipeOf] collapses THREE different cases to the same null: no
     * recipe JSON at all (a genuinely captured pad); recipe JSON of a
     * different, perfectly valid kind ([Mutate], [Eras], the treatment
     * card, this object's own `"robin"` bookkeeping - none of them carry
     * PadRecipe's `"recipe"` version key, by convention); and recipe JSON
     * that clearly WAS meant to be a PadRecipe (the `"recipe"` version
     * key is present) but failed to parse - genuinely corrupt. All three
     * are correct for [recipeOf]'s own callers (all mean "nothing to
     * re-render from" alike), but a developer chasing "why didn't this
     * pad re-render" needs to tell the third apart from the other two -
     * conflating it with the entirely-ordinary second case would make
     * this fire on most treated pads, which is worse than the silence it
     * replaces. Checking for the `"recipe"` key specifically (rather
     * than "any JSON at all") is what keeps it to the genuine case.
     *
     * Both Robin's zone grid and KitBuilder's ghost layers land on the
     * identical `soften()` fallback regardless (safe and correct in
     * every case here - this changes no behavior), so this is called at
     * exactly that fork to log the corrupt case, once, without touching
     * [recipeOf]'s signature or forcing every caller to handle a richer
     * return type.
     */
    fun hasCorruptRecipe(pad: KitPad): Boolean {
        val looksLikePadRecipe = pad.recipe?.entries?.containsKey("recipe") == true
        return looksLikePadRecipe && recipeOf(pad) == null
    }

    /** Thin logging wrapper over [hasCorruptRecipe] - split out so a test
     * can assert the predicate directly instead of scraping stderr. */
    fun warnIfCorruptRecipe(pad: KitPad, slot: Int, context: String) {
        if (hasCorruptRecipe(pad)) {
            System.err.println(
                "$context: pad $slot's recipe JSON declares itself a PadRecipe but failed to " +
                    "parse - treating it as captured audio (soften, no re-render)",
            )
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

    /**
     * SNAP's own cross: [ap]'s and [bp]'s 256-point lines, crossed point
     * by point through `crossTable` — the identical three-way coin
     * [pick] flips for a macro, just spun once per point instead of once
     * per name — and their envelopes likewise, [crossEnvelope]'s own
     * "rides half the time" rule when only one parent drew one.
     * `SnapPatch`'s own constructor refuses a flat table or a shape that
     * never opens, so the coin gets one more full spin (table and
     * envelope both) before falling back to A's own line verbatim —
     * [macros] is already decided and rides through unchanged either way.
     */
    private fun crossSnap(ap: SnapPatch, bp: SnapPatch, macros: Map<String, Float>, rng: Random): SnapPatch {
        repeat(2) {
            val table = crossTable(ap.table, bp.table) { rng.nextInt(3) }
            val envelope = crossEnvelope(ap.envelope, bp.envelope, rng)
            try {
                return SnapPatch(ap.name, ap.voice, macros, table, envelope)
            } catch (e: IllegalArgumentException) {
                // A flat table, or a shape that never opens — the coin spins again.
            }
        }
        return SnapPatch(ap.name, ap.voice, macros, ap.table, ap.envelope)
    }

    /** [a]'s and [b]'s drawn envelopes, crossed point by point when both carry one — [crossMacros]'s own rule when only one parent has one at all. */
    private fun crossEnvelope(a: IntArray?, b: IntArray?, rng: Random): IntArray? {
        if (a == null && b == null) return null
        if (a == null || b == null) return if (rng.nextBoolean()) (a ?: b) else null
        return crossTable(a, b) { rng.nextInt(3) }
    }

    /** The child's recipe: A's patch with crossed macros when the engines agree, and the crossed rack. */
    internal fun cross(a: PadRecipe?, b: PadRecipe?, rng: Random): PadRecipe {
        val patch: Patch? = a?.patch?.let { ap ->
            val bp = b?.patch
            if (bp != null && bp.engine == ap.engine && bp.voiceName == ap.voiceName) {
                val names = (ap.macros.keys + bp.macros.keys).toList().sorted()
                val macros = names.associateWith { n -> pick(ap.macros[n], bp.macros[n], rng)!!.coerceIn(0f, 1f) }
                // SNAP is the one engine whose "settings" are a drawn line,
                // not knobs alone — two photos, one child means the line
                // itself crosses too, not just the macros the photo set.
                if (ap is SnapPatch && bp is SnapPatch) crossSnap(ap, bp, macros, rng) else withMacros(ap, macros)
            } else {
                ap
            }
        }
        val fa = a?.fx
        val fb = b?.fx
        val fx = if (fa == null && fb == null) {
            null
        } else {
            var chain = FxChain(
                reverse = if (fa != null && fb != null) {
                    if (rng.nextBoolean()) fa.reverse else fb.reverse
                } else {
                    fa?.reverse ?: fb!!.reverse
                },
            )
            for (name in FxChain.SECTION_NAMES) {
                chain = chain.withSection(name, crossMacros(fa?.section(name), fb?.section(name), rng))
            }
            chain
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
