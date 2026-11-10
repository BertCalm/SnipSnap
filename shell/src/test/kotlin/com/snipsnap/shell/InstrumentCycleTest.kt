package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.json.JsonException
import com.snipsnap.xpm.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.nio.file.Files
import kotlin.test.*

class InstrumentCycleTest {
    private fun program() = KeygroupProgram("Cycle", listOf(Keygroup(60, 72, 60,
        listOf(0..79, 80..127).flatMapIndexed { zone, window -> (0..2).map { take ->
            VelocityLayer("v${zone}r$take", 256, window.first, window.last)
        } }, cycleLayers = true)), explicitNoteOn = true)
    @Test fun `saved cycles preserve takes and use independent touch and note cursors`() {
        val root = Files.createTempDirectory("cycle-keys").toFile()
        try {
            val program = program()
            InstrumentStore.write(root, "Cycle", program, "data")
            val loaded = InstrumentStore.read(InstrumentStore.sidecar(root, "Cycle"))
            assertEquals(program, loaded.toProgram())
            val cursor = KeyHit.CycleCursor()
            fun play(note: Int=60, velocity: Float=.5f) = KeyHit.resolveAll(loaded, note, velocity, cursor) { 256L }.single().hit.sampleFile
            assertEquals("data/v0r0.wav", play())
            assertEquals("data/v0r1.wav", play())
            assertEquals("data/v1r0.wav", play(velocity=1f))
            assertEquals("data/v0r0.wav", play(61))
            assertEquals("data/v0r2.wav", play())
            assertEquals("data/v0r0.wav", play())
            assertEquals("data/v1r1.wav", play(velocity=1f))
            assertEquals(6, loaded.sampleFiles.size)
            assertFalse(KeygroupWriter().write(program).contains("<ZonePlay>0</ZonePlay>"))
            assertEquals(2, program.keygroups.single().mpcLayers.size)
            val native = Mpc3TrackWriter().keygroupPayloadText(program)
            assertTrue(Regex(""""zonePlayTime"\s*:\s*1""").containsMatchIn(native))
            for (layer in program.keygroups.single().layers) assertTrue(native.contains(layer.sampleName))
        } finally { root.deleteRecursively() }
    }
    @Test fun `reference player actually changes waveform and wraps after three keys`() {
        val root = Files.createTempDirectory("cycle-play").toFile()
        try {
            InstrumentStore.write(root, "Cycle", program(), "data")
            val inst = InstrumentStore.read(InstrumentStore.sidecar(root, "Cycle"))
            val samples = inst.sampleFiles.mapIndexed { i, file -> file to FloatArray(256) { (i + 1) * .01f } }.toMap()
            val player = InstrumentEngine(InstrumentEngine.Loaded(inst, 44100, samples), 44100)
            fun hit(v: Float): Float {
                assertTrue(player.noteOn(60, v))
                val out = FloatArray(512); player.render(out, 256)
                return out[0]
            }
            val soft = (0..3).map { hit(.5f) }
            assertTrue(soft[0] < soft[1] && soft[1] < soft[2]); assertEquals(soft[0], soft[3])
            assertTrue(hit(1f) > soft.max())
        } finally { root.deleteRecursively() }
    }
    @Test fun `old sidecars still reject overlapping velocity windows`() {
        val root = Files.createTempDirectory("cycle-corrupt").toFile()
        try {
            val file = InstrumentStore.sidecar(root, "Cycle")
            InstrumentStore.write(root, "Cycle", program(), "data")
            file.writeText(Regex(""""cycleLayers"\s*:\s*true""").replace(file.readText(), "\"cycleLayers\": false"))
            val error = assertFailsWith<JsonException> { InstrumentStore.read(file) }
            assertTrue(error.message!!.contains("without overlap"))
        } finally { root.deleteRecursively() }
    }
}
