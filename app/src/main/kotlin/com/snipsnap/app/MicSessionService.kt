package com.snipsnap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.snipsnap.audio.CaptureRing
import com.snipsnap.shell.Copy
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The armed mic session: a microphone-type foreground [Service] that keeps
 * one [CaptureRing] fed with the last [RING_SECONDS] of audio.
 *
 * **Privacy contract: memory only until SNIP; disarm drops the ring.** No
 * byte the ring holds touches disk unless the user presses SNIP while it
 * is still in the ring; the moment the session ends — [ACTION_EJECT] or
 * this service dying — the ring reference is dropped and that audio is
 * gone. Nothing the user did not snip ever survives past disarm.
 *
 * The service does three things and nothing else:
 * - [ACTION_ARM] opens an [AudioRecord] and starts one plain reader thread
 *   copying it into the ring.
 * - [ACTION_SNIP] snapshots the ring on a separate thread and hands the
 *   snapshot to [commitSnip] — the session itself keeps rolling, so one
 *   armed session can produce many snips.
 * - [ACTION_EJECT] (and [onDestroy]) stops the reader, releases the
 *   record, and drops the ring.
 */
class MicSessionService : Service() {

    private var ring: CaptureRing? = null
    private var record: AudioRecord? = null
    private var readerThread: Thread? = null

    @Volatile
    private var reading = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> handleArm()
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

