package com.snipsnap.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.snipsnap.app.ui.GROOVE_SWING_DEFAULT
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.GrooveProgram
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.LiveRecord
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.shell.BeatClock
import com.snipsnap.shell.Copy
import com.snipsnap.shell.PadHit
import com.snipsnap.shell.VoiceAllocator
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The project's beat: a clock, a position and a program to play over the
 * open kit's pads. It used to be GROOVE's `withFrameNanos` loop and the
 * handful of locals around it, which made "the beat" and "the screen that
 * draws it" the same thing - leave BEAT and the beat stopped. This is the
 * loop and its state without the screen, so it can outlast one.
 *
 * What stays with the screen is everything about the program as a person
 * edits it (the five programs, FEEL, SWING, the step editor), RECORD's
 * take and count-in, BOUNCE and AUDITION. The screen pushes the program it
 * is showing in through [setProgram], and rides the clock through a
 * [BeatTap]: the hooks either side of the notes where the things that only
 * make sense next to the roll (a bounce arming on the downbeat, an
 * audition swapping a pad's sound) need to stand.
 *
 * The clock is a `withFrameNanos` loop, [run], driven from a
 * `LaunchedEffect` that App keeps for the beat's life, and it has to stay
 * on that timebase: `GrooveScreen.recordHit` compares a touch's
 * `uptimeMillis` with the nanoseconds this loop anchors, and a clock on a
 * thread of its own would put the two on different ones.
 *
 * It sounds through the kit's [PadHost], held through [hosts] for exactly
 * as long as it is playing, so the host exists for the beat when no screen
 * has one and is the same host when a screen does. It is its own
 * [AudioVoice]: acquired on [play], released on [stop], and told to be
 * quiet by a focus loss or by App when the app stops.
 *
 * Main thread throughout, like everything it holds.
 */
class BeatPlayer(
    /** The registry the beat takes its host from. A screen that wants the same kit's host takes it from here too, so they are one. */
    val hosts: PadHosts,
    /** For the one thing [toggle] does off the main thread: reading the kit's grooves when no screen has. */
    private val scope: CoroutineScope,
    /** The kit the beat plays over, read live: the open kit, so an edit elsewhere is heard on the next hit. */
    private val kitNow: () -> KitShelf.Entry?,
    /** The program settings to stand in with when no screen has pushed a program (the ones App hoists for GROOVE). */
    private val settings: () -> Settings = { Settings() },
) : AudioVoice {

    /** FEEL, SWING, the program and the roll of the dice that [setProgram]'s clip would have been computed with. */
    data class Settings(
        val progIndex: Int = 0,
        val swingPercent: Int = GROOVE_SWING_DEFAULT,
        val feel: Int = 0,
        val seed: Int = 1,
    )

    /**
     * What one tick is working with, handed to the [BeatTap] either side of
     * the notes. One object, reused, so a frame allocates nothing; valid only
     * for the call it is passed to.
     */
    class Tick {
        var clip: Mpc3Clip? = null
        var lastPos = 0f
        var np = 0f
        var totalSteps = 0f
        var barSteps = GrooveEdit.STEPS_PER_BAR
        var stepsPerSecond = 0.0
        var wrapped = false
    }

    /** The beat is running. The dock's chip and the roll's needle read this; nothing in App reads [position]. */
    var playing by mutableStateOf(false)
        private set

    /** Steps into the loop. Written every frame, so only the roll that draws it should read it in composition. */
    var position by mutableFloatStateOf(0f)
        private set

    /** The bar the needle is in, from one. Written only when it changes, so the dock can follow it at bar granularity. */
    var bar by mutableIntStateOf(1)
        private set

    /**
     * A take is going in: RECORD has armed, count-in included. Set by the
     * screen that owns the take; this clock only reads them, to click the
     * metronome and to anchor the first beat. Leaving the screen sets them
     * both false, which drops the take and leaves the beat playing without
     * a click.
     */
    var recording by mutableStateOf(false)

    /** The count-in is sounding. Always with [recording] true: the capture gate opens with the anchor. */
    var countingIn by mutableStateOf(false)

    /** The screen's hooks into the loop, or null when no screen is up to need them. */
    var tap: BeatTap? = null

    /**
     * The last tick's reading, in the frame clock's nanoseconds and in steps:
     * the reference a touch is placed against. Not Compose state, because
     * nothing draws from it; it is read from a pointer callback between
     * frames. Written by the loop on every tick, and by [anchorAt] for the
     * few moments a screen knows where the needle will be before a tick does.
     */
    var anchorNanos = 0L
        private set
    var anchorPos = 0f
        private set

    private val _triggered = MutableSharedFlow<Int>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The slot of each pad the clock sounded, as it sounded it: what a grid lights from. Never suspends the frame it is emitted from. */
    val triggered: SharedFlow<Int> = _triggered

    private val tick = Tick()
    private var clip: Mpc3Clip? = null
    private var fromScratchSteps = LiveRecord.DEFAULT_BARS * GrooveEdit.STEPS_PER_BAR
    private var programDir: File? = null

    /**
     * The program to play: [clip], computed by whoever is showing it, for the
     * kit at [dir]. [fromScratchSteps] is the loop's length when there is no
     * clip yet - a from-scratch take plays against `recordBars` bars of
     * nothing.
     *
     * Called by a screen after every composition that changed it. A null
     * [clip] is a real answer (a kit with no groove), and so a screen must
     * not push one for a kit it has not finished loading.
     */
    fun setProgram(dir: File, clip: Mpc3Clip?, fromScratchSteps: Int) {
        this.programDir = dir
        this.clip = clip
        this.fromScratchSteps = fromScratchSteps
        noteBar()
    }

    /**
     * The program has something to sound. A beat playing over none is a clock
     * counting nothing - a from-scratch take whose screen was left - and the
     * dock would say it was playing for silence.
     */
    val audible: Boolean get() = clip?.notes?.isNotEmpty() == true

    /** Where the needle stands now; RECORD resets it to the top. */
    fun seek(pos: Float) {
        position = pos
        noteBar()
    }

    /**
     * Say where the needle is, or will be, at [nanos] on the frame clock's
     * timebase, before a tick has. RECORD does this at the instant it arms,
     * so a hit on bar one's downbeat has a reference to be placed against
     * even though the loop has not started.
     */
    fun anchorAt(nanos: Long, pos: Float) {
        anchorNanos = nanos
        anchorPos = pos
    }

    /**
     * Start the clock over the open kit. Takes the kit's host from [hosts]
     * and holds it until [stop], so the beat sounds with no screen up; and
     * takes audio focus. Does nothing with no kit open, or when already going.
     */
    fun play() {
        if (playing) return
        val entry = kitNow() ?: return
        val host = hosts.acquire(this, entry.dir)
        host.request(entry)
        AudioFocus.acquire(this)
        playing = true
    }

    /**
     * Stop the clock. The voices already sounding ring out, as they always
     * have; the host is let go, and goes with the last holder.
     */
    fun stop() {
        if (!playing) return
        playing = false
        AudioFocus.release(this)
        hosts.release(this)
    }

    /**
     * The dock's chip. Stops a running beat; starts a stopped one, if there
     * is something to start, and says why not when there is not.
     *
     * A take going in owns the clock, so the chip does nothing then: the
     * same answer the screen's own play request gave. A kit whose grooves
     * nobody has loaded yet (no screen has been up) is read first, off the
     * main thread, which is why [say] can be called after this returns.
     */
    fun toggle(say: (String) -> Unit) {
        if (recording || countingIn) return
        if (playing) {
            stop()
            return
        }
        val entry = kitNow()
        if (entry == null) {
            say(Copy.ADD_KIT_FIRST)
            return
        }
        if (entry.kit.pads.isEmpty()) {
            say(Copy.BEAT_NEEDS_PADS)
            return
        }
        if (programDir == entry.dir) {
            startOrSay(say)
            return
        }
        scope.launch {
            try {
                loadProgram(entry)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "toggle: the kit's grooves would not read", e)
                say(Copy.actionFailed("PLAY"))
                return@launch
            }
            startOrSay(say)
        }
    }

    private fun startOrSay(say: (String) -> Unit) {
        // A beat with no notes has nothing to start: say so, rather than let
        // the chip do nothing and the dock stay silent.
        if (clip?.notes.isNullOrEmpty()) say(Copy.NOTHING_TO_PLAY) else play()
    }

    /**
     * Read the kit's grooves and compute the program from the settings App
     * keeps for GROOVE, for a beat started with no screen to push one.
     *
     * YOURS is the first of the player's own programs: which of several
     * they last cycled to is the screen's, not hoisted, so the beat cannot
     * know it. A program index that names a YOURS that is not there falls
     * back to the captured program, as the screen does.
     */
    private suspend fun loadProgram(entry: KitShelf.Entry) {
        val dir = entry.dir
        val (base, yours) = withContext(Dispatchers.IO) {
            GrooveStore.load(dir).firstOrNull { !GrooveEdit.isProgE(it) } to GrooveEdit.loadAll(dir)
        }
        val e = yours.firstOrNull()
        val s = settings()
        val prog = if (s.progIndex >= (if (e != null) 5 else 4)) 0 else s.progIndex
        val computed = base?.let { GrooveProgram.compute(prog, it, s.swingPercent, s.feel, GrooveFeel.generated(s.seed), e) }
        // A screen that came up while the disk was read has pushed the live
        // program, and its answer wins; so does the kit having changed.
        if (programDir == dir || kitNow()?.dir != dir) return
        setProgram(dir, computed, LiveRecord.DEFAULT_BARS * GrooveEdit.STEPS_PER_BAR)
    }

    /** The kit's bank follows an edit made elsewhere, for the beat when it is the only one holding the host. */
    fun kitChanged(entry: KitShelf.Entry) {
        hosts.peek(entry.dir)?.request(entry)
    }

    /**
     * Quiet, now: the take is dropped, the clock stops and every voice is
     * faded. A focus loss and the app going to the background both mean this,
     * and neither waits for a screen to be there to ask.
     */
    override fun silence() {
        recording = false
        countingIn = false
        kitNow()?.let { hosts.peek(it.dir)?.allOff() }
        stop()
    }

    /**
     * The kit the beat was over is gone, or another is open: stop, and forget
     * the program and the position, which belonged to it.
     */
    fun forget() {
        recording = false
        countingIn = false
        stop()
        clip = null
        programDir = null
        position = 0f
        bar = 1
    }

    /**
     * One sound, through the host's engine and allocator: the pad at [slot],
     * [velocity] hard. Forced one-shot: neither the roll nor a cell tap has
     * a release gesture to end a gate pad with. A slot with nothing on it is
     * silence rather than a stuck voice - the allocation is handed back.
     *
     * [auditionPos] and [auditionBarSteps] are AUDITION's seam, non-null only
     * from the clock's own note dispatch: the one caller actually playing
     * "the pattern". The step editor's preview and RECORD's touch input call
     * this too, but a manual tap is not the automatic bar-line swap, so they
     * get the pad's plain sound.
     */
    fun hit(slot: Int, velocity: Float = 1f, auditionPos: Float? = null, auditionBarSteps: Int = GrooveEdit.STEPS_PER_BAR) {
        val entry = kitNow() ?: return
        val host = hosts.peek(entry.dir) ?: return
        val pad = entry.kit.pad(slot) ?: return
        if (!host.up || !host.engine.isUp()) return
        val allocator: VoiceAllocator = host.allocator
        val allocation = allocator.noteOn(slot, velocity, pad.muteGroup, oneShot = true)
        for (voice in allocation.choked + allocation.stolen) host.engine.stop(voice.id)
        // Resolved here, on the caller's side of `PadEngine.hit`'s own
        // monitor, so the tap's lookups happen before the swap is built and
        // never inside it: `hit`'s own KDoc is explicit that the lambda runs
        // while its monitor is held and must not call back into the engine.
        val swap = if (auditionPos != null) tap?.swapFor(slot, auditionPos, auditionBarSteps) else null
        if (!host.engine.hit(pad, velocity, allocation.started.id, swap)) allocator.voiceEnded(allocation.started.id)
    }

    private fun noteBar() {
        val barSteps = clip?.let { GrooveEdit.stepsPerBar(it) } ?: GrooveEdit.STEPS_PER_BAR
        val b = BeatClock.bar(position, barSteps)
        if (b != bar) bar = b
    }

    /**
     * The playback clock - a `withFrameNanos` loop converting elapsed wall
     * time to elapsed steps (TAPE's own dt-clamp lesson against a stale
     * frame gap fast-forwarding the needle). Notes are read live each tick
     * from the program last pushed, so switching programs mid-play changes
     * what's triggered on the very next tick.
     *
     * Every note triggers here, not just the seven lane notes the roll
     * draws - a groove with a TONAL pad or unclassified sample hit should
     * still be heard, same as it's still written by MIDI. The lane notes are
     * just seven points on the writer's own chromatic map, not a rule of
     * their own, so `Mpc3Note.slotFor` recovers the pad slot for any note -
     * including the ones past the wrap, where plain subtraction gives a slot
     * no kit has; [hit] is silence on a slot with nothing loaded.
     *
     * Run from a `LaunchedEffect` keyed on [playing], the kit's folder and
     * the kit itself: an edit that reaches the clock while it runs restarts
     * it, and restarting costs nothing - `lastPos` is read from [position],
     * which is state, so the needle resumes where it was.
     *
     * Position advances UNCONDITIONALLY whenever [playing] is true, not only
     * when there's a clip with notes to trigger: a from-scratch take has no
     * clip yet, but the clock still has to run (against `fromScratchSteps`)
     * for a touch to have anything to be placed against. Triggering stays
     * conditional on a real clip; advancing does not. [anchorNanos] and
     * [anchorPos] mirror `lastNanos`/`lastPos` on every tick so a touch,
     * arriving between frames from a pointer callback, reads the SAME
     * reference point this loop just used - one formula, shared.
     */
    suspend fun run() {
        if (!playing) return
        val entry = kitNow() ?: return
        val host = hosts.peek(entry.dir) ?: return
        val engine = host.engine
        // Nothing to count against until there is a bank to sound: a beat
        // started before the first one commits would play its first bar
        // silent. Only waited for here, at the start, never mid-loop - a
        // reload leaves the old bank sounding until the new one is whole.
        while (!host.bankReady) withFrameNanos { }
        var lastNanos = withFrameNanos { it }
        var lastPos = position
        anchorNanos = lastNanos
        anchorPos = lastPos
        // Fix 1's own seam bug: the count-in's click loop fires its four
        // clicks on ITS OWN schedule, then seeks to 0 and plays - which
        // starts THIS loop with `lastPos = 0f`. The beat-crossing pass below
        // fires on `b > lastPos && b <= np`, so `b = 0` - bar 1's downbeat,
        // the most important click of the whole take - never crosses on this
        // first tick; every LATER pass over the loop does catch it, via the
        // wrap clause. One accented click here, once, at restart, anchors
        // that first beat instead. Gated on `recording`, not `countingIn`:
        // an overdub with the loop already stopped also resets the position
        // to 0 and restarts this same loop, and needs the identical anchor
        // click. A tempo or kit change mid-take (the kit is one of this
        // loop's own keys) re-enters here too and fires one EXTRA click that
        // isn't a real downbeat - accepted: one spurious click mid-take beats
        // a silent bar 1 every time.
        if (recording) engine.clickHit(accent = true)
        // A bounce armed or in flight when this loop is cancelled - STOP, a
        // kit or tempo change, or leaving the screen mid-bounce all restart
        // or end it - is abandoned by the tap's `loopEnded`, the same silent
        // call a dropped take makes: the pattern's own loop is gone, so there
        // is no downbeat left to bounce against.
        try {
            while (currentCoroutineContext().isActive) {
                withFrameNanos { now ->
                    // The host's housekeeping rides the beat's frame too: with
                    // no screen up, nothing else drains the endings of the
                    // voices this loop starts. Safe beside a screen's own turn
                    // of it - the endings land on the one allocator either way.
                    host.pump(now)
                    val dtNanos = BeatClock.dtNanos(now, lastNanos)
                    lastNanos = now
                    val clip = clip

                    // The clip's own length and bar, not `bars × 16`: an
                    // ORBIT clip declares its meter, and a two-bar 3/4 clip
                    // looped at 32 steps played eight of silence on the end
                    // of every pass (September wiring review, finding 2).
                    // A from-scratch take has no clip yet and is 4/4.
                    val totalSteps = (clip?.let { GrooveEdit.stepsInClip(it) } ?: fromScratchSteps).toFloat()
                    val barSteps = clip?.let { GrooveEdit.stepsPerBar(it) } ?: GrooveEdit.STEPS_PER_BAR
                    val bpm = kitNow()?.kit?.tempoBpm ?: KitPreview.DEFAULT_BPM
                    val stepsPerSecond = BeatClock.stepsPerSecond(bpm)
                    var np = BeatClock.advance(lastPos, dtNanos, stepsPerSecond)
                    val wrapped = BeatClock.wrapped(np, totalSteps)

                    tick.clip = clip
                    tick.lastPos = lastPos
                    tick.np = np
                    tick.totalSteps = totalSteps
                    tick.barSteps = barSteps
                    tick.stepsPerSecond = stepsPerSecond
                    tick.wrapped = wrapped

                    // BOUNCE's pattern-change guard and its start/stop, ahead
                    // of the note dispatch below on purpose (review's own
                    // finding): `hit()` only queues a command onto a ring the
                    // audio thread drains on its own next callback, so arming
                    // or requesting a stop AFTER this frame's notes are
                    // already queued risks the very downbeat that started the
                    // bounce (or the next pass's first note) landing on the
                    // wrong side of the boundary. Arming here, before any
                    // queueing, closes that gap as far as this frame can.
                    tap?.beforeNotes(tick)

                    if (clip != null && clip.notes.isNotEmpty()) {
                        // `BeatClock.crossed` is the old inline test, wrap clause
                        // and all (`BeatClockTest` pins it).
                        BeatClock.forEachNoteCrossed(clip, lastPos, np, totalSteps) { n ->
                            // Mpc3Note.slotFor, not `note - 35`: the map wraps, so
                            // notes 0..35 are pads 93..128 and subtracting alone
                            // gives them a slot no kit has. A clip that plays on
                            // the MPC would be silent in this roll.
                            //
                            // AUDITION's own seam: `np`, the playhead THIS
                            // frame is advancing to, not the note's own
                            // position within the clip, which the wrap clause
                            // can offset by a full `totalSteps` - `np`
                            // is what the position is about to become, so it's
                            // the same "which bar is this" the screen itself
                            // will show a moment later.
                            val slot = Mpc3Note.slotFor(n.note)
                            hit(slot, auditionPos = np, auditionBarSteps = barSteps)
                            _triggered.tryEmit(slot)
                        }
                    }
                    // Fix 1 - the metronome through the WHOLE take, not just the
                    // count-in: driven from THIS clock's own beat-boundary
                    // crossings (same `crossed` shape the note loop above just
                    // used), never a second delay-based loop timed off
                    // System.nanoTime() - that reintroduces the two-timebase
                    // bug class the anchor mechanism already cost this project
                    // a shipped defect closing once. A beat is 4 steps;
                    // accent lands on every bar downbeat, same as the
                    // count-in's own `beat == 0` accent. Gated on `recording`,
                    // not `playing` - ordinary PLAY/STOP must stay silent
                    // here; only a take actually in progress gets a click to
                    // play against.
                    if (recording) {
                        BeatClock.forEachBeatCrossed(totalSteps, lastPos, np, barSteps) { accent ->
                            engine.clickHit(accent = accent)
                        }
                    }
                    // The rare early-DONE case (PadEngine's own byte budget
                    // filled before the loop naturally wrapped) is checked
                    // after dispatch, not before - nothing about it is tied
                    // to a note or wrap boundary, so there is no ordering
                    // risk to close here the way there is above. Landing the
                    // shorter, already-complete take beats refusing the
                    // whole bounce over a ceiling that exists for memory,
                    // not for musical correctness.
                    tap?.afterNotes(tick)
                    np = BeatClock.wrap(np, totalSteps)
                    lastPos = np
                    position = np
                    anchorNanos = now
                    anchorPos = np
                    val b = BeatClock.bar(np, barSteps)
                    if (b != bar) bar = b
                }
            }
        } finally {
            tap?.loopEnded()
        }
    }

    private companion object {
        const val TAG = "BeatPlayer"
    }
}

/**
 * What a screen hangs on the beat's clock. All four are called on the main
 * thread, inside the frame; [BeatPlayer.Tick] is valid only for the call.
 */
interface BeatTap {

    /** Before this frame's notes are queued: the one place a bounce can arm or finish without a note landing on the wrong side of its boundary. */
    fun beforeNotes(tick: BeatPlayer.Tick)

    /** After the notes and the metronome, before the position moves. */
    fun afterNotes(tick: BeatPlayer.Tick)

    /** The loop ended - stopped, or restarted by a kit change - so whatever hung on it has no downbeat left to wait for. */
    fun loopEnded()

    /** AUDITION: a rewrite of the hit about to sound at [slot], or null to play the pad as it is. The lookups happen here, not in what is returned. */
    fun swapFor(slot: Int, pos: Float, barSteps: Int): ((PadHit.Hit) -> PadHit.Hit?)? = null
}
