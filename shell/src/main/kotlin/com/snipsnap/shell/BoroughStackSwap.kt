package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.BoroughKits
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs

/**
 * The PERC STACK picker for an open BOROUGH kit (docs/BOROUGH.md, round 3.2): A13 to A16 re-rendered with another
 * stack by [BoroughKits.stackPads] for the kit they sit in (a stack's level and tails follow its kit, round 3.1),
 * while A01 to A12, the kit's glue and everything else in kit.json stay as they are.
 *
 * **Which kit and which stack.** Every BOROUGH pad carries its provenance in `source` (engine BOROUGH, `kit`, `slot`,
 * `kitVersion`; a stack pad also `stack`), written by [BoroughKits] and kept by kit.json through save and reload.
 * [held] reads it: the kit is the one most of A01 to A16's BOROUGH pads name, and the stack is the one the BOROUGH
 * stack pads still on A13 to A16 name, when they agree.
 *
 * **Untouched or changed.** A stack pad is untouched when it is exactly what a swap to its stack would write today:
 * its kit.json entry equal field for field to the pad [KitAssembler] makes from [BoroughKits.stackPads] (name, class,
 * colour, level, pan, tune, choke, one-shot, shape, chain with its velocity zones and takes, recipe, provenance;
 * everything but the file's name), and its WAV the same PCM within [KitBuilderModel.RENDER_MATCH], the tolerance the
 * app already uses for "a re-render and the file on disk are the same sound". So a new sample on the slot, a chop, a
 * level or pan or tune change, a treatment, a cleared or moved pad and a WAV rewritten outside the app all count as
 * changed. What it cannot see: a change that leaves both kit.json and the PCM as rendered (a setting kept somewhere
 * else, such as live kit effects), and it is conservative the other way: a stack pad printed by another version of
 * [BoroughKits] (its `kitVersion`, or audio a later build renders differently) reads as changed, because there is no
 * way to tell that from an edit. Deciding means rendering the stack the kit holds (a couple of seconds here); a slot
 * whose provenance already says it is not that stack's pad is changed without rendering anything.
 *
 * **The swap** stages the new stack with [KitAssembler] (the same writer the starter uses: three velocity zones of
 * three takes each, as a chain with its zones) in a temporary folder and adopts the four pads through
 * [KitBuilderModel.adoptAssembled], which copies each WAV in under a fresh name, saves kit.json (VERSIONS keeps the
 * kit as it was), and only then moves the replaced pads' files to THE BIN, so no old stack WAV is left behind in the
 * kit folder. The caller holds the kit write lock around [swap] (the app's `KitWrites.mutex`), as for any
 * open-mutate-save.
 */
object BoroughStackSwap {
    /** A13 to A16. */
    val STACK_SLOTS: IntRange = (BoroughKits.DRUM_PADS + 1)..BoroughKits.PADS
    private val BANK_A = 1..BoroughKits.PADS
    private const val ENGINE = "BOROUGH"

    /** What an open kit holds: the BOROUGH kit, and the stack on A13 to A16 (null when no one stack can be named). */
    data class Held(val kit: BoroughKits.Kit, val stack: BoroughKits.Stack?)

    /** One chip of the PERC STACK row. */
    data class Choice(val stack: BoroughKits.Stack, val label: String, val selected: Boolean)

    sealed interface Result {
        /** The kit holds no BOROUGH pads on bank A: nothing was read or written past kit.json. */
        data object NotBorough : Result
        /** A13 to A16 already hold this stack: nothing happens. */
        data class SameStack(val stack: BoroughKits.Stack) : Result
        /** These slots are not the stack as it was made; nothing was written. Ask, then swap with replaceChanges. */
        data class NeedsConfirm(val stack: BoroughKits.Stack, val changedSlots: List<Int>) : Result
        /** A13 to A16 hold [to] now; [kit] is kit.json as saved. */
        data class Swapped(val kit: Kit, val from: BoroughKits.Stack?, val to: BoroughKits.Stack) : Result
    }

    private fun kitOf(pad: KitPad): BoroughKits.Kit? =
        if (pad.source["engine"] != ENGINE) null else BoroughKits.Kit.entries.firstOrNull { it.name == pad.source["kit"] }

    /** The stack [pad] was printed as, when it is a BOROUGH stack pad of [kit] sitting on the slot it was made for. */
    private fun stackOf(pad: KitPad, kit: BoroughKits.Kit): BoroughKits.Stack? {
        if (pad.slot !in STACK_SLOTS || kitOf(pad) != kit || pad.source["slot"] != pad.slot.toString()) return null
        return BoroughKits.Stack.entries.firstOrNull { it.name == pad.source["stack"] }
    }

