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
import com.snipsnap.audio.CaptureRing
import com.snipsnap.shell.Copy
import java.io.File
import kotlin.concurrent.thread
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
        if (ring != null) return // one session at a time; already armed

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
        if (minBuffer <= 0) return // device rejects the format outright

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
            return
        } catch (e: UnsupportedOperationException) {
            return // params the device won't accept
        }

        if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
            newRecord.release()
            return
        }

        val newRing = CaptureRing(RING_SECONDS * SAMPLE_RATE)

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        val started = runCatching { newRecord.startRecording() }.isSuccess &&
            newRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            newRecord.release()
            return
        }

        record = newRecord
        ring = newRing
        reading = true
        readerThread = thread(name = "MicSession") {
            // The realtime loop: preallocated once, nothing else in here —
            // no allocation, no locks, no logging per iteration.
            val block = FloatArray(READ_BLOCK_FRAMES)
            while (reading) {
                val n = newRecord.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) break // record died or was stopped out from under us
                newRing.write(block, n)
            }
        }
        _armed.value = true
    }

    private fun handleSnip() {
        val activeRing = ring ?: return // SNIP with nothing armed: no-op
        thread(name = "MicSessionSnip") {
            val samples = activeRing.snapshot(RING_SECONDS * SAMPLE_RATE)
            val file = commitSnip(samples, SAMPLE_RATE)
            if (file != null) _lastSnipFile.value = file
        }
    }

    private fun handleEject() {
        stopReaderAndRecord()
        stopForeground(STOP_FOREGROUND_REMOVE)
        _armed.value = false
    }

    /**
     * Stops the reader loop, releases the [AudioRecord], and drops the ring
     * reference — the privacy spine: disarm drops the ring, so nothing the
     * user did not snip survives past this call.
     *
     * Order matters: [reading] flips false and [AudioRecord.stop] is called
     * first, which unblocks a reader thread parked in a blocking `read()`;
     * only after joining that thread (bounded, so a wedged read can't hang
     * shutdown forever) is the record released. Releasing while a read is
     * still in flight is the same class of race [AndroidAudioSink] avoids
     * on the playback side.
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
         * The seam Task 3's `SnipStore` fills in. Until it exists, SNIP
         * still exercises the real read path — a real ring snapshot on
         * every press — it just has nowhere durable to put it yet, so the
         * default hands back null and nothing is written to disk. Task 3
         * reassigns this to a function backed by `SnipStore.commit(...)`.
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
