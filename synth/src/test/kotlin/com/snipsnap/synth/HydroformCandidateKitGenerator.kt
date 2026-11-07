package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.PadNoteMap
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.math.*

/** Candidate recipes and an editable kit; deliberately absent from the factory roster. */
object HydroformCandidateKitGenerator {
    private const val NAME = "SnipSnap Hydroform Candidate Kit"
    private val velocities = listOf(.3f, .6f, 1f)
    private fun patch(name: String, voice: HydroformVoice, tune: Float, charge: Float,
                      standoff: Float, yield: Float, arrest: Float, hold: Boolean = false) =
        HydroformPatch(name, voice, Hydroform.defaults(voice) + mapOf(
            "TUNE" to tune, "CHARGE" to charge, "STANDOFF" to standoff,
            "YIELD" to yield, "ARREST" to arrest) +
            if (voice == HydroformVoice.DOME) mapOf("HOLD" to if (hold) 1f else 0f) else emptyMap())

    /** Shape proposals for listening, not claims of heard preset quality. */
    fun candidates(): List<HydroformPatch> = listOf(
        patch("LOW PRESSURE", HydroformVoice.DOME, .5f, .45f, .10f, .80f, .30f),
        patch("PRESSURE VESSEL", HydroformVoice.DOME, .5f, .90f, .35f, .35f, .45f),
        patch("DIE CUT", HydroformVoice.DOME, .5f, .75f, .25f, .40f, 1f),
        patch("RETURN FRONT", HydroformVoice.DOME, .5f, .70f, .85f, .60f, .55f),
        patch("YIELD BREAK", HydroformVoice.DOME, .5f, 1f, .60f, .10f, .70f),
        patch("DIE CYCLE", HydroformVoice.DOME, .5f, .70f, .35f, .50f, .65f, true),
        patch("PLATE TAP", HydroformVoice.BULKHEAD, 5f/24, .35f, .15f, .85f, .80f),
        patch("BENT PANEL", HydroformVoice.BULKHEAD, 0f, .60f, .45f, .55f, .40f),
        patch("BULK STRIKE", HydroformVoice.BULKHEAD, 3f/24, .75f, .35f, .50f, .65f),
        patch("CUT RIM", HydroformVoice.BULKHEAD, 8f/24, .70f, .70f, .65f, 1f),
        patch("TEARING FACE", HydroformVoice.BULKHEAD, 10f/24, 1f, .65f, .10f, .75f),
        patch("OPEN PANEL", HydroformVoice.BULKHEAD, .5f, .60f, .85f, .70f, .10f),
    )

