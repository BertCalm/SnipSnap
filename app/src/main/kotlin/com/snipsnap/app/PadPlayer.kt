package com.snipsnap.app

import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File

/**
 * M0's interim pad voice: one [SoundPool] per open kit.
 *
 * Deliberately dumb — APP_PLAN.md's walking-skeleton milestone says
 * "interim SoundPool is fine here". Real latency work belongs to a later
 * Oboe voice engine; wiring that in would be effort spent on a component
 * scheduled to die.
 *
 * M4 (PLAY) needs real choke-group behaviour on top of `:shell`'s tested
 * `VoiceAllocator`, which means the screen must be able to stop a specific
 * sounding voice — SoundPool can only do that by stream id, and the old
 * no-return `play()` threw that id away. [play] now returns it (scaled by
 * [stopStream]'s caller-supplied velocity) and [stopStream] stops it; the
 * old no-arg call sites keep compiling unchanged via `velocity`'s default.
 *
 * Level and pan are honoured (constant-power-ish: full side at centre,
 * the other side fading as pan leaves it), because a kit that ships with
 * mix decisions should not preview flat.
 */
class PadPlayer {

    private val pool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .build()

    private val soundBySlot = mutableMapOf<Int, Int>()
    private val gainBySlot = mutableMapOf<Int, Pair<Float, Float>>()

    /**
     * Queue every pad's WAV for decoding. Loading is asynchronous; a tap
     * that beats the decoder plays nothing (SoundPool's contract), which
     * on a 16-pad kit resolves within a moment of the screen opening.
     */
    fun load(entry: KitShelf.Entry) {
        for (pad in entry.kit.pads) {
            val f = File(entry.dir, pad.sampleFile)
            if (!f.isFile) continue
            soundBySlot[pad.slot] = pool.load(f.absolutePath, 1)
            val left = pad.level * (2f * (1f - pad.pan)).coerceAtMost(1f)
            val right = pad.level * (2f * pad.pan).coerceAtMost(1f)
            gainBySlot[pad.slot] = left to right
        }
    }

    /**
     * Play [slot] at [velocity] (0..1, default 1 = the old fixed-gain
     * behaviour) and return the SoundPool stream id, or 0 if the pad has
     * no loaded sample or SoundPool couldn't start a stream — 0 is
     * SoundPool's own "no stream" sentinel, so [stopStream] treats it as a
     * no-op rather than a real id to stop.
     */
    fun play(slot: Int, velocity: Float = 1f): Int {
        val sound = soundBySlot[slot] ?: return 0
        val (left, right) = gainBySlot[slot] ?: (1f to 1f)
        return pool.play(sound, left * velocity, right * velocity, 1, 0, 1f)
    }

    /** Stop a voice started by [play], identified by the stream id it returned. */
    fun stopStream(streamId: Int) {
        if (streamId != 0) pool.stop(streamId)
    }

    fun release() {
        pool.release()
        soundBySlot.clear()
        gainBySlot.clear()
    }

    companion object {
        /**
         * SoundPool's real concurrent-voice ceiling — past this it silently
         * reclaims its own oldest stream, independent of anything a caller's
         * voice-count bookkeeping thinks is still active. PLAY's
         * `VoiceAllocator` must be configured at this same number (not its
         * own 32 default), or the allocator counts voices SoundPool has
         * already reused out from under it — see PlayScreen.kt's allocator
         * construction and status line, both of which read this constant
         * instead of duplicating the literal.
         */
        const val MAX_STREAMS = 16
    }
}
