package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snipsnap.app.MixVoice
import com.snipsnap.shell.Arranger
import com.snipsnap.shell.SongCursor

/**
 * SONG's live play (song plan Phase 2): PLAY from any section, LOOP the one
 * playing, a section queued to play next, and the record of what was heard
 * that KEEP THIS ORDER turns into the song.
 *
 * The transport is [SongCursor] over the mix PLAY already renders, streamed
 * by a [MixVoice]. Where the screen draws the needle comes from the
 * speaker's position ([MixVoice.heardFrames]), not a wall clock: after a
 * jump or a loop, time since PLAY no longer says where in the song it is.
 *
 * A plain class, as [SongKeeper] is: ARRANGE's composable sits near the dex
 * method-size limit, and state it does not declare costs it nothing.
 */
internal class LivePlay {
    var playing by mutableStateOf(false)
        private set

    /** LOOP: the section playing starts again when its last pass ends. Kept across plays. */
    var loop by mutableStateOf(false)
        private set

    /** The section that plays when the current pass ends. */
    var queued by mutableStateOf<Int?>(null)
        private set

    /** The section the speaker is playing now. */
    var active by mutableStateOf<Int?>(null)
        private set

    /** The section of every pass heard in the last play, in order: what KEEP THIS ORDER keeps. */
    var take by mutableStateOf<List<Int>>(emptyList())
        private set

    private var voice: MixVoice? = null
    private var cursor: SongCursor? = null

    /** Plays [mix] from section [from]'s first pass. */
    fun start(mix: Arranger.Mix, from: Int) {
        stop()
        val frames = mix.snip.samples.size / SongCursor.CHANNELS
        val passes = mix.passes.ifEmpty { listOf(Arranger.Pass(0, 0, frames)) }
        val c = SongCursor(mix.snip.samples, passes, from).also { it.loop = loop }
        val v = MixVoice(c, mix.snip.samples, mix.snip.sampleRate)
        cursor = c
        voice = v
        playing = true
        active = from
        queued = null
        take = emptyList()
        v.start()
    }

    /** Stops, keeping what was heard up to now (not what was written ahead of the speaker) as [take]. */
    fun stop() {
        val v = voice ?: return
        val heard = v.heardFrames()
        v.stop()
        cursor?.let { take = it.played(heard) }
        voice = null
        cursor = null
        playing = false
        active = null
        queued = null
    }

    fun toggleLoop() {
        loop = !loop
        cursor?.loop = loop
    }

    /** [section] plays when the current pass ends; queuing the one already queued takes it back. */
    fun queue(section: Int) {
        val c = cursor ?: return
        val next = if (queued == section) null else section
        c.queued = next
        queued = next
    }

    /** Once a frame while playing: follows the speaker, and stops at the song's end. */
    fun tick() {
        val v = voice ?: return
        val c = cursor ?: return
        active = c.sectionAt(v.heardFrames())
        // The cursor takes the queue when it writes the next pass, a buffer ahead of the speaker.
        if (queued != null && c.queued == null) queued = null
        if (v.ended) stop()
    }

    /** The song changed: what was played belongs to the old one. */
    fun forget() {
        stop()
        take = emptyList()
    }

    fun release() {
        voice?.release()
        voice = null
        cursor = null
        playing = false
    }
}
