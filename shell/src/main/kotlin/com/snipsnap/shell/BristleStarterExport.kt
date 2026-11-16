package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.kit.*
import com.snipsnap.synth.BristleKits
import java.io.File

/** The phone starter retains its full grid. MPC gets real velocity files and explicit cycling companions.
 * Avoid the existing Slice Motion writer until its missing embedded WAV slice map is implemented. */
object BristleStarterExport {
    data class Result(val native: ExportResult, val companions: List<ExportResult>)

    fun write(destination: File): Result {
        require(!destination.exists()) { "Bristle starter export already exists" }
        val parent=destination.absoluteFile.parentFile.apply { mkdirs() }
        val stage=java.nio.file.Files.createTempDirectory(parent.toPath(),".bristle-starter-").toFile()
        val scratch=java.nio.file.Files.createTempDirectory(parent.toPath(),".bristle-source-").toFile()
        try {
            val pads=BristleKits.build()
            val velocity=pads.map { pad -> pad.copy(velocityTakes=emptyList(),softVariants=pad.velocityTakes.dropLast(1).map { it.first() }) }
            val dir=File(scratch,"velocity")
            val kit=KitAssembler.assembleArranged(BristleKits.NAME,velocity,dir,KeySpec.parse("Cminpent"))
            val native=Mpc3Exporter.exportTrack(kit,dir,stage,mpc2Twin=true)
            val companions=listOf("Soft","Medium","Hard").mapIndexed { touch,label ->
                fun scaled(snip: Snip)=snip.copy(samples=FloatArray(snip.samples.size) { snip.samples[it]*BristleKits.velocities[touch] })
                val name="Bristle $label Cycle"
                val folder=File(stage,"Cycling Programs/$name").apply { mkdirs() }
                val files=mutableListOf<File>()
                val slots=pads.mapIndexed { index,pad ->
                    val row=pad.velocityTakes[touch].map(::scaled)
                    val layers=row.mapIndexed { take,snip ->
                        val stem="A${(index+1).toString().padStart(2,'0')}_RR${take+1}"
                        val file=File(folder,"$stem.wav")
                        com.snipsnap.audio.WavWriter.write(file,snip);files+=file
                        com.snipsnap.xpm.VelocityLayer(stem,snip.frameCount.toLong(),0,127)
                    }
                    com.snipsnap.xpm.Pad(layers.first().sampleName,layers.first().frameCount,cycleLayers=layers)
                }
                val program=com.snipsnap.xpm.XpmWriter().writeTo(folder,com.snipsnap.xpm.DrumProgram(name,slots))
                ExportResult(folder,program,files,emptyList())
            }
            File(stage,"READ-ME.txt").writeText("The main MPC 3 .xtd and MPC 2 .xpm use three timbral velocity layers, first takes.\n"+
                "Cycling Programs contains Soft, Medium and Hard .xpm programs with two separate take files per pad.\n"+
                "These fixed-touch programs cycle takes; live velocity changes volume. Combined velocity and cycling remains open.\n"+
                "The SnipSnap FRESH TAPE starter retains all three touch pools and both takes.\n")
            java.nio.file.Files.move(stage.toPath(),destination.toPath())
            fun moved(r: ExportResult)=r.copy(directory=File(destination,r.directory.relativeTo(stage).path),
                program=File(destination,r.program.relativeTo(stage).path),samples=r.samples.map { File(destination,it.relativeTo(stage).path) })
            return Result(moved(native),companions.map(::moved))
        } finally { stage.deleteRecursively();scratch.deleteRecursively() }
    }
}