    private fun handleArm() {
        if (ring != null) {
            // One session at a time; already armed. Still worth a call —
            // this is also the re-entry path for a just-granted overlay
            // permission (App's ACTION_MANAGE_OVERLAY_PERMISSION result
            // calls arm() again on return): attachBubbleIfAllowed() is a
            // no-op if a bubble is already up, and doesn't touch the
            // AudioRecord either way.
            //
            // But this path is still a fresh startForegroundService()
            // dispatch (App.arm() always calls it), and Android 12+
            // requires startForeground() satisfied on EACH dispatch, not
            // just the one that originally armed the session — skipping
            // it here risks ForegroundServiceDidNotStartInTimeException.
            // Re-posting the same NOTIFICATION_ID is an update, not a
            // second notification.
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            attachBubbleIfAllowed()
            return
        }

        // startForeground() runs FIRST, before the AudioRecord (or the
        // 10.6 MB ring) is ever built: on the Android 14+ FGS-type model,
        // opening a mic AudioRecord before the service has reached the
        // mic-eligible foreground state can silently yield zeros — no
        // exception, nothing to catch, just dead air. This also trivially
        // satisfies the ~5s startForeground() window, since nothing else
        // runs before it.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

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
            // silently doing nothing. The ARM button UI (Task 4) owns any
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

        if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "arm: AudioRecord built but left STATE_UNINITIALIZED")
            newRecord.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val started = runCatching { newRecord.startRecording() }.isSuccess &&
            newRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            Log.w(TAG, "arm: startRecording() never reached RECORDSTATE_RECORDING")
            newRecord.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val newRing = CaptureRing(RING_SECONDS * SAMPLE_RATE)
        record = newRecord
        ring = newRing
        reading = true
        readerThread = thread(name = "MicSession") {
            // The realtime loop: preallocated once, nothing else in here —
            // no allocation, no locks, no logging per iteration. read() is
            // caught, not left to throw, because this runs on a plain
            // thread{} where an uncaught exception kills the process — see
            // stopReaderAndRecord's KDoc for the shutdown race this guards.
            val block = FloatArray(READ_BLOCK_FRAMES)
            while (reading) {
                val n = try {
                    newRecord.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                } catch (e: IllegalStateException) {
                    break
                }
                if (n <= 0) break // record died or was stopped out from under us
                // Published for the ARM screen's live level meter (and, as
                // the mic-alive diagnostic, its liveness reading) — one
                // float store per block into a conflated StateFlow. This is
                // a plain blocking-read loop, not a realtime audio
                // callback, so the allocation-free-loop discipline that
                // governs CaptureRing.write doesn't apply to publishing
                // here; the peak scan itself allocates nothing either way.
                var peak = 0f
                for (i in 0 until n) {
                    val a = abs(block[i])
                    if (a > peak) peak = a
                }
                _level.value = peak
                newRing.write(block, n)
            }
            // Covers every way this loop ends — the normal `reading =
            // false` teardown (stopReaderAndRecord already zeroes this
            // too, so it's a harmless redundant write there), the
            // IllegalStateException catch's `break`, and the `n <= 0`
            // break on a dead/stopped record. Without this, a HAL failure
            // mid-session would freeze the meter at its last non-zero
            // reading while the wall-clock elapsed timer keeps ticking —
            // reading as "recording fine" for exactly the case (a dead
            // mic) this indicator exists to catch.
            _level.value = 0f
        }
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
        val activeRing = ring ?: return // SNIP with nothing armed: no-op
        thread(name = "MicSessionSnip") {
            val samples = activeRing.snapshot(RING_SECONDS * SAMPLE_RATE)
            // commitSnip runs the cleanup/doctor DSP chain on this bare
            // thread{} — same risk the reader loop above guards against:
            // an uncaught exception here kills the process, not just this
            // snip. CaptureDoctor.clean legitimately refuses (throws) on a
            // capture it judges distorted rather than clicky; that must
            // cost this one SNIP, not the whole armed session.
            val file = runCatching { commitSnip(samples, SAMPLE_RATE) }
                .onFailure { Log.w(TAG, "snip: commit failed", it) }
                .getOrNull()
            if (file != null) _lastSnipFile.value = file
        }
    }

    private fun handleEject() {
        stopReaderAndRecord()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * Stops the reader loop, releases the [AudioRecord], drops the ring
     * reference, and publishes `armed = false` — the privacy spine: disarm
     * drops the ring, so nothing the user did not snip survives past this
     * call. This is the one funnel both [ACTION_EJECT] and [onDestroy] go
     * through, so `armed` cannot go stale on a teardown path that isn't a
     * user-initiated eject (FGS reclaim, task swipe, an external
     * `stopService`) — every route to "this session is over" clears it
     * here, not just the explicit-eject one.
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
     * The bubble rides with the session the same way: [BubbleOverlay.detach]
     * runs here too, so every route to "this session is over" takes it
     * down, not just an explicit drag-to-eject.
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
        ring = null
        BubbleOverlay.detach()
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

    private fun buildNotification(): Notification {
        ensureChannel()
        val snipIntent = Intent(this, MicSessionService::class.java).setAction(ACTION_SNIP)
        val snipPending = PendingIntent.getService(
            this,
            0,
            snipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(Copy.SESSION_ARMED)
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
        const val ACTION_SNIP = "com.snipsnap.app.SNIP"
        const val ACTION_EJECT = "com.snipsnap.app.EJECT"
        const val RING_SECONDS = 60

        private const val TAG = "MicSessionService"
        private const val SAMPLE_RATE = 44_100
        private const val READ_BLOCK_FRAMES = 2048
        private const val READER_JOIN_TIMEOUT_MS = 1_000L
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1

        private val _armed = MutableStateFlow(false)
        val armed: StateFlow<Boolean> = _armed.asStateFlow()

        private val _lastSnipFile = MutableStateFlow<File?>(null)
        val lastSnipFile: StateFlow<File?> = _lastSnipFile.asStateFlow()

        /**
         * The reader loop's per-block peak, `0f..1f` — the live input level
         * for the ARM screen's recording indicator, and also the mic-alive
         * diagnostic: a session that's truly armed but hearing silence
         * holds this at (or near) zero, which is exactly what a dead mic
         * looks like too. Paired in the UI with a wall-clock elapsed
         * readout ([armedAtElapsedRealtime]) so the two together can tell
         * "recording, hearing nothing" apart from "not actually recording."
         * Conflated, so publishing at ~21 Hz (READ_BLOCK_FRAMES @ 44.1k)
         * from the reader thread is cheap — one float store per block, no
         * allocation.
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
         * Arms a session. Must be called from a foreground context — the
         * ARM button in a visible Activity, not a background trigger —
         * because Android 15 restricts starting a foreground service from
         * the background (docs/CAPTURE_RESEARCH_2026.md, drift #4: the
         * `BOOT_COMPLETED` restriction is the documented instance of a
         * broader background-start limit this sidesteps by construction:
         * a synchronous call from a button's onClick is itself a
         * foreground start).
         */
        fun arm(context: Context) {
            context.startForegroundService(
                Intent(context, MicSessionService::class.java).setAction(ACTION_ARM),
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
