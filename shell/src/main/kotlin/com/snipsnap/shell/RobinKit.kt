package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitRobin
import com.snipsnap.kit.RobinSettings
import com.snipsnap.kit.isRobinChain
import java.io.File

/**
 * The kit-wide round-robin switch and dial. [on] deals takes to every pad
 * that can take them, [set] re-deals the pads that follow the kit, [off]
 * puts every original back. Long samples, loops, layered pads and other
 * people's chains are never touched. Everything here is deterministic
 * (same original + settings + seed + slot = same bytes), so the app's kit
 * UNDO is just `set(model, previous)`.
 */
object RobinKit {

    /** Samples longer than this are phrases, not hits; a robin would multiply them. */
    const val LONG_SEC = 3.0f

    enum class Skip { LOOP, LAYERED, FOREIGN_CHAIN, OWN_SETTING }

    data class Result(val changed: List<Int>, val skipped: Map<Int, Skip>)

    /** Why [pad] can't take a robin, or null when it can (or already is one). */
    private fun refusal(pad: KitPad, frames: Long, rate: Int): Skip? = when {
        pad.chain != null -> if (pad.isRobinChain) null else Skip.FOREIGN_CHAIN
        pad.velocityLayers.isNotEmpty() -> Skip.LAYERED
        pad.loop || frames.toDouble() > LONG_SEC.toDouble() * rate -> Skip.LOOP
        else -> null
    }

    fun eligible(pad: KitPad, frames: Long, rate: Int): Boolean = refusal(pad, frames, rate) == null

    private fun framesOf(model: KitBuilderModel, pad: KitPad): Pair<Long, Int> {
        val wav = WavReader.read(File(model.kitDir, pad.sampleFile))
        return wav.frameCount.toLong() to wav.sampleRate
    }

    /** Switch round robin on for the kit with its stored (or default) settings. */
    fun on(model: KitBuilderModel): Result {
        val kitRobin = model.kit.robin ?: KitRobin()
        val settings = RobinSettings(kitRobin.amount, kitRobin.takes, 0, followsKit = true)
        val changed = mutableListOf<Int>()
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
                }
                continue
            }
            val (frames, rate) = if (pad.chain != null || pad.velocityLayers.isNotEmpty() || pad.loop) 0L to 1
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
        return Result(changed, skipped)
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
}
