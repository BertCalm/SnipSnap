package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.synth.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class TrefoilIntegrationTest {
    @Test fun `saved promoted and drafted Trefoil recipes retain note time seed touch and both models`() {
        val root=createTempDirectory("trefoil-shelf").toFile()
        try {
            for(model in listOf(1,2)) {
                val p=TrefoilPresets.all().first().copy(name="SAVED \"$model\" \$KEY",model=model,midi=57,velocity=.42f,seed=Long.MIN_VALUE+19)
                UserPresets.save(root,p,model.toLong())
                val restored=UserPresets.read(root).single { it.name==p.name }.patch
                assertEquals(p,restored)
                val line=UserPresets.rosterLine(restored)
                assertTrue(line.startsWith("TrefoilPatch.fromJsonText("))
                val literal=line.removePrefix("TrefoilPatch.fromJsonText(").removeSuffix("),").replace("\\\$","\$")
                assertEquals(p,TrefoilPatch.fromJsonText(Json.parse(literal).str()))
                assertEquals(p.midi.toFloat(),Spread.detect(Snip(FloatArray(441),1,44100),p))
            }
            val starter=assertNotNull(StarterKits.byId("trefoil"))
            assertEquals("TREFOIL",starter.displayName);assertFalse(starter.seeded)
        } finally { root.deleteRecursively() }
    }
}