    /** The BOROUGH kit and stack [kit] holds, or null when bank A has no BOROUGH pad. Pure: reads the pads' provenance. */
    fun held(kit: Kit): Held? {
        val named = kit.pads.filter { it.slot in BANK_A }.sortedBy { it.slot }.mapNotNull { pad -> kitOf(pad) }
        if (named.isEmpty()) return null
        val counts = named.groupingBy { it }.eachCount()
        val most = counts.values.max()
        // The most named; a tie goes to the lowest slot's.
        val borough = named.first { counts.getValue(it) == most }
        val stacks = STACK_SLOTS.mapNotNull { slot -> kit.pad(slot)?.let { stackOf(it, borough) } }.toSet()
        return Held(borough, stacks.singleOrNull())
    }

    /** The PERC STACK row for [kit]: the four stacks in order, the one it holds selected; null for a kit that is not BOROUGH. */
    fun choices(kit: Kit): List<Choice>? {
        val held = held(kit) ?: return null
        return BoroughKits.Stack.entries.map { Choice(it, label(it), it == held.stack) }
    }

    /** A stack's chip and line text: HANDS, TAMBOURINE, BELLS, TOPS. */
    fun label(stack: BoroughKits.Stack): String = stack.title.uppercase(java.util.Locale.ROOT)

    /**
     * The slots of A13 to A16 that are not the stack [held] names as it was made, by the rule in this object's KDoc,
     * ascending; empty when all four are untouched. Every slot is changed when no one stack can be named. Renders
     * that stack when any slot's provenance leaves the question open.
     */
    fun changedSlots(kitDir: File, kit: Kit, held: Held = requireNotNull(BoroughStackSwap.held(kit)) { "not a BOROUGH kit" }): List<Int> {
        val stack = held.stack ?: return STACK_SLOTS.toList()
        val candidates = STACK_SLOTS.filter { slot -> kit.pad(slot)?.let { stackOf(it, held.kit) } == stack }
        val changed = STACK_SLOTS.filter { it !in candidates }.toMutableList()
        if (candidates.isNotEmpty()) {
            staged(held.kit, stack, kit) { dir, made ->
                for (slot in candidates) {
                    if (!asMade(kitDir, kit.pad(slot)!!, dir, made.pad(slot)!!)) changed += slot
                }
            }
        }
        return changed.sorted()
    }

    /**
     * [stack] onto A13 to A16 of the kit in [kitDir]. Refuses a kit that is not BOROUGH and does nothing for the stack
     * it already holds. Unless [replaceChanges], a kit whose stack pads were changed is left alone and the answer names
     * the changed slots, for the caller to ask about first.
     */
    fun swap(kitDir: File, stack: BoroughKits.Stack, replaceChanges: Boolean = false): Result {
        val model = KitBuilderModel.open(kitDir)
        val held = held(model.kit) ?: return Result.NotBorough
        if (held.stack == stack) return Result.SameStack(stack)
        if (!replaceChanges) {
            val changed = changedSlots(kitDir, model.kit, held)
            if (changed.isNotEmpty()) return Result.NeedsConfirm(stack, changed)
        }
        val saved = staged(held.kit, stack, model.kit) { dir, made ->
            model.adoptAssembled(made.pads.sortedBy { it.slot }.map { it to File(dir, it.sampleFile) })
        }
        return Result.Swapped(saved.copy(pads = saved.pads.sortedBy { it.slot }), held.stack, stack)
    }

    /** The line the dock says for [result]; null when there is nothing to say (an ask is a dialog, not a line). */
    fun line(result: Result): String? = when (result) {
        Result.NotBorough -> Copy.PERC_STACK_NONE
        is Result.SameStack -> null
        is Result.NeedsConfirm -> null
        is Result.Swapped -> Copy.percStackSwapped(label(result.to))
    }

    /**
     * [stack] as it plays in [borough], written by [KitAssembler] onto A13 to A16 of a temporary folder (the drum slots
     * left empty), handed to [use] with the kit the assembler saved there, then deleted.
     */
    private fun <T> staged(borough: BoroughKits.Kit, stack: BoroughKits.Stack, kit: Kit, use: (File, Kit) -> T): T {
        val dir = createTempDirectory("borough-stack").toFile()
        try {
            val arranged: List<ArrangedPad?> = List(BoroughKits.DRUM_PADS) { null } + BoroughKits.stackPads(borough, stack)
            val made = KitAssembler.assembleArranged(kit.name, arranged, dir, key = kit.key, tempoBpm = kit.tempoBpm)
            return use(dir, made)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** [saved] in [kitDir] is [made] in [madeDir]: the same kit.json entry but for the file's name, and the same PCM. */
    private fun asMade(kitDir: File, saved: KitPad, madeDir: File, made: KitPad): Boolean {
        if (saved.copy(sampleFile = made.sampleFile) != made) return false
        val onDisk = runCatching { WavReader.read(File(kitDir, saved.sampleFile)) }.getOrNull() ?: return false
        val fresh = WavReader.read(File(madeDir, made.sampleFile))
        if (onDisk.channels != fresh.channels || onDisk.sampleRate != fresh.sampleRate || onDisk.frameCount != fresh.frameCount) return false
        return onDisk.samples.indices.all { abs(onDisk.samples[it] - fresh.samples[it]) <= KitBuilderModel.RENDER_MATCH }
    }
}
