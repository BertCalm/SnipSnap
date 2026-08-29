package com.snipsnap.app

import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File

/**
 * M0's interim pad voice: one [SoundPool] per open kit.
 *
 * Deliberately dumb — APP_PLAN.md's walking-skeleton milestone says
 * "interim SoundPool is fine here". Choke groups, velocity from touch and
 * real latency work belong to M4's Oboe voice allocator (`:shell`'s
 * `VoiceAllocator` is already written and tested for it); wiring those
 * into SoundPool would be effort spent on a component scheduled to die.
 *
 * Level and pan are honoured (constant-power-ish: full side at centre,
 * the other side fading as pan leaves it), because a kit that ships with
 * mix decisions should not preview flat.
 */
class PadPlayer {

    private val pool = SoundPool.Builder()
        .setMaxStreams(16)
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

    fun play(slot: Int) {
        val sound = soundBySlot[slot] ?: return
        val (left, right) = gainBySlot[slot] ?: (1f to 1f)
        pool.play(sound, left, right, 1, 0, 1f)
    }

    fun release() {
        pool.release()
        soundBySlot.clear()
        gainBySlot.clear()
    }
}
