package com.snipsnap.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.snipsnap.shell.AudioFocusAction
import com.snipsnap.shell.AudioPolicy
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Something that makes sound and can be told to stop or resume — the one
 * shape every playback path (the `PadEngine`/`SurfaceEngine`/
 * `InstrumentPlayer` screens, [TapeVoice], [GrainVoice], [AndroidAudioSink])
 * presents to [AudioFocus] so it never has to know which kind of engine it's
 * talking to.
 *
 * Both calls land on the main thread, from inside [AudioFocus]'s own focus
 * listener (see its KDoc for exactly when) — so neither may block. No join,
 * no bounded wait, nothing that would park the main thread on an audio
 * thread's own teardown. [silence] is "go quiet *now*, as cheaply as you
 * already know how" — `allOff()`, `gate(false)`, flipping a running flag,
 * `AudioTrack.pause()` — never the same call as a screen's own teardown or
 * close, which is free to block because it never runs from here.
 */
interface AudioVoice {

    /** Stop making sound immediately. Cheap and non-blocking — see the interface KDoc. */
    fun silence()

    /**
     * Focus returned after a loss this voice was [silence]d for and that
     * left room for a comeback (Android's two `TRANSIENT` flavors — never a
     * plain, indefinite loss; see [AudioFocus]'s KDoc for why a plain loss
     * never reaches this). Default is a no-op: most of this app's voices
     * are gesture-driven — a pad hit, a held key, a finger on the surface —
     * and the gesture that made the sound is long gone by the time focus
     * comes back, so there is nothing honest to restart. [AndroidAudioSink]
     * is the one voice that overrides this, because its "sound" is a
     * continuous transport with a real position to un-pause from, not a
     * finger that already lifted.
     */
    fun resume() {}
}

/**
 * The app's one audio-focus owner. Every playback path acquires and
 * releases through this instead of calling `requestAudioFocus` itself —
 * six copies of that call is exactly how this codebase's bugs propagate
 * (see CLAUDE.md's "Critical Patterns": the same lesson that says route
 * store mutations through actions, not six components each calling
 * `setState` directly).
 *
 * **Why one shared owner and not one request per voice.** Android tracks
 * focus per *app process*, not per stream — a second concurrent
 * `requestAudioFocus` from this process doesn't add anything beyond the
 * first, it just reissues the same grant and gives the app two independent
 * places that could each mishandle a loss differently. [voices] is a
 * reference-counted registry instead: the first [acquire] actually calls
 * `requestAudioFocus`, the last matching [release] abandons it, and every
 * voice in between just rides the app's single grant.
 *
 * **STOP vs. PAUSE — the distinction that survives past [AudioPolicy].**
 * [AudioPolicy.actionFor] sorts Android's three loss callbacks into two
 * actions: a plain `AUDIOFOCUS_LOSS` is [AudioFocusAction.STOP]; both
 * `AUDIOFOCUS_LOSS_TRANSIENT` and `_TRANSIENT_CAN_DUCK` are
 * [AudioFocusAction.PAUSE]. Duck was considered for `CAN_DUCK` and
 * rejected: there is no master-gain stage anywhere in this app's playback
 * paths — `SurfaceEngine`, [GrainVoice], and [AndroidAudioSink] expose no
 * gain parameter at all, and improvising one by driving `PadEngine
 * .setGain` per active voice would mean changing what the mix sounds like
 * mid-performance — signal-path territory this change does not touch, and
 * a sampler ducked to a fifth of its level mid-pattern is arguably a worse,
 * more confusing performance than one that goes cleanly silent and comes
 * back. Pausing is both the only implementable answer here and the more
 * honest one.
 *
 * STOP and PAUSE both call [AudioVoice.silence] on every registered voice —
 * from a voice's own side, going quiet is going quiet either way. What
 * differs is what *this object* does with its own focus grant afterward:
 * PAUSE leaves the [AudioFocusRequest] in place and waits for the
 * `AUDIOFOCUS_GAIN` Android sends when the interruption ends, at which
 * point every voice's [AudioVoice.resume] runs. STOP abandons the grant
 * right there, inside [onFocusChange] — matching Android's own contract
 * that a plain `AUDIOFOCUS_LOSS` is not expected to be followed by an
 * unsolicited `AUDIOFOCUS_GAIN` — so [AudioVoice.resume] never fires from
 * it. The registry ([voices]) is untouched by either: those entries are
 * still "making sound" as far as this object's bookkeeping goes (a
 * backgrounded screen that hasn't torn its engine down yet), so the very
 * next [acquire] — the same screen coming back to the foreground, or a
 * fresh one starting — notices the grant is gone (`focusRequest == null`)
 * and requests a brand new one, exactly as if the app were starting cold.
 *
 * **Becoming noisy** (headphones unplugged mid-playback) is not a focus
 * event at all — it is `Intent.ACTION_AUDIO_BECOMING_NOISY`, registered on
 * the same acquire/release edge as the focus request so there is one
 * lifecycle to leak, not two — and it is always treated as STOP: the whole
 * point of that broadcast is that it fires *before* the route flips to the
 * speaker, and there is no "duck to the speaker more quietly" that is ever
 * the right call for a sampler someone was just listening to on
 * headphones. `AudioDeviceCallback` was the other candidate and was
 * rejected: it fires *after* the route has already changed, which would
 * mean inferring after the fact whether the device that just vanished was
 * the one actually in use — `ACTION_AUDIO_BECOMING_NOISY` is delivered
 * exactly when it's needed and never otherwise, so there is nothing to
 * infer.
 *
 * **Thread safety.** [acquire]/[release] mutate [voices], [focusRequest]
 * and the noisy receiver under [lock]; the actual calls into voices'
 * [AudioVoice.silence]/[AudioVoice.resume] happen *outside* that lock,
 * over a snapshot iteration of [voices] ([CopyOnWriteArraySet]'s iterator
 * already is one) — a voice's own [AudioVoice.silence] calling back into
 * [release] (as several of this app's voices legitimately do, from a
 * background thread of their own) can never re-enter [lock] while this
 * object is still holding it.
 */
