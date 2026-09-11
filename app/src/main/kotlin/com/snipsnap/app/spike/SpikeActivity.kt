package com.snipsnap.app.spike

// SPIKE ONLY - throwaway feasibility probe, delete with this branch.
//
// Drives the consent dance so the service can open an AudioPlaybackCapture
// session: runtime permissions first, then the MediaProjection dialog, then
// hand the token to the foreground service.

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

private const val TAG = "SnipSpike"

class SpikeActivity : ComponentActivity() {

    private val projection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            Log.e(TAG, "PROBE_RESULT consent=DENIED code=${result.resultCode}")
            finish()
            return@registerForActivityResult
        }
        Log.i(TAG, "PROBE consent=GRANTED, starting service")
        val svc = Intent(this, CaptureProbeService::class.java).apply {
            putExtra(CaptureProbeService.EXTRA_CODE, result.resultCode)
            putExtra(CaptureProbeService.EXTRA_DATA, result.data)
        }
        startForegroundService(svc)
        finish()
    }

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val mic = granted[android.Manifest.permission.RECORD_AUDIO] ?: false
        Log.i(TAG, "PROBE perms mic=$mic all=$granted")
        if (!mic) {
            Log.e(TAG, "PROBE_RESULT mic=DENIED - cannot probe")
            finish()
            return@registerForActivityResult
        }
        askConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += android.Manifest.permission.POST_NOTIFICATIONS
        }
        perms.launch(wanted.toTypedArray())
    }

    private fun askConsent() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection.launch(mgr.createScreenCaptureIntent())
    }
}
