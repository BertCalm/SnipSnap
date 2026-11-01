package com.snipsnap.synth

/** Finite model-two playing gestures. Authored roles, not changes to the accepted renderer. */
data class TrefoilPreset(val id: String,val role: String,val description: String,val patch: TrefoilPatch)

object TrefoilPresets {
    private fun entry(id: String,name: String,role: String,description: String,voice: TrefoilVoice,
        weave: Float,nudge: Float,exchange: Float,thread: Float,memory: Float,
        seconds: Float,active: Float,seed: Long=43L)=TrefoilPreset(id,role,description,
        TrefoilPatch(name,voice,mapOf("WEAVE" to weave,"NUDGE" to nudge,"EXCHANGE" to exchange,
            "THREAD" to thread,"MEMORY" to memory,"HOLD" to 0f),midi=60,velocity=.8f,
            seed=seed,seconds=seconds,activeSeconds=active,model=2))

    val entries: List<TrefoilPreset> = listOf(
        entry("seed","Seed Pluck","Plucks","A small, rounded contact with a short memory.",TrefoilVoice.WEAVE,.30f,0f,.45f,.20f,.04f,2.2f,.16f),
        entry("glass","Glass Pin","Plucks","A fine upper edge on a compact strike.",TrefoilVoice.HOLLOW,.18f,.06f,.35f,.42f,.08f,2.8f,.18f),
        entry("flick","Wire Flick","Plucks","A bright contact that leaves room between notes.",TrefoilVoice.WIRE,.55f,.18f,.62f,.90f,.06f,2.5f,.20f),
        entry("knock","Orbit Knock","Plucks","A disturbed, lower-colored knock with a finite ring.",TrefoilVoice.TANGLE,.68f,.34f,.78f,.55f,.05f,2.6f,.18f,27L),
        entry("weave","First Weave","Keys","The ordered relationship shaped into a played key.",TrefoilVoice.WEAVE,.42f,0f,.55f,.28f,.25f,4.2f,.65f),
        entry("rounded","Rounded Keys","Keys","Gentler contact and a restrained afterimage.",TrefoilVoice.HOLLOW,.28f,.08f,.50f,.12f,.18f,3.8f,.85f),
        entry("silver","Silver Keys","Keys","An upper-partial crown with a defined release.",TrefoilVoice.WIRE,.45f,.12f,.65f,.62f,.20f,4.2f,.70f),
        entry("twin","Twin Turn","Keys","A longer key gesture with quicker exchanges.",TrefoilVoice.WEAVE,.64f,.20f,.80f,.40f,.30f,4.8f,1.1f,52L),
        entry("close","Close Wire","Motion","Faster contact moves through a bright body.",TrefoilVoice.WIRE,.72f,.25f,.90f,.85f,.24f,4.5f,1.6f),
        entry("broken","Broken Weave","Motion","A perturbed gesture that unsettles the meetings.",TrefoilVoice.TANGLE,.58f,.50f,.85f,.66f,.35f,5.6f,2f,27L),
        entry("afterglow","Afterglow","Memory","A brief played contact leaves upper harmonics behind.",TrefoilVoice.TRACE,.30f,.22f,.58f,.56f,.75f,7f,1.2f),
        entry("arc","Long Arc","Memory","Slower movement and the longest finite afterimage.",TrefoilVoice.TRACE,.16f,.30f,.70f,.36f,.90f,8f,1.5f,58L),
    )
    /** Paste UserPresets' complete JSON constructors here without losing non-macro state. */
    private val promoted: List<TrefoilPatch> = listOf()
    fun all(): List<TrefoilPatch> = entries.map { it.patch }+promoted
    fun forVoice(voice: TrefoilVoice): List<TrefoilPatch> = all().filter { it.voice==voice }
}