    private data class Hit(val beat: Double, val slot: Int)
    private fun hits(): List<Hit> = buildList {
        for (bar in 0..3) {
            val b=bar*4.0
            add(Hit(b, 1+bar)); add(Hit(b+1.5, 1+bar)); add(Hit(b+2, 1+bar))
            add(Hit(b+1, 9)); add(Hit(b+3, 11))
            for (off in listOf(.5,2.5,3.5)) add(Hit(b+off, 12+bar%3))
        }
        add(Hit(7.75,15)); add(Hit(12.0,16))
    }.sortedWith(compareBy({it.beat},{it.slot}))

    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "testkit/hydroform-candidate-bank").canonicalFile.apply { mkdirs() }
        val candidates=candidates()
        require(candidates.size==12 && candidates.map { it.name }.toSet().size==12)
        require(candidates.groupingBy { it.voice }.eachCount().values.all { it==6 })
        val presets=candidates.mapIndexed { i,p ->
            val id="preset-%02d".format(java.util.Locale.ROOT,i+1)
            val snip=p.render()
            check(Patches.fromJsonText(p.toJsonText()).render().samples.contentEquals(snip.samples))
            val wav=File(root,"presets/$id.wav")
            AccordAuditionFiles.writeAudio(wav,snip)
            val recipe=File(root,"presets/$id.json").apply { writeText(p.toJsonText()+"\n") }
            linkedMapOf("id" to id,"name" to p.name,"voice" to p.voice.name,
                "file" to wav.relativeTo(root).invariantSeparatorsPath,
                "recipe" to recipe.relativeTo(root).invariantSeparatorsPath,"macros" to p.macros,
                "metrics" to AccordAuditionFiles.metrics(snip))
        }
        val walk=listOf(0,3,5,7,10,12,15,17)
        val walkNames=listOf("G0","BB0","C1","D1","F1","G1","BB1","C2")
        val patches=walk.mapIndexed { i,n -> candidates[0].copy(name="PRESSURE ${walkNames[i]}",
            macros=candidates[0].macros+("TUNE" to n/24f)) } +
            candidates.filter { it.voice==HydroformVoice.BULKHEAD } + listOf(candidates[4],candidates[5])
        check(patches.size==16 && Hydroform.isLoop(patches.last().voice,patches.last().macros))
        val arranged=patches.map { p ->
            val recipe=PadRecipe(p);val main=recipe.render()
            ArrangedPad(main,Hydroform.drumClassFor(p.voice,p.macros),recipe=recipe.toJsonValue(),
                // A16 is a one-bar audio phrase played once; repeating is a sequencer choice.
                oneShot=true, softVariants=velocities.take(2).map { v ->
                    Velocity.peakMatch(main,p.copy(velocity=v).render())
                }, displayName=p.name, muteGroup=0,
                layerNames=listOf("SOFT","MIDDLE","HARD"),
                source=linkedMapOf("engine" to HydroformPatch.ENGINE,"model" to "1",
                    "status" to "candidate-awaiting-listen","velocitySoft" to ".3","velocityMid" to ".6",
                    "velocityMain" to "1.0","velocityNormalization" to "peak-match-main",
                    "classificationPolicy" to "intended-musical-role; detector-reported-separately"))
        }
        val staging=File(root,".work")
        require(!staging.exists() || staging.deleteRecursively())
        try {
            val kit=KitAssembler.assembleArranged(NAME,arranged,staging,key=KeySpec.parse("Gminpent"),tempoBpm=120f)
            val before=Preflight.check(kit,staging);check(!before.blocked()) { "$before" }
            val clip=Mpc3Clip("Hydroform Four Bars",4,hits().map {
                Mpc3Note(Mpc3Note.noteFor(it.slot),(it.beat*Mpc3Clip.PULSES_PER_BEAT).roundToLong(),1f)
            })
            val destination=File(root,NAME)
            val exported=Mpc3Exporter.exportTrack(kit,staging,destination,overwrite=true,clip=clip,mpc2Twin=true)
            val data=File(destination,Mpc3TrackWriter.trackDataDirName(kit.name))
            val xtd=ExportReadBack.verify(kit,ExportOutcome(ExportFormat.MPC3_TRACK,exported.program,data,exported.findings))
            val xpm=ExportReadBack.verify(kit,ExportOutcome(ExportFormat.PROGRAM_FOLDER,File(data,"$NAME.xpm"),null,exported.findings))
            check(!(xtd+xpm).blocked()) { "read-back: ${xtd+xpm}" }
            val saved=KitStore.load(data)
            check(saved.pads.size==16 && saved.pads.all { it.velocityLayers.size==3 })
            check(!Preflight.check(saved,data).blocked())
            val rows=saved.pads.sortedBy { it.slot }.map { pad ->
                val p=patches[pad.slot-1];val main=arranged[pad.slot-1].snip
                val zones=pad.velocityLayers.mapIndexed { j,layer ->
                    val zonePatch=p.copy(velocity=velocities[j])
                    val expected=if(j==2) main else Velocity.peakMatch(main,zonePatch.render())
                    val file=File(data,layer.sampleFile);val actual=WavReader.read(file);val info=WavInfo.read(file)
                    check(info.sampleRate==44100 && info.channels==1 && info.bitsPerSample==24)
                    check(actual.frameCount==expected.frameCount)
                    check(actual.samples.indices.maxOf { abs(actual.samples[it]-expected.samples[it]) } <= 3f/8388608)
                    val id="A%02d-%s".format(java.util.Locale.ROOT,pad.slot,listOf("soft","middle","hard")[j])
                    val preview=File(root,"preview/$id.wav")
                    WavWriter.write(preview,actual,WavWriter.BitDepth.PCM_16)
                    val recipe=File(root,"recipes/$id.json").apply { parentFile.mkdirs();writeText(zonePatch.toJsonText()+"\n") }
                    check(Patches.fromJsonText(recipe.readText()).render().samples.contentEquals(zonePatch.render().samples))
                    linkedMapOf("id" to id,"velocity" to velocities[j],"velStart" to layer.velStart,"velEnd" to layer.velEnd,
                        "file" to preview.relativeTo(root).invariantSeparatorsPath,"nativeFile" to file.relativeTo(root).invariantSeparatorsPath,
                        "recipe" to recipe.relativeTo(root).invariantSeparatorsPath,
                        "normalization" to if(j==2) "native" else "peak-match-main",
                        "sha256" to AccordAuditionFiles.hash(file))
                }
                val detected=Classifier.classify(main)
                linkedMapOf("slot" to PadNoteMap.labelForPad(pad.slot),"name" to p.name,"voice" to p.voice.name,
                    "proposedClass" to pad.drumClass.name,"detectedClass" to detected.drumClass.name,
                    "detectorConfidence" to detected.confidence,"oneShot" to true,"isPhrase" to (pad.slot==16),
                    "rootHz" to Hydroform.frequencyFor(p.voice,p.macros.getValue("TUNE")),
                    "macros" to p.macros,"zones" to zones,"metrics" to AccordAuditionFiles.metrics(main))
            }
            // Preview uses delivered hard-zone PCM, saved mixer levels and a conservative
            // global gain. It does not claim to emulate a hardware velocity curve.
            val mix=FloatArray(10*Dsp.RATE)
            for(hit in hits()) {
                val pad=saved.pads.first { it.slot==hit.slot };val a=WavReader.read(File(data,pad.sampleFile)).samples
                val start=(hit.beat*.5*Dsp.RATE).roundToInt()
                for(i in 0 until min(a.size,mix.size-start)) mix[start+i]+=a[i]*pad.level*.25f
            }
            Dsp.limitPeak(mix,.92f);Dsp.fadeTail(mix)
            WavWriter.write(File(root,"pattern-preview.wav"),Snip(mix,1,Dsp.RATE),WavWriter.BitDepth.PCM_16)
            File(destination,"README.txt").writeText("""
HYDROFORM CANDIDATE KIT / not a factory bank

Load $NAME.xtd beside its ${data.name}/ folder on MPC 3.
The MPC 2 $NAME.xpm twin is inside that data folder beside its WAVs.
Keep the folders together. kit.json retains editable SnipSnap recipes.

A01-A08: G-minor-pentatonic DOME walk, G0 through C2.
A09-A14: six BULKHEAD candidates. A15: YIELD BREAK. A16: DIE CYCLE.
Every pad plays once. A16 is a two-second, one-bar phrase at an assumed 120 BPM;
its seam is measured in the engine but no gated or automatic looping is claimed here.
The XTD contains a four-bar pattern; set project tempo to 120 BPM. XPM has no pattern.
All 48 sample zones are mono 44.1 kHz PCM24. Soft/middle strikes (.3/.6) are
re-rendered and peak-matched to the native hard strike (1); MPC velocity owns level.
No effects or choke groups. Intended roles and heuristic detector classes can disagree.
Check the audition and pad-map before choosing final roles. Native listening,
phone behavior and MPC hardware loading remain owner checks.

Why it is built this way: a pitched low row makes the bass range comparable; the
second row contrasts material and arrest; a final phrase tests HOLD in musical context.
Candidate status preserves the factory roster until a listening verdict is recorded.
""".trimIndent()+"\n")
            AccordAuditionFiles.writeJson(File(root,"bank.json"),linkedMapOf(
                "revision" to "R7","status" to "candidate-awaiting-native-listen","engineModel" to 1,
                "tempoBpm" to 120,"key" to "G minor pentatonic","presets" to presets,"pads" to rows,
                "pattern" to "pattern-preview.wav","patternPolicy" to "delivered hard-zone PCM, pad mixer gain, 0.25 global preview gain; no hardware emulation",
                "preflightPassed" to true,"mpc3ReadBackPassed" to true,"mpc2ReadBackPassed" to true,
                "kitZip" to "hydroform-candidate-kit.zip","findings" to (xtd+xpm).map { it.message }))
            // The downloadable kit is self-contained: its map resolves from the ZIP
            // root, while bank.json above resolves from the web audition directory.
            for (folder in listOf("presets", "recipes")) {
                File(root,folder).copyRecursively(File(destination,folder),overwrite=true)
            }
            File(root,"pattern-preview.wav").copyTo(File(destination,"pattern-preview.wav"),overwrite=true)
            val archiveRows=rows.map { row -> row + ("zones" to
                (row.getValue("zones") as List<*>).map { value ->
                    @Suppress("UNCHECKED_CAST")
                    val zone=value as Map<String,Any>
                    val path=(zone.getValue("nativeFile") as String).removePrefix("$NAME/")
                    zone + mapOf("file" to path,"nativeFile" to path)
                }) }
            AccordAuditionFiles.writeJson(File(destination,"pad-map.json"),linkedMapOf(
                "revision" to "R7","status" to "candidate-awaiting-native-listen",
                "tempoBpm" to 120,"key" to "G minor pentatonic","pads" to archiveRows,
                "presets" to presets,"pattern" to "pattern-preview.wav"))
            AccordAuditionFiles.archive(destination,File(root,"hydroform-candidate-kit.zip"))
            println("HYDROFORM: 12 candidates, 16 pads, 48 PCM24 zones, both MPC read-backs and replay passed")
        } finally { staging.deleteRecursively() }
    }
}
