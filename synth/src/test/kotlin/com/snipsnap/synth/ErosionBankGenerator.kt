package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Saved recipes produce every sound; the browser only plays encoded WAVs. */
object ErosionBankGenerator {
    private const val KIT_NAME="SnipSnap Erosion Bank"
    private fun json(v: Any?): JsonValue = when(v) {
        null -> JsonValue.Null
        is JsonValue -> v
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Number -> JsonValue.Num(v.toDouble())
        is Map<*,*> -> JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::json))
        else -> error("unsupported manifest value")
    }
    private fun writeJson(file: File,v: Any?) { file.parentFile.mkdirs();file.writeText(Json.write(json(v))) }
    private fun hash(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun scaled(s: Snip,gain: Double)=s.copy(samples=FloatArray(s.samples.size) { (s.samples[it]*gain).toFloat() })
    private fun matchGain(x: FloatArray): Double {
        val window=(Dsp.RATE*.05).toInt()
        var active=0.0
        for(i in x.indices step window) {
            val end=min(x.size,i+window)
            active=max(active,sqrt((i until end).sumOf { x[it].toDouble().pow(2) }/(end-i)))
        }
        return min(.08/active.coerceAtLeast(1e-12),.95/x.maxOf { abs(it).toDouble() }.coerceAtLeast(1e-12))
    }
    private fun zip(folder: File,out: File,prefix: String="") {
        val files=folder.walkTopDown().filter { it.isFile && it.canonicalFile!=out.canonicalFile }.sortedBy { it.relativeTo(folder).path }.toList()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for(f in files) {
                z.putNextEntry(ZipEntry(prefix+f.relativeTo(folder).invariantSeparatorsPath).also { it.time=0 })
                f.inputStream().use { it.copyTo(z) };z.closeEntry()
            }
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.single()).canonicalFile
        root.mkdirs();File(root,"audio").mkdirs();File(root,"recipes").mkdirs()
        val rows=mutableListOf<Map<String,Any?>>()
        val cards=StringBuilder()
        val arranged=mutableListOf<ArrangedPad?>()
        val frozen=mutableListOf<List<Snip>>()
        Erosion.render(ErosionVoice.ARC) // warm one local render; no phone timing claim
        for((i,entry) in ErosionBank.entries().withIndex()) {
            val id="pad-${i+1}"
            val layers=ErosionBank.velocities.map { velocity ->
                val patch=entry.patch.copy(velocity=velocity)
                val recipe=PadRecipe(patch)
                val start=System.nanoTime();val snip=recipe.render();val ms=(System.nanoTime()-start)/1e6
                check(snip.samples.contentEquals(PadRecipe.fromJsonText(recipe.toJsonText()).render().samples)) { "$id $velocity recipe replay changed" }
                val stem="$id-${(velocity*100).toInt()}"
                val file=WavWriter.write(File(root,"audio/$stem.wav"),snip)
                val delivered=WavReader.read(file)
                check(delivered.samples.all { it.isFinite() && abs(it)<=1f })
                File(root,"recipes/$stem.json").writeText(recipe.toJsonText())
                rows+=mapOf("id" to stem,"pad" to i+1,"name" to entry.name,"role" to entry.role,
                    "voice" to patch.voice.name,"velocity" to velocity,"seconds" to delivered.durationSeconds,
                    "class" to Classifier.classify(delivered).drumClass.name,"peak" to delivered.samples.maxOf { abs(it) },
                    "mean" to delivered.samples.sumOf { it.toDouble() }/delivered.samples.size,
                    "wav" to "audio/$stem.wav","recipe" to "recipes/$stem.json","sha256" to hash(file),"localRenderMs" to ms)
                snip
            }
            val hard=layers.last()
            val zoneLayers=layers.map { Velocity.peakMatch(hard,it) }
            frozen+=zoneLayers
            arranged+=ArrangedPad(hard,Classifier.classify(hard).drumClass,recipe=PadRecipe(entry.patch).toJsonValue(),
                displayName=entry.name,softVariants=zoneLayers.take(2),layerNames=listOf("SOFT","MEDIUM","HARD"),
                source=mapOf("engine" to "EROSION","role" to entry.role,"touch" to "0.35,0.65,1.0; soft zones peak-matched to hard"),muteGroup=0)
            cards.append("<article><h3>${PadNoteMap.labelForPad(i+1)} · ${entry.name}</h3><p>${entry.role} · ${entry.patch.voice}</p>")
            for(v in ErosionBank.velocities) cards.append("<label>${if(v<.5f) "Soft" else if(v<1f) "Medium" else "Hard"}<audio controls preload='none' src='audio/$id-${(v*100).toInt()}.wav'></audio></label>")
            cards.append("</article>\n")
        }
        val work=Files.createTempDirectory("erosion-bank-source-").toFile()
        val kitDir=File(root,"kit").also { it.mkdirs() }
        val kit=try {
            val k=KitAssembler.assembleArranged(KIT_NAME,arranged,work)
            check(k.pads.size==16 && k.pads.all { it.velocityLayers.size==3 })
            val result=Mpc3Exporter.exportTrack(k,work,kitDir,overwrite=true,mpc2Twin=true)
            val data=File(kitDir,Mpc3TrackWriter.trackDataDirName(k.name))
            KitStore.save(k,data)
            check(KitStore.load(data)==k)
            val findings=listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK,result.program,data,result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER,File(data,"$KIT_NAME.xpm"),null,result.findings)
            ).flatMap { ExportReadBack.verify(k,it) }
            check(!findings.blocked()) { findings.joinToString { it.message } }
            check(result.samples.size==48) { "expected 48 exported velocity samples, got ${result.samples.size}" }
            // Verify actual encoded export samples against all three native
            // touch renders, including the house zone peak-matching policy.
            for((i,pad) in k.pads.sortedBy { it.slot }.withIndex()) {
                val entry=ErosionBank.entries()[i]
                assertZones(pad.velocityLayers)
                for((j,layer) in pad.velocityLayers.withIndex()) {
                    val actual=File(data,layer.sampleFile)
                    val expected=WavWriter.write(File(work,"expected-$i-$j.wav"),frozen[i][j])
                    check(actual.readBytes().contentEquals(expected.readBytes())) { "export changed pad ${pad.slot} layer $j" }
                    val p=entry.patch.copy(velocity=ErosionBank.velocities[j])
                    File(data,"${layer.sampleStem}.recipe.json").writeText(PadRecipe(p).toJsonText())
                }
            }
            writeJson(File(root,"kit-verification.json"),mapOf("pads" to 16,"zonesPerPad" to 3,"sampleFiles" to 48,
                "MPC3ReadBack" to true,"MPC2ReadBack" to true,"allEncodedLayersMatch" to true,
                "hardware" to "not tested","velocityPolicy" to "soft native touch is peak-matched to hard; hardware supplies level response"))
            File(kitDir,"README.txt").writeText("EROSION provisional playing bank. Sixteen ARC/CRATER pads, three native touch zones each.\nMPC 3: load $KIT_NAME.xtd. MPC 2: load $KIT_NAME.xpm inside TrackData. Keep the folder together.\nSOFT/MEDIUM/HARD windows are 1-41, 42-84 and 85-127. Velocity zero is note-off.\nSoft zones are peak-matched to the hard zone; the MPC's velocity curve controls level.\nLayer .recipe.json files recreate the native touch before peak matching; kit.json retains the editable hard patch.\nSCAR remains a separate experiment. No seamless HOLD or hardware acceptance is claimed.\n")
            k
        } finally { work.deleteRecursively() }
        zip(kitDir,File(root,"erosion-mpc-bank.zip"),"$KIT_NAME/")
        // This phrase uses the ACTUAL exported PCM zones, selected by their
        // declared velocity windows. The documented level law is linear.
        val data=File(kitDir,Mpc3TrackWriter.trackDataDirName(KIT_NAME))
        val bpm=112.0;val beat=60/bpm
        val score=mutableListOf<Map<String,Any>>()
        for(bar in 0..3) {
            for(step in 0..7) score+=mapOf("beat" to bar*4+step*.5,"pad" to if(step%2==0) 1 else 2,"velocity" to if(step%2==0) 90 else 48)
            for(b in listOf(0.0,2.0)) score+=mapOf("beat" to bar*4+b,"pad" to 9,"velocity" to 118)
            for(b in listOf(1.0,3.0)) score+=mapOf("beat" to bar*4+b,"pad" to 10,"velocity" to 110)
            score+=mapOf("beat" to bar*4+2.75,"pad" to 4,"velocity" to 38)
            if(bar%2==1) score+=mapOf("beat" to bar*4+3.5,"pad" to 6,"velocity" to 78)
        }
        val phrase=FloatArray(((16*beat+1.5)*Dsp.RATE).toInt())
        for(event in score) {
            val pad=kit.pads.single { it.slot==(event.getValue("pad") as Int) }
            val velocity=event.getValue("velocity") as Int
            val zone=pad.velocityLayers.single { velocity in it.velStart..it.velEnd }
            val wav=WavReader.read(File(data,zone.sampleFile))
            val at=((event.getValue("beat") as Double)*beat*Dsp.RATE).roundToInt()
            for(i in wav.samples.indices) if(at+i<phrase.size) phrase[at+i]+=wav.samples[i]*(velocity/127f)*pad.level
        }
        val phraseGain=.90/phrase.maxOf { abs(it).toDouble() }.coerceAtLeast(1e-12)
        WavWriter.write(File(root,"bank-pattern.wav"),scaled(Snip(phrase,1,Dsp.RATE),phraseGain))
        writeJson(File(root,"pattern.json"),mapOf("bpm" to bpm,"events" to score,"masterGain" to phraseGain,
            "audioSource" to "actual exported PCM velocity zones","velocityGainLaw" to "linear velocity/127; not a hardware simulation"))
        val scar=scar(root)
        val sourceHashes=listOf("src/main/kotlin/com/snipsnap/synth/Erosion.kt","src/main/kotlin/com/snipsnap/synth/ErosionBank.kt",
            "src/main/kotlin/com/snipsnap/synth/Dsp.kt","src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "../audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt").associateWith { hash(File(it)) }
        writeJson(File(root,"manifest.json"),mapOf("bank" to rows,"scar" to scar,"sourceSha256" to sourceHashes,
            "status" to "provisional; new bank and SCAR await owner listening","originalVoices" to "198 frozen float-render comparisons tested separately"))
        File(root,"index.html").writeText("""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>EROSION playing bank and SCAR</title>
<style>body{margin:40px auto;max-width:1120px;padding:20px;background:#131a20;color:#e4edf4;font:16px/1.5 system-ui}a{color:#8cdbd5}article{display:inline-block;vertical-align:top;margin:8px;padding:16px;width:min(290px,calc(100% - 48px));background:#202d36}audio{width:100%}h3{font-size:16px}label{display:block;margin-top:12px}</style>
<h1>EROSION · playing bank</h1><p>Sixteen ARC/CRATER pads, three native touch layers per pad. These are provisional playing recipes; the original voices retain their renderer. SCAR is a separate first-listen experiment below.</p>
<p><a href="../native-round/index.html">Previous native round</a> · <a href="../index.html">Original references</a> · <a href="erosion-mpc-bank.zip">Download MPC 3 / MPC 2 bank</a> · <a href="erosion-playing-round.zip">Download complete round</a> · <a href="manifest.json">Measurements and recipes</a></p>
<h2>Hear the bank together</h2><audio controls preload="none" src="bank-pattern.wav"></audio><p>Four bars at 112 BPM, using the exported velocity samples. One master gain, linear velocity level, no effects. <a href="pattern.json">Score</a> · <a href="kit-verification.json">Export verification</a></p>
<h2>Sixteen pads</h2><p>Clip players use the native patch output. Kit soft zones use the house peak match against their hard zone so the hardware can supply the level curve.</p>$cards
<h2>SCAR · separate experiment</h2><p>Initial crack → clustered sparks → recovery gap → quieter aftershock. Twelve percent of the same energy budget is held for the aftershock. Listen to the gap and whether the final response earns a third voice.</p>
<article><h3>SCAR native default</h3><audio controls preload="none" src="audio/scar-default.wav"></audio></article>
<article><h3>Aftershock on · shared gain</h3><audio controls preload="none" src="audio/scar-after-on.wav"></audio></article>
<article><h3>Aftershock off · same primary and gain</h3><audio controls preload="none" src="audio/scar-after-off.wav"></audio></article>
<article><h3>Short recovery</h3><audio controls preload="none" src="audio/scar-short.wav"></audio></article>
<article><h3>Long recovery</h3><audio controls preload="none" src="audio/scar-long.wav"></audio></article>
<h2>Why it is built this way</h2><p>The bank turns the two existing voices into distinct pad roles. Native touch changes contact while retaining the pattern. SCAR spends a held reserve after a real gap; removing the reserve leaves its primary unchanged. HOLD remains a later loop-design round.</p>""")
        zip(root,File(root,"erosion-playing-round.zip"))
        check(File(root,"erosion-playing-round.zip").length()<25L*1024*1024)
        println("EROSION bank: 16 pads x 3 touch layers; both MPC formats verified; 5 SCAR comparisons; $root")
    }
    private fun assertZones(layers: List<KitLayer>) {
        check(layers.map { it.velStart to it.velEnd }==listOf(1 to 41,42 to 84,85 to 127))
    }
    private fun scar(root: File): Map<String,Any> {
        val p=ErosionPatch("SCAR STUDY",ErosionVoice.SCAR)
        for((id,patch) in listOf("scar-default" to p,"scar-short" to p.copy(macros=p.macros+("FEED" to .05f)),
                "scar-long" to p.copy(macros=p.macros+("FEED" to .85f)))) {
            val recipe=PadRecipe(patch);val s=recipe.render()
            check(s.samples.contentEquals(PadRecipe.fromJsonText(recipe.toJsonText()).render().samples))
            WavWriter.write(File(root,"audio/$id.wav"),s)
            File(root,"recipes/$id.json").writeText(recipe.toJsonText())
        }
        val full=Erosion.source(ErosionVoice.SCAR)
        val off=Erosion.source(ErosionVoice.SCAR,scarAftershock=false)
        // Both pass the same output chain. Global mean removal and
        // independent level matching would change the shared primary.
        val a=Erosion.finish(full,level=false,removeMean=false)
        val b=Erosion.finish(off,level=false,removeMean=false)
        val gain=matchGain(a)
        WavWriter.write(File(root,"audio/scar-after-on.wav"),scaled(Snip(a,1,Dsp.RATE),gain))
        WavWriter.write(File(root,"audio/scar-after-off.wav"),scaled(Snip(b,1,Dsp.RATE),gain))
        val t=Erosion.schedule(ErosionVoice.SCAR,emptyMap())
        val stages=requireNotNull(t.scarStages)
        // The offline sinc resampler has a short look-ahead; exclude a
        // five-millisecond guard immediately before the aftershock onset.
        val prefix=((stages.aftershockStart-.005)*Dsp.RATE).toInt()
        check(a.copyOfRange(0,prefix).contentEquals(b.copyOfRange(0,prefix)))
        val result=mapOf("reserve" to stages.reservedEnergy,"clusterEnd" to stages.clusterEnd,
            "aftershockStart" to stages.aftershockStart,"conservationError" to t.conservationError,
            "primaryAndGapPrefixExact" to true,"verifiedPrefixSeconds" to prefix.toDouble()/Dsp.RATE,"comparisonGain" to gain,
            "comparisonOutput" to "same band-limit, resampling, 20Hz high-pass and tail fade; no global mean removal or independent leveling",
            "events" to t.events.map { mapOf("seconds" to it.seconds,"energy" to it.energy) })
        writeJson(File(root,"scar-diagnostics.json"),result)
        return result
    }
}
