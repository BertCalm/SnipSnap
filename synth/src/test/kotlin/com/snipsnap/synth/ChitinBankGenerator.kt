package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Focused candidate bank and reconstructible MPC program, without promoting unheard presets. */
object ChitinBankGenerator {
    private fun zip(target: File, entries: List<Pair<File, String>>) {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            for ((file, path) in entries.sortedBy { it.second }) {
                out.putNextEntry(ZipEntry(path).apply { time = 0L })
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "testkit/chitin-bank").apply { mkdirs() }
        val html=StringBuilder("<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>CHITIN candidate bank</title><style>body{font:17px system-ui;max-width:1000px;margin:40px auto;padding:0 16px;background:#191d21;color:#eee}article{padding:14px 0;border-bottom:1px solid #444}audio{width:100%;margin:10px 0}a{color:#a9d5ff}h1{line-height:1.1}small{color:#bbc}</style><h1>CHITIN candidate bank</h1><p>Twelve candidates from the approved SHELL and WING core. All notes are finite. Choose the colors you want to keep.</p><p><a href='chitin-mpc-kit.zip' download>Download the 16-pad MPC kit</a> · <a href='recipes.zip' download>Download all twelve recipes</a> · <a href='lab/'>Open the full control audition</a></p>")
        val manifest=mutableListOf<String>()
        val metrics=StringBuilder("name,voice,frames,class,rawPeak,filterPeak,energyExcess,renderMillis\n")
        for(voice in ChitinVoice.entries) {
            html.append("<h2>${voice.name}</h2>")
            for((index,p) in ChitinPresets.forVoice(voice).withIndex()) {
                val id="${voice.name.lowercase(Locale.ROOT)}-${index+1}"
                val r=Chitin.inspect(voice,p.macros);val d=r.diagnostics
                WavWriter.write(File(root,"$id.wav"),r.snip)
                File(root,"$id.json").writeText(p.toJsonText())
                val longNote=Chitin.drumClassFor(voice,p.macros).name=="LOOP"
                html.append("<article><b>${p.name}</b><br><small>${if(longNote) "Long finite note" else "Finite note"}</small><audio controls preload=none src='$id.wav'></audio><a href='$id.json' download>Recipe</a></article>")
                manifest += "{\"id\":\"$id\",\"name\":\"${p.name}\",\"voice\":\"${voice.name}\",\"audio\":\"$id.wav\",\"recipe\":\"$id.json\"}"
                metrics.append("${p.name},${voice.name},${r.snip.frameCount},${Chitin.drumClassFor(voice,p.macros)},${d.rawPeak},${d.filterPeak},${d.energyExcess},${d.renderMillis}\n")
            }
        }
        val pads=SynthKits.chitin()
        val work=File(root,".kit-work")
        try {
            val kit=KitAssembler.assembleArranged("SnipSnap Chitin Kit",pads,work)
            val result=KitExporter.exportProgramFolder(kit,work,root,overwrite=true)
            check(result.samples.size==16)
        } finally { work.deleteRecursively() }
        html.append("<h2>The 16 pads</h2><p>A01–A08 climb C minor pentatonic from C1 to F2. A09–A16 compare colors, with long notes last. Every pad plays once and retains its recipe.</p>")
        val padRows=mutableListOf<String>()
        for((i,patch) in ChitinKits.patches().withIndex()) {
            val label="A${(i+1).toString().padStart(2,'0')}"
            WavWriter.write(File(root,"$label.wav"),pads[i]!!.snip)
            html.append("<article><b>$label ${patch.name}</b><audio controls preload=none src='$label.wav'></audio></article>")
            padRows += "{\"slot\":\"$label\",\"name\":\"${patch.name}\",\"audio\":\"$label.wav\",\"rootMidi\":${Chitin.midiFor(patch.voice,patch.macros.getValue("TUNE"))}}"
        }
        html.append("<script>document.addEventListener('play',e=>{if(e.target.tagName==='AUDIO')document.querySelectorAll('audio').forEach(a=>{if(a!==e.target)a.pause()})},true)</script></html>")
        File(root,"index.html").writeText(html.toString())
        File(root,"candidates.json").writeText(manifest.joinToString(",","[","]"))
        File(root,"pads.json").writeText(padRows.joinToString(",","[","]"))
        File(root,"metrics.csv").writeText(metrics.toString())
        val program = File(root, "SnipSnap Chitin Kit")
        val layout = ChitinKits.patches().mapIndexed { i, p ->
            "A${(i+1).toString().padStart(2,'0')}  ${p.name}  MIDI ${Chitin.midiFor(p.voice,p.macros.getValue("TUNE"))}"
        }.joinToString("\n")
        File(program,"PAD-LAYOUT.txt").writeText("CHITIN candidate kit\n\nLoad SnipSnap Chitin Kit.xpm. All pads are finite one-shots.\nA01-A08 climb C minor pentatonic from C1 to F2.\n\n$layout\n\nHardware listening acceptance is pending.\n")
        zip(File(root,"chitin-mpc-kit.zip"), program.walkTopDown().filter { it.isFile }
            .map { it to it.relativeTo(root).invariantSeparatorsPath }.toList())
        val recipes = ChitinVoice.entries.flatMap { v -> (1..6).map { i ->
            val name="${v.name.lowercase(Locale.ROOT)}-$i.json"; File(root,name) to name
        } }
        zip(File(root,"recipes.zip"), recipes)
        println("CHITIN bank: ${root.absolutePath}")
    }
}
