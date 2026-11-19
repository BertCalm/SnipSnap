package com.snipsnap.shell

import com.snipsnap.kit.*
import com.snipsnap.synth.SideroomKits
import java.io.File

/** Discrete-WAV fallback: select a touch program, then cycle all three actual takes.
 * This does not claim simultaneous velocity switching and cycling on a single pad.
 */
object SideroomDelivery {
    val touches = listOf("Soft", "Medium", "Hard")

    fun exportCycles(genre: SideroomKits.Kit, root: File, overwrite: Boolean = false,
                     pads: List<ArrangedPad> = SideroomKits.build(genre)): List<ExportResult> {
        require(pads.size == SideroomKits.PADS && pads.all { p -> p.velocityTakes.size == 3 && p.velocityTakes.all { it.size == 3 } })
        root.mkdirs()
        return touches.mapIndexed { z, touch ->
            val work = java.nio.file.Files.createTempDirectory("sideroom-cycle-").toFile()
            try {
                val selected = pads.mapIndexed { role, p ->
                    val row = p.velocityTakes[z]
                    p.copy(snip = row.first(), takes = row.drop(1), velocityTakes = emptyList(), recipe = null,
                        source = mapOf("family" to "SIDEROOM", "sourceModel" to SideroomKits.VERSION.toString(),
                            "kit" to genre.name, "role" to role.toString(), "deliveryTouch" to touch,
                            "delivery" to "fixed-touch-cycle"))
                }
                val kit = KitAssembler.assembleArranged("Sideroom ${genre.title} $touch Cycle", selected, work,
                    tempoBpm = genre.bpm.toFloat()).let { k -> k.copy(pads = k.pads.map { it.copy(robin = RobinSettings(null, 3, followsKit = false)) }) }
                KitStore.save(kit, work)
                Mpc3Exporter.exportTrack(kit, work, File(root, touch.lowercase()), overwrite = overwrite,
                    mpc2Twin = true, splitRoundRobinTakes = true)
            } finally { work.deleteRecursively() }
        }
    }
}
