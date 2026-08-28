package com.snipsnap.xpm

/**
 * One velocity zone of a pad — the format gives every pad four layers, each
 * with its own sample and velocity window, and this is how a phone-made kit
 * gets soft hits that *sound* soft instead of just quiet.
 */
data class VelocityLayer(
    val sampleName: String,
    val frameCount: Long,
    /** MIDI velocity window, 0..127 inclusive. */
    val velStart: Int,
    val velEnd: Int,
    /**
     * Sustain loop start frame; `0` = no loop. When set, the layer loops
     * from here **to the end of the sample** — the one shape both real
     * idioms share (MPC 2: `SliceLoop=1` + `SliceLoopStart`, looping to
     * `SliceEnd`; MPC 3: `sliceInfo.LoopMode=1` + `LoopStart`, looping to
     * `End`). Held notes sustain forever; release comes from the program's
     * amp envelope. Drum programs ignore this.
     */
    val loopStartFrame: Long = 0,
) {
    init {
        require(sampleName.isNotBlank()) { "layer sampleName must not be blank" }
        require(frameCount >= 0) { "layer frameCount must not be negative" }
        require(velStart in 0..127 && velEnd in 0..127 && velStart <= velEnd) {
            "bad velocity window $velStart..$velEnd"
        }
        require(loopStartFrame in 0 until maxOf(frameCount, 1)) {
            "loopStartFrame $loopStartFrame outside sample (0..<$frameCount)"
        }
    }
}

/**
 * One velocity zone of a chain grid, frame windows already resolved:
 * which velocities anchor at which slice, and the anchor take's own
 * frame window ([windowStart]..[windowEnd]) for writers that speak in
 * frames rather than slice indices (the MPC 2 layer, `sliceInfo`).
 */
data class ChainZonePlay(
    val velStart: Int,
    val velEnd: Int,
    val baseSlice: Int,
    val cycle: Int,
    val windowStart: Long,
    val windowEnd: Long,
) {
    init {
        require(velStart in 0..127 && velEnd in 0..127 && velStart <= velEnd) {
            "bad velocity window $velStart..$velEnd"
        }
        require(baseSlice >= 0) { "baseSlice must not be negative: $baseSlice" }
        require(cycle >= 1) { "a zone cycles at least 1 take, got $cycle" }
        require(windowStart in 0 until windowEnd) { "bad frame window $windowStart..$windowEnd" }
    }
}

/**
 * Chain playback for a pad (MPC 3 "Slice Motion"): the pad's sample is a
 * chain of takes, and each hit steps to the next slice. Decoded from the
 * corpus's PSK kit — per-layer `sliceIndex` with `sliceIncrement 1` and
 * `sliceCycleLength` = takes cycled. The MPC 2 generation has no Slice
 * Motion; its export windows the layer to slice 0 ([firstSliceEnd]) so
 * the pad plays take one instead of the whole chain.
 *
 * [zones] is the full velocity × round-robin grid (the PSK scheme):
 * 2..4 zones, soft first, tiling 0..127. The `.xtd` writes one layer
 * per zone (loudest first, per-zone base slice and cycle); the `.xpm`
 * windows each layer to its zone's anchor take — real velocity
 * switching on the MPC 2, no robin, that generation's honest ceiling.
 */
data class ChainPlay(
    /** End frame of slice 0 — the MPC 2 fallback window. */
    val firstSliceEnd: Long,
    /** Slices cycled per hit, 2..128 (single-zone; zones carry their own). */
    val cycle: Int,
    val zones: List<ChainZonePlay>? = null,
) {
    init {
        require(firstSliceEnd > 0) { "firstSliceEnd must be positive: $firstSliceEnd" }
        require(cycle in 2..128) { "cycle is 2..128, got $cycle" }
        zones?.let { zs ->
            require(zs.size in 2..4) { "a grid has 2..4 zones, got ${zs.size}" }
            require(zs.first().velStart == 0 && zs.last().velEnd == 127) {
                "zones tile 0..127 - got ${zs.first().velStart}..${zs.last().velEnd}"
            }
            for (i in 1 until zs.size) {
                require(zs[i].velStart == zs[i - 1].velEnd + 1) {
                    "zones must be contiguous soft-first: ${zs[i - 1].velEnd} then ${zs[i].velStart}"
                }
            }
        }
    }
}

/**
 * A single pad's worth of a drum program.
 *
 * [sampleName] is the WAV filename *without* extension — the MPC resolves it
 * against the folder the .xpm sits in. [frameCount] is the sample's length in
 * frames, which becomes SliceEnd; get it from [WavInfo].
 */
