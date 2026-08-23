package com.snipsnap.loop

import kotlin.math.roundToInt

/**
 * One block in a track's chain.
 *
 * The two kinds differ only in how they become audio. Once baked they are
 * both a buffer of exactly [Session.intervalFrames] — which is what lets one
 * engine play both without knowing the difference.
 */
sealed class Block

/** A captured or chopped WAV, fitted to the interval at bake time. */
data class LoopBlock(val sampleFile: String) : Block() {
    init {
        require(sampleFile.isNotBlank()) { "sampleFile must not be blank" }
        require(!sampleFile.contains('/') && !sampleFile.contains('\\')) {
            "sampleFile must be a bare filename, was '$sampleFile'"
        }
    }
}

/** One hit in a pattern: which pad, how hard, nudged how far off the grid. */
data class Step(
    /** Which 16th of the interval, 0-based. */
    val step: Int,
    /** Pad slot in the referenced kit, 1-based, matching KitPad.slot. */
    val slot: Int,
    val velocity: Float = 1f,
    /** Frames off the grid, positive or negative. Swing and human feel. */
    val microOffset: Int = 0,
) {
    init {
        require(step >= 0) { "step must not be negative: $step" }
        require(slot >= 1) { "slot is 1-based: $slot" }
        require(velocity in 0f..1f) { "velocity out of range: $velocity" }
    }
}

/** A sequence of hits against a kit, rendered to audio at bake time. */
data class PatternBlock(val kit: String, val steps: List<Step>) : Block() {
    init { require(kit.isNotBlank()) { "kit must not be blank" } }
}

/** One column of the grid: a name, a chain, and whether it is heard. */
data class Track(
    val name: String,
    val chain: List<Block>,
    val engaged: Boolean = true,
    val level: Float = 1f,
    val pan: Float = 0f,
) {
    init {
        require(chain.isNotEmpty()) { "track '$name' has an empty chain" }
        require(chain.size <= Session.MAX_CHAIN) {
            "track '$name' has ${chain.size} blocks, cap is ${Session.MAX_CHAIN}"
        }
        require(level >= 0f) { "level must not be negative: $level" }
        require(pan in -1f..1f) { "pan out of range: $pan" }
    }
}

/**
 * The whole grid.
 *
 * Every engaged track plays at once, and at each interval boundary every track
 * advances one block along its own chain. Chains of different lengths drift
 * against each other and only realign after the least common multiple of their
 * lengths — which is the entire point of the feature.
 *
 * [bpm] is live. [barsPerInterval] is not: every baked buffer is exactly one
 * interval long, so changing it would invalidate all of them at once and
 * require truncation rules nobody wants mid-performance.
 */
data class Session(
    val tracks: List<Track>,
    val bpm: Float,
    val barsPerInterval: Int,
    val sampleRate: Int,
) {
    init {
        require(tracks.size == TRACK_COUNT) {
            "a session has exactly $TRACK_COUNT tracks, got ${tracks.size}"
        }
        require(bpm in 40f..220f) { "bpm out of range: $bpm" }
        require(barsPerInterval in VALID_BARS) {
            "barsPerInterval must be one of $VALID_BARS, was $barsPerInterval"
        }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
    }

    /** Frames in one interval. Every block bakes to exactly this many. */
    val intervalFrames: Int
        get() = (barsPerInterval * BEATS_PER_BAR * (60.0 / bpm) * sampleRate).roundToInt()

    /** 16ths in one interval — the resolution a pattern block is written on. */
    val stepsPerInterval: Int get() = STEPS_PER_BAR * barsPerInterval

    companion object {
        const val TRACK_COUNT = 6
        const val MAX_CHAIN = 8
        const val STEPS_PER_BAR = 16
        const val BEATS_PER_BAR = 4
        val VALID_BARS = listOf(1, 2, 4, 8)
    }
}
