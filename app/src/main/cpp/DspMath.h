#pragma once

#include <cmath>

namespace snipsnap {

/**
 * 0..1, and NaN-safe: the test is written `!(v > 0)` rather than `v < 0`
 * so a NaN lands on 0 instead of sailing through - `NaN < 0` and
 * `NaN > 1` are both false, so the obvious spelling passes NaN on.
 *
 * One copy for every engine (SurfaceEngine, LiveSnapEngine and GRAIN's
 * arithmetic in Grain.h), so the spelling can't drift apart.
 */
inline float clamp01(float v) { return !(v > 0.0f) ? 0.0f : (v > 1.0f ? 1.0f : v); }

/**
 * A macro arriving from outside (a control frame over JNI, a corner from
 * the sidecar). A non-finite reading is not a value, so it reads as
 * `fallback`; everything else is clamped.
 *
 * This is the door, and it matters more in the live engines than in most
 * places: the smoothers and the filters carry state across callbacks, so
 * one NaN does not pass through and leave - `value_ += k * (NaN - value_)`
 * is NaN for ever, the filter state goes with it, and the engine plays
 * NaN for the rest of the session even after the reading comes back.
 * There is a live way in: a gravity sensor that reports NaN feeds TILT,
 * and TILT is resonance in XYZ.
 */
inline float control01(float v, float fallback) { return std::isfinite(v) ? clamp01(v) : fallback; }

}  // namespace snipsnap
