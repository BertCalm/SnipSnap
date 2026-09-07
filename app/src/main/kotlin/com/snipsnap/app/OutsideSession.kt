package com.snipsnap.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import kotlin.math.min

/**
 * The OUTSIDE trip, on the phone: listen, send, keep listening.
 *
 * One blocking [run] does the whole thing on the caller's thread (the
 * PAD SHEET calls it on `Dispatchers.IO`): an [AudioRecord] starts
 * filling the return, and once [preRollFrames] of room have been heard
 * an [AudioTrack] plays the send — out of whatever the phone's output
 * route is right now: the speaker into the room, or the headphone jack
 * or a USB interface into a pedal, an amp, a spring. Recording keeps
 * going until [listenFrames] are in. Nothing in here lines anything up:
 * the return goes back to `:shell`'s `OutsideSheet`, and `Outside.align`
 * finds the send in it wherever the audio stack put it. That is the
 * design's whole point — the trip's latency is *measured*, never
 * assumed, so this class is free to be dumb.
 *
 * The send plays from a `MODE_STATIC` track: the whole clip is written
 * before `play()`, so there is no streaming thread to pace and no
 * under-run to fear; the trade is that the start latency is the audio
 * stack's own, which the alignment absorbs.
 *
 * The record source is UNPROCESSED where the device offers it, else
 * VOICE_RECOGNITION — the same choice `MicSessionService` makes, for the
 * same reason, and here it matters more: a source with echo cancellation
 * would work hard to *remove* exactly the signal we are sending.
 *
 * Caller's contract: RECORD_AUDIO already granted (the ARM flow on KITS
 * does that), and no other session on the mic (an armed
 * `MicSessionService` holds it — the card refuses first).
 */
object OutsideSession {

    private const val READ_BLOCK_FRAMES = 2048

    /**
     * Listen for [listenFrames] at [send]'s rate, playing [send] once
     * [preRollFrames] are in; [onSending] fires on this thread as play
     * starts, for the status line. Returns the mono return. Throws
     * [IllegalStateException] when the device refuses the record or the
     * track, or a read fails partway — never a zero-padded return that
     * would pass for a quiet room. The card shows the message, in the
     * same shape every other failure takes.
     */
    fun run(
        context: Context,
        send: Snip,
        preRollFrames: Int,
        listenFrames: Int,
        onSending: () -> Unit = {},
    ): Snip {
        val rate = send.sampleRate
        val mono = if (send.channels == 1) send.samples else Cleanup.toMono(send).samples

        val record = openRecord(context, rate)
        val track = try {
            buildTrack(rate, mono)
        } catch (e: Exception) {
            record.release()
            throw e
        }

        val out = FloatArray(listenFrames)
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "the mic never started recording" }
            val block = FloatArray(READ_BLOCK_FRAMES)
            var filled = 0
            var sending = false
            while (filled < listenFrames) {
                val want = min(block.size, listenFrames - filled)
                // A read that fails is a failed trip, never a quiet one: a
                // zero-padded return would read as a room that went silent
                // and skew the alignment and the tail cut. Fail fast, and
                // say where it stopped.
                val n = try {
                    record.read(block, 0, want, AudioRecord.READ_BLOCKING)
                } catch (e: IllegalStateException) {
                    throw IllegalStateException("the mic died at $filled of $listenFrames frames", e)
                }
                check(n > 0) { "the mic stopped at $filled of $listenFrames frames (read returned $n)" }
                System.arraycopy(block, 0, out, filled, n)
                filled += n
                if (!sending && filled >= preRollFrames) {
                    sending = true
                    onSending()
                    track.play()
                }
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        return Snip(out, 1, rate)
    }

    private fun openRecord(context: Context, rate: Int): AudioRecord {
        val unprocessed = context.getSystemService(AudioManager::class.java)
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minBuffer > 0) { "the device refuses a $rate Hz float mic ($minBuffer)" }
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 4)
                .build()
        } catch (e: SecurityException) {
            throw IllegalStateException("the mic is not granted", e)
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException("the device rejected the mic format", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("the mic would not open")
        }
        return record
    }

    /** A static track holding the whole send, written before play. */
    private fun buildTrack(rate: Int, mono: FloatArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(mono.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        if (track.state != AudioTrack.STATE_NO_STATIC_DATA && track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("the output would not open")
        }
        val written = track.write(mono, 0, mono.size, AudioTrack.WRITE_BLOCKING)
        if (written != mono.size) {
            track.release()
            throw IllegalStateException("the output took $written of ${mono.size} frames")
        }
        return track
    }
}
