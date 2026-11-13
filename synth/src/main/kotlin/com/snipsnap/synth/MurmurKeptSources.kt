package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * The three kept PLUCK sources. Factory patches keep the existing house output stage;
 * reference gains describe the independently retained raw-native listening recordings.
 * JSON strings are immutable snapshots. Each patch/JSON read decodes a fresh deep copy.
 */
object MurmurKeptSources {
    const val FINITE_WIRE = "finite-hollow-wire"
    const val HELD_WIRE = "held-hollow-wire"
    const val STEADY_C2 = "approved-c2"
    const val NATIVE_RENDERER_SHA256 = "683bf1f204569401ce3c12fa451559329a4ed2acd0c1b7f71a816f18ef2dbdf2"
    const val FACTORY_OUTPUT = "HOUSE_NORMALIZED"
    const val REFERENCE_OUTPUT = "RAW_NATIVE_WITH_SAVED_GAIN"

    data class Presentation(val cycles: Int, val releaseFrames: Int, val silenceFrames: Int,
                            val onsetFrames: Int = 0, val rounding: String = "nearest integer, ties to even")

    data class Reference(
        val rawOutputGain: Float,
        val nativePcmSha256: String,
        val nativeReferenceWavSha256: String,
        val heardWavSha256: String,
        val heardRecipeJson: String,
        val heardRecipeSha256: String,
        val presentation: Presentation? = null,
    )

