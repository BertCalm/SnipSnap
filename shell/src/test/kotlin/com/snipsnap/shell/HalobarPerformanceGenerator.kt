package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Native finite keys, exported PCM playback, chord-tool notes and authored roll/riser examples. */
object HalobarPerformanceGenerator {
    private fun json(x: Any?): JsonValue = when(x) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(x)
        is Boolean -> JsonValue.Bool(x)
        is Number -> JsonValue.Num(x.toDouble())
        is Map<*,*> -> JsonValue.Obj(x.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(x.map(::json))
        else -> error("unsupported manifest value")
    }
    data class Event(val at: Double, val midi: Int, val velocity: Float, val duration: Double)
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/halobar-performance").apply { mkdirs() }
        val keys = File(root, "keygroups").apply { mkdirs() }
        val clips = File(root, "clips").apply { mkdirs() }
        val rows = mutableListOf<Map<String, Any?>>()
        fun performance(id: String, title: String, spec: HalobarPadMaker.Spec,
                        sidecar: File, instrument: InstrumentStore.Instrument, events: List<Event>, seconds: Double) {
            val decoded = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            val engine = InstrumentEngine(InstrumentEngine.Loaded(instrument, Halobar.RATE, decoded), Halobar.RATE)
            val on = events.groupBy { (it.at * Halobar.RATE).toInt() }
            val off = events.groupBy { ((it.at + it.duration) * Halobar.RATE).toInt() }
            val boundaries = (on.keys + off.keys + listOf(0, (seconds * Halobar.RATE).toInt())).distinct().sorted()
            val out = FloatArray(boundaries.last() * 2)
            for (i in 0 until boundaries.lastIndex) {
                val start = boundaries[i]
                off[start]?.forEach { engine.noteOff(it.midi) }
                on[start]?.forEach { check(engine.noteOn(it.midi, it.velocity)) }
                val frames = boundaries[i+1] - start
                val block = FloatArray(frames * 2); engine.render(block, frames); block.copyInto(out, start * 2)
            }
            val file = "clips/$id.wav"
            WavWriter.write(File(root, file), Snip(out, 2, Halobar.RATE), WavWriter.BitDepth.PCM_16)
            rows += mapOf("id" to id, "title" to title, "file" to file, "voice" to spec.patch.voice.name,
                "source" to "PCM24 keygroup package reloaded through InstrumentEngine", "events" to events.map {
                    mapOf("atSeconds" to it.at, "midi" to it.midi, "velocity" to it.velocity, "durationSeconds" to it.duration)
                }, "seconds" to seconds)
        }
        for (voice in HalobarVoice.entries) {
            val spec = HalobarPadMaker.Spec(HalobarPresets.auditionFor(voice).first().copy(name = voice.name), .35f, 17)
            val name = "HALO ${voice.name.replace('_', ' ')}"
            val notes = HalobarPadMaker.zoneMidis(spec).map { midi ->
                println("Capture $voice MIDI $midi")
                HalobarPadMaker.renderZone(spec, midi)
            }
            HalobarPadMaker.export(name, spec, notes, keys, overwrite = true)
            val sidecar = InstrumentStore.sidecar(keys, name)
            val instrument = InstrumentStore.read(sidecar)
            val rootMidi = Halobar.rootMidi(voice)
            val repeats = listOf(.42f, 1f).flatMapIndexed { zone, velocity -> (0..2).map { take ->
                Event((zone * 3 + take) * 1.2, rootMidi, velocity, .75)
            } }
            performance("${voice.name.lowercase()}-touch-cycle", "$name: soft three, hard three", spec,
                sidecar, instrument, repeats, 7.5)
            val chords = ChordStabs.stabs(KeySpec.parse("Cm"))
            val progression = listOf(0, 3, 4, 8).flatMapIndexed { i, index ->
                HalobarChords.notes(voice, chords[index]).map { Event(i * 1.2, it, .75f, .8) }
            }
            performance("${voice.name.lowercase()}-chords", "$name: Cm, Fm, Gm, Cm7", spec,
                sidecar, instrument, progression, 5.0)
            if (voice in listOf(HalobarVoice.VIBRAPHONE, HalobarVoice.TUBULAR_BELLS)) {
                val strikes = listOf(.0, .42, .78, 1.08, 1.32, 1.50, 1.64, 1.76, 1.88, 2.0)
                val roll = strikes.mapIndexed { i, at ->
                    Event(at, rootMidi, listOf(.42f,.58f,.66f,.52f,.72f,.62f,.78f,.7f,.86f,1f)[i],
                        if (i == strikes.lastIndex) .9 else strikes[i+1] - at)
                }
                performance("${voice.name.lowercase()}-roll", "$name: accelerating mallet roll", spec,
                    sidecar, instrument, roll, 3.5)
                val rise = listOf(0,3,5,7,10,12).mapIndexed { i, offset -> Event(i*.3, rootMidi+offset, .45f+i*.1f, .65) }
                performance("${voice.name.lowercase()}-riser", "$name: rising minor pentatonic", spec,
                    sidecar, instrument, rise, 2.8)
            }
        }
        val manifest = mapOf("engine" to "HALOBAR", "status" to "Software audition, owner listening pending",
            "keygroupModel" to 1, "touchLevels" to 2, "takesPerLevel" to 3, "seed" to 17,
            "finiteSamples" to true, "phoneRoundRobin" to true,
            "mpcKeygroupRoundRobin" to false, "mpcFallback" to "First take per velocity level; other captures retained for phone playback",
            "devicePlaybackVerified" to false, "mpcHardwareLoadingVerified" to false, "clips" to rows)
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        val cards = rows.joinToString("\n") { row ->
            "<article><h2>${row["title"]}</h2><audio controls preload=\"none\" src=\"${row["file"]}\"></audio></article>"
        }
        File(root, "index.html").writeText("""<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>HALOBAR performance</title>
<style>body{font:17px/1.6 system-ui;max-width:980px;margin:auto;padding:28px 20px;background:#111a1d;color:#e4e8e0}a{color:#9cd5c5}article{padding:12px;border-top:1px solid #42504e}audio{width:100%;max-width:640px}h2{font-size:1.2rem}</style>
<nav><a href="../">← Tubular bells</a></nav><h1>Six voices, playable keys</h1>
<p>These performances reload the actual exported PCM24 keygroup WAVs. Every voice has two touch levels and three distinct native mallet strikes per level. Soft-three/hard-three lets you compare successive takes. Chords use the existing chord tool's voicings, moved into each instrument's register.</p>
<p>The authored rolls accelerate with alternating touch. The risers climb a minor pentatonic scale. These are finite performances; HOLD controls the ring of each strike.</p>
<p>Phone keygroups preserve velocity and round robin in the saved sidecar. MPC keygroups currently export the first take of each touch level. Combined MPC keygroup round robin remains open. Device playback, hardware loading and owner listening remain unverified.</p>
$cards<p><a href="manifest.json">Performance event scores and status</a></p>
<script>const ps=[...document.querySelectorAll('audio')];ps.forEach(p=>p.addEventListener('play',()=>ps.forEach(q=>{if(p!==q)q.pause()})));</script></html>""")
        ZipOutputStream(File(root, "HALOBAR-Six-Keygroups.zip").outputStream()).use { zip ->
            for (file in keys.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                zip.putNextEntry(ZipEntry("HALOBAR Keys/"+file.relativeTo(keys).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        println("HALOBAR: six keygroups and ${rows.size} exported-sample performances written.")
    }
}
