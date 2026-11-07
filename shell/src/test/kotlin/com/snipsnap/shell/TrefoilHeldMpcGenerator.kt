package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small native sampler trial: three original roots, real loop metadata, no rack. */
object TrefoilHeldMpcGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "testkit/trefoil-held-mpc");root.mkdirs()
        val patch=TrefoilPatch("Trefoil Held",TrefoilVoice.WEAVE,TrefoilHeld.defaults(TrefoilVoice.WEAVE),
            velocity=.78f,seed=43012026L,playback=TrefoilPlayback.HELD)
        val directory=File(root,"Trefoil Held");val spec=TrefoilPadMaker.Spec(patch)
        val roots=TrefoilPadMaker.zoneMidis(spec);val notes=roots.map { TrefoilPadMaker.renderZone(spec,it) }
        TrefoilPadMaker.export("Trefoil Held",spec,notes,directory,overwrite=true)
        val instrument=InstrumentStore.read(InstrumentStore.sidecar(directory,"Trefoil Held"))
        val samples=instrument.zones.associate { zone ->
            val file=File(directory,zone.sample);val smpl=requireNotNull(WavReader.readSmplStrict(file))
            check(smpl.loop?.startFrame==zone.loopStartFrame)
            zone.sample to WavReader.read(file).samples
        }
        val player=InstrumentEngine(InstrumentEngine.Loaded(instrument,44100,samples),44100)
        data class Event(val seconds: Double,val note: Int,val on: Boolean,val velocity: Float=.8f)
        val events=listOf(Event(0.0,60,true),Event(1.0,60,false),Event(1.7,48,true),Event(2.1,60,true),
            Event(2.5,72,true),Event(3.2,48,false),Event(3.4,60,false),Event(3.6,72,false),
            Event(4.1,63,true,.45f),Event(4.9,63,false)).sortedBy { it.seconds }
        val frames=6*44100;val out=FloatArray(frames);var frame=0;var event=0
        while(frame<frames) {
            while(event<events.size && (events[event].seconds*44100).toInt()<=frame) {
                val e=events[event++];if(e.on)check(player.noteOn(e.note,e.velocity)) else player.noteOff(e.note)
            }
            val end=minOf(frames,frame+512,if(event<events.size)(events[event].seconds*44100).toInt() else frames)
            val block=FloatArray((end-frame)*2);player.render(block,end-frame)
            for(i in frame until end)out[i]=(block[(i-frame)*2]+block[(i-frame)*2+1])*.5f*.65f
            frame=end
        }
        check(out.all { it.isFinite() && kotlin.math.abs(it)<.99f })
        WavWriter.write(File(root,"sampler-demo.wav"),Snip(out,1,44100))
        File(directory,"READ-ME.txt").writeText("""
            Trefoil Held: native Kotlin sampler trial
            Open Trefoil Held.xty on MPC 3. For MPC 2, open the .xpm in its TrackData folder.
            Keep every exported folder and file together. Three native roots: MIDI ${roots.joinToString()}.
            A short onset ramp leads to the complete measured sustain. Hold a key to repeat; lift to release.
            NUDGE is zero. Release is a sample-player envelope, ${patch.releaseSeconds} seconds in SnipSnap.
            The matching MPC envelope value is not a measured hardware release time.
            This trial has one authored touch per root. Key velocity changes playback level.
            Compare low/middle/high notes, overlaps, soft/hard keys and lift during a loop boundary.
            The web sampler demonstration uses these actual exported files with monitor gain .65.
            Physical MPC validation of this held trial is pending. The approved finite bank is unchanged.
        """.trimIndent()+"\n")
        val archive=File(root,"Trefoil-Held-MPC.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            directory.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).path }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath).also { it.time=0 });f.inputStream().use { it.copyTo(zip) };zip.closeEntry()
            }
        }
        println("TREFOIL native MPC trial: ${archive.absolutePath} (${archive.length()} bytes)")
    }
}
