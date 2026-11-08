package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.*
import com.snipsnap.xpm.*
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Sampler trial: native source velocities and verified editable hard-layer recipes. */
object BackwashKitGenerator {
    private val velocities = listOf(.3f, .6f, 1f)
    private data class Pad(val name: String, val voice: BackwashVoice, val macros: Map<String, Float> = emptyMap(), val model: Int)
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/backwash-kit").apply { mkdirs() }
        val work = Files.createTempDirectory("backwash-kit-").toFile()
        try { generate(root, work, args.getOrNull(1) == "pushed") } finally { work.deleteRecursively() }
    }
    private fun generate(root: File, work: File, pushed: Boolean) {
        val NAME = if (pushed) "SnipSnap Backwash Pushed" else "SnipSnap Backwash Kit"
        val k = BackwashVoice.KICK; val s = BackwashVoice.SNARE; val c = BackwashVoice.HAT_CLOSED
        val o = BackwashVoice.HAT_OPEN; val t = BackwashVoice.TOM
        val pads = (if (pushed) BackwashPresets.processingKitOrder() else BackwashPresets.kitOrder()).map { Pad(it.name, it.voice, it.macros, it.model) }
        val recipeDir = File(root, "recipes").apply { mkdirs() }
        val arranged = pads.mapIndexed { index, p ->
            val patch = BackwashPatch(p.name,p.voice,Backwash.defaults(p.voice)+p.macros,p.model)
            val recipe = PadRecipe(patch)
            val layers = velocities.mapIndexed { z,v ->
                // No additional sampling gain: MPC playback velocity controls level once.
                val wav = patch.render(v)
                check(wav.samples.all { it.isFinite() && abs(it)<=1f } && wav.peak()>.01f)
                val wrapper = JsonValue.Obj(linkedMapOf("format" to JsonValue.Str("snipsnap-backwash-strike"),
                    "model" to JsonValue.Num(patch.model.toDouble()),"velocity" to JsonValue.Num(v.toDouble()),"recipe" to recipe.toJsonValue()))
                val file = File(recipeDir,"${PadNoteMap.labelForPad(index+1)}-${z+1}.json")
                file.writeText(Json.write(wrapper))
                val decoded=Json.parse(file.readText()).obj()
                check(decoded.getValue("model").int()==patch.model)
                val restored=PadRecipe.fromJsonValue(decoded.getValue("recipe")).patch as BackwashPatch
                check(wav.samples.contentEquals(restored.render(decoded.getValue("velocity").num().toFloat()).samples))
                wav
            }
            check(layers.last().samples.contentEquals(recipe.render().samples))
            ArrangedPad(layers.last(),p.voice.drumClass,recipe.toJsonValue(),
                level=when(p.voice){k->.9f;s->1f;t->.85f;else->.38f},softVariants=layers.take(2),displayName=p.name,
                layerNames=listOf("Soft","Medium","Hard"),muteGroup=if(p.voice==c||p.voice==o)1 else 0,
                source=mapOf("engine" to "BACKWASH","model" to patch.model.toString(),"sourceVelocities" to "0.3,0.6,1.0",
                    "layerRecipeFormat" to "snipsnap-backwash-strike","layerRecipeBase" to "recipes/${PadNoteMap.labelForPad(index+1)}",
                    "samplingGain" to "1"))
        }
        val kit=KitAssembler.assembleArranged(NAME,arranged,work,tempoBpm=94f)
        val notes=mutableListOf<Mpc3Note>()
        fun hit(slot:Int,beat:Double,v:Int){notes+=Mpc3Note(Mpc3Note.noteFor(slot),(beat*960).roundToLong(),v/127f)}
        for(bar in 0..3){
            val b=bar*4.0
            for(i in 0..7)hit(if(bar==1&&i==7)4 else 3,b+i*.5,if(i%2==0)88 else 56)
            hit(1,b,114);hit(1,b+2,110);hit(1,b+2.75,80)
            hit(2,b+1.012,109);hit(2,b+3.012,106)
            if(bar%2==0)hit(2,b+2.5,34)
        }
        hit(11,14.5,100);hit(10,15.0,108);hit(9,15.5,116)
        val clip=Mpc3Clip("Backwash Trial",4,notes.sortedBy{it.timePulses})
        val dest=File(root,NAME)
        val result=Mpc3Exporter.exportTrack(kit,work,dest,overwrite=true,mpc2Twin=true,clip=clip)
        val data=result.samples.first().parentFile;val twin=File(data,"$NAME.xpm")
        check(result.samples.size==48)
        val restored=KitStore.load(data)
        check(!Preflight.check(restored,data).blocked())
        val findings=listOf(ExportOutcome(ExportFormat.MPC3_TRACK,result.program,data,result.findings),
            ExportOutcome(ExportFormat.PROGRAM_FOLDER,twin,null,result.findings)).flatMap{ExportReadBack.verify(kit,it)}
        check(!findings.blocked()) { findings.toString() }
        verifyPlayback(kit,result.program,twin,data)
        val map=StringBuilder("pad,name,voice,muteGroup,level,soft,medium,hard\n")
        for((i,pad) in kit.pads.withIndex()){
            check(pad.velocityLayers.size==3)
            check(pad.velocityLayers.map{it.velStart}==listOf(1,42,85))
            check(pad.velocityLayers.map{it.velEnd}==listOf(41,84,127))
            for((z,layer) in pad.velocityLayers.withIndex()){
                val sample=File(data,layer.sampleFile)
                val expected=(arranged[i].softVariants+arranged[i].snip)[z]
                val bytes=ByteArrayOutputStream().also{WavWriter.write(it,expected,WavWriter.BitDepth.PCM_24)}.toByteArray()
                check(sample.readBytes().contentEquals(bytes)) { "export changed ${sample.name}" }
                val info=WavInfo.read(sample);check(info.channels==1&&info.sampleRate==44100&&info.bitsPerSample==24)
            }
            map.append("${PadNoteMap.labelForPad(pad.slot)},${pad.displayName},${pads[i].voice},${pad.muteGroup},${pad.level},")
            map.append(pad.velocityLayers.joinToString(","){"$NAME/${data.name}/${it.sampleFile}"}).append('\n')
        }
        File(root,"pads.csv").writeText(map.toString())
        File(root,"factory-presets.json").writeText(Json.write(JsonValue.Arr(BackwashPresets.all().map { it.toJsonValue() })))
        renderEvidence(kit,data,clip,File(root,"groove.wav"))
        val touch=Mpc3Clip("Touch and choke",8,buildList {
            for(slot in 1..16)for((z,v)in listOf(30,64,110).withIndex())add(Mpc3Note(Mpc3Note.noteFor(slot),((slot-1)*1440+z*480).toLong(),v/127f))
        })
        renderEvidence(kit,data,touch,File(root,"pad-walk.wav"))
        File(root,"groove-events.csv").writeText("pad,timePulses,velocity\n"+clip.notes.joinToString("\n"){"${PadNoteMap.labelForPad(Mpc3Note.slotFor(it.note))},${it.timePulses},${(it.velocity*127).roundToInt()}"})
        File(root,"read-back.txt").writeText("PASS: 48 source wrappers replay exactly; 48 exported WAVs match PCM24 renders.\nPASS: both MPC formats read back; all 16 pads have gap-free zones 1–41 / 42–84 / 85–127, no velocity-zero layer, full sample ends, one-shot playback, no loops, correct mute groups and note maps.\n"+findings.joinToString("\n"){"${it.severity}: ${it.message}"})
        File(root,"README.txt").writeText("""
            BACKWASH / Model ${Backwash.MODEL_VERSION} / MPC playing trial
            MPC 3: copy $NAME intact; load $NAME.xtd.
            MPC 2: enter its _[TrackData] folder and load $NAME.xpm.
            Set session tempo to 94 BPM for the included four-bar MPC 3 pattern.
            All 16 pads have three source strikes at .3/.6/1.0; MIDI zones 1–41,42–84,85–127.
            All hats share mute group 1. Drums are one-shots; no held loops.
            Samples include BACKWASH's chorus/compression/amp. No extra playback effects.
            No extra attenuation is baked into soft samples; hardware velocity controls level.
            kit.json stores each hard-layer PadRecipe; source model 3 with processing model ${Backwash.MODEL_VERSION}.
            recipes/Axx-1..3.json additionally records source velocity and model for every layer.
            These strike wrappers are not ordinary PadRecipe JSON: reconstruct with Backwash.render
            using their nested recipe patch, velocity, and the current BACKWASH renderer. Generator checks exact replay.
            Groove and pad-walk WAVs use the delivered samples, select the actual velocity zone,
            apply pad levels and a linear preview velocity gain, and fade chokes over 4 ms.
            This is a listening preview, not an emulation of your MPC's velocity curve.
            MPC 2 uses the repository's mono same-pad retrigger policy; MPC 3 its normal polyphony.
            Hardware loading, pad feel, balance and tails remain for your MPC audition.
        """.trimIndent()+"\n")
        val files=root.walkTopDown().filter{it.isFile&&it.extension!="zip"&&it.name!="SHA256SUMS.txt"}.sortedBy{it.path}.toList()
        File(root,"SHA256SUMS.txt").writeText(files.joinToString("\n"){"${sha(it)}  ${it.relativeTo(root).invariantSeparatorsPath}"}+"\n")
        ZipOutputStream(File(root,"backwash-mpc-kit.zip").outputStream().buffered()).use{zip->
            for(file in root.walkTopDown().filter{it.isFile&&it.extension!="zip"}.sortedBy{it.path}){
                zip.putNextEntry(ZipEntry("Backwash MPC Kit/"+file.relativeTo(root).invariantSeparatorsPath).also{it.time=0})
                file.inputStream().use{it.copyTo(zip)};zip.closeEntry()
            }
        }
        println("BACKWASH: 16 pads, 48 verified layers, MPC3 + MPC2, preview from exported WAVs: ${root.absolutePath}")
    }
    private fun sha(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it)}
    private fun renderEvidence(kit:Kit,data:File,clip:Mpc3Clip,file:File){
        val notes=clip.notes.sortedBy{it.timePulses};val scale=60.0/94/960*44100
        val out=FloatArray((clip.lengthPulses*scale+2*44100).toInt())
        for((index,note)in notes.withIndex()){
            val pad=kit.pads.single{it.slot==Mpc3Note.slotFor(note.note)}
            val v=(note.velocity*127).roundToInt()
            val layer=pad.velocityLayers.single{v in it.velStart..it.velEnd}
            val audio=WavReader.read(File(data,layer.sampleFile));val start=(note.timePulses*scale).roundToInt()
            val choke=if(pad.muteGroup==0)null else notes.drop(index+1).firstOrNull{n->kit.pads.single{it.slot==Mpc3Note.slotFor(n.note)}.muteGroup==pad.muteGroup}
            val count=min(audio.samples.size,choke?.let{((it.timePulses-note.timePulses)*scale).roundToInt()}?:audio.samples.size)
            for(i in 0 until count){val fade=if(choke==null)1f else ((count-1-i)/176f).coerceIn(0f,1f)
                out[start+i]+=audio.samples[i]*pad.level*note.velocity*fade}
        }
        Dsp.limitPeak(out);Dsp.fadeTail(out)
        WavWriter.write(file,Snip(out,1,44100),WavWriter.BitDepth.PCM_24)
    }
    private fun verifyPlayback(kit: Kit, native: File, mpc2: File, data: File) {
        val project = Mpc3Project.read(native)
        val instruments = project.drumPrograms().single().getValue("instruments").arr()
        val nativeMap = project.programs().single().getValue("padNoteMap").obj().getValue("noteForPad").obj()
        val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val document = factory.newDocumentBuilder().parse(mpc2)
        val xml = document.getElementsByTagName("Instrument")
        val xmlMap = document.getElementsByTagName("PadNote")
        for (pad in kit.pads) {
            val instrument = instruments[pad.slot - 1].obj()
            require(nativeMap.getValue("value${pad.slot - 1}").int() == Mpc3Note.noteFor(pad.slot))
            val note = xmlMap.item(pad.slot - 1) as Element
            require(note.getElementsByTagName("Note").item(0).textContent.toInt() == PadNoteMap.noteForPad(pad.slot))
            require(instrument.getValue("triggerMode").int() == 0 && instrument.getValue("whichMuteGroup").int() == pad.muteGroup)
            require(instrument.getValue("synthSection").obj().getValue("velocitySensitivity").num() == 1.0)
            require(!instrument.getValue("monophonic").bool() && instrument.getValue("polyphony").int() == 3)
            require(instrument.getValue("coarseTune").int() == 0 && instrument.getValue("fineTune").int() == 0)
            require(instrument.getValue("muteTargets").obj().values.all { it.int() == 0 })
            val nativeFilled = instrument.getValue("layersv").arr().map { it.obj() }.filter { it.getValue("sampleFile").str().isNotBlank() }
            require(nativeFilled.size == 3 && nativeFilled.map { it.getValue("velocityStart").int() } == listOf(85,42,1))
            val element = xml.item(pad.slot - 1) as Element
            fun text(tag: String) = element.getElementsByTagName(tag).item(0).textContent
            require(text("OneShot") == "True" && text("MuteGroup").toInt() == pad.muteGroup && text("Mono") == "True" && text("Polyphony") == "1")
            require(text("VelocitySensitivity").toFloat() == 1f)
            require(text("TuneCoarse") == "0" && text("TuneFine") == "0" && (1..4).all { text("MuteTarget$it") == "0" })
            val xmlLayers = element.getElementsByTagName("Layer")
            val xmlFilled = (0 until xmlLayers.length).map { xmlLayers.item(it) as Element }.filter {
                it.getElementsByTagName("SampleName").item(0).textContent.isNotBlank()
            }
            require(xmlFilled.size == 3)
            for ((index, zone) in pad.velocityLayers.withIndex()) {
                val n = nativeFilled.single { it.getValue("velocityStart").int() == zone.velStart }
                require(n.getValue("sampleFile").str() == zone.sampleFile && n.getValue("velocityEnd").int() == zone.velEnd)
                require(!n.getValue("loop").bool() && n.getValue("coarseTune").int() == 0 && n.getValue("fineTune").int() == 0)
                require(n.getValue("volume").obj().getValue("gainCoefficient").num() == 1.0)
                val slice = n.getValue("sliceInfo").obj()
                require(slice.getValue("Start").int() == 0 && slice.getValue("End").int() == WavInfo.read(File(data, zone.sampleFile)).frameCount.toInt() && slice.getValue("LoopMode").int() == 0)
                val x = xmlFilled[index]
                fun z(tag: String) = x.getElementsByTagName(tag).item(0).textContent
                require(z("SampleName") == zone.sampleStem && z("VelStart").toInt() == zone.velStart && z("VelEnd").toInt() == zone.velEnd)
                require(z("SliceStart") == "0" && z("SliceEnd").toInt() == WavInfo.read(File(data, zone.sampleFile)).frameCount.toInt() && z("SliceLoop") == "0" && z("Loop") == "False")
                require(z("TuneCoarse") == "0" && z("TuneFine") == "0" && z("Volume").toFloat() == 1f)
            }
            for (velocity in 0..127) {
                val expected = if (velocity == 0) 0 else 1
                require(nativeFilled.count { velocity in it.getValue("velocityStart").int()..it.getValue("velocityEnd").int() } == expected)
                require(xmlFilled.count { velocity in it.getElementsByTagName("VelStart").item(0).textContent.toInt()..it.getElementsByTagName("VelEnd").item(0).textContent.toInt() } == expected)
            }
        }
    }

}
