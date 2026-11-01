package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Append model-6 evidence; never overwrite any of the 195 published model-1–5 clips. */
object RimeCompleteAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single()).absoluteFile
        val file = File(root,"manifest.json")
        val manifest = LinkedHashMap(Json.parse(file.readText()).obj())
        val entries = manifest.getValue("clips").arr().filter { !it.obj().getValue("id").str().startsWith("r6-") }.toMutableList()
        check(entries.size == 195) { "Begin with the complete preserved model-5 listening pack" }
        fun add(id: String, section: String, title: String, description: String, patch: RimePatch,
            macro: String? = null, value: Float? = null, role: String? = null) {
            check(id.startsWith("r6-"))
            entries += render(root,id,section,title,description,patch,macro,value,role)
        }
        for (entry in RimePresets.entries().filter { it.patch.model == 6 }) {
            add("r6-bank-${entry.id}","complete-bank",entry.patch.name,entry.description,entry.patch,role=entry.role)
        }
        val freshVoices = RimeVoice.entries - Rime.LEGACY_VOICES.toSet()
        for (voice in freshVoices) {
            val p = RimePresets.starter(voice)
            val stem = voice.name.lowercase()
            add("r6-$stem-start","complete-start",p.name,"The new voice at its authored note and controls.",p)
            for (macro in Rime.TIMBRAL) for (step in listOf(0f,.25f,.5f,.75f,1f)) {
                add("r6-$stem-${macro.lowercase()}-${(step*100).roundToInt()}","controls","$macro ${(step*100).roundToInt()}%",
                    "One control changes; pitch, velocity, seed and the other controls stay fixed.",p.copy(macros=p.macros+(macro to step)),macro,step)
            }
            for (midi in listOf(36,60,84)) add("r6-$stem-note-$midi","register","Register $midi","Same voice at a low, middle or high root.",p.copy(midi=midi))
            for (velocity in listOf(.3f,.6f,1f)) add("r6-$stem-velocity-${(velocity*100).roundToInt()}","register","Velocity ${(velocity*100).roundToInt()}%",
                "Original level retains native dynamics; level matching compares the change in color.",p.copy(velocity=velocity))
            for ((a,b) in listOf("DETAIL" to "EDGE","DETAIL" to "UNFOLD","ANGLE" to "BODY","EDGE" to "BODY")) {
                for (av in listOf(.15f,.9f)) for (bv in listOf(.15f,.9f)) {
                    add("r6-$stem-${a.lowercase()}-${(av*100).toInt()}-${b.lowercase()}-${(bv*100).toInt()}","interactions",
                        "$a ${(av*100).toInt()} / $b ${(bv*100).toInt()}","The paired controls change at the same note and velocity.",
                        p.copy(macros=p.macros+mapOf(a to av,b to bv)))
                }
            }
            for (midi in listOf(24,60,96)) add("r6-$stem-high-$midi","stress","All high / $midi","All five timbral controls at maximum, with note-aware bandwidth.",
                p.copy(midi=midi,gateSeconds=.5f,macros=p.macros+Rime.TIMBRAL.associateWith { 1f }))
        }
        for (voice in RimeVoice.entries) for (unfold in listOf(.3f,.85f)) {
            val p = RimePresets.starter(voice).copy(model=6,macros=Rime.defaults(voice)+mapOf("DETAIL" to .95f,
                "ANGLE" to .1f,"EDGE" to .7f,"BODY" to .55f,"UNFOLD" to unfold,"HOLD" to 1f))
            val moving = Rime.movingHold(p.macros,p.model)
            add("r6-hold-${voice.name.lowercase()}-${if(moving) "moving" else "static"}","hold-motion",
                if(moving) "Moving detail" else "Steady detail","A real opening followed by complete sustain cycles. The root continues through the scale motion.",p)
        }
        val work = Files.createTempDirectory("rime-complete-kit").toFile()
        try {
            val kit = RimeKitGenerator.generate(work,complete=true)
            File(root,"downloads").mkdirs()
            kit.archive.copyTo(File(root,"downloads/rime-complete-mpc-kit.zip"),overwrite=true)
            File(kit.directory,"pad-map.json").copyTo(File(root,"downloads/rime-complete-pad-map.json"),overwrite=true)
        } finally { work.deleteRecursively() }
        manifest["model"] = JsonValue.Num(6.0)
        manifest["generatedAt"] = JsonValue.Str(Instant.now().toString())
        manifest["status"] = JsonValue.Str("Six voices and 36 authored presets; static and moving HOLD. Original models and 195 clips retained. New sounds and MPC hardware loading await owner listening.")
        manifest["completeBank"] = json(mapOf("presets" to 36,"voices" to 6,"starterPads" to 16,"velocityLayers" to 3))
        manifest["clips"] = JsonValue.Arr(entries)
        file.writeText(Json.write(JsonValue.Obj(manifest)))
        ZipOutputStream(File(root,"downloads/rime-complete-bank.zip").outputStream().buffered()).use { zip ->
            for (entry in RimePresets.entries()) {
                val p=entry.patch
                val clip=entries.single { it.obj().getValue("id").str()==if(p.model==4) "bank-${entry.id}" else "r6-bank-${entry.id}" }.obj()
                val recipe=File(root,clip.getValue("files").obj().getValue(if(p.model==4) "patch" else "recipe").str())
                zip.putNextEntry(ZipEntry("patches/${entry.id}.json").also { it.time=0 }); zip.write(recipe.readBytes()); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("README.txt").also { it.time=0 })
            zip.write("RIME / 36 complete factory recipes, six per voice. Nine accepted finite recipes remain model 4; the additions use model 6. Replay with the SnipSnap CLI: synth RIME VOICE --patch patch.json --out output. HOLD recipes retain their complete model, note, velocity, gate, seed and controls. Native keyboard instruments are separate downloads. New voicings await owner listening.\n".toByteArray())
            zip.closeEntry()
        }
        println("RIME complete audition: ${entries.size} clips; 36 presets; six voices")
    }

    private fun render(root: File,id: String,section: String,title: String,description: String,p: RimePatch,
        macro: String?,value: Float?,role: String?): JsonValue.Obj {
        val began=System.nanoTime()
        val held=if(Rime.isLoop(p.macros)) RimeHeld.render(p) else null
        val probe=if(held==null) Rime.inspect(p.voice,p.macros,p.midi,p.velocity,p.gateSeconds,p.seed,model=p.model) else null
        val samples=if(held==null) requireNotNull(probe).samples else {
            val prefix=held.note.loopStartFrame.toInt()
            val repeats=if(Rime.movingHold(p.macros,p.model))3 else 6
            FloatArray(prefix+repeats*held.cycle.size).also { out ->
                held.note.snip.samples.copyInto(out,0,0,prefix)
                repeat(repeats) { held.cycle.copyInto(out,prefix+it*held.cycle.size) }
                Dsp.fadeTail(out)
            }
        }
        val elapsed=(System.nanoTime()-began)/1e6
        check(samples.all { it.isFinite() && abs(it) <= .94001f })
        val raw="audio/raw/$id.wav"; val matched="audio/matched/$id.wav"; val recipe="recipes/$id.json"
        File(root,raw).apply { parentFile.mkdirs(); WavWriter.write(this,Snip(samples,1,Dsp.RATE)) }
        val balanced=samples.copyOf(); Dsp.levelTo(balanced,Dsp.RATE,.10f,ceiling=.9f)
        File(root,matched).apply { parentFile.mkdirs(); WavWriter.write(this,Snip(balanced,1,Dsp.RATE)) }
        File(root,recipe).apply { parentFile.mkdirs(); writeText(p.toJsonText()) }
        val hz=Keys.midiHz(p.midi)
        val spectrum=RimeContour.prepare(held?.actualHz ?: 440.0*2.0.pow((p.midi-69)/12.0),
            p.macros.getValue("DETAIL").toDouble(),p.macros.getValue("EDGE").toDouble(),
            apertureAngle=(p.macros.getValue("ANGLE").toDouble()%1)*2*PI,apertureLaw=4,variant=RimeContour.variantFor(p.voice))
        val detected=FineTuning.measuredHz(Snip(held?.cycle?:samples,1,Dsp.RATE),hz,fromSec=.02f,bodySeconds=.18f)
        val metrics=mapOf("durationSeconds" to samples.size.toDouble()/Dsp.RATE,"renderMs" to elapsed,
            "internalPeak" to (held?.internalPeak?:probe!!.internalPeak),"maxBodyEnergy" to (held?.maxBodyEnergy?:probe!!.maxBodyEnergy),
            "peak" to samples.maxOf { abs(it) },"mean" to samples.average(),"rawExportGain" to 1,
            "activeContourTerms" to spectrum.coefficients.count { it.energy>1e-16 },
            "activeTermEnergyFloor" to 1e-16,"safeMaximum" to spectrum.safeMaximum,"resolvedCutoff" to spectrum.cutoff,
            "measuredErrorCents" to detected?.let { FineTuning.cents(it.toDouble(),hz.toDouble()) },
            "classifiedAs" to Classifier.classify(Snip(samples,1,Dsp.RATE)).drumClass.name,
            "seamError" to held?.seamError,"loopFrames" to held?.cycle?.size,"loopStartFrame" to held?.note?.loopStartFrame,
            "holdAdaptation" to if(held==null) null else if(Rime.movingHold(p.macros,p.model)) "moving" else "static")
        val waveform=(0 until 96).map { bucket ->
            val from=bucket*samples.size/96; val to=max(from+1,(bucket+1)*samples.size/96)
            (from until to).maxOf { abs(balanced[it]) }
        }
        println("$id: ${elapsed.roundToInt()} ms; seam=${held?.seamError}")
        return json(mapOf("id" to id,"section" to section,"voice" to p.voice.name,"title" to title,"description" to description,
            "midi" to p.midi,"velocity" to p.velocity,"gateSeconds" to p.gateSeconds,"model" to p.model,"macros" to p.macros,
            "macro" to macro,"value" to value,"role" to role,"files" to mapOf("raw" to raw,"matched" to matched,"recipe" to recipe,"patch" to recipe),
            "metrics" to metrics,"waveform" to waveform)) as JsonValue.Obj
    }

    private fun json(v: Any?): JsonValue = when(v) {
        null -> JsonValue.Null; is String -> JsonValue.Str(v); is Number -> JsonValue.Num(v.toDouble())
        is Map<*,*> -> JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::json))
        else -> error("Unsupported field $v")
    }
}
