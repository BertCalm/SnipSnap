package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

/** Original performance, never sampled from the reference video. All files use the actual Kotlin board. */
object BrineAuditionGenerator {
    private data class Part(val id: String, val voice: SkinVoice, val gain: Float,
        val delay: Int, val near: Float, val far: Float, val macros: Map<String, Float>)
    private fun part(id: String, voice: SkinVoice, gain: Float, delay: Int, near: Float, far: Float,
        vararg macros: Pair<String, Float>) = Part(id, voice, gain, delay, near, far, Skin.defaults(voice) + macros.toMap())
    private val parts = listOf(
        part("kick", SkinVoice.KICK, .65f, 79, 1f, .35f, "TUNE" to .30f, "DECAY" to .28f, "CLICK" to .24f, "PUNCH" to .48f),
        part("snare", SkinVoice.SNARE, .48f, 123, .50f, .85f, "TUNE" to .40f, "DECAY" to .27f, "SNAP" to .48f, "RATTLE" to .43f, "SIZZLE" to .32f),
        part("hat", SkinVoice.HAT_CLOSED, .14f, 161, .15f, 1f, "DECAY" to .19f, "TONE" to .32f, "RING" to .32f),
        part("tom", SkinVoice.TOM, .36f, 97, .55f, .70f, "TUNE" to .35f, "DECAY" to .30f, "TONE" to .40f))
    private data class Hit(val part: Int, val beat: Double, val velocity: Float)
    private val hits = buildList {
        for (bar in 0..1) {
            val b = bar * 4.0
            for (t in listOf(0.0, 1.75, 2.5)) add(Hit(0, b + t, .9f))
            for (t in listOf(1.0, 3.0)) add(Hit(1, b + t, .88f))
            add(Hit(1, b + 2.75, .30f))
            for (n in 0..7) add(Hit(2, b + n * .5, if (n % 2 == 0) .72f else .48f))
        }
        add(Hit(3, 7.5, .7f))
    }.sortedBy { it.beat }
    private const val BPM = 100.0

    private fun source(selected: Int? = null): Snip {
        val length = if (selected == null) (8 * 60 / BPM + .6) else 1.4
        val out = FloatArray((length * Dsp.RATE).roundToInt())
        val events = if (selected == null) hits else listOf(Hit(selected, 0.0, .88f))
        events.forEachIndexed { index, hit ->
            val p = parts[hit.part]
            val x = Skin.render(p.voice, p.macros, hit.velocity, take = index).samples
            val near = Dsp.FixedOnePole(6500f)
            val far = Dsp.FixedOnePole(3800f)
            val at = (hit.beat * 60 / BPM * Dsp.RATE).roundToInt()
            for (i in x.indices) {
                // Two fixed pickup perspectives per source, not two identical copies of the final bus.
                val a = near.lp(x[i]) * p.near
                val b = far.lp(x[i]) * p.far
                val level = p.gain * hit.velocity * .65f
                if (at + i < out.size) out[at + i] += a * level
                if (at + i + p.delay < out.size) out[at + i + p.delay] += b * level
            }
        }
        return Snip(out, 1, Dsp.RATE)
    }

