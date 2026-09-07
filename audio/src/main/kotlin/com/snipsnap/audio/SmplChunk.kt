package com.snipsnap.audio

/**
 * The sampler sheet a WAV can carry inside itself: the RIFF `smpl` chunk's
 * two fields that matter to a sampler, the MIDI root note and one forward
 * sustain loop. Written only when asked for — a drum hit has no root and
 * no loop, and the writer stays byte-stable for every kit that never
 * asked — so a one-note instrument or a looped pad loaded on its own at
 * the MPC, outside any program, arrives already tuned and looping.
 *
 * Frames, not bytes, and the loop's end is exclusive here (the frame
 * after the last one played); the chunk itself stores the last frame
 * inclusive, and the writer and reader convert.
 */
data class SmplChunk(
    val rootNote: Int,
    val loop: Loop? = null,
) {
    data class Loop(val startFrame: Long, val endFrameExclusive: Long) {
        init {
            require(startFrame >= 0) { "loop start must not be negative: $startFrame" }
            require(endFrameExclusive > startFrame) {
                "loop end $endFrameExclusive must come after its start $startFrame"
            }
        }
    }

    init {
        require(rootNote in 0..127) { "root note must be a MIDI note 0..127: $rootNote" }
    }

    /** The loop must lie inside the sample it is written into. */
    fun requireInside(frameCount: Int) {
        val l = loop ?: return
        require(l.endFrameExclusive <= frameCount) {
            "loop end ${l.endFrameExclusive} runs past the sample's $frameCount frames"
        }
    }

    /** The chunk body: 36 bytes of header, 24 more per loop. */
    val byteSize: Int get() = HEADER_BYTES + (if (loop != null) LOOP_BYTES else 0)

    companion object {
        const val TAG = "smpl"
        const val HEADER_BYTES = 36
        const val LOOP_BYTES = 24
    }
}
