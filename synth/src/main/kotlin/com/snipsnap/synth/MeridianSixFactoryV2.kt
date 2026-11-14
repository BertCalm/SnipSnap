package com.snipsnap.synth

import java.util.Collections

/**
 * Permanent complete source anchors for the finite six-voice bank. Nine original
 * finite factories and the four kept model-4 finite recipes are copied exactly,
 * including LONG TRAIL's finite HOLD=.65. No current roster/default lookup may
 * migrate a saved bank. The three octave roles live only in the fixed layout.
 * This private bank adds no global presets or held counterparts.
 */
internal object MeridianSixFactoryV2 {
    private val encoded = listOf(
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "FIRST ARC",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.800000011920929,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 0.550000011920929,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "BACK ARC",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.20000000298023224,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 0.550000011920929,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "CLOSE GRAIN",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.699999988079071,
            "LENGTH": 0.3199999928474426,
            "REACH": 0.41999998688697815,
            "SURFACE": 0.1599999964237213,
            "MEMORY": 0.18000000715255737,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "DEEP KNOCK",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.8999999761581421,
            "LENGTH": 0.18000000715255737,
            "REACH": 0.9200000166893005,
            "SURFACE": 0.7200000286102295,
            "MEMORY": 0.2800000011920929,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "STILL PAIR",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.5,
            "LENGTH": 0.5,
            "REACH": 0.550000011920929,
            "SURFACE": 0.6000000238418579,
            "MEMORY": 0.44999998807907104,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "LONG TRAIL",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.7200000286102295,
            "LENGTH": 0.7799999713897705,
            "REACH": 0.699999988079071,
            "SURFACE": 0.6200000047683716,
            "MEMORY": 0.8399999737739563,
            "HOLD": 0.6499999761581421
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "HARD GRAIN",
          "voice": "CLACK",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.800000011920929,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 1,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 3,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "HOLLOW TONE",
          "voice": "CHIME",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.800000011920929,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 1,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 3,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "BRIGHT EDGE",
          "voice": "RASP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.800000011920929,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 1,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 3,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "OPEN LINE",
          "voice": "DRAW",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.7599999904632568,
            "LENGTH": 0.47999998927116394,
            "REACH": 0.6499999761581421,
            "SURFACE": 0.25,
            "MEMORY": 0.44999998807907104,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 4,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "WIDE THREAD",
          "voice": "DRAW",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.2199999988079071,
            "LENGTH": 0.6600000262260437,
            "REACH": 0.46000000834465027,
            "SURFACE": 0.7599999904632568,
            "MEMORY": 0.6499999761581421,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 4,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "AFTER PRESS",
          "voice": "LIFT",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.7599999904632568,
            "LENGTH": 0.47999998927116394,
            "REACH": 0.6499999761581421,
            "SURFACE": 0.44999998807907104,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 4,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "SOFT RETURN",
          "voice": "LIFT",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.23999999463558197,
            "LENGTH": 0.6800000071525574,
            "REACH": 0.47999998927116394,
            "SURFACE": 0.8199999928474426,
            "MEMORY": 0.6200000047683716,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 4,
          "seedContext": 4810
        }
        """.trimIndent()
    )
    val patches: List<MeridianPatch> = Collections.unmodifiableList(encoded.map { text ->
        MeridianPatch.fromJsonText(text).let { patch ->
            require(!Meridian.isLoop(patch.macros, patch.model))
            patch.copy(macros = Collections.unmodifiableMap(LinkedHashMap(patch.macros)))
        }
    })
    fun forName(name: String): MeridianPatch = patches.single { it.name == name }
}
