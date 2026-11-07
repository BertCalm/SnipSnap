package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.xpm.WavInfo
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitRobin
import com.snipsnap.kit.RobinSettings
import com.snipsnap.kit.isRobinChain
import java.io.File

/**
 * The kit-wide round-robin switch and dial. [on] deals takes to every pad
 * that can take them, [set] re-deals the pads that follow the kit, [off]
 * puts every original back. Long samples, loops, layered pads, other
 * people's chains and tonal or note-keyed pads (a robin's pitch spread
 * would detune them) are never touched; the pad switch still takes those.
 * Everything here is deterministic
 * (same original + settings + seed + slot = same bytes), so the app's kit
 * UNDO is just `set(model, previous)`.
 */
object RobinKit {

    /** Samples longer than this are phrases, not hits; a robin would multiply them. */
    const val LONG_SEC = 3.0f

    enum class Skip { LOOP, LAYERED, FOREIGN_CHAIN, OWN_SETTING, TONAL }

    /** [kept]: pads already dealt with these settings, left as they are (ON again churns nothing). */
    data class Result(val changed: List<Int>, val skipped: Map<Int, Skip>, val kept: List<Int> = emptyList())

    /** Why [pad] can't take a robin, or null when it can (or already is one). */
    private fun refusal(pad: KitPad, frames: Long, rate: Int): Skip? = when {
        pad.chain != null -> if (pad.isRobinChain) null else Skip.FOREIGN_CHAIN
        pad.velocityLayers.isNotEmpty() -> Skip.LAYERED
        isTonal(pad) -> Skip.TONAL
        pad.loop || frames.toDouble() > LONG_SEC.toDouble() * rate -> Skip.LOOP
        else -> null
    }

    fun eligible(pad: KitPad, frames: Long, rate: Int): Boolean = refusal(pad, frames, rate) == null

    /** A pitched pad: classed TONAL, or one SPREAD keyed to a note. */
    private fun isTonal(pad: KitPad): Boolean = pad.drumClass == DrumClass.TONAL || Spread.NOTE_KEY in pad.source

    /** Length and rate from the header alone: the switch asks every pad, and a long one would decode whole. */
    private fun framesOf(model: KitBuilderModel, pad: KitPad): Pair<Long, Int> {
        val wav = WavInfo.read(File(model.kitDir, pad.sampleFile))
        return wav.frameCount to wav.sampleRate
    }

    /** Switch round robin on for the kit with its stored (or default) settings. */
    fun on(model: KitBuilderModel): Result {
        val kitRobin = model.kit.robin ?: KitRobin()
        val settings = RobinSettings(kitRobin.amount, kitRobin.takes, 0, followsKit = true)
        val changed = mutableListOf<Int>()
        val kept = mutableListOf<Int>()
        val skipped = linkedMapOf<Int, Skip>()
        for (pad in model.kit.pads.toList()) {
            val slot = pad.slot
            if (pad.isRobinChain) {
                val own = pad.robin
                if (own == null || !own.followsKit) {
                    skipped[slot] = Skip.OWN_SETTING
                } else if (own != settings) {
                    Robin.rerender(model, slot, settings)
                    changed += slot
                } else {
                    kept += slot
                }
                continue
            }
            val (frames, rate) = if (pad.chain != null || pad.velocityLayers.isNotEmpty() || pad.loop || isTonal(pad)) 0L to 1
            else framesOf(model, pad)
            val why = refusal(pad, frames, rate)
            if (why != null) {
                skipped[slot] = why
                continue
            }
            Robin.apply(model, slot, settings)
            changed += slot
        }
        model.setRobin(kitRobin)
        return Result(changed, skipped, kept)
    }