    data class Source(
        val id: String,
        val presetName: String,
        val originalNativePatchJson: String,
        val originalNativePatchSha256: String,
        val supportedMidi: IntRange,
        val reference: Reference,
    ) {
        fun originalPatch(): MurmurPatch = MurmurPatch.fromJsonText(originalNativePatchJson)
        fun factoryPatch(): MurmurPatch = originalPatch().copy(name = presetName)

        /** Provenance, not a PadRecipe output-gain instruction or an automatic approval of edits. */
        fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(id),
            "presetName" to JsonValue.Str(presetName),
            "originalNativePatchText" to JsonValue.Str(originalNativePatchJson),
            "originalNativePatchSha256" to JsonValue.Str(originalNativePatchSha256),
            "factoryPatch" to factoryPatch().toJsonValue(),
            "factoryOutput" to JsonValue.Str(FACTORY_OUTPUT),
            "nativeRendererSha256" to JsonValue.Str(NATIVE_RENDERER_SHA256),
            "lowMidi" to JsonValue.Num(supportedMidi.first.toDouble()),
            "highMidi" to JsonValue.Num(supportedMidi.last.toDouble()),
            "approvalScope" to JsonValue.Str("Exact heard note and native velocity; other qualified cells are engineering derivatives"),
            "reference" to JsonValue.Obj(linkedMapOf(
                "outputStage" to JsonValue.Str(REFERENCE_OUTPUT),
                "rawOutputGain" to JsonValue.Num(reference.rawOutputGain.toDouble()),
                "nativePcmSha256" to JsonValue.Str(reference.nativePcmSha256),
                "nativeReferenceWavSha256" to JsonValue.Str(reference.nativeReferenceWavSha256),
                "heardWavSha256" to JsonValue.Str(reference.heardWavSha256),
                "heardRecipeText" to JsonValue.Str(reference.heardRecipeJson),
                "heardRecipeSha256" to JsonValue.Str(reference.heardRecipeSha256),
                "presentation" to (reference.presentation?.let { p -> JsonValue.Obj(linkedMapOf(
                    "cycles" to JsonValue.Num(p.cycles.toDouble()),
                    "releaseFrames" to JsonValue.Num(p.releaseFrames.toDouble()),
                    "silenceFrames" to JsonValue.Num(p.silenceFrames.toDouble()),
                    "onsetFrames" to JsonValue.Num(p.onsetFrames.toDouble()),
                    "rounding" to JsonValue.Str(p.rounding),
                )) } ?: JsonValue.Null),
            )),
        ))
    }

    // Keep the original fixture text including whitespace; its hash authenticates the saved file.
    private const val NATIVE_0 = """{
  "engine": "MURMUR",
  "version": 1,
  "name": "SHORT ANSWER",
  "voice": "PLUCK",
  "macros": {
    "MOUTH": 0.3499999940395355,
    "EDGE": 0.550000011920929,
    "BREATH": 0.10000000149011612,
    "MOTION": 0.3499999940395355,
    "BODY": 0.44999998807907104,
    "HOLD": 0
  },
  "midi": 60,
  "velocity": 1,
  "config": {
    "routing": "PAIRS",
    "ratios": [
      1,
      2,
      2.006,
      3
    ],
    "trajectory": "ATTACK",
    "direction": 1,
    "referenceSeconds": 0.48,
    "attack": 0.002,
    "decay": 0.24,
    "sustain": 0.08,
    "release": 0.35,
    "targets": [
      {
        "name": "pluck Closed hum",
        "centers": [
          283.5,
          840,
          1680.0000000000002,
          2860.0000000000005,
          3800
        ],
        "widths": [
          103.49999999999999,
          186.29999999999998,
          254.14999999999998,
          322,
          488.74999999999994
        ],
        "gains": [
          1.1700000000000002,
          0.7200000000000001,
          0.325,
          0.18000000000000002,
          0.065
        ],
        "sourceBalance": 0.05000000000000001
      },
      {
        "name": "pluck Rounded",
        "centers": [
          346.5,
          787.5,
          1568.0000000000002,
          2585,
          3500
        ],
        "widths": [
          124.19999999999999,
          165.6,
          234.6,
          322,
          439.87499999999994
        ],
        "gains": [
          1.105,
          0.81,
          0.39,
          0.18000000000000002,
          0.0975
        ],
        "sourceBalance": 0.098
      },
      {
        "name": "pluck Open",
        "centers": [
          798,
          1312.5,
          2744.0000000000005,
          3630.0000000000005,
          4400
        ],
        "widths": [
          196.64999999999998,
          206.99999999999997,
          293.25,
          349.59999999999997,
          508.29999999999995
        ],
        "gains": [
          0.8450000000000001,
          1.26,
          0.8450000000000001,
          0.36000000000000004,
          0.0975
        ],
        "sourceBalance": 0.23000000000000004
      },
      {
        "name": "pluck Narrow",
        "centers": [
          378,
          2152.5,
          3192.0000000000005,
          4180,
          4800
        ],
        "widths": [
          113.85,
          238.04999999999998,
          342.125,
          386.4,
          566.9499999999999
        ],
        "gains": [
          0.7150000000000001,
          1.215,
          0.9750000000000001,
          0.27,
          0.078
        ],
        "sourceBalance": 0.13
      }
    ],
    "model": 4,
    "body": "HOLLOW_WIRE_V1"
  }
}
"""

    private const val HEARD_0 = """{
  "renderer": "snipsnap-murmur-body-study",
  "version": 1,
  "source_sha256": "626793bc9743b075b296e51231aa9d36b943872ac56eb3da60ede5943e991a19",
  "variant": "HOLLOW_WIRE",
  "patch": {
    "engine": "MURMUR",
    "version": 1,
    "name": "SHORT ANSWER",
    "voice": "PLUCK",
    "macros": {
      "MOUTH": 0.3499999940395355,
      "EDGE": 0.550000011920929,
      "BREATH": 0.10000000149011612,
      "MOTION": 0.3499999940395355,
      "BODY": 0.44999998807907104,
      "HOLD": 0
    },
    "midi": 60,
    "velocity": 1,
    "config": {
      "routing": "PAIRS",
      "ratios": [
        1,
        2,
        2.006,
        3
      ],
      "trajectory": "ATTACK",
      "direction": 1,
      "referenceSeconds": 0.48,
      "attack": 0.002,
      "decay": 0.24,
      "sustain": 0.08,
      "release": 0.35,
      "targets": [
        {
          "name": "pluck Closed hum",
          "centers": [
            283.5,
            840,
            1680.0000000000002,
            2860.0000000000005,
            3800
          ],
          "widths": [
            103.49999999999999,
            186.29999999999998,
            254.14999999999998,
            322,
            488.74999999999994
          ],
          "gains": [
            1.1700000000000002,
            0.7200000000000001,
            0.325,
            0.18000000000000002,
            0.065
          ],
          "sourceBalance": 0.05000000000000001
        },
        {
          "name": "pluck Rounded",
          "centers": [
            346.5,
            787.5,
            1568.0000000000002,
            2585,
            3500
          ],
          "widths": [
            124.19999999999999,
            165.6,
            234.6,
            322,
            439.87499999999994
          ],
          "gains": [
            1.105,
            0.81,
            0.39,
            0.18000000000000002,
            0.0975
          ],
          "sourceBalance": 0.098
        },
        {
          "name": "pluck Open",
          "centers": [
            798,
            1312.5,
            2744.0000000000005,
            3630.0000000000005,
            4400
          ],
          "widths": [
            196.64999999999998,
            206.99999999999997,
            293.25,
            349.59999999999997,
            508.29999999999995
          ],
          "gains": [
            0.8450000000000001,
            1.26,
            0.8450000000000001,
            0.36000000000000004,
            0.0975
          ],
          "sourceBalance": 0.23000000000000004
        },
        {
          "name": "pluck Narrow",
          "centers": [
            378,
            2152.5,
            3192.0000000000005,
            4180,
            4800
          ],
          "widths": [
            113.85,
            238.04999999999998,
            342.125,
            386.4,
            566.9499999999999
          ],
          "gains": [
            0.7150000000000001,
            1.215,
            0.9750000000000001,
            0.27,
            0.078
          ],
          "sourceBalance": 0.13
        }
      ],
      "model": 2
    }
  },
  "output_gain": 2.000858783721924
}
"""

    private const val NATIVE_1 = """{
  "engine": "MURMUR",
  "version": 1,
  "name": "OPEN STRING",
  "voice": "PLUCK",
  "macros": {
    "MOUTH": 0.8399999737739563,
    "EDGE": 0.2199999988079071,
    "BREATH": 0.03999999910593033,
    "MOTION": 0.019999999552965164,
    "BODY": 0.8799999952316284,
    "HOLD": 1
  },
  "midi": 60,
  "velocity": 0.8600000143051147,
  "config": {
    "routing": "PAIRS",
    "ratios": [
      1,
      2,
      2.006,
      3
    ],
    "trajectory": "ATTACK",
    "direction": 1,
    "referenceSeconds": 0.48,
    "attack": 0.002,
    "decay": 0.24,
    "sustain": 0.08,
    "release": 0.35,
    "targets": [
      {
        "name": "pluck Closed hum",
        "centers": [
          283.5,
          840,
          1680.0000000000002,
          2860.0000000000005,
          3800
        ],
        "widths": [
          103.49999999999999,
          186.29999999999998,
          254.14999999999998,
          322,
          488.74999999999994
        ],
        "gains": [
          1.1700000000000002,
          0.7200000000000001,
          0.325,
          0.18000000000000002,
          0.065
        ],
        "sourceBalance": 0.05000000000000001
      },
      {
        "name": "pluck Rounded",
        "centers": [
          346.5,
          787.5,
          1568.0000000000002,
          2585,
          3500
        ],
        "widths": [
          124.19999999999999,
          165.6,
          234.6,
          322,
          439.87499999999994
        ],
        "gains": [
          1.105,
          0.81,
          0.39,
          0.18000000000000002,
          0.0975
        ],
        "sourceBalance": 0.098
      },
      {
        "name": "pluck Open",
        "centers": [
          798,
          1312.5,
          2744.0000000000005,
          3630.0000000000005,
          4400
        ],
        "widths": [
          196.64999999999998,
          206.99999999999997,
          293.25,
          349.59999999999997,
          508.29999999999995
        ],
        "gains": [
          0.8450000000000001,
          1.26,
          0.8450000000000001,
          0.36000000000000004,
          0.0975
        ],
        "sourceBalance": 0.23000000000000004
      },
      {
        "name": "pluck Narrow",
        "centers": [
          378,
          2152.5,
          3192.0000000000005,
          4180,
          4800
        ],
        "widths": [
          113.85,
          238.04999999999998,
          342.125,
          386.4,
          566.9499999999999
        ],
        "gains": [
          0.7150000000000001,
          1.215,
          0.9750000000000001,
          0.27,
          0.078
        ],
        "sourceBalance": 0.13
      }
    ],
    "model": 4,
    "body": "HOLLOW_WIRE_V1"
  }
}
"""

    private const val HEARD_1 = """{
  "renderer": "snipsnap-murmur-body-audition",
  "version": 1,
  "packager_source_sha256": "8c2c5ff4c9e13335816a9d5f32f43b07452d2d214eb8559f675e02970aa828f8",
  "body_recipe": {
    "renderer": "snipsnap-murmur-body-study",
    "version": 1,
    "source_sha256": "626793bc9743b075b296e51231aa9d36b943872ac56eb3da60ede5943e991a19",
    "variant": "HOLLOW_WIRE",
    "patch": {
      "engine": "MURMUR",
      "version": 1,
      "name": "OPEN STRING",
      "voice": "PLUCK",
      "macros": {
        "MOUTH": 0.8399999737739563,
        "EDGE": 0.2199999988079071,
        "BREATH": 0.03999999910593033,
        "MOTION": 0.019999999552965164,
        "BODY": 0.8799999952316284,
        "HOLD": 1
      },
      "midi": 60,
      "velocity": 0.8600000143051147,
      "config": {
        "routing": "PAIRS",
        "ratios": [
          1,
          2,
          2.006,
          3
        ],
        "trajectory": "ATTACK",
        "direction": 1,
        "referenceSeconds": 0.48,
        "attack": 0.002,
        "decay": 0.24,
        "sustain": 0.08,
        "release": 0.35,
        "targets": [
          {
            "name": "pluck Closed hum",
            "centers": [
              283.5,
              840,
              1680.0000000000002,
              2860.0000000000005,
              3800
            ],
            "widths": [
              103.49999999999999,
              186.29999999999998,
              254.14999999999998,
              322,
              488.74999999999994
            ],
            "gains": [
              1.1700000000000002,
              0.7200000000000001,
              0.325,
              0.18000000000000002,
              0.065
            ],
            "sourceBalance": 0.05000000000000001
          },
          {
            "name": "pluck Rounded",
            "centers": [
              346.5,
              787.5,
              1568.0000000000002,
              2585,
              3500
            ],
            "widths": [
              124.19999999999999,
              165.6,
              234.6,
              322,
              439.87499999999994
            ],
            "gains": [
              1.105,
              0.81,
              0.39,
              0.18000000000000002,
              0.0975
            ],
            "sourceBalance": 0.098
          },
          {
            "name": "pluck Open",
            "centers": [
              798,
              1312.5,
              2744.0000000000005,
              3630.0000000000005,
              4400
            ],
            "widths": [
              196.64999999999998,
              206.99999999999997,
              293.25,
              349.59999999999997,
              508.29999999999995
            ],
            "gains": [
              0.8450000000000001,
              1.26,
              0.8450000000000001,
              0.36000000000000004,
              0.0975
            ],
            "sourceBalance": 0.23000000000000004
          },
          {
            "name": "pluck Narrow",
            "centers": [
              378,
              2152.5,
              3192.0000000000005,
              4180,
              4800
            ],
            "widths": [
              113.85,
              238.04999999999998,
              342.125,
              386.4,
              566.9499999999999
            ],
            "gains": [
              0.7150000000000001,
              1.215,
              0.9750000000000001,
              0.27,
              0.078
            ],
            "sourceBalance": 0.13
          }
        ],
        "model": 2
      }
    },
    "output_gain": 1.0885975360870361
  },
  "source_cycle_wav_sha256": "3a8c07f472a5d0667bf38bd063524b7450f8bd3cb2b9ccb1d7670893d70b814b",
  "playback": {
    "cycles": 2,
    "onset_frames": 0,
    "release_frames": 3528,
    "silence_frames": 11025,
    "rounding": "nearest integer, ties to even"
  }
}
"""

    private const val NATIVE_2 = """{
  "engine": "MURMUR",
  "version": 1,
  "name": "SHORT ANSWER",
  "voice": "PLUCK",
  "macros": {
    "MOUTH": 0.3499999940395355,
    "EDGE": 0.550000011920929,
    "BREATH": 0.10000000149011612,
    "MOTION": 0.3499999940395355,
    "BODY": 0.44999998807907104,
    "HOLD": 0
  },
  "midi": 36,
  "velocity": 0.6499999761581421,
  "config": {
    "routing": "PAIRS",
    "ratios": [
      1,
      1,
      4,
      3
    ],
    "trajectory": "ATTACK",
    "direction": 1,
    "referenceSeconds": 0.48,
    "attack": 0.002,
    "decay": 0.24,
    "sustain": 0.08,
    "release": 0.35,
    "targets": [
      {
        "name": "pluck Closed hum",
        "centers": [
          283.5,
          840,
          1680.0000000000002,
          2860.0000000000005,
          3800
        ],
        "widths": [
          90,
          162,
          221,
          280,
          425
        ],
        "gains": [
          1.1700000000000002,
          0.7200000000000001,
          0.325,
          0.18000000000000002,
          0.065
        ],
        "sourceBalance": 0.05000000000000001
      },
      {
        "name": "pluck Rounded",
        "centers": [
          346.5,
          787.5,
          1568.0000000000002,
          2585,
          3500
        ],
        "widths": [
          108,
          144,
          204,
          280,
          382.5
        ],
        "gains": [
          1.105,
          0.81,
          0.39,
          0.18000000000000002,
          0.0975
        ],
        "sourceBalance": 0.098
      },
      {
        "name": "pluck Open",
        "centers": [
          798,
          1312.5,
          2744.0000000000005,
          3630.0000000000005,
          4400
        ],
        "widths": [
          171,
          180,
          255,
          304,
          442
        ],
        "gains": [
          0.8450000000000001,
          1.26,
          0.8450000000000001,
          0.36000000000000004,
          0.0975
        ],
        "sourceBalance": 0.23000000000000004
      },
      {
        "name": "pluck Narrow",
        "centers": [
          378,
          2152.5,
          3192.0000000000005,
          4180,
          4800
        ],
        "widths": [
          99,
          207,
          297.5,
          336,
          493
        ],
        "gains": [
          0.7150000000000001,
          1.215,
          0.9750000000000001,
          0.27,
          0.078
        ],
        "sourceBalance": 0.13
      }
    ],
    "model": 4,
    "body": "STEADY_ROOT_V1"
  }
}
"""

    private const val HEARD_2 = """{
  "renderer": "snipsnap-murmur-finite-root",
  "version": 1,
  "source_sha256": "58642d41290485af6497ab80297ac0fe992eb1a5f8bc6663f271cce161b39142",
  "root_policy": "quadrature-075-c3-c4-smoothstep",
  "variant": "STEADY_WITH_ROOT",
  "patch": {
    "engine": "MURMUR",
    "version": 1,
    "name": "SHORT ANSWER",
    "voice": "PLUCK",
    "macros": {
      "MOUTH": 0.3499999940395355,
      "EDGE": 0.550000011920929,
      "BREATH": 0.10000000149011612,
      "MOTION": 0.3499999940395355,
      "BODY": 0.44999998807907104,
      "HOLD": 0
    },
    "midi": 36,
    "velocity": 0.6499999761581421,
    "config": {
      "routing": "PAIRS",
      "ratios": [
        1,
        1,
        4,
        3
      ],
      "trajectory": "ATTACK",
      "direction": 1,
      "referenceSeconds": 0.48,
      "attack": 0.002,
      "decay": 0.24,
      "sustain": 0.08,
      "release": 0.35,
      "targets": [
        {
          "name": "pluck Closed hum",
          "centers": [
            283.5,
            840,
            1680.0000000000002,
            2860.0000000000005,
            3800
          ],
          "widths": [
            90,
            162,
            221,
            280,
            425
          ],
          "gains": [
            1.1700000000000002,
            0.7200000000000001,
            0.325,
            0.18000000000000002,
            0.065
          ],
          "sourceBalance": 0.05000000000000001
        },
        {
          "name": "pluck Rounded",
          "centers": [
            346.5,
            787.5,
            1568.0000000000002,
            2585,
            3500
          ],
          "widths": [
            108,
            144,
            204,
            280,
            382.5
          ],
          "gains": [
            1.105,
            0.81,
            0.39,
            0.18000000000000002,
            0.0975
          ],
          "sourceBalance": 0.098
        },
        {
          "name": "pluck Open",
          "centers": [
            798,
            1312.5,
            2744.0000000000005,
            3630.0000000000005,
            4400
          ],
          "widths": [
            171,
            180,
            255,
            304,
            442
          ],
          "gains": [
            0.8450000000000001,
            1.26,
            0.8450000000000001,
            0.36000000000000004,
            0.0975
          ],
          "sourceBalance": 0.23000000000000004
        },
        {
          "name": "pluck Narrow",
          "centers": [
            378,
            2152.5,
            3192.0000000000005,
            4180,
            4800
          ],
          "widths": [
            99,
            207,
            297.5,
            336,
            493
          ],
          "gains": [
            0.7150000000000001,
            1.215,
            0.9750000000000001,
            0.27,
            0.078
          ],
          "sourceBalance": 0.13
        }
      ],
      "model": 2
    }
  },
  "output_gain": 3.3389108180999756
}
"""

    private val sources = listOf(
        Source("finite-hollow-wire", "HOLLOW WIRE NOTE", NATIVE_0, "ec8ee43ab080ed1c0f97ef578206f3a7a95d417b21cceaf74bafa7b496d492fc", 36..96,
            Reference(2.000858783721924f, "6ccd1ecab8e2124f0aca17e9b6769a6cbda8edea060bf51f12eaae6b64a8b8e5",
                "22e71cbd248a75c99aeba6521bdbf58553fa7911baefc1ee767ebf1841bb137d",
                "22e71cbd248a75c99aeba6521bdbf58553fa7911baefc1ee767ebf1841bb137d", HEARD_0, "3305e501a998a3de921a7b2cae28bef05f14798956a3538b369a394710c282b3")),
        Source("held-hollow-wire", "HOLLOW WIRE HOLD", NATIVE_1, "54227024db276b6492a06db188ba665a1028fcd54d24f91527fd691eaece2693", 36..96,
            Reference(1.0885975360870361f, "29912f67bf0ed9ca1aaa17d404a73040160ad4aefc786dcb9fbbbd910d887974",
                "3a8c07f472a5d0667bf38bd063524b7450f8bd3cb2b9ccb1d7670893d70b814b",
                "b595fd3d5c3a7fde5e3f68244bd7fdce84ab2305de70ad485d067851abfc4ac9", HEARD_1, "9f8d84b77766810ce69dfc740fb8d3d33a462323cd872805f37fff19d61e22f1", presentation = Presentation(cycles = 2, releaseFrames = 3528, silenceFrames = 11025))),
        Source("approved-c2", "STEADY ROOT C2", NATIVE_2, "f0676aad9c5ea0123ed93121b8112e5e77ea3dfd5f787816e9385f40efb1ba15", 36..54,
            Reference(3.3389108180999756f, "3e1eccc0f7e1ee9aad90852b1eae9b730deedd7f5e8baaf3c2044fde216468b6",
                "f444994044e6b2b5f4d2403ae8f7403aa927c5c9ae5154c2df94719d15074ca3",
                "f444994044e6b2b5f4d2403ae8f7403aa927c5c9ae5154c2df94719d15074ca3", HEARD_2, "e7b1ef1c6ab45fd9ca07e070e5801aec2ae1af26d32cf8015dca956961d9e61a")),
    )

    fun all(): List<Source> = sources.toList()
    fun byId(id: String): Source = sources.single { it.id == id }
    fun presets(): List<MurmurPatch> = sources.map { it.factoryPatch() }
}