data class Pad(
    val sampleName: String,
    val frameCount: Long,
    /** 0.0..1.0, linear. MPC default for an instrument is ~0.707946 (-3 dB). */
    val level: Float = 0.707946f,
    /** 0.0 = hard left, 0.5 = centre, 1.0 = hard right. */
    val pan: Float = 0.5f,
    /** Semitones, -36..36. */
    val tuneCoarse: Int = 0,
    /** Cents, -100..100. */
    val tuneFine: Int = 0,
    /** 0 = no mute group, 1..32 otherwise. Classic use: hats share a group. */
    val muteGroup: Int = 0,
    /** True = play the whole sample regardless of pad release. Right for drums. */
    val oneShot: Boolean = true,
    /**
     * Explicit velocity zones, soft first. Null = the single-sample pad
     * (the default, and the shape the golden file pins): [sampleName] on
     * layer 1 across the full 0..127. When set (1..4 zones), each zone
     * becomes one `<Layer>` with its own sample and velocity window.
     */
    val velocityLayers: List<VelocityLayer>? = null,
    /**
     * Pad colour as packed 24-bit `0xRRGGBB`, or null for unset. Real
     * programs carry these in the ProgramPads blob (`pads.valueN`, `0` =
     * unset) with `Universal` switched off — decoded from commercial packs,
     * see docs/XPM_STRUCTURE.md. This is how a SnipSnap kit arrives on the
     * MPC already wearing its class colours.
     */
    val color: Int? = null,
    /**
     * Pad shape as metadata — the hardware renders it, the audio stays
     * pristine. Null means the format's own default, so an unshaped pad
     * writes byte-identical to before these fields existed. All values
     * 0..1 in the formats' own units: [attack]/[decay] on the volume
     * envelope, [cutoff]/[resonance] on the pad's first filter slot.
     */
    val attack: Float? = null,
    val decay: Float? = null,
    val cutoff: Float? = null,
    val resonance: Float? = null,
    /**
     * Per-hit randomization, 0..1 — MPC 3 only. The `.xtd` layer
     * carries real pitch/volume/pan randomization the hardware renders
     * per hit; the `.xpm` has no such fields, so the MPC 2 generation
     * honestly ignores this. Distinct from round robin, which the MPC 3
     * does have — chain-based Slice Motion, see [chain].
     */
    val humanize: Float? = null,
    /** Chain playback (see [ChainPlay]); null = the ordinary one-shot pad. */
    val chain: ChainPlay? = null,
) {
    init {
        require(sampleName.isNotBlank()) { "sampleName must not be blank" }
        if (chain != null) {
            require(velocityLayers == null) { "a chain pad is single-zone (velocity x round-robin grids come later)" }
        }
        for ((name, v) in listOf(
            "attack" to attack, "decay" to decay, "cutoff" to cutoff,
            "resonance" to resonance, "humanize" to humanize,
        )) {
            v?.let { require(it in 0f..1f) { "$name out of range: $it" } }
        }
        require(frameCount >= 0) { "frameCount must not be negative: $frameCount" }
        require(level in 0f..1f) { "level out of range: $level" }
        require(pan in 0f..1f) { "pan out of range: $pan" }
        require(tuneCoarse in -36..36) { "tuneCoarse out of range: $tuneCoarse" }
        require(tuneFine in -100..100) { "tuneFine out of range: $tuneFine" }
        require(muteGroup in 0..32) { "muteGroup out of range: $muteGroup" }
        color?.let { require(it in 1..0xFFFFFF) { "color must be packed 0xRRGGBB (1..0xFFFFFF), got $it" } }
        velocityLayers?.let { layers ->
            require(layers.size in 1..4) { "a pad has 1..4 layers, got ${layers.size}" }
            for (i in 1 until layers.size) {
                require(layers[i].velStart > layers[i - 1].velEnd) {
                    "velocity zones must ascend without overlap: " +
                        "${layers[i - 1].velStart}..${layers[i - 1].velEnd} then ${layers[i].velStart}..${layers[i].velEnd}"
                }
            }
        }
    }
}

/**
 * An MPC drum program, targeting the MPC 2-era XPM format (File_Version 2.1)
 * as written by MPC standalone firmware.
 *
 * [pads] is ordered from pad A01 upward. A null entry is an empty pad; trailing
 * nulls are simply not emitted. Up to [PadNoteMap.PAD_COUNT] entries.
 */
data class DrumProgram(
    val name: String,
    val pads: List<Pad?>,
    /** Program master level, 0.0..1.0. MPC default is 0.71. */
    val level: Float = 0.71f,
    /** Program pan, 0.0..1.0. */
    val pan: Float = 0.5f,
    /** Voices. MPC default is 16. */
    val polyphony: Int = 16,
) {
    init {
        require(name.isNotBlank()) { "program name must not be blank" }
        require(pads.size <= PadNoteMap.PAD_COUNT) {
            "a drum program holds at most ${PadNoteMap.PAD_COUNT} pads, got ${pads.size}"
        }
        require(level in 0f..1f) { "level out of range: $level" }
        require(pan in 0f..1f) { "pan out of range: $pan" }
        require(polyphony in 1..32) { "polyphony out of range: $polyphony" }
    }

    /** Pads actually carrying a sample, paired with their 1-based pad number. */
    fun occupiedPads(): List<IndexedValue<Pad>> =
        pads.withIndex().mapNotNull { (i, pad) -> pad?.let { IndexedValue(i + 1, it) } }
}
