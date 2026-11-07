package com.snipsnap.app.ui

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.synth.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureNanoTime
import org.junit.Assert.assertTrue

/** Run on the owner's phone to obtain Android timings; host numbers are kept separate. */
@RunWith(AndroidJUnit4::class)
class TrefoilHeldBenchmark {
    @Test fun recordColdCapturesAndOwnedCacheHits() {
        val base=TrefoilPatch("BENCHMARK",TrefoilVoice.WEAVE,TrefoilHeld.defaults(TrefoilVoice.WEAVE),
            velocity=.78f,seed=43012026L,playback=TrefoilPlayback.HELD)
        val rows=JSONArray()
        try {
            for(midi in listOf(48,60,72)) {
                val patch=base.copy(midi=midi)
                TrefoilHeld.clearCache()
                val cold=measureNanoTime {
                    val held=TrefoilHeld.render(patch)
                    assertTrue(held.snip.samples.all { it.isFinite() })
                    assertTrue(held.diagnostics.stateRelativeError<1e-8)
                }/1e6
                val hits=JSONArray()
                repeat(7){hits.put(measureNanoTime{TrefoilHeld.render(patch)}/1e6)}
                rows.put(JSONObject().put("midi",midi).put("cacheColdMillis",cold).put("ownedCacheHitMillis",hits))
            }
            val report=JSONObject().put("deviceModel",Build.MODEL).put("manufacturer",Build.MANUFACTURER)
                .put("androidApi",Build.VERSION.SDK_INT).put("fingerprint",Build.FINGERPRINT)
                .put("measurement","Android runtime; record whether the target is a phone or emulator")
                .put("coldDefinition","audio cache cleared; geometry/JIT may already be warm")
                .put("rows",rows)
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            File(context.filesDir,"trefoil-held-benchmark.json").writeText(report.toString(2)+"\n")
            Log.i("TrefoilHeldBenchmark",report.toString())
        } finally { TrefoilHeld.clearCache() }
    }
}
