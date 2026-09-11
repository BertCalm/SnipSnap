package com.snipsnap.app.spike

// SPIKE ONLY - throwaway feasibility probe, delete with this branch.
//
// The single question: does AudioPlaybackCapture return real audio on this
// phone, or digital silence? Records PROBE_SECONDS of whatever is playing,
// logs peak/RMS, and dumps raw float32 for independent host-side checking.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "SnipSpike"
private const val CHANNEL = "snipspike"
private const val NOTE_ID = 1
private const val PROBE_SECONDS = 5
private const val RATE = 44_100
private const val CHANNELS = 2

class CaptureProbeService : Service() {

    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ is strict about ordering: the service must already be
        // foreground with the mediaProjection type before getMediaProjection.
        startForegroundProbe()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            Log.e(TAG, "PROBE_RESULT error=no projection token")
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = mgr.getMediaProjection(code, data)
        if (proj == null) {
            Log.e(TAG, "PROBE_RESULT error=getMediaProjection returned null")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        // API 34+ refuses to start capture unless a callback is registered.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "PROBE projection stopped")
            }
        }, Handler(Looper.getMainLooper()))

        thread(name = "probe-capture") { record(proj) }
        return START_NOT_STICKY
    }

    private fun record(proj: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBytes = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(RATE * CHANNELS * 4)

        val record = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBytes)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "PROBE_RESULT error=AudioRecord build failed: $t")
            stopSelf(); return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "PROBE_RESULT error=AudioRecord not initialized state=${record.state}")
            record.release(); stopSelf(); return
        }

        val total = RATE * CHANNELS * PROBE_SECONDS
        val all = FloatArray(total)
        var got = 0
        record.startRecording()
        Log.i(TAG, "PROBE recording ${PROBE_SECONDS}s ...")
        val chunk = FloatArray(4096)
        val deadline = System.currentTimeMillis() + (PROBE_SECONDS * 1000L) + 3000L
        while (got < total && System.currentTimeMillis() < deadline) {
            val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                Log.w(TAG, "PROBE read returned $n")
                continue
            }
            val copy = minOf(n, total - got)
            System.arraycopy(chunk, 0, all, got, copy)
            got += copy
        }
        record.stop()
        record.release()
        proj.stop()

        // Peak and RMS decide the whole question: a blocked app yields exact
        // digital silence, not merely quiet audio.
        var peak = 0f
        var sumSq = 0.0
        for (i in 0 until got) {
            val v = all[i]
            val a = abs(v)
            if (a > peak) peak = a
            sumSq += v.toDouble() * v.toDouble()
        }
        val rms = if (got > 0) sqrt(sumSq / got) else 0.0
        val nonZero = (0 until got).count { all[it] != 0f }

        val raw = File(filesDir, "probe.raw")
        runCatching {
            java.io.DataOutputStream(raw.outputStream().buffered()).use { out ->
                for (i in 0 until got) out.writeFloat(all[i])
            }
        }.onFailure { Log.e(TAG, "PROBE raw dump failed: $it") }

        Log.i(
            TAG,
            "PROBE_RESULT frames=$got peak=$peak rms=$rms nonZeroSamples=$nonZero " +
                "verdict=${if (peak > 0f) "AUDIO" else "SILENCE"} raw=${raw.absolutePath}",
        )
        stopSelf()
    }

    private fun startForegroundProbe() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "SnipSnap spike", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val note: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("SnipSnap capture probe")
            .setContentText("Recording ${PROBE_SECONDS}s ...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }
}
