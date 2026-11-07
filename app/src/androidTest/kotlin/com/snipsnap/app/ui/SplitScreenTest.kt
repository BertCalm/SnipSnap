package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.SnipStore
import com.snipsnap.shell.SplitAquarium
import com.snipsnap.shell.SplitStore
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class SplitScreenTest : ComposeScreenTest() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val folders = mutableListOf<File>()
    private val beforeSnips = SnipStore.list(context.filesDir).toSet()
    private val prints = AtomicInteger(0)
    @After fun cleanup() {
        folders.forEach { it.deleteRecursively() }
        SnipStore.list(context.filesDir).filterNot { it in beforeSnips }.forEach { it.delete() }
    }
    private fun kit(name: String): KitShelf.Entry {
        val dir = File(context.cacheDir, "split-$name-${System.nanoTime()}").apply { check(mkdirs()) }
        folders += dir
        WavWriter.write(File(dir, "source.wav"), Snip(FloatArray(4410) { (.3 * sin(2 * Math.PI * 440 * it / 44100)).toFloat() }, 1, 44100))
        val sound = AquariumPerformanceSettings(AquariumRecipe(mapOf("aqua_tide_mix" to 1f,
            "aqua_tide_depth" to .8f, "aqua_tide_stereoPhase" to 180f)), true, .125f)
        val kit = Kit(name, listOf(KitPad(1, "source.wav")), tempoBpm = 123.5f, aquariumPerformance = sound.toJsonValue())
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }
    private fun invoke(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick) { assertTrue("$label accepts the action", it()) }
        pump()
    }
    private fun ready() = waitFor("SPLIT settings ready") { runCatching { button("FX").assertIsEnabled() }.isSuccess }
    private fun split() {
        invoke("SPLIT · TAKE THIS PAD APART")
        waitFor("the three stems", 60_000) { runCatching { button("PLAY").assertIsEnabled() }.isSuccess }
    }
    private fun host(entry: () -> KitShelf.Entry?, lifecycle: LifecycleOwner? = null) {
        setPhoneContent {
            val appScope = rememberCoroutineScope()
            val content: @androidx.compose.runtime.Composable () -> Unit = {
                SplitScreen(entry(), "◄ KIT", {}, { prints.incrementAndGet() }, {}, {}, appScope)
            }
            if (lifecycle == null) content() else CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { content() }
        }
        ready()
    }
    private fun fxReady() = waitFor("SPLIT FX route ready") {
        compose.onNodeWithTag("split-aquarium-route").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
    }

    @Test fun saved_route_and_tail_print_stereo_without_rewriting_kit_or_source() {
        val entry = kit("PRINT")
        val metadata = File(entry.dir, "kit.json").readBytes(); val source = File(entry.dir, "source.wav").readBytes()
        host({ entry }); invoke("FX"); fxReady()
        val toggle = compose.onNodeWithTag("split-aquarium-route")
        toggle.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        waitFor("SPLIT route saved and drawn") {
            runCatching { SplitStore.load(entry.dir).fromKit }.getOrNull() == true &&
                toggle.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true
        }
        invoke("Shorten SPLIT FX print tail"); invoke("Shorten SPLIT FX print tail")
        waitFor("zero tail saved") { runCatching { SplitStore.load(entry.dir).tailSeconds }.getOrNull() == 0f }
        invoke("DONE"); split(); invoke("PLAY"); invoke("STOP"); invoke("PRINT")
        waitFor("stereo print landed", 60_000) { prints.get() == 1 }
        val file = SnipStore.list(context.filesDir).first { it !in beforeSnips }
        val saved = WavReader.read(file)
        assertEquals(2, saved.channels); assertEquals(44100, saved.sampleRate)
        assertTrue(saved.samples.indices.step(2).any { abs(saved.samples[it] - saved.samples[it + 1]) > 1e-4f })
        assertArrayEquals(metadata, File(entry.dir, "kit.json").readBytes())
        assertArrayEquals(source, File(entry.dir, "source.wav").readBytes())
    }

    @Test fun kit_switch_stops_the_old_desk_and_an_accepted_save_stays_with_its_original_kit() {
        val a = kit("A"); val b = kit("B"); val current = mutableStateOf<KitShelf.Entry?>(a)
        host({ current.value }); split(); invoke("PLAY"); invoke("FX"); fxReady()
        runBlocking { KitWrites.mutex.lock() }
        try {
            compose.onNodeWithTag("split-aquarium-route").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            pump()
            compose.runOnUiThread { current.value = b }; pump()
            button("PLAY").assertIsNotEnabled()
            assertTrue(compose.onAllNodesWithContentDescription("STOP").fetchSemanticsNodes().isEmpty())
            invoke("FX")
        } finally { KitWrites.mutex.unlock() }
        fxReady()
        waitFor("outgoing kit save completed") { runCatching { SplitStore.load(a.dir).fromKit }.getOrNull() == true }
        assertFalse(SplitStore.load(b.dir).fromKit)
        assertFalse(File(b.dir, SplitStore.FILE_NAME).exists())
        assertEquals(false, compose.onNodeWithTag("split-aquarium-route").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
    }

    @Test fun background_release_stops_playback_and_foreground_waits_for_a_fresh_play() {
        val entry = kit("LIFECYCLE")
        SplitStore.save(entry.dir, SplitAquarium.Settings(true, 0f))
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        host({ entry }, owner); split(); invoke("PLAY")
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }; pump()
        button("PLAY").assertIsEnabled()
        assertTrue(compose.onAllNodesWithContentDescription("STOP").fetchSemanticsNodes().isEmpty())
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }; pump()
        button("PLAY").assertIsEnabled()
        invoke("PLAY"); invoke("STOP")
        assertTrue(SplitStore.load(entry.dir).fromKit)
    }
}