    private fun json(x: Any?): JsonValue = when (x) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(x)
        is Number -> JsonValue.Num(x.toDouble())
        is Boolean -> JsonValue.Bool(x)
        is Map<*, *> -> JsonValue.Obj(x.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(x.map(::json))
        else -> error("Unsupported JSON: $x")
    }
    private fun hash(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/brine-audition").absoluteFile
        root.mkdirs(); File(root, "audio").mkdirs(); File(root, "recipes").mkdirs()
        val clips = mutableListOf<Map<String, Any?>>()
        val base = Brine.Settings()
        val groove = source()
        // Warm the real JVM path before reporting wall time. Host measurements are not phone promises.
        repeat(3) { Brine.process(source(1)) }
        fun export(id: String, group: String, label: String, dry: Snip, settings: Brine.Settings = base,
            stage: Brine.Stage = Brine.Stage.AMP) {
            val start = System.nanoTime()
            val result = Brine.process(dry, settings, stage)
            val millis = (System.nanoTime() - start) / 1e6
            val size = dry.frameCount + Dsp.RATE
            val raw = Snip(result.samples.copyOf(size), 1, Dsp.RATE)
            check(raw.samples.all { it.isFinite() })
            val matched = AuditionLevel.level(raw)
            val rawPath = "audio/$id-raw.wav"; val matchedPath = "audio/$id.wav"
            val rf = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
            val mf = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            val recipe = "recipes/$id.json"
            // Each settings file loads directly through the public Settings decoder.
            File(root, recipe).writeText(settings.toJsonText())
            clips += mapOf("id" to id, "group" to group, "label" to label, "raw" to rawPath,
                "audio" to matchedPath, "recipe" to recipe, "milliseconds" to millis,
                "peak" to raw.peak(), "mean" to raw.samples.average(), "loudness" to Loudness.of(raw),
                "through" to stage.name, "source" to if (dry === groove) "phrase" else if (id.startsWith("r2-") || id.startsWith("r3-") || id.startsWith("r4-") || id.startsWith("r5-") || id.startsWith("r6-") || id.startsWith("r7-")) id else id.substringBefore("-"),
                "frames" to raw.frameCount, "sha256" to hash(mf), "rawSha256" to hash(rf), "settings" to settings.values())
            println("$id: ${"%.1f".format(millis)} ms, peak ${raw.peak()}")
        }
        val expansionSettings = base.copy(room = 0f, grit = 0f)
        fun pair(id: String, group: String, source: Snip) {
            export("$id-dry", group, "Before pedals", source, stage = Brine.Stage.DRY)
            export("$id-full", group, "Full pedal chain", source, expansionSettings)
        }
        val arrangement = BrinePerformance.arrangement(1f)
        val tucked = BrinePerformance.arrangement(.75f)
        export("r8-balance-current", "Whole kit · hats → ride → crash", "Current balance / full chain", arrangement, expansionSettings)
        export("r8-balance-tucked", "Whole kit · hats → ride → crash", "Cymbals down 2.5 dB / full chain", tucked, expansionSettings)
        export("r8-balance-space", "Whole kit · hats → ride → crash", "Cymbals down / gentler spring and motion", tucked, expansionSettings.copy(splash=.20f,sweep=.16f))
        export("r8-balance-dry", "Whole kit · hats → ride → crash", "Cymbals down / before pedals", tucked, stage=Brine.Stage.DRY)
        for (ride in listOf(false,true)) {
            val id = if(ride) "ride" else "snare"
            val group = "Performance · $id"
            export("r8-$id-fixed",group,"Repeated medium / one take",BrinePerformance.phrase(ride,false,false),stage=Brine.Stage.DRY)
            export("r8-$id-alternate",group,"Repeated medium / alternating takes",BrinePerformance.phrase(ride,true,false),stage=Brine.Stage.DRY)
            pair("r8-$id-touch", "Touch · $id soft → medium → hard",BrinePerformance.phrase(ride,true,true))
        }
        val references = File(System.getProperty("brine.references") ?: "testkit/brine-references")
        require(references.isDirectory) { "Supply -PbrineReferences with the downloaded/aligned CC0 benchmark files" }
        fun reference(id: String): Snip {
            val f = File(references, "$id-aligned.wav").takeIf { it.isFile } ?: File(references, "$id.wav")
            val x = WavReader.read(f)
            require(x.channels == 1 && x.sampleRate == Dsp.RATE)
            val a = x.samples.copyOf(); Dsp.normalize(a, .9f)
            return Snip(a, 1, Dsp.RATE)
        }
        for ((v,id) in listOf(BrineCymbals.Voice.RIDE to "ride-main",
            BrineCymbals.Voice.CHOKED_CRASH to "choke-main", BrineCymbals.Voice.CRASH to "crash-main")) {
            val group = "Reference benchmark · ${v.label}"
            val ref = reference(id)
            export("r7-${v.id}-reference", group, "01 · Recorded reference · source", ref, stage=Brine.Stage.DRY)
            if (v != BrineCymbals.Voice.CRASH) export("r7-${v.id}-check", group,
                "02 · Alternate recorded take · not used for fitting", reference(if(v==BrineCymbals.Voice.RIDE) "ride-check" else "choke-check"), stage=Brine.Stage.DRY)
            export("r7-${v.id}-old-source", group, "03 · Previous synth · source",
                Snip(BrineMetal.source(v),1,Dsp.RATE), stage=Brine.Stage.DRY)
            export("r7-${v.id}-source", group, "04 · Reference-fitted synth · source",
                Snip(BrineReferenceCymbals.source(v),1,Dsp.RATE), stage=Brine.Stage.DRY)
            val referenceRecorded=BrineReferenceCymbals.record(v,ref.samples)
            val fitted=BrineReferenceCymbals.render(v)
            export("r7-${v.id}-reference-recorded", group, "05 · Reference + BRINE recording", referenceRecorded, stage=Brine.Stage.DRY)
            export("r7-${v.id}-recorded", group, "06 · Fitted synth + BRINE recording", fitted, stage=Brine.Stage.DRY)
            export("r7-${v.id}-reference-full", group, "07 · Reference + full chain", referenceRecorded, expansionSettings)
            export("r7-${v.id}-full", group, "08 · Fitted synth + full chain", fitted, expansionSettings)
        }
        for (style in BrineCymbals.Groove.entries)
            pair("r7-${style.name.lowercase()}", "Reference-fitted groove · ${style.name.lowercase()}", BrineReferenceCymbals.renderGroove(style))
        File(references,"sources.json").copyTo(File(root,"reference-sources.json"),overwrite=true)
        File(root,"REFERENCE-CREDITS.txt").writeText(checkNotNull(javaClass.getResource("/brine/README.txt")).readText())
        File(root,"LICENSE-CC0.txt").writeText(checkNotNull(javaClass.getResource("/brine/LICENSE-CC0.txt")).readText())
        for (v in listOf(BrineCymbals.Voice.RIDE, BrineCymbals.Voice.CHOKED_CRASH, BrineCymbals.Voice.CRASH)) {
            val group = "Metal resonance · ${v.label}"
            val before = BrineCymbals.render(v)
            val after = BrineMetal.render(v)
            export("r6-${v.id}-before", group, "Previous · before pedals", before, stage = Brine.Stage.DRY)
            export("r6-${v.id}-after", group, "Struck metal · before pedals", after, stage = Brine.Stage.DRY)
            export("r6-${v.id}-before-full", group, "Previous · full chain", before, expansionSettings)
            export("r6-${v.id}-after-full", group, "Struck metal · full chain", after, expansionSettings)
        }
        for (style in BrineCymbals.Groove.entries)
            pair("r6-${style.name.lowercase()}", "Revised metal groove · ${style.name.lowercase()}", BrineMetal.renderGroove(style))
        for (style in BrineCymbals.Groove.entries) {
            val group = "Cymbal grooves · ${style.name.lowercase()}"
            pair("r5-${style.name.lowercase()}", group, BrineCymbals.renderGroove(style))
            export("r5-${style.name.lowercase()}-space", group, "Cymbals down 2.5 dB · full chain",
                BrineCymbals.renderGroove(style, .75f), expansionSettings)
        }
        for (v in BrineCymbals.Voice.entries)
            pair("r5-${v.id}", v.label, BrineCymbals.render(v))
        fun tomComparison(id: String, group: String, before: Snip, after: Snip) {
            export("$id-before-dry", group, "Previous tom · before pedals", before, stage = Brine.Stage.DRY)
            export("$id-after-dry", group, "Thumpy resonant tom · before pedals", after, stage = Brine.Stage.DRY)
            export("$id-before-full", group, "Previous tom · full chain", before, expansionSettings)
            export("$id-after-full", group, "Thumpy resonant tom · full chain", after, expansionSettings)
        }
        tomComparison("r4-tom", "Thumpy resonant tom · start here",
            BrineKitExpansion.render(BrineKitExpansion.Voice.FLOOR_TOM), BrineKitExpansion.renderThumpyTom())
        tomComparison("r4-groove", "Tom in the kit",
            BrineKitExpansion.render(), BrineKitExpansion.renderThumpyTom(inGroove = true))
        pair("r3-groove", "Expanded kit · start here", BrineKitExpansion.render())
        pair("r3-snare-phrase", "Snare touch · ghost / main / ghost / rimshot", BrineKitExpansion.snarePhrase())
        for (voice in BrineKitExpansion.Voice.entries) {
            val id = BrineKitExpansion.articulation(voice).part.id
            pair("r3-$id", voice.label, BrineKitExpansion.render(voice))
        }
        export("r2-before", "Closer to the reference", "01 · Previous recording", groove, stage = Brine.Stage.RECORDING)
        val revised = BrineKit.render()
        export("r2-source", "Closer to the reference", "02 · Revised kit · before pedals", revised, stage = Brine.Stage.DRY)
        export("r2-wires", "Closer to the reference", "03 · More wire texture · before pedals", BrineKit.render(BrineKit.Character.WIRES), stage = Brine.Stage.DRY)
        export("r2-room", "Closer to the reference", "04 · More room bleed · before pedals", BrineKit.render(BrineKit.Character.ROOM), stage = Brine.Stage.DRY)
        val pedalSettings = base.copy(room = 0f, grit = 0f)
        export("r2-full", "Closer to the reference", "05 · Revised kit + full pedal chain", revised, pedalSettings)
        for (i in 0..3) export("r2-solo-$i", "Revised single hits", BrineKit.parts()[i].id,
            BrineKit.render(solo = i), stage = Brine.Stage.DRY)
        for (stage in Brine.Stage.entries) export("stage-${stage.name.lowercase()}", "Start here", when(stage) {
            Brine.Stage.DRY -> "01 · Dry two-mic kit"
            Brine.Stage.RECORDING -> "02 · Room + recording saturation"
            Brine.Stage.SPRING -> "03 · Add spring"
            Brine.Stage.MODULATION -> "04 · Add slow phaser"
            Brine.Stage.AMP -> "05 · Full chain"
        }, groove, stage = stage)
        for (i in parts.indices) {
            val dry = source(i)
            export("${parts[i].id}-dry", "Single hits", "${parts[i].id} · dry", dry, stage = Brine.Stage.DRY)
            export("${parts[i].id}-full", "Single hits", "${parts[i].id} · full chain", dry)
        }
        for (macro in Brine.MACROS) for (v in listOf(.1f, .5f, .9f)) {
            val s = when (macro.name) {
                "ROOM" -> base.copy(room = v); "GRIT" -> base.copy(grit = v)
                "SPLASH" -> base.copy(splash = v); "SWEEP" -> base.copy(sweep = v)
                "RATE" -> base.copy(rate = v); else -> base.copy(drive = v)
            }
            export("${macro.name.lowercase()}-${(v * 100).roundToInt()}", "Control sweeps", "${macro.name} · $v", groove, s)
        }
        for (splash in listOf(.1f, .8f)) for (drive in listOf(.1f, .8f))
            export("pair-${(splash*10).toInt()}-${(drive*10).toInt()}", "Interactions", "SPLASH $splash / DRIVE $drive", groove, base.copy(splash = splash, drive = drive))
        export("edge-hot", "Edges", "All controls high", groove, Brine.Settings(1f, 1f, 1f, 1f, 1f, 1f))
        export("edge-low", "Edges", "All controls low · speaker remains", groove, Brine.Settings(0f, 0f, 0f, 0f, 0f, 0f))
        val manifest = Json.write(json(mapOf("title" to "BRINE · whole-kit balance and touch", "version" to Brine.VERSION,
            "status" to "R7 cymbals and R4 tom approved by listener; R8 balance and touch await audition", "sampleRate" to Dsp.RATE,
            "levelPolicy" to "Matched: AuditionLevel loudest 200 ms RMS target .03, not LUFS. Raw: board output without audition gain.",
            "sourceRevision" to BrineKit.VERSION,
            "performanceRevision" to mapOf("version" to 8, "velocities" to BrinePerformance.velocities,
                "takes" to 2, "rideTouch" to "Proposed velocity-dependent treble shading of R7 measured profile",
                "balance" to "Current, cymbals -2.5 dB, and cymbals -2.5 dB with SPLASH .20 / SWEEP .16",
                "mpc" to "Separate take programs; three velocity zones on snare and ride; no automatic round robin"),
            "referenceRevision" to mapOf("version" to 7, "method" to "Measured spectral envelopes with new deterministic phases; no source waveform playback",
                "profiles" to listOf("ride-main", "choke-main", "crash-main"), "fftSize" to 2048,
                "hop" to 256, "timeKnots" to 21, "license" to "CC0-1.0", "credits" to "REFERENCE-CREDITS.txt"),
            "metalRevision" to mapOf("version" to BrineMetal.VERSION,
                "rideModes" to BrineMetal.rideModes.map { mapOf("hz" to it.hz, "weight" to it.weight, "t60" to it.t60) },
                "crashModes" to BrineMetal.crashModes.map { mapOf("hz" to it.hz, "weight" to it.weight, "t60" to it.t60) },
                "contactMs" to listOf(1.2, 4.0), "chokeSeconds" to BrineMetal.CHOKE_SECONDS,
                "chokeReleaseSeconds" to BrineMetal.CHOKE_RELEASE_SECONDS,
                "rideGain" to .18, "crashGain" to .25,
                "modeWeightTilt" to mapOf("pivotHz" to 1600, "exponent" to 2.4, "ceiling" to 12)),
            "cymbalRevision" to mapOf("version" to BrineCymbals.VERSION,
                "halfHat" to BrineCymbals.halfHat, "ride" to BrineCymbals.ride,
                "rideStick" to BrineCymbals.rideStick, "rideMix" to listOf(.9, .1),
                "crashLayers" to BrineCymbals.crashLayers, "crashBloomMs" to 6,
                "crashChokeMs" to 170, "chokeReleaseMs" to 6,
                "parts" to BrineCymbals.Voice.entries.map { v -> val p = BrineCymbals.part(v)
                    mapOf("id" to p.id, "gain" to p.gain, "farDelay" to p.farDelay, "farGain" to p.farGain) },
                "grooves" to BrineCymbals.Groove.entries.map { style -> mapOf("style" to style.name,
                    "events" to BrineCymbals.events(style).map { e -> mapOf("part" to e.part.id,
                        "beat" to e.beat, "velocity" to e.velocity, "rimMix" to e.rimMix) }) }),
            "tomRevision" to mapOf("version" to 4, "macros" to BrineKitExpansion.thumpyTom().part.macros,
                "gain" to BrineKitExpansion.thumpyTom().part.gain,
                "velocity" to BrineKitExpansion.thumpyTom().velocity),
            "expansion" to mapOf("version" to BrineKitExpansion.VERSION, "bpm" to BrineKit.BPM,
                "hatChokeReleaseMs" to 6, "recording" to "unchanged R2 finish",
                "articulations" to BrineKitExpansion.Voice.entries.map { v ->
                    val a = BrineKitExpansion.articulation(v)
                    mapOf("id" to a.part.id, "voice" to a.part.voice.name, "macros" to a.part.macros,
                        "velocity" to a.velocity, "rimMix" to a.rimMix, "gain" to a.part.gain,
                        "farDelay" to a.part.farDelay, "farGain" to a.part.farGain)
                }, "groove" to BrineKitExpansion.groove().map { e ->
                    mapOf("part" to e.part.id, "beat" to e.beat, "velocity" to e.velocity, "rimMix" to e.rimMix)
                }),
            "revisedSource" to mapOf("bpm" to BrineKit.BPM, "pattern" to "same events as original source below",
                "compressor" to mapOf("AMOUNT" to .18, "ATTACK" to .32, "RECOVER" to .72),
                "recording" to mapOf("ROOM" to 0, "GRIT" to .18),
                "contour" to mapOf("bodyHz" to 540, "bodyDb" to 14, "bodyQ" to .8,
                    "shelfHz" to 2000, "shelfDb" to -6.2, "saturationGain" to 6.6,
                    "bandwidthHz" to 6500, "bandwidthPoles" to 4),
                "characters" to BrineKit.Character.entries.map { c -> mapOf("name" to c.name,
                    "parts" to BrineKit.parts(c).map { p -> mapOf("id" to p.id, "voice" to p.voice.name,
                        "macros" to p.macros, "gain" to p.gain, "farDelay" to p.farDelay, "farGain" to p.farGain) }) }),
            "clips" to clips, "source" to mapOf("bpm" to BPM, "beats" to 8,
                "parts" to parts.map { mapOf("id" to it.id, "voice" to it.voice.name, "macros" to it.macros,
                    "gain" to it.gain, "near" to it.near, "far" to it.far, "farDelayFrames" to it.delay) },
                "hits" to hits.mapIndexed { i, h -> mapOf("part" to parts[h.part].id, "beat" to h.beat, "velocity" to h.velocity, "take" to i) }))))
        File(root, "manifest.json").writeText(manifest)
        File(root, "metrics.csv").writeText("id,render_ms,peak,mean,loudness,frames\n" + clips.joinToString("\n") {
            listOf("id", "milliseconds", "peak", "mean", "loudness", "frames").joinToString(",") { k -> it[k].toString() }
        } + "\n")
        val template = checkNotNull(javaClass.getResource("/audition/brine-audition.html")).readText()
        File(root, "index.html").writeText(template.replace("/*MANIFEST*/", "const manifest = $manifest;"))
        BrineMpcGenerator.write(root)
        println("Wrote ${clips.size} comparisons to $root")
    }
}