object AudioFocus {

    private lateinit var appContext: Context
    private val lock = Any()
    private val voices = CopyOnWriteArraySet<AudioVoice>()
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Called once, from [SnipSnapApplication.onCreate]. Every later
     * [acquire]/[release] needs only the application [Context] this
     * stashes, so no playback path — [TapeVoice], [GrainVoice],
     * [AndroidAudioSink] included — needs a `Context` of its own.
     */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun audioManager(): AudioManager? =
        if (::appContext.isInitialized) appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager else null

    /**
     * Register [voice] as making sound. The first [acquire] across every
     * voice currently registered is the one that requests audio focus and
     * starts listening for the noisy broadcast; every acquire after that
     * just adds to the registry and returns. If the request is denied
     * outright — another app already holds exclusive or transient focus —
     * every registered voice (this one included) is told to [AudioVoice.silence]
     * immediately: whatever just started should not be heard either.
     */
    fun acquire(voice: AudioVoice) {
        var denied = false
        synchronized(lock) {
            voices.add(voice)
            if (focusRequest == null) denied = !claimLocked()
        }
        if (denied) silenceAll()
    }

    /**
     * [voice] stopped making sound. Once every voice has released, focus is
     * abandoned and the noisy receiver comes down. Safe for a voice that
     * was never registered, or released twice — only the edge that empties
     * the registry does anything.
     */
    fun release(voice: AudioVoice) {
        synchronized(lock) {
            voices.remove(voice)
            if (voices.isEmpty()) abandonLocked()
        }
    }

    /** Caller must hold [lock]. Returns whether focus was actually granted. */
    private fun claimLocked(): Boolean {
        val am = audioManager() ?: return false
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            // Load-bearing, not decorative: false is the framework's own
            // default, and at false the framework auto-ducks
            // TRANSIENT_CAN_DUCK itself and never calls onFocusChange for
            // it at all — the PAUSE branch this app maps that case to (see
            // the class KDoc) would be dead code without this.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(::onFocusChange, mainHandler)
            .build()
        val granted = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) return false
        focusRequest = request
        registerNoisyReceiverLocked()
        return true
    }

    /** Caller must hold [lock]. Idempotent — safe when nothing is currently held. */
    private fun abandonLocked() {
        val am = audioManager()
        focusRequest?.let { am?.abandonAudioFocusRequest(it) }
        focusRequest = null
        unregisterNoisyReceiverLocked()
    }

    private fun registerNoisyReceiverLocked() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Same action as a permanent focus loss: stop, don't expect
                // a comeback — see the class KDoc's "Becoming noisy" section.
                silenceAll()
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (registered) noisyReceiver = receiver
    }

    private fun unregisterNoisyReceiverLocked() {
        noisyReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        noisyReceiver = null
    }

    /** The `AudioManager` focus-change callback — always the [mainHandler], never an audio thread. */
    private fun onFocusChange(focusChange: Int) {
        when (AudioPolicy.actionFor(focusChange)) {
            AudioFocusAction.STOP -> {
                // Abandon right here: Android's own contract is that a
                // plain AUDIOFOCUS_LOSS is not followed by an unsolicited
                // AUDIOFOCUS_GAIN, so there is nothing to keep the request
                // open for — see the class KDoc for what this means for the
                // next acquire().
                synchronized(lock) { abandonLocked() }
                silenceAll()
            }
            AudioFocusAction.PAUSE -> silenceAll()
            AudioFocusAction.RESUME -> resumeAll()
            AudioFocusAction.IGNORE -> {}
        }
    }

    private fun silenceAll() {
        for (voice in voices) voice.silence()
    }

    private fun resumeAll() {
        for (voice in voices) voice.resume()
    }
}
