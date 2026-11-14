package com.snipsnap.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.CairnSingInstrumentMaker
import com.snipsnap.shell.CairnSingInstrumentPicker
import com.snipsnap.shell.CairnSingRenderedCaptures
import com.snipsnap.synth.CairnSamplerDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The phone's half of the CAIRN SING delivery: this device's own render of all
 * nine retained SING captures must match the Float SHA-256 pins the JVM
 * matches, or the picker refuses them. A real study is then admitted from the
 * rendered cache, exactly as MAKE › INSTRUMENTS › CAIRN SING does.
 */
@RunWith(AndroidJUnit4::class)
class CairnSingRenderOnDeviceTest {
    @Test
    fun this_device_renders_all_nine_sing_captures_to_their_pins_and_admits_a_study() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "cairn-sing-device-${System.nanoTime()}")
        try {
            val cache = File(root, "captures")
            val ids = CairnSingInstrumentMaker.Program.entries.flatMap(CairnSingInstrumentMaker::sourceIds)
            assertEquals(9, ids.distinct().size)
            for (program in CairnSingInstrumentMaker.Program.entries) {
                val captures = CairnSingRenderedCaptures.readCaptures(program, cache)
                for ((id, capture) in captures) assertEquals(id, CairnSingInstrumentMaker.floatSha256(id), capture.captureSha256)
            }
            for (id in ids) {
                val file = CairnSingRenderedCaptures.captureFile(id, cache)
                assertEquals(id, CairnSingInstrumentMaker.floatSha256(id), CairnSamplerDelivery.sha(file.readBytes()))
            }
            val shelf = File(root, "instruments")
            val made = CairnSingInstrumentPicker.make(CairnSingInstrumentMaker.Program.MOVING.id, shelf, { program, stop ->
                CairnSingRenderedCaptures.readCaptures(program, cache, stop,
                    render = { error("every capture is already cached") })
            })
            val listed = InstrumentStore.list(shelf).single()
            assertEquals(made.sidecar, listed.first)
            assertEquals((48..72).toList(), listed.second.zones.flatMap { (it.lowNote..it.highNote).toList() })
            assertTrue(listed.second.zones.all { it.loopStartFrame == CairnSamplerDelivery.LOOP_START.toLong() })
        } finally { root.deleteRecursively() }
    }
}
