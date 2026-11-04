package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.TempoFit
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.ChainZone
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.RobinSettings
import com.snipsnap.kit.isRobinChain
import java.util.Random

/**
 * Round robin for our own kits (HH1.2) — the PSK corpus trick, aimed the
 * other way. Commercial kits chain N *recorded* takes and let Slice
 * Motion step through them; our pads usually have one take, so the robin
 * renders the missing ones: N seeded, subtly different variants of the
 * pad's sample — micro level, micro pitch, a hair of start jitter, the
 * differences real drummers can't help making — concatenated into one
 * chain WAV. The pad becomes a chain ([ChainInfo]), the MPC 3 cycles a
 * take per hit, the preview does the same, and the MPC 2 plays take one
 * (which is the untouched original, deliberately first).
 *
 * Bin-backed like every treatment: undo pulls the single take back out
 * byte-identical and clears the chain. Every other audio edit works on a
 * robin pad: [around] lifts the chain, edits the original, re-renders the takes.
 */
object Robin {

    const val DEFAULT_TAKES = 3

    /** The corpus's chains cycle 2..4; 8 matches the layer count ceiling. */
    const val MAX_TAKES = 8

    /**
     * Variation caps — the humanize philosophy at render time: audible
     * side by side, invisible in a groove. Level ±8% (~0.7 dB), pitch
     * ±10 cents, up to 2ms shaved off the front.
     */
    const val LEVEL_SPREAD = 0.08f
    const val PITCH_SPREAD_CENTS = 10f
    const val START_JITTER_SEC = 0.002f

    /**
     * The grid's zone grading (II4): tone via the ghost layers' own
     * soften depths (softest zone first, top zone pristine), level from
     * [ZONE_LEVEL_FLOOR] up to unity — soft hits play takes that are
     * both quieter and darker, the way real dynamics work. A synth pad
     * reads these as `Velocity.atVelocity` velocities (`1f - depth`) and
     * re-renders; a captured pad still reads them as `Velocity.soften`
     * depths, the only thing there is to do with a frozen take (see
     * [apply]).
     */
    private val ZONE_SOFTEN = mapOf(
        2 to listOf(0.55f),
        3 to listOf(0.7f, 0.4f),
        4 to listOf(0.8f, 0.55f, 0.3f),
    )
    const val ZONE_LEVEL_FLOOR = 0.55f

    /**
     * Turn pad [slot]'s single take into a chain of [takes]. Take one is
     * the original verbatim; the rest are seeded variants, so the same
     * (seed, takes) always renders the same chain. Saves are the
     * caller's job, as everywhere in the model.
     *
     * [zones] (2..4) renders the full velocity × round-robin grid
     * instead: a dynamics-graded chain, soft→hard like the PSK's, each
     * zone [takes] takes — its graded render un-jittered first, then
     * seeded variants — anchored at its own base slice. The top zone's
     * anchor is the untouched original.
     */
    fun apply(
        model: KitBuilderModel,
        slot: Int,
        takes: Int = DEFAULT_TAKES,
        seed: Int = 0,
        zones: Int? = null,
    ): KitPad {
        require(takes in 2..MAX_TAKES) { "takes is 2..$MAX_TAKES, got $takes" }
        zones?.let { require(it in 2..4) { "zones is 2..4, got $it" } }
        if (zones == null) return applyLegacy(model, slot, RobinSettings(null, takes, seed, followsKit = false))
        // Zone grids keep `robin = null` on the pad, so they are not robin chains.
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.chain == null) { "pad $slot is already a round-robin chain - `robin --undo` first" }

        // A synth pad's soft zones re-render at velocity (genuinely
        // different onset, not the same take low-passed); a captured pad,
        // or one whose recipe also carries an fx chain atVelocity can't
        // replay, still softens the take it has - Velocity.layerAt makes
        // that same call for KitBuilder's ghost layers and StarterKits'
        // VELOCITY starter, so this door doesn't carry its own copy of the
        // decision. Read off `pad` now - `replaceAudio` below overwrites
        // the recipe with the robin's own {"robin": ...} bookkeeping, so
        // this is the last moment the patch is reachable.
        //
        // The spec is resolved once here, not per zone (Velocity.atVelocity's
        // KDoc: macroSpecsFor is an O(5×n) scan best paid once per patch).
        // Skipped entirely for a patch+fx pad: layerAt's fx gate discards
        // it regardless of what it resolves to (Task 5b), so there's no
        // point paying for the scan.
        val padRecipe = Breed.recipeOf(pad)
        if (padRecipe == null) Breed.warnIfCorruptRecipe(pad, slot, "robin")
        val patch = padRecipe?.patch
        val patchFx = padRecipe?.fx
        val brightnessSpec = if (patch != null && patchFx == null) {
            com.snipsnap.synth.Velocity.brightnessSpec(patch)
        } else {
            null
        }

