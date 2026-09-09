package com.snipsnap.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.snipsnap.audio.BlockWatch
import com.snipsnap.audio.CaptureRing
import com.snipsnap.audio.SilenceWatch
import com.snipsnap.shell.Copy
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The armed capture session: a foreground [Service] that keeps one
 * [CaptureRing] fed with the last [RING_SECONDS] of audio from one of two
 * sources — the microphone ([Source.MIC], the room) or, with the user's
 * MediaProjection consent, whatever other apps are playing
 * ([Source.INSIDE], `AudioPlaybackCapture`; see `docs/ANDROID_CAPTURE.md`).
 *
 * **Privacy contract: memory only until SNIP; disarm drops the ring.** No
 * byte the ring holds touches disk unless the user presses SNIP while it
 * is still in the ring; the moment the session ends — [ACTION_EJECT], the
 * platform stopping the projection, or this service dying — every ring
 * reference (the instance field and the companion's `activeRing`, kept
 * for on-demand pad-grab snapshots) is dropped and that audio is gone.
 * Nothing the user did not snip ever survives past disarm.
 *
 * The service does four things and nothing else:
 * - [ACTION_ARM] opens a microphone [AudioRecord] and starts one plain
 *   reader thread copying it into the ring. It also watches the stream
 *   for sustained exact digital silence — a permission revoked mid-
 *   session usually doesn't error, it just makes `read()` hand back
 *   full-length blocks of exact zeros — and publishes that as
 *   [micSilent], deliberately *not* by tearing the session down (see
 *   [micSilent]'s own KDoc for why).
 * - [ACTION_ARM_INSIDE] does the same over a playback-capture
 *   [AudioRecord] built on the consent the app just collected, and
 *   watches the stream for dead air: an app that opts out of capture
 *   hands the recorder exact zeros rather than refusing, so silence
 *   that holds for whole seconds while the phone reports music playing
 *   is published as [blocked] — the honest way to say "this app blocks
 *   the tape" instead of handing over an empty waveform.
 * - [ACTION_SNIP] snapshots the ring on a separate thread and hands the
 *   snapshot to [commitSnip] — the session itself keeps rolling, so one
 *   armed session can produce many snips.
 * - [ACTION_EJECT] (and [onDestroy]) stops the reader, releases the
 *   record and any projection, and drops the ring.
 */
class MicSessionService : Service() {

    enum class Source { MIC, INSIDE }

    private var ring: CaptureRing? = null
    private var record: AudioRecord? = null
    private var projection: MediaProjection? = null
    private var readerThread: Thread? = null
    private var foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    /**
     * Where the reader thread posts its own-initiative teardown when it
     * dies on its own (see the `while (reading)` loop in [beginSession]).
     * stopReaderAndRecord() must always run on the same thread every other
     * caller (handleEject, onDestroy, the projection callback) already
     * uses — the main thread — so a dead-reader teardown can never race a
     * user-initiated EJECT touching the same fields concurrently.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var reading = false

    /**
     * The platform ending the projection under us — the lock screen
     * (Android 15 QPR1 stops every projection on lock), the status-bar
     * chip, a revoke. Routine, not an error: the session simply ends and
     * [phoneStops] ticks so the app can say so. Our own eject nulls
     * [projection] before stopping it, so this sees nothing to do then.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (projection == null) return
            projection = null
            stopReaderAndRecord()
            stopForeground(STOP_FOREGROUND_REMOVE)
            _phoneStops.value = _phoneStops.value + 1
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> handleArm()
            ACTION_ARM_INSIDE -> handleArmInside(intent)
            ACTION_SNIP -> handleSnip()
            ACTION_EJECT -> handleEject()
        }
        // Nothing armed after handling this action (arm failed, eject just
        // ran, or this start doesn't correspond to a live session) — don't
        // linger as a started-but-not-foreground Service.
        if (ring == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopReaderAndRecord()
        super.onDestroy()
    }

    /**
     * Re-entry for an already-armed session of either source. Still worth
     * a call — this is also the path for a just-granted overlay permission
     * (App's ACTION_MANAGE_OVERLAY_PERMISSION result calls arm() again on
     * return): attachBubbleIfAllowed() is a no-op if a bubble is already
     * up, and doesn't touch the AudioRecord either way.
     *
     * But this path is still a fresh startForegroundService() dispatch
     * (App.arm() always calls it), and Android 12+ requires
     * startForeground() satisfied on EACH dispatch, not just the one that
     * originally armed the session — skipping it here risks
     * ForegroundServiceDidNotStartInTimeException. Re-posting the same
     * NOTIFICATION_ID is an update, not a second notification. The type
     * re-posted is the live session's own, not the caller's guess.
     */
    private fun reenter() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(_source.value ?: Source.MIC),
            foregroundType,
        )
        attachBubbleIfAllowed()
    }

    private fun handleArm() {
        if (ring != null) {
            reenter()
            return
        }

        // startForeground() runs FIRST, before the AudioRecord (or the
        // 10.6 MB ring) is ever built: on the Android 14+ FGS-type model,
        // opening a mic AudioRecord before the service has reached the
        // mic-eligible foreground state can silently yield zeros — no
        // exception, nothing to catch, just dead air. This also trivially
        // satisfies the ~5s startForeground() window, since nothing else
        // runs before it.
        foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIFICATION_ID, buildNotification(Source.MIC), foregroundType)

        val unprocessedSupported = getSystemService(AudioManager::class.java)
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessedSupported) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            // Documented no-noise-suppression guarantee even where UNPROCESSED
            // itself isn't offered — see docs/CAPTURE_RESEARCH_2026.md ("The
            // mic fallback's viability").
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuffer <= 0) {
            // One-time arm-path branch, not the realtime loop: logged so a
            // dead ARM is diagnosable from logcat/a bug report instead of
            // silently doing nothing. The ARM button UI owns any
            // user-facing message.
            Log.w(TAG, "arm: getMinBufferSize rejected the format outright ($minBuffer)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val newRecord = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 4)
                .build()
        } catch (e: SecurityException) {
            // RECORD_AUDIO not granted. arm() is only meant to be called
            // from the ARM button, which is responsible for holding the
            // permission first — this is the defensive floor, not the
            // permission flow itself.
            Log.w(TAG, "arm: RECORD_AUDIO not granted", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "arm: device rejected the AudioRecord params", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        if (!startRecording(newRecord, "arm")) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        beginSession(newRecord, Source.MIC, stereo = false)
    }

    /**
     * INSIDE: the consent result the app collected from
     * [MediaProjectionManager.createScreenCaptureIntent] rides in on the
     * intent. Order is the platform's, not ours: the service must already
     * be foreground with the mediaProjection type before
     * [MediaProjectionManager.getMediaProjection] is called (Android 14+),
     * and the callback must be registered before capture starts. Each
     * consent is good for exactly one projection — the app asks afresh
     * on every ARM INSIDE, never caching the intent.
     */
    private fun handleArmInside(intent: Intent) {
        if (ring != null) {
            reenter()
            return
        }
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "arm inside: no consent on the intent")
            return
        }

        foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        startForeground(NOTIFICATION_ID, buildNotification(Source.INSIDE), foregroundType)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = try {
            manager?.getMediaProjection(code, data)
        } catch (e: RuntimeException) {
            // A reused consent (Android 14+) or a projection the system
            // refuses: SecurityException / IllegalStateException — the
            // arm dies here, honestly, and the app says so.
            Log.w(TAG, "arm inside: getMediaProjection refused", e)
            null
        }
        if (newProjection == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        newProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        // Only MEDIA, GAME and UNKNOWN streams are capturable; calls,
        // alarms and notifications are excluded by the platform, so a
        // phone call can never land in the ring.
        val config = AudioPlaybackCaptureConfiguration.Builder(newProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "arm inside: getMinBufferSize rejected the format outright ($minBuffer)")
            dropProjection(newProjection)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val newRecord = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 4)
                .build()
        } catch (e: SecurityException) {
            Log.w(TAG, "arm inside: RECORD_AUDIO not granted", e)
            dropProjection(newProjection)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "arm inside: device rejected the playback-capture params", e)
            dropProjection(newProjection)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        if (!startRecording(newRecord, "arm inside")) {
            dropProjection(newProjection)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        projection = newProjection
        beginSession(newRecord, Source.INSIDE, stereo = true)
    }

    /** A projection that never became a session: unhook it before it can call [projectionCallback] back. */
    private fun dropProjection(p: MediaProjection) {
        runCatching { p.unregisterCallback(projectionCallback) }
        runCatching { p.stop() }
    }

    private fun startRecording(newRecord: AudioRecord, what: String): Boolean {
        if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "$what: AudioRecord built but left STATE_UNINITIALIZED")
            newRecord.release()
            return false
        }
        val started = runCatching { newRecord.startRecording() }.isSuccess &&
            newRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            Log.w(TAG, "$what: startRecording() never reached RECORDSTATE_RECORDING")
            newRecord.release()
            return false
        }
        return true
    }

    /**
     * The one place a session starts, whichever source: the ring, the
     * reader thread, the bubble, `armed`. INSIDE reads stereo and folds
     * to mono in the loop (the ring is mono; a snip is mono), and runs
     * the [BlockWatch]. MIC reads mono straight into the ring, and runs
     * its own bare [SilenceWatch] (not [BlockWatch] — there is no
     * platform oracle like `isMusicActive` to gate a MIC verdict on, so
     * the tick itself is the whole verdict) to publish [micSilent].
     */
    private fun beginSession(newRecord: AudioRecord, source: Source, stereo: Boolean) {
        val newRing = CaptureRing(RING_SECONDS * SAMPLE_RATE)
        val watch = if (source == Source.INSIDE) {
            BlockWatch.forSeconds(SILENCE_HOLD_SECONDS, SAMPLE_RATE)
        } else {
            null
        }
        val muteWatch = if (source == Source.MIC) {
            SilenceWatch.forSeconds(MIC_SILENCE_HOLD_SECONDS, SAMPLE_RATE)
        } else {
            null
        }
        val audioManager = getSystemService(AudioManager::class.java)
        record = newRecord
        ring = newRing
        activeRing = newRing
        reading = true
        readerThread = thread(name = "MicSession") {
            // The realtime loop: preallocated once, nothing else in here —
            // no allocation, no locks, no logging per iteration. read() is
            // caught, not left to throw — IllegalStateException as before,
            // SecurityException added because a permission revoked
            // mid-session can also surface there (when it surfaces as an
            // exception at all — see the zero-read handling below for the
            // more common case, where Android just hands back silence or
            // an error code instead) — because this runs on a plain
            // thread{} where an uncaught exception kills the process — see
            // stopReaderAndRecord's KDoc for the shutdown race this guards.
            val channels = if (stereo) 2 else 1
            val raw = FloatArray(READ_BLOCK_FRAMES * channels)
            val mono = if (stereo) FloatArray(READ_BLOCK_FRAMES) else raw
            // Rule for telling a dead source from a transient blip:
            // a negative return is one of AudioRecord's own error codes
            // (ERROR, ERROR_BAD_VALUE, ERROR_DEAD_OBJECT,
            // ERROR_INVALID_OPERATION) — unambiguous, so it breaks the
            // loop immediately, no retry. A positive-but-short read
            // (got > 0, got < raw.size) is not treated as a problem at
            // all — frames is just derived from `got`, same as always. A
            // *zero*-length read is the ambiguous case: READ_BLOCKING is
            // documented to block until there's data or an error, so a
            // lone got == 0 is already unusual, but treating any single
            // one as fatal risks tripping on a benign HAL hiccup (a route
            // change, a brief reconfigure). Requiring a run of
            // MAX_CONSECUTIVE_ZERO_READS in a row before calling it death
            // absorbs that without masking a genuinely silenced source —
            // real audio, even near-silent room tone, resets the counter
            // the moment a single sample comes back.
            var consecutiveZeroReads = 0
            while (reading) {
                val got = try {
                    newRecord.read(raw, 0, raw.size, AudioRecord.READ_BLOCKING)
                } catch (e: IllegalStateException) {
                    break
                } catch (e: SecurityException) {
                    break
                }
                if (got < 0) break // an explicit AudioRecord error code — the source is gone, no retry
                if (got == 0) {
                    if (++consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) break
                    continue
                }
                consecutiveZeroReads = 0
                val frames = got / channels
                if (stereo) {
                    var i = 0
                    var j = 0
                    while (i < frames) {
                        mono[i] = 0.5f * (raw[j] + raw[j + 1])
                        i++
                        j += 2
                    }
                }
                // Published for the ARM screen's live level meter (and, as
                // the mic-alive diagnostic, its liveness reading) — one
                // float store per block into a conflated StateFlow. This is
                // a plain blocking-read loop, not a realtime audio
                // callback, so the allocation-free-loop discipline that
                // governs CaptureRing.write doesn't apply to publishing
                // here; the peak scan itself allocates nothing either way.
                // Read from `mono` (the same array either source writes into
                // the ring from) so the meter always reflects the post-fold
                // signal, INSIDE's stereo-to-mono included.
                var peak = 0f
                for (i in 0 until frames) {
                    val a = abs(mono[i])
                    if (a > peak) peak = a
                }
                _level.value = peak
                newRing.write(mono, frames)
                // The whole verdict is BlockWatch's, and tested there:
                // it ticks once per SILENCE_HOLD_SECONDS of dead air, so
                // the binder call behind isMusicActive runs a few times a
                // minute at worst and never per block - a case counts the
                // calls, because nothing else would notice that changing.
                // feed returns true only when the verdict moved, so this
                // publishes on a change rather than every block.
                if (watch != null && watch.feed(mono, frames) { audioManager?.isMusicActive == true }) {
                    _blocked.value = watch.blocked
                }
                // MIC's mute verdict: no platform oracle to gate on (there
                // is no "is the mic supposed to be hearing something?"
                // equivalent to isMusicActive), so the tick alone is the
                // whole test. This is BlockWatch's own tick/clear shape,
                // hand-rolled rather than reused, because BlockWatch's
                // verdict and KDoc are the INSIDE-specific "this app
                // blocks the tape" concept — see SilenceWatchTest's
                // "verdict latch" test for why the `!muteWatch.silent`
                // branch (not just `else`) is required: `feed` zeroes the
                // run on the tick that returns true, so `silent` also
                // reads false on that same block, and a plain `else`
                // here would clear the verdict one block after raising
                // it.
                if (muteWatch != null) {
                    if (muteWatch.feed(mono, frames)) {
                        _micSilent.value = true
                    } else if (!muteWatch.silent) {
                        _micSilent.value = false
                    }
                }
            }
            // `reading` still true here means the loop broke out on its
            // own — a negative error code, a caught
            // IllegalStateException/SecurityException, or
            // MAX_CONSECUTIVE_ZERO_READS dead reads in a row — rather than
            // via stopReaderAndRecord() flipping `reading` false first.
            // That path also unblocks a parked read() (via
            // AudioRecord.stop()), but it always sets `reading = false`
            // *before* doing so, so a thread waking up from a
            // deliberately-triggered stop always sees `reading` already
            // false here. Only the true case is an unannounced death the
            // user needs to hear about; every other teardown already runs
            // through stopReaderAndRecord() from a user EJECT, onDestroy,
            // or the projection callback.
            val diedUnexpectedly = reading
            // Covers every way this loop ends — the normal `reading =
            // false` teardown (stopReaderAndRecord already zeroes this
            // too, so it's a harmless redundant write there), the
            // IllegalStateException/SecurityException catches' `break`,
            // and the dead-read breaks above. Without this, a HAL failure
            // mid-session would freeze the meter at its last non-zero
            // reading while the wall-clock elapsed timer keeps ticking —
            // reading as "recording fine" for exactly the case (a dead
            // mic) this indicator exists to catch.
            _level.value = 0f
            if (diedUnexpectedly) {
                // The actual teardown (the call that flips `armed` false
                // and drops the ring) has to run on the main thread, same
                // as every other stopReaderAndRecord() caller, not here
                // on the reader thread itself — calling it directly would
                // both race a concurrent user-initiated EJECT touching
                // the same fields and self-join (readerThread.join()
                // from inside readerThread). Rechecking identity —
                // `record === newRecord`, not just non-null — once the
                // posted runnable actually runs closes two windows
                // between this thread noticing `reading` was still true
                // and the post executing: a user's own EJECT/onDestroy
                // reaching stopReaderAndRecord() first (this dead session
                // was ended deliberately after all — `record` is null by
                // then), and the rarer ABA case where EJECT *and* a fresh
                // re-ARM both land in that window (`record` is non-null
                // again, but it's a brand-new AudioRecord for a brand-new
                // session, not the one this thread was reading). Either
                // way this skips both the redundant call
                // (stopReaderAndRecord() is idempotent regardless —
                // onDestroy already calls it a second time after
                // handleEject in the ordinary shutdown sequence — so
                // calling it again would be harmless) and, more
                // importantly, a false "the mic died" toast or tearing
                // down a session that isn't this one.
                //
                // stopForeground()+stopSelf() alongside it, mirroring
                // handleEject()/the projection callback exactly: without
                // these the "TAPE ROLLING" notification (still
                // setOngoing(true), still offering a SNIP action that
                // would now hit handleSnip()'s `ring ?: return` and do
                // nothing) would keep lying even after `_armed` goes
                // false, and this Service would linger as a started FGS
                // nothing will ever stop. stopSelf() re-enters onDestroy
                // -> stopReaderAndRecord(), the same already-idempotent
                // second call the projection path relies on.
                mainHandler.post {
                    if (record === newRecord) {
                        stopReaderAndRecord()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        _sessionDied.value = _sessionDied.value + 1
                        stopSelf()
                    }
                }
            }
        }
        _source.value = source
        attachBubbleIfAllowed()
        // SystemClock.elapsedRealtime(), not System.currentTimeMillis() —
        // immune to a wall-clock adjustment mid-session, and the anchor a
        // UI-side elapsed-time readout diffs against so it survives that
        // UI remounting (ArmControl recomposing doesn't reset the timer).
        armedAtElapsedRealtime = SystemClock.elapsedRealtime()
        _armed.value = true
    }

    /**
     * The bubble rides with the session — attached here (a no-op if the
     * overlay permission isn't granted; the notification stays the whole
     * story), detached in [stopReaderAndRecord], the session's own one
     * teardown funnel.
     */
    private fun attachBubbleIfAllowed() {
        BubbleOverlay.attachIfAllowed(this) { ring }
    }

    private fun handleSnip() {
        val snapRing = ring ?: return // SNIP with nothing armed: no-op
        // Own id per attempt (see [_lastSnipError]'s KDoc for why): lets a
        // collector baseline "already reported" by id, the same discipline
        // [phoneStops] uses, instead of nulling this out and racing a UI
        // that might read it in between.
        val attempt = snipAttemptSeq.incrementAndGet()
        thread(name = "MicSessionSnip") {
            val samples = snapRing.snapshot(RING_SECONDS * SAMPLE_RATE)
            // commitSnip runs the cleanup/doctor DSP chain on this bare
            // thread{} — same risk the reader loop above guards against:
            // an uncaught exception here kills the process, not just this
            // snip. CaptureDoctor.clean legitimately refuses (throws) on a
            // capture it judges distorted rather than clicky; that must
            // cost this one SNIP, not the whole armed session.
            val file = runCatching { commitSnip(samples, SAMPLE_RATE) }
                .onFailure { Log.w(TAG, "snip: commit failed", it) }
                .getOrNull()
            if (file != null) {
                _lastSnipFile.value = file
            } else {
                // Either the runCatching above caught something, or
                // commitSnip returned null cleanly — both are a miss the
                // optimistic in-app toast already claimed as a success.
                _lastSnipError.value = attempt to "SNIP FAILED — COULDN'T KEEP THAT ONE"
            }
        }
    }

    private fun handleEject() {
        stopReaderAndRecord()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * Stops the reader loop, releases the [AudioRecord], stops any
     * projection, drops the ring reference, and publishes `armed = false`
     * — the privacy spine: disarm drops the ring, so nothing the user did
     * not snip survives past this call. This is the one funnel
     * [ACTION_EJECT], the projection's own stop and [onDestroy] all go
     * through, so `armed` cannot go stale on a teardown path that isn't a
     * user-initiated eject (FGS reclaim, task swipe, an external
     * `stopService`, the lock screen) — every route to "this session is
     * over" clears it here, not just the explicit-eject one.
     *
     * Order matters: [reading] flips false and [AudioRecord.stop] is called
     * first, which unblocks a reader thread parked in a blocking `read()`;
     * only after a bounded join is the record released, so a wedged HAL
     * can't hang shutdown forever. That join is a timeout, not a guarantee,
     * though — if it expires while the reader thread is still inside
     * `read()`, [release] proceeds anyway and races that in-flight call,
     * the same class of race [AndroidAudioSink.write] has on the playback
     * side. The reader loop's `read()` call is wrapped in the matching
     * catch for the same reason `AndroidAudioSink.write` catches around its
     * blocking call: this runs on a plain `thread{}`, where an exception
     * that escapes the loop kills the process, not just this session.
     *
     * The projection is nulled before it is stopped so its own callback
     * sees a session already over and does nothing. The bubble rides
     * with the session the same way: [BubbleOverlay.detach] runs here
     * too, so every route to "this session is over" takes it down, not
     * just an explicit drag-to-eject.
     */
    private fun stopReaderAndRecord() {
        reading = false
        val r = record
        if (r != null) {
            runCatching { r.stop() }
            readerThread?.join(READER_JOIN_TIMEOUT_MS)
            runCatching { r.release() }
        }
        readerThread = null
        record = null
        val p = projection
        projection = null
        if (p != null) dropProjection(p)
        ring = null
        activeRing = null
        BubbleOverlay.detach()
        _blocked.value = false
        _micSilent.value = false
        _source.value = null
        _armed.value = false
        _level.value = 0f
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(source: Source): Notification {
        ensureChannel()
        val snipIntent = Intent(this, MicSessionService::class.java).setAction(ACTION_SNIP)
        val snipPending = PendingIntent.getService(
            this,
            0,
            snipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (source == Source.INSIDE) Copy.INSIDE_ARMED else Copy.SESSION_ARMED
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now),
                    "SNIP",
                    snipPending,
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_ARM = "com.snipsnap.app.ARM"
        const val ACTION_ARM_INSIDE = "com.snipsnap.app.ARM_INSIDE"
        const val ACTION_SNIP = "com.snipsnap.app.SNIP"
        const val ACTION_EJECT = "com.snipsnap.app.EJECT"
        const val EXTRA_RESULT_CODE = "com.snipsnap.app.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.snipsnap.app.RESULT_DATA"
        const val RING_SECONDS = 60

        private const val TAG = "MicSessionService"

        /**
         * Widened from `private` so other `:app` code can build a Snip at
         * the session's real sample rate (Task 5's pad-grab path) — the
         * value itself is unchanged (44_100).
         */
        internal const val SAMPLE_RATE = 44_100
        private const val READ_BLOCK_FRAMES = 2048
        private const val READER_JOIN_TIMEOUT_MS = 1_000L

        /** See the reader loop's own KDoc in [beginSession] for the rule this enforces. */
        private const val MAX_CONSECUTIVE_ZERO_READS = 3
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1

        /** Dead air this long, with music reported playing, is a block — not a rest in the song. */
        private const val SILENCE_HOLD_SECONDS = 3.0

        /**
         * How long a MIC session's stream must read exact digital silence
         * before [micSilent] fires — over 3x INSIDE's [SILENCE_HOLD_SECONDS]
         * on purpose. INSIDE gets a second opinion for free (`isMusicActive`)
         * before it commits to a verdict; MIC has no such oracle, so the
         * hold is the only thing standing between a real mute and a false
         * alarm, and a false alarm here is worse than the one it's guarding
         * against — the UI response chosen for [micSilent] is a warning, not
         * a teardown, but a warning that fires on a legitimate quiet room is
         * still a lie the app told about the user's own recording.
         *
         * The dominant real false-positive risk isn't a quiet room — real
         * air has dither/thermal noise that [SilenceWatch.threshold]
         * already rejects almost by construction (see
         * `SilenceWatchTest`'s "a quiet noise floor never does" case). It's
         * an OEM HAL applying an aggressive noise gate on the processed
         * audio path (`handleArm`'s `VOICE_RECOGNITION` fallback, used on
         * devices without `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`) that
         * can itself hold exact zeros through a real quiet passage. Ten
         * seconds is chosen to be implausible for even an aggressive gate
         * to hold unbroken — a gate closing for that long with nothing
         * before or after it to reopen for is itself indistinguishable
         * from a dead input as far as the user's purposes go.
         */
        private const val MIC_SILENCE_HOLD_SECONDS = 10.0

        private val _armed = MutableStateFlow(false)
        val armed: StateFlow<Boolean> = _armed.asStateFlow()

        private val _source = MutableStateFlow<Source?>(null)
        /** Which source the live session reads, or null when nothing is armed. */
        val source: StateFlow<Source?> = _source.asStateFlow()

        private val _blocked = MutableStateFlow(false)
        /**
         * True while an INSIDE session is hearing only digital silence
         * with music reported playing: the app on top blocks capture.
         * Clears the moment sound arrives, and on disarm.
         */
        val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

        private val _micSilent = MutableStateFlow(false)
        /**
         * True while a MIC session's stream has read [MIC_SILENCE_HOLD_SECONDS]
         * of unbroken exact digital silence — the platform revoking mic
         * access mid-session (or a hardware/privacy mute) usually doesn't
         * error, it just makes `AudioRecord.read()` keep returning full-
         * length blocks of exact zeros, which every check `sessionDied`
         * covers is blind to. Named for the observation, not a diagnosis:
         * this says "nothing has arrived in a while", not "the mic died",
         * because the only sample-content test available (the same
         * [SilenceWatch] INSIDE uses for [blocked]) genuinely cannot tell
         * "revoked" apart from "a stretch of true silence held long enough
         * to look identical at the wire" — nothing can, from inside the
         * stream alone.
         *
         * Deliberately **not** wired through [sessionDied] or any other
         * path that tears the session down. That distinction is the whole
         * design call here: a MIC session's ring can hold genuine (if
         * quiet) audio the user still wants — ending the session drops the
         * ring (the service's own privacy contract), which would destroy
         * real captured content on a false alarm. A true mute loses
         * nothing by staying armed instead: the stream is worthless zeros
         * either way, and the user can see the warning and eject
         * themselves. A false alarm on a genuinely quiet recording, by
         * contrast, costs everything if it tears the session down and
         * nothing if it doesn't. The costs are asymmetric in one direction
         * only, so this only ever warns.
         *
         * Clears the moment real sound arrives, and on disarm — same
         * transition shape as [blocked], minus the `isMusicActive` gate
         * INSIDE has and MIC doesn't (see [MIC_SILENCE_HOLD_SECONDS]'s
         * KDoc for why the hold does more work here as a result).
         */
        val micSilent: StateFlow<Boolean> = _micSilent.asStateFlow()

        private val _phoneStops = MutableStateFlow(0)
        /** Counts sessions the platform ended (lock screen, the stop chip) — the app toasts on each tick. */
        val phoneStops: StateFlow<Int> = _phoneStops.asStateFlow()

        private val _sessionDied = MutableStateFlow(0)
        /**
         * Counts sessions that ended because the reader thread died on its
         * own — [AudioRecord.read] returning a persistent error code, an
         * `IllegalStateException`/`SecurityException` out of `read()`, or
         * the OS killing this Service outright (`onDestroy` runs
         * [stopReaderAndRecord] before the process actually dies, but
         * nothing here posts to this flow from a route that never gets to
         * run at all — a hard kill with no `onDestroy` announces nothing
         * either, same as before this existed).
         *
         * The sibling to [phoneStops]: that one is only the
         * `MediaProjection.Callback.onStop()` path (ARM INSIDE, ended by
         * the platform); this is a plain ARM (mic) or ARM INSIDE session
         * whose reader loop itself gave out. A plain mic session dying
         * used to announce nothing — the user found out by reopening the
         * app to a UI that had quietly stopped believing it was armed.
         * The app toasts on each tick, same discipline as [phoneStops]:
         * baseline by count at first composition, so a recreated Activity
         * doesn't replay an old failure.
         *
         * **What this does NOT catch.** A permission revoked mid-session
         * usually doesn't error at all: the platform mutes the input and
         * `read()` keeps returning full-length blocks of zero-VALUED
         * samples. That is a positive return, no exception, no short read
         * — invisible to every check above, which is why none of them are
         * the fix for it. [micSilent] is: it runs the same sample-content
         * inspection ([SilenceWatch]) this service already used for
         * [Source.INSIDE]'s `blocked` verdict, now also wired for plain MIC
         * sessions, tuned tight enough (see [MIC_SILENCE_HOLD_SECONDS]'s
         * KDoc) to tell a muted mic from a merely quiet room rather than
         * conflating them. It's deliberately a separate, non-terminal
         * signal rather than another tick of *this* flow — see
         * [micSilent]'s own KDoc for why folding it into `sessionDied`
         * (and the ejection that implies) would be the wrong call.
         *
         * What's still uncovered, and likely to stay that way: a hard
         * process kill with no `onDestroy` at all announces nothing, same
         * as before either of these existed — there's no code path left to
         * run when the process itself is gone. And [micSilent] shares
         * [SilenceWatch]'s own limit: it detects *exact* digital silence,
         * so a source that goes near-silent-but-not-quite (a hardware
         * fault that leaves a hair of noise, rather than a clean mute)
         * reads as a very quiet room, not a dead one — the same ambiguity
         * a human listening back would have.
         */
        val sessionDied: StateFlow<Int> = _sessionDied.asStateFlow()

        // The reader session's ring, exposed so an in-app capture surface can pull
        // the last N frames on demand (GRAB to a pad) without committing a 60s snip
        // file. Set on the service's main thread when the reader starts (handleArm),
        // cleared on disarm (stopReaderAndRecord) — cleared, so no static reference to
        // un-snipped mic audio outlives the session's teardown. Volatile: a future
        // GRAB path is expected to call snapshotTail() off the main thread (mirroring
        // handleSnip's own snapshot-on-a-thread pattern), so this field is written on
        // one thread and read on another. CaptureRing.snapshot is single-consumer-safe
        // (documented tearing only at the oldest edge).
        @Volatile private var activeRing: CaptureRing? = null

        /** Newest [frames] mono samples from the live session, or null if not armed. */
        fun snapshotTail(frames: Int): FloatArray? {
            val ring = activeRing ?: return null
            if (frames <= 0) return null
            val out = ring.snapshot(frames)
            return if (out.isEmpty()) null else out
        }

        private val _lastSnipFile = MutableStateFlow<File?>(null)
        val lastSnipFile: StateFlow<File?> = _lastSnipFile.asStateFlow()

        /** Bumped once per [handleSnip] attempt — see [_lastSnipError]'s KDoc. */
        private val snipAttemptSeq = AtomicInteger(0)

        /**
         * Paired with [lastSnipFile]: the in-app SNIP button (`App.kt`'s
         * `onSnip`) toasts `Copy.SNIPPED` optimistically, before
         * [handleSnip]'s commit has even started — immediate feedback is
         * good UX, but it means that toast is a promise, not a report. If
         * the commit then fails or [commitSnip] returns null (a capture
         * `CaptureDoctor.clean` legitimately refuses, most likely), this is
         * the correction: a short, honest string a collector can toast to
         * retract the lie.
         *
         * The `Int` half is [handleSnip]'s own per-attempt id, not a value
         * to clear between attempts: a collector baselines "already
         * reported" against the id it last saw (the same discipline
         * [phoneStops] uses), so a fresh `App()` composition never replays
         * an old failure just because this still holds one, and two
         * consecutive failures with the identical message still read as two
         * distinct events (different ids), not a same-value no-op.
         */
        private val _lastSnipError = MutableStateFlow<Pair<Int, String>?>(null)
        val lastSnipError: StateFlow<Pair<Int, String>?> = _lastSnipError.asStateFlow()

        /**
         * The reader loop's per-block peak, `0f..1f` — the live input level
         * for the ARM screen's recording indicator, and also the mic-alive
         * diagnostic: a session that's truly armed but hearing a quiet
         * room holds this at *near* zero, which used to be visually
         * indistinguishable from a dead source. Paired in the UI with a
         * wall-clock elapsed readout ([armedAtElapsedRealtime]) so the two
         * together tell "recording, hearing nothing" apart from "not
         * actually recording."
         *
         * It no longer has to carry that ambiguity alone: this value IS the
         * same per-block peak [SilenceWatch] tests against its threshold,
         * so a UI that compares it to [SilenceWatch.DEFAULT_THRESHOLD]
         * directly gets an instantaneous (one block, ~46ms) read on whether
         * the *current* block is exact silence or a live noise floor —
         * `KitsScreen.kt`'s `LevelBar` does exactly this to tint the meter.
         * That's a per-block observation, not a verdict: it can and will
         * read "exact zero" for one harmless block during ordinary silence
         * between sounds. [micSilent] is the verdict built on the same
         * signal held for [MIC_SILENCE_HOLD_SECONDS] — the meter answers
         * "what is this instant", the toast answers "has this held long
         * enough to mean something."
         */
        private val _level = MutableStateFlow(0f)
        val level: StateFlow<Float> = _level.asStateFlow()

        /**
         * [SystemClock.elapsedRealtime] at the moment this session actually
         * armed (not the re-entry path in [handleArm] that only refreshes
         * the foreground notification) — the anchor a UI-side elapsed-time
         * readout diffs against, so the counter survives the UI remounting
         * (ArmControl recomposing, navigating away and back) without
         * resetting to zero on a session that's still rolling. Wall-clock,
         * not audio-driven, by design: see [_level]'s KDoc for why the two
         * signals are paired.
         */
        var armedAtElapsedRealtime: Long = 0L
            private set

        /**
         * The seam `SnipStore` fills in, reassigned by
         * [SnipSnapApplication.onCreate] — not `MainActivity` — so a
         * process the OS restarts to deliver a SNIP intent re-wires this
         * before any component runs, [MainActivity] included. The default
         * below only ever runs in a context nothing rewired it (a stray
         * unit test), where SNIP still exercises the real read path — a
         * real ring snapshot on every press — it just has nowhere durable
         * to put it, so the default hands back null and nothing is written
         * to disk.
         */
        var commitSnip: (samples: FloatArray, sampleRate: Int) -> File? = { _, _ -> null }

        /**
         * Arms a microphone session. Must be called from a foreground
         * context — the ARM button in a visible Activity, not a background
         * trigger — because Android 15 restricts starting a foreground
         * service from the background (docs/CAPTURE_RESEARCH_2026.md,
         * drift #4: the `BOOT_COMPLETED` restriction is the documented
         * instance of a broader background-start limit this sidesteps by
         * construction: a synchronous call from a button's onClick is
         * itself a foreground start).
         */
        fun arm(context: Context) {
            context.startForegroundService(
                Intent(context, MicSessionService::class.java).setAction(ACTION_ARM),
            )
        }

        /**
         * Arms an INSIDE session on a consent result fresh from
         * [MediaProjectionManager.createScreenCaptureIntent]. Same
         * foreground-context rule as [arm]; the activity-result callback
         * that delivers the consent is one.
         */
        fun armInside(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, MicSessionService::class.java)
                    .setAction(ACTION_ARM_INSIDE)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }

        fun snip(context: Context) {
            context.startService(
                Intent(context, MicSessionService::class.java).setAction(ACTION_SNIP),
            )
        }

        fun eject(context: Context) {
            context.startService(
                Intent(context, MicSessionService::class.java).setAction(ACTION_EJECT),
            )
        }
    }
}
