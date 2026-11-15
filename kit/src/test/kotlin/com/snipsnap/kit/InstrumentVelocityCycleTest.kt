package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.nio.file.Files
import kotlin.test.*

class InstrumentVelocityCycleTest {
    private fun program(verified: Boolean) = KeygroupProgram("Cycle", listOf(Keygroup(48, 72, 60,
        (0..1).flatMap { zone -> (1..3).map { take ->
            VelocityLayer("z${zone}t$take", 100L, if (zone == 0) 0 else 80, if (zone == 0) 79 else 127)
        } }, cycleLayers = true, mpcVelocityCycle = verified)))

    @Test fun `v4 bytes and fallback stay unchanged while v5 retains the export policy`() {
        val folder = Files.createTempDirectory("mpc-cycle-sidecar").toFile()
        try {
            for (verified in listOf(false, true)) {
                val source = program(verified)
                val text = InstrumentStore.render(source, "Cycle_[TrackData]")
                assertEquals(if (verified) 5 else 4, Json.parse(text).obj().getValue("version").int())
                assertEquals(verified, "mpcVelocityCycle" in text)
                val file = InstrumentStore.sidecar(folder, "Cycle").apply { writeText(text) }
                val loaded = InstrumentStore.read(file)
                assertEquals(source, loaded.toProgram(), "MPC matching-window policy survives persistence")
                assertEquals(if (verified) 6 else 2, loaded.toProgram().keygroups.single().mpcLayers.size)
                val zone = loaded.zones.single()
                assertEquals(listOf("z0t1.wav", "z0t2.wav", "z0t3.wav", "z0t1.wav"),
                    (0..3).map { zone.layerFor(40, it)!!.sample.substringAfterLast('/') })
                assertEquals("z1t1.wav", zone.layerFor(110)!!.sample.substringAfterLast('/'))
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun `v5 refuses missing policy or a policy without cycling with named errors`() {
        val folder = Files.createTempDirectory("mpc-cycle-invalid").toFile()
        try {
            val original = InstrumentStore.render(program(true), "Cycle_[TrackData]")
            fun changeZone(edit: (MutableMap<String, JsonValue>) -> Unit): String {
                val root = Json.parse(original).obj()
                val zones = (root.getValue("zones") as JsonValue.Arr).items.map {
                    JsonValue.Obj(it.obj().toMutableMap().also(edit))
                }
                return Json.write(JsonValue.Obj(root + ("zones" to JsonValue.Arr(zones))))
            }
            for ((text, message) in listOf(
                changeZone { it.remove("mpcVelocityCycle") } to "zone has no mpcVelocityCycle",
                changeZone { it["cycleLayers"] = JsonValue.Bool(false) } to
                    "MPC velocity cycle requires cycling across multiple velocity windows",
            )) {
                val file = InstrumentStore.sidecar(folder, "Cycle").apply { writeText(text) }
                val error = assertFailsWith<JsonException> { InstrumentStore.read(file) }
                assertEquals(message, error.message)
            }
        } finally { folder.deleteRecursively() }
    }
}