        var boundaries: List<Long> = emptyList()
        val recipe = JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "robin" to JsonValue.Obj(
                    linkedMapOf<String, JsonValue>(
                        "takes" to JsonValue.Num(takes.toDouble()),
                        "seed" to JsonValue.Num(seed.toDouble()),
                        "zones" to JsonValue.Num(zones.toDouble()),
                    ),
                ),
            ),
        )
        model.replaceAudio(slot, recipe) { original ->
            val rng = Random(seed.toLong() * 31 + slot)
            val rendered = run {
                val soften = ZONE_SOFTEN.getValue(zones)
                // Resolved once for the whole grid, not once per zone -
                // this is layerAt's format probe (a full patch.render(),
                // thrown away), the expensive half of the same hoist
                // brightnessSpec already got above (Task 5b).
                val useAtVelocity = com.snipsnap.synth.Velocity.canUseAtVelocity(original, patch, patchFx)
                buildList {
                    for (z in 0 until zones) {
                        val graded = if (z < zones - 1) {
                            val level = ZONE_LEVEL_FLOOR + (1f - ZONE_LEVEL_FLOOR) * z / (zones - 1)
                            // soften[z] is a softening depth (0 untouched, 1 softest);
                            // layerAt (like atVelocity) speaks velocity (1
                            // untouched, 0 softest) - hence 1f - soften[z].
                            val darker = com.snipsnap.synth.Velocity.layerAt(
                                original, patch, patchFx, 1f - soften[z], brightnessSpec, useAtVelocity,
                            )
                            gain(darker, level)
                        } else {
                            original
                        }
                        add(graded)
                        repeat(takes - 1) { add(variant(graded, rng)) }
                    }
                }
            }
            boundaries = sliceStarts(rendered)
            concat(rendered)
        }
        val zoneList = (0 until zones).map { z ->
            ChainZone(
                velStart = if (z == 0) 0 else 128 * z / zones,
                velEnd = if (z == zones - 1) 127 else 128 * (z + 1) / zones - 1,
                baseSlice = z * takes,
                cycle = takes,
            )
        }
        return model.update(slot) {
            it.copy(chain = ChainInfo(boundaries, cycle = takes, zones = zoneList))
        }
    }

    /**
     * The typed door (single-zone only): renders [settings] on the amount
     * dial and keeps the pad's own recipe, so a synth pad stays a synth pad.
     * A null amount is the legacy robin (today's [variant], bookkeeping
     * recipe), kept byte-identical for the CLI and replay.
     */
    fun apply(model: KitBuilderModel, slot: Int, settings: RobinSettings): KitPad {
        val amount = settings.amount ?: return applyLegacy(model, slot, settings)
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.chain == null) { "pad $slot is already a round-robin chain - `robin --undo` first" }
        var boundaries: List<Long> = emptyList()
        model.replaceAudio(slot, pad.recipe) { original ->
            val rng = Random(settings.seed.toLong() * 31 + slot)
            val rendered = RobinVariation.takes(original, settings.takes, amount, rng)
            boundaries = sliceStarts(rendered)
            concat(rendered)
        }
        return model.update(slot) {
            it.copy(chain = ChainInfo(boundaries, cycle = settings.takes, zones = null), robin = settings)
        }
    }

    /** Today's render: legacy bookkeeping recipe, the 3-draw [variant] stream. */
    private fun applyLegacy(model: KitBuilderModel, slot: Int, settings: RobinSettings): KitPad {
        val takes = settings.takes
        val seed = settings.seed
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.chain == null) { "pad $slot is already a round-robin chain - `robin --undo` first" }
        var boundaries: List<Long> = emptyList()
        val recipe = JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "robin" to JsonValue.Obj(
                    linkedMapOf<String, JsonValue>(
                        "takes" to JsonValue.Num(takes.toDouble()),
                        "seed" to JsonValue.Num(seed.toDouble()),
                    ),
                ),
            ),
        )
        model.replaceAudio(slot, recipe) { original ->
            val rng = Random(seed.toLong() * 31 + slot)
            val rendered = buildList {
                add(original)
                repeat(takes - 1) { add(variant(original, rng)) }
            }
            boundaries = sliceStarts(rendered)
            concat(rendered)
        }
        return model.update(slot) {
            it.copy(chain = ChainInfo(boundaries, cycle = takes, zones = null), robin = settings)
        }
    }

    private fun sliceStarts(rendered: List<Snip>): List<Long> {
        var at = 0L
        return rendered.map { t -> at.also { _ -> at += t.frameCount } }
    }

    /**
     * Deals the takes of every pad an import landed with robin settings and no chain, from the
     * original it landed with: deterministic, so the chain comes back as it was exported, and the
     * original goes into the bin where [undo] looks for it. The kit is saved into [dir] first, so a
     * model can open it there; the importer saves what this returns.
     */
    val dealer: com.snipsnap.kit.RobinDealer = com.snipsnap.kit.RobinDealer { kit, dir ->
        com.snipsnap.kit.KitStore.save(kit, dir)
        val model = KitBuilderModel.open(dir)
        for (pad in kit.pads) {
            val settings = pad.robin ?: continue
            if (pad.chain != null) continue
            model.update(pad.slot) { it.copy(robin = null) }
            apply(model, pad.slot, settings)
            model.update(pad.slot) { it.copy(recipe = pad.recipe) }
        }
        model.kit
    }

    /** Undo then apply: new settings over the same original. Only a robin chain qualifies. */
    fun rerender(model: KitBuilderModel, slot: Int, settings: RobinSettings): KitPad {
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.isRobinChain) { "pad $slot isn't a round-robin chain - nothing to re-render" }
        undo(model, slot)
        return apply(model, slot, settings)
    }

    /** Take one (slice 0 of the chain WAV) is the original, for previews. */
    fun original(model: KitBuilderModel, slot: Int): Snip {
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        val chain = requireNotNull(pad.chain) { "pad $slot isn't a round-robin chain" }
        val whole = com.snipsnap.audio.WavReader.read(java.io.File(model.kitDir, pad.sampleFile))
        return take(whole, chain, 0) ?: whole
    }

    /**
     * Take [i] of [chain] cut out of [whole], the chain's decoded WAV. The window is clamped to the
     * audio, so a kit.json whose boundaries run past the file (a WAV swapped under it) plays what is
     * there rather than throwing; null when nothing of that take is left to play.
     */
    fun take(whole: Snip, chain: ChainInfo, i: Int): Snip? {
        if (i !in chain.boundaries.indices) return null
        val frames = whole.frameCount.toLong()
        val w = chain.window(i, frames)
        val from = w.first.coerceIn(0L, frames)
        val to = (w.last + 1).coerceIn(from, frames)
        if (to <= from) return null
        val ch = whole.channels
        return Snip(whole.samples.copyOfRange((from * ch).toInt(), (to * ch).toInt()), ch, whole.sampleRate)
    }

    /** [pad] as [undo] leaves it: chain and settings cleared, a lone legacy bookkeeping recipe dropped, a kept patch recipe kept. */
    internal fun lifted(pad: KitPad): KitPad {
        val bookkeeping = pad.recipe?.entries?.keys == setOf("robin")
        return pad.copy(chain = null, robin = null, recipe = if (bookkeeping) null else pad.recipe)
    }

    /**
     * Whether [chain]'s boundaries fit a WAV of [frames] frames: every take starts inside it. A single
     * take copied over a chain (the original alone) never fits, since take two starts where it ends.
     */
    internal fun fits(chain: ChainInfo, frames: Long): Boolean = chain.boundaries.last() < frames

    /**
     * The single take back, the chain cleared: out of the bin byte-identical, or - when the bin no
     * longer holds it (EMPTY THE BIN, the sweep) - cut from the chain itself, since a robin chain's
     * take one is the untouched original ([robinTakeOne]: byte-faithful for 16/24-bit PCM).
     */
    fun undo(model: KitBuilderModel, slot: Int): KitPad {
        val pad = model.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        require(pad.chain != null) { "pad $slot isn't a round-robin chain - nothing to undo" }
        if (model.restoreFromBin(pad.sampleFile) == null) {
            // A velocity grid's slice 0 is its softest zone and a foreign chain's is someone's take:
            // only a robin chain's take one is the original.
            require(pad.isRobinChain) { "nothing to restore for pad $slot - the bin holds no earlier take of it" }
            val one = com.snipsnap.kit.robinTakeOne(pad, model.kitDir)
            com.snipsnap.kit.AtomicFile.writeBytes(java.io.File(model.kitDir, pad.sampleFile), one)
        }
        return model.update(slot) { lifted(it) }
    }

    /**
     * Run [block] - any audio edit - on a robin pad's original. A robin chain is lifted
     * ([undo]: the single take back, chain cleared), [block] edits it, and the takes are
     * re-rendered from the edited result with the settings the pad had before. The pad is
     * re-read after [block], so a recipe it wrote is kept. If [block] throws, the takes are
     * put back from whatever is on the pad and the error propagates. Any other pad: just [block].
     */
    fun <T> around(model: KitBuilderModel, slot: Int, block: () -> T): T {
        val before = model.pad(slot)
        if (before == null || !before.isRobinChain) return block()
        val settings = checkNotNull(before.robin)
        undo(model, slot)
        val result = try {
            block().also { checkRenderable(model, slot) }
        } catch (t: Throwable) {
            try {
                relay(model, slot, settings)
            } catch (again: Throwable) {
                t.addSuppressed(again)
            }
            throw t
        }
        relay(model, slot, settings)
        return result
    }

    /**
     * BACK on a bin row: [entry] copied over the file it came from. When a robin chain plays that
     * file, copying one take over the chain would leave its boundaries pointing past the end, so the
     * restore runs [around] the pad: the restored take becomes the original and the takes re-render.
     * A row that is the very take [undo] lifts was already restored by the lift; the pad is then
     * re-dealt from it. Null when the row is gone, as [KitBuilderModel.restoreFromBin] says.
     */
    fun restoreEntry(model: KitBuilderModel, entry: KitBuilderModel.BinEntry): java.io.File? {
        if (!entry.file.isFile) return null
        val pad = model.kit.pads.firstOrNull { it.sampleFile == entry.originalName && it.isRobinChain }
            ?: return model.restoreFromBin(entry)
        return around(model, pad.slot) {
            model.restoreFromBin(entry) ?: java.io.File(model.kitDir, entry.originalName)
        }
    }

    /**
     * The bin row holding the sound a robin pad had before its last edit, for ◀ BEFORE. The newest
     * row under the pad's file is the pad's own original ([undo] restores it) and the one under it
     * is what the edit replaced; a pad with no earlier row answers its own original. Any other
     * pad: its newest row.
     */
    fun beforeTake(model: KitBuilderModel, slot: Int): KitBuilderModel.BinEntry? {
        val pad = model.pad(slot) ?: return null
        val rows = model.binContents().filter { it.originalName == pad.sampleFile }.sortedByDescending { it.binnedAtMillis }
        return if (pad.isRobinChain) rows.getOrNull(1) ?: rows.firstOrNull() else rows.firstOrNull()
    }

    /** The backstop: an edit that left the pad in a shape the takes cannot render from fails like any other failed edit. */
    private fun checkRenderable(model: KitBuilderModel, slot: Int) {
        val pad = model.pad(slot) ?: throw IllegalArgumentException("pad $slot is gone - nothing to re-render the takes from")
        require(pad.chain == null && pad.velocityLayers.isEmpty()) {
            "pad $slot can't keep its round-robin takes after that edit - it is layered or chained"
        }
    }

    /** Re-render the takes of the pad as it now stands, keeping a recipe the edit wrote over the legacy bookkeeping one. */
    private fun relay(model: KitBuilderModel, slot: Int, settings: RobinSettings) {
        val edited = model.pad(slot)?.recipe
        apply(model, slot, settings)
        if (edited != null) model.update(slot) { it.copy(recipe = edited) }
    }

    private fun variant(original: Snip, rng: Random): Snip {
        // Draw all three before any early return, so the rng stream (and
        // with it every later take) never depends on this take's values.
        val trimFrames = (rng.nextFloat() * START_JITTER_SEC * original.sampleRate).toInt()
            .coerceAtMost(original.frameCount / 4)
        val cents = (rng.nextFloat() * 2f - 1f) * PITCH_SPREAD_CENTS
        val gain = 1f + (rng.nextFloat() * 2f - 1f) * LEVEL_SPREAD

        var s = if (trimFrames > 0) {
            Snip(
                original.samples.copyOfRange(trimFrames * original.channels, original.samples.size),
                original.channels,
                original.sampleRate,
            )
        } else {
            original
        }
        // Micro repitch by resample - the sampler way, pitch and length
        // riding together like a slightly different hit would.
        val speed = Math.pow(2.0, cents / 1200.0).toFloat()
        s = TempoFit.repitch(s, 1000f, 1000f * speed)
        val out = FloatArray(s.samples.size)
        for (i in s.samples.indices) out[i] = (s.samples[i] * gain).coerceIn(-1f, 1f)
        return Snip(out, s.channels, s.sampleRate)
    }

    private fun gain(s: Snip, level: Float): Snip {
        val out = FloatArray(s.samples.size)
        for (i in s.samples.indices) out[i] = (s.samples[i] * level).coerceIn(-1f, 1f)
        return Snip(out, s.channels, s.sampleRate)
    }

    /** [takes] end to end as one snip — a chain's WAV; CHOP's FOLD lands its takes the same way. */
    internal fun concat(takes: List<Snip>): Snip {
        val channels = takes.first().channels
        val rate = takes.first().sampleRate
        val total = takes.sumOf { it.samples.size }
        val out = FloatArray(total)
        var at = 0
        for (t in takes) {
            t.samples.copyInto(out, at)
            at += t.samples.size
        }
        return Snip(out, channels, rate)
    }
}
