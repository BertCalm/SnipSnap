package com.snipsnap.xpm

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
) {
    init {
        require(sampleName.isNotBlank()) { "sampleName must not be blank" }
        require(frameCount >= 0) { "frameCount must not be negative: $frameCount" }
        require(level in 0f..1f) { "level out of range: $level" }
        require(pan in 0f..1f) { "pan out of range: $pan" }
        require(tuneCoarse in -36..36) { "tuneCoarse out of range: $tuneCoarse" }
        require(tuneFine in -100..100) { "tuneFine out of range: $tuneFine" }
        require(muteGroup in 0..32) { "muteGroup out of range: $muteGroup" }
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