    /**
     * The one dock line for ON: how many pads now play round robin (dealt now, already dealt, or
     * keeping their own setting), or that none can, then why pads were left alone.
     */
    fun onLine(r: Result): String {
        val on = r.changed.size + r.kept.size + r.skipped.values.count { it == Skip.OWN_SETTING }
        val why = r.skipped.values.toSet()
        return buildList {
            add(if (on == 0) Copy.ROBIN_KIT_NONE else Copy.robinKitOn(on))
            if (Skip.LOOP in why) add(Copy.ROBIN_SKIPPED_LOOPS)
            if (Skip.TONAL in why) add(Copy.ROBIN_SKIPPED_TONAL)
            if (Skip.LAYERED in why || Skip.FOREIGN_CHAIN in why) add(Copy.ROBIN_SKIPPED_LAYERED)
        }.joinToString(" ")
    }

    /** Store new kit defaults and re-render the robin pads that follow them. */
    fun set(model: KitBuilderModel, settings: KitRobin): Result {
        val target = RobinSettings(settings.amount, settings.takes, 0, followsKit = true)
        val changed = mutableListOf<Int>()
        val skipped = linkedMapOf<Int, Skip>()
        for (pad in model.kit.pads.toList()) {
            if (!pad.isRobinChain) continue
            val own = pad.robin
            if (own == null || !own.followsKit) {
                skipped[pad.slot] = Skip.OWN_SETTING
            } else if (own != target) {
                Robin.rerender(model, pad.slot, target)
                changed += pad.slot
            }
        }
        model.setRobin(settings)
        return Result(changed, skipped)
    }

    /** Undo every robin chain, followers and own-setting pads alike; the kit setting is cleared. */
    fun off(model: KitBuilderModel): Result {
        val changed = mutableListOf<Int>()
        for (pad in model.kit.pads.toList()) {
            if (!pad.isRobinChain) continue
            Robin.undo(model, pad.slot)
            changed += pad.slot
        }
        model.setRobin(null)
        return Result(changed, emptyMap())
    }

    /** [action] stopped part way (or its final save failed); the kit on disk was saved as it stood where it could be. */
    class Stopped(cause: Throwable) : RuntimeException(cause.message, cause)

    /**
     * Open the kit in [kitDir], run [action], save. The pad loops aren't atomic and every step
     * writes its WAVs and bin entries at once, so a throw leaves some pads converted. Each step
     * updates the model right after its own disk write, so the model matches disk between pads:
     * on failure [onFailure] runs (it sets the kit's switch so what was dealt can be undone) and
     * the model is saved as it stands, then [Stopped] carries the original error out. kit.json
     * therefore always describes the files, never a half-converted kit it doesn't know about.
     * The final save is inside the guard too: pads were dealt, so a save that fails is a stop. A kit
     * that will not open throws as it is, since nothing was touched.
     */
    fun runSaving(kitDir: File, onFailure: (KitBuilderModel) -> Unit = {}, action: (KitBuilderModel) -> Result): Result {
        val model = KitBuilderModel.open(kitDir)
        try {
            val result = action(model)
            model.save()
            return result
        } catch (t: Throwable) {
            try {
                onFailure(model)
                model.save()
            } catch (again: Throwable) {
                t.addSuppressed(again)
            }
            throw Stopped(t)
        }
    }

    /** [on] on the kit in [kitDir], saved. A stop keeps the switch ON so OFF can undo what was dealt. */
    fun onSaving(kitDir: File): Result =
        runSaving(kitDir, onFailure = { it.setRobin(it.kit.robin ?: KitRobin()) }) { on(it) }

    /** [set] on the kit in [kitDir], saved. A stop keeps the new settings; pads that lag are re-dealt by the next ON or SET. */
    fun setSaving(kitDir: File, settings: KitRobin): Result =
        runSaving(kitDir, onFailure = { it.setRobin(settings) }) { set(it, settings) }

    /** [off] on the kit in [kitDir], saved. A stop leaves the switch ON for the pads still chained. */
    fun offSaving(kitDir: File): Result = runSaving(kitDir) { off(it) }
}
