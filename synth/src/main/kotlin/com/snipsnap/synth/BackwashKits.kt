package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/** Two authored genre arrangements, with different tuning, damping, roles and articulation. */
object BackwashKits {
    enum class Genre { SOUL_POCKET, PSYCH_ROCK }
    fun patches(genre: Genre): List<BackwashPatch> {
        val soul=genre==Genre.SOUL_POCKET
        fun p(v:BackwashVoice,n:String,t:Float=.5f,d:Float=if(v.legacy) .75f else 0f,art:String="HIT"):BackwashPatch {
            val m=Backwash.defaults(v)+mapOf("TUNE" to t,"DAMP" to d,
                "SWAY" to if(soul).28f else .65f,"TRAIL" to if(soul).18f else .65f,
                "PRESS" to if(soul).45f else .7f,"DRIVE" to if(soul).1f else .3f)
            return BackwashPatch(n,v,m,articulation=art)
        }
        return listOf(
            p(BackwashVoice.KICK,if(soul)"Pocket Kick" else "Low Rock Kick",if(soul).40f else .22f,if(soul).92f else .62f),
            p(BackwashVoice.SNARE,if(soul)"Pocket Snare" else "Open Rock Snare",if(soul).62f else .44f,if(soul).9f else .4f),
            p(BackwashVoice.HAT_CLOSED,"Closed Hat",if(soul).46f else .6f,if(soul).95f else .65f),
            p(BackwashVoice.HAT_OPEN,"Open Hat",if(soul).46f else .6f,if(soul).8f else .4f),
            p(BackwashVoice.CROSS_STICK,"Cross Stick",.5f,if(soul).12f else 0f),
            p(BackwashVoice.TAMBOURINE,"Tambourine",.5f,if(soul).08f else 0f),
            p(BackwashVoice.COWBELL,"Cowbell",if(soul).5f else .40f,if(soul).1f else 0f),
            p(BackwashVoice.RIDE,"Ride Bell",.5f,if(soul).08f else 0f),
            p(BackwashVoice.TOM,"Floor Tom",if(soul).24f else .1f,if(soul).85f else .45f),
            p(BackwashVoice.TOM,"Rack Tom",if(soul).48f else .38f,if(soul).88f else .48f),
            p(BackwashVoice.TOM,"High Tom",if(soul).65f else .68f,if(soul).9f else .5f),
            p(BackwashVoice.SNARE,"Ghost Snare",if(soul).66f else .5f,.97f),
            p(BackwashVoice.TAMBOURINE,"Tamb Roll",art="ROLL"),
            p(BackwashVoice.TAMBOURINE,"Tamb Riser",art="RISER"),
            p(if(soul)BackwashVoice.CROSS_STICK else BackwashVoice.COWBELL,if(soul)"Low Cross Stick" else "High Cowbell",if(soul).32f else .65f,.15f),
            p(BackwashVoice.KICK,"Tight Kick",if(soul).48f else .32f,.99f),
        )
    }
    fun build(genre: Genre): List<ArrangedPad> = patches(genre).map { p ->
        ArrangedPad(p.render(),p.voice.drumClass,PadRecipe(p).toJsonValue(),
            level=when(p.voice){BackwashVoice.KICK->.9f;BackwashVoice.SNARE->.8f;BackwashVoice.HAT_CLOSED,BackwashVoice.HAT_OPEN->.32f;else->.65f},
            displayName=p.name,muteGroup=if(p.voice==BackwashVoice.HAT_CLOSED||p.voice==BackwashVoice.HAT_OPEN)1 else 0,
            source=mapOf("engine" to "BACKWASH","model" to p.model.toString(),"genre" to genre.name))
    }
}
