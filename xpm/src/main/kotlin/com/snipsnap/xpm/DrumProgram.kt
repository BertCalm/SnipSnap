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
) {
    init {
        require(sampleName.isNotBlank()) { "sampleName must not be blank" }
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
