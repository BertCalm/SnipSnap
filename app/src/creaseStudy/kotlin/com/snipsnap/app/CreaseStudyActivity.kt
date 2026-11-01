package com.snipsnap.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors

/** A separately installed, one-key transport proof. No sampler import/export changes. */
class CreaseStudyActivity : ComponentActivity(), AudioVoice {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var handle = 0L
    private var workerHandle = 0L // worker thread only
    private var generation = 0
    private var foreground = false
    private var resumed = false
    private var destroyed = false
    private var opening = false
    private var ready by mutableStateOf(false)
    private var held by mutableStateOf(false)
    private var unfold by mutableStateOf(true)
    private var message by mutableStateOf("Loading verified C4 cache…")
    private var gesture = 0L
    private var keyboard = 0L
    private var keyboardKey = 0
    private val monitor = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (handle != 0L && ready && NativeCreaseStudy.failed(handle)) {
                silence(); ready=false; message="Audio route closed. Tap Retry audio.";
            }
            main.postDelayed(this,250)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().background(Color(0xff101b20)).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text("SnipSnap · Crease",color=Color.White,fontSize=28.sp)
                Text("Reed Mark C4 · phone study",color=Color(0xffa9c7c3))
                Text(message,color=Color.White)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(onClick={silence();unfold=false},enabled=ready && !held) { Text(if(!unfold) "✓ Fade only" else "Fade only") }
                    Button(onClick={silence();unfold=true},enabled=ready && !held) { Text(if(unfold) "✓ Unfold" else "Unfold") }
                }
                // Pointer cancellation always reaches finally. Accessibility uses a
                // finite preview, rather than leaving a latched note behind.
                Box(Modifier.testTag("crease-study-key").fillMaxWidth().height(220.dp).background(if(held) Color(0xff176d64) else Color(0xff26454c))
                    .semantics { role=Role.Button; onClick("Play C4 preview") {
                        val token=press(); main.postDelayed({release(token)},600); token!=0L
                    } }
                    .pointerInput(ready,unfold) {
                        awaitEachGesture {
                            val down=awaitFirstDown(requireUnconsumed=false)
                            val token=press()
                            try { do { val event=awaitPointerEvent(); event.changes.forEach { it.consume() } } while(event.changes.any { it.id==down.id && it.pressed }) }
                            finally { release(token) }
                        }
                    }.padding(24.dp)) {
                    BasicText(if(held) "C4 · HOLDING" else "PRESS & HOLD C4",style=TextStyle(color=Color.White,fontSize=24.sp))
                }
                Button(onClick={silence()},enabled=ready) { Text("Stop") }
                if(!ready) Button(onClick={openAudio()}) { Text("Retry audio") }
                Text("Release your finger to hear the tail. Space or Enter also holds C4.\n\nOne accepted sound, fixed velocity. This study uses cached PCM, with no live synthesis solver. Headphones recommended.",color=Color(0xffa9c7c3))
            }
        } }
        main.post(monitor)
    }
    override fun onStart() { super.onStart();generation++;foreground=true;openAudio() }
    override fun onResume() { super.onResume();resumed=true;if(ready && resumed) AudioFocus.acquire(this) }
    override fun onPause() { resumed=false;silence();AudioFocus.release(this);super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus);if(!hasFocus) silence() }
    override fun onStop() {
        generation++;foreground=false;silence();handle=0;ready=false;opening=false;AudioFocus.release(this)
        worker.execute { if(workerHandle!=0L) NativeCreaseStudy.stop(workerHandle) }
        super.onStop()
    }
    override fun onDestroy() {
        generation++;destroyed=true;main.removeCallbacksAndMessages(null);silence();handle=0;AudioFocus.release(this)
        worker.execute { if(workerHandle!=0L) NativeCreaseStudy.destroy(workerHandle);workerHandle=0 }
        worker.shutdown();super.onDestroy()
    }
    private fun openAudio() {
        if(destroyed || !foreground || opening || ready) return
        opening=true;message="Loading verified C4 cache…"
        val requestedGeneration=generation
        worker.execute {
            val result=runCatching {
                if(workerHandle==0L) {
                    val m=assets.open("crease/manifest.json").use { CreaseStudyCache.readLimited(it,1024*1024).toString(Charsets.UTF_8) }.let(CreaseStudyCache::manifest)
                    val data=CreaseStudyCache.decode(m,assets.open("crease/${m.file}"))
                    workerHandle=NativeCreaseStudy.create(m.native,data.pcm)
                    check(workerHandle!=0L) { "Native reader refused the study cache" }
                }
                check(NativeCreaseStudy.start(workerHandle)) { "Could not open low-latency audio" }
                workerHandle
            }
            main.post {
                if(destroyed || !foreground || generation!=requestedGeneration) return@post
                opening=false;ready=result.isSuccess;handle=result.getOrDefault(0)
                message=if(ready) "Ready · C4 · 44.1 kHz source" else result.exceptionOrNull()?.message ?: "Audio unavailable"
                if(ready && resumed) AudioFocus.acquire(this)
            }
        }
    }
    private fun press(): Long {
        if(!ready || !foreground || !resumed || handle==0L) return 0
        AudioFocus.acquire(this)
        gesture=NativeCreaseStudy.press(handle,unfold);held=true;return gesture
    }
    private fun release(token: Long) {
        if(token==0L || token!=gesture || handle==0L) return
        NativeCreaseStudy.release(handle,token);held=false;gesture=0
    }
    override fun silence() {
        if(handle!=0L) NativeCreaseStudy.panic(handle)
        held=false;gesture=0;keyboard=0;keyboardKey=0
    }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if(keyCode==KeyEvent.KEYCODE_SPACE || keyCode==KeyEvent.KEYCODE_ENTER) {
            if(event.repeatCount==0) { keyboard=press();keyboardKey=keyCode };return true
        }
        return super.onKeyDown(keyCode,event)
    }
    override fun onKeyUp(keyCode: Int,event: KeyEvent): Boolean {
        if(keyCode==KeyEvent.KEYCODE_SPACE || keyCode==KeyEvent.KEYCODE_ENTER) { if(keyboardKey==keyCode) { release(keyboard);keyboard=0;keyboardKey=0 };return true }
        return super.onKeyUp(keyCode,event)
    }
}
