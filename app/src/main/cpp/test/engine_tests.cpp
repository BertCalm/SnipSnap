// The native engines, driven by hand. See CMakeLists.txt.
#include <algorithm>
#include <cmath>
#include <limits>
#include <thread>
#include <vector>

#include "PadEngine.h"
#include "ParameterSmoother.h"
#include "PrintBuffer.h"
#include "SpscRing.h"
#include "SurfaceEngine.h"
#include "check.h"

using namespace snipsnap;

namespace {

constexpr int32_t kRate = 48000;  // both engines default to 48 kHz before start()

/** Run one callback of `frames` and return the interleaved stereo it wrote. */
std::vector<float> callback(PadEngine& e, int32_t frames) {
    std::vector<float> out(static_cast<size_t>(frames) * 2, 123.0f);
    e.onAudioReady(nullptr, out.data(), frames);
    return out;
}

std::vector<float> callback(SurfaceEngine& e, int32_t frames) {
    std::vector<float> out(static_cast<size_t>(frames) * 2, 123.0f);
    e.onAudioReady(nullptr, out.data(), frames);
    return out;
}

float peak(const std::vector<float>& v) {
    float p = 0.0f;
    for (float x : v) p = std::max(p, std::fabs(x));
    return p;
}

std::vector<int32_t> ended(PadEngine& e) {
    int32_t buf[256];
    const size_t n = e.drainEnded(buf, 256);
    return std::vector<int32_t>(buf, buf + n);
}

/** A mono ramp i/1000 (inside full scale - the mix clamps at 1), so a frame's value tells you which frame was read. */
std::vector<float> ramp(int64_t frames) {
    std::vector<float> v(static_cast<size_t>(frames));
    for (int64_t i = 0; i < frames; ++i) v[static_cast<size_t>(i)] = static_cast<float>(i) / 1000.0f;
    return v;
}

PadCommand noteOn(int32_t id, int32_t sample, int64_t start, int64_t end, float gL = 1.0f, float gR = 1.0f, double pitch = 1.0) {
    PadCommand c;
    c.type = PadCommand::Type::NoteOn;
    c.voiceId = id;
    c.sample = sample;
    c.start = start;
    c.end = end;
    c.gainL = gL;
    c.gainR = gR;
    c.pitch = pitch;
    return c;
}

PadCommand reversed(int32_t id, int32_t sample, int64_t start, int64_t end) {
    PadCommand c = noteOn(id, sample, start, end);
    c.reverse = true;
    return c;
}

PadCommand looping(int32_t id, int32_t sample, int64_t end, int64_t loopStart) {
    PadCommand c = noteOn(id, sample, 0, end);
    c.loopStart = loopStart;
    return c;
}

PadCommand stop(int32_t id, float fadeMs) {
    PadCommand c;
    c.type = PadCommand::Type::Stop;
    c.voiceId = id;
    c.fadeMs = fadeMs;
    return c;
}

PadCommand allOff(float fadeMs) {
    PadCommand c;
    c.type = PadCommand::Type::AllOff;
    c.fadeMs = fadeMs;
    return c;
}

/** A pad engine with one 1000-frame mono ramp (index 0) and one 100-frame stereo sample (index 1), adopted. */
PadEngine& seeded() {
    static PadEngine* e = nullptr;
    delete e;
    e = new PadEngine(kRate);
    e->beginBank();
    e->addSample(ramp(1000), 1, kRate);
    std::vector<float> stereo(200);
    for (int i = 0; i < 100; ++i) { stereo[2 * i] = 0.5f; stereo[2 * i + 1] = -0.25f; }
    e->addSample(std::move(stereo), 2, kRate);
    e->commitBank();
    callback(*e, 8);  // adopt
    ended(*e);
    return *e;
}

}  // namespace

// ---- SpscRing ------------------------------------------------------------------

TEST(ring_keeps_order_and_drops_when_full) {
    SpscRing<int, 4> ring;  // three usable slots
    CHECK(ring.push(1));
    CHECK(ring.push(2));
    CHECK(ring.push(3));
    CHECK(!ring.push(4));  // full: dropped, not blocked
    int v = 0;
    CHECK(ring.pop(v)); CHECK_EQ(v, 1);
    CHECK(ring.pop(v)); CHECK_EQ(v, 2);
    CHECK(ring.push(5));  // room again, and the index wraps
    CHECK(ring.pop(v)); CHECK_EQ(v, 3);
    CHECK(ring.pop(v)); CHECK_EQ(v, 5);
    CHECK(!ring.pop(v));
}

// ---- ParameterSmoother ---------------------------------------------------------

TEST(smoother_glides_and_settles_and_snaps) {
    ParameterSmoother s;
    s.configure(1000.0f, 48000.0f);
    s.snap(0.0f);
    s.setTarget(1.0f);
    const float first = s.next();
    CHECK(first > 0.0f && first < 1.0f);
    for (int i = 0; i < 48000; ++i) s.next();
    CHECK_NEAR(s.value(), 1.0f, 1e-4);
    s.snap(0.25f);
    CHECK_NEAR(s.value(), 0.25f, 1e-7);
    CHECK_NEAR(s.next(), 0.25f, 1e-7);  // target snapped too: no glide

    ParameterSmoother slow, fast;
    slow.configure(5.0f, 48000.0f);
    fast.configure(500.0f, 48000.0f);
    slow.setTarget(1.0f);
    fast.setTarget(1.0f);
    CHECK(fast.next() > slow.next());
}

// ---- PrintBuffer ---------------------------------------------------------------

TEST(print_buffer_records_to_its_ceiling_then_is_done) {
    PrintBuffer p;
    CHECK(p.state() == PrintBuffer::State::Idle);
    CHECK(p.arm(10));
    CHECK(p.state() == PrintBuffer::State::Recording);
    float a[6] = {1, 2, 3, 4, 5, 6};
    CHECK(p.record(a, 6));
    CHECK(!p.record(a, 6));  // fills at 10 and stops itself
    CHECK(p.state() == PrintBuffer::State::Done);
    CHECK_EQ(static_cast<int>(p.framesWritten()), 10);
    CHECK_NEAR(p.data()[9], 4.0f, 1e-7);
    p.clear();
    CHECK(p.state() == PrintBuffer::State::Idle);
}

TEST(print_buffer_stop_lands_on_the_callback_and_rearm_is_refused_meanwhile) {
    PrintBuffer p;
    CHECK(p.arm(100));
    float a[4] = {1, 1, 1, 1};
    p.record(a, 4);
    CHECK(!p.arm(50));  // recording: the callback may be writing
    p.requestStop();
    CHECK(p.state() == PrintBuffer::State::Stopping);
    CHECK(!p.arm(50));  // still the callback's until it says Done
    CHECK(!p.record(a, 4));  // the callback makes the last move
    CHECK(p.state() == PrintBuffer::State::Done);
    CHECK_EQ(static_cast<int>(p.framesWritten()), 4);
    CHECK(p.arm(5));  // Done: ours again
    p.clear();
}

// ---- PadEngine -----------------------------------------------------------------

TEST(pad_engine_plays_the_window_with_its_gains_and_reports_the_end) {
    PadEngine& e = seeded();
    CHECK(e.pushCommand(noteOn(7, 0, 100, 104, 0.5f, 0.25f)));
    auto out = callback(e, 8);
    // Frames 100..103 of the ramp, left at half, right at a quarter; then silence.
    CHECK_NEAR(out[0], 0.05f, 1e-6); CHECK_NEAR(out[1], 0.025f, 1e-6);
    CHECK_NEAR(out[6], 0.0515f, 1e-6); CHECK_NEAR(out[7], 0.02575f, 1e-6);
    CHECK_NEAR(out[8], 0.0f, 1e-7);
    const auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 1);
    if (!ids.empty()) CHECK_EQ(ids[0], 7);
}

TEST(pad_engine_repitches_by_the_ratio) {
    PadEngine& e = seeded();
    e.pushCommand(noteOn(1, 0, 0, 100, 1.0f, 1.0f, 2.0));
    auto out = callback(e, 100);
    CHECK_NEAR(out[2 * 10], 0.020f, 1e-6);  // frame 10 reads sample 20 at double speed
    CHECK_NEAR(out[2 * 60], 0.0f, 1e-7);   // 100 frames at 2x is over by frame 50
    CHECK_EQ(static_cast<int>(ended(e).size()), 1);
}

TEST(pad_engine_reads_stereo_as_stereo) {
    PadEngine& e = seeded();
    e.pushCommand(noteOn(2, 1, 0, 100));
    auto out = callback(e, 4);
    CHECK_NEAR(out[0], 0.5f, 1e-6);
    CHECK_NEAR(out[1], -0.25f, 1e-6);
}

TEST(pad_engine_choke_is_a_fade_not_a_cut) {
    PadEngine& e = seeded();
    // A flat region: read the ramp far in with tiny gain so the level is ~constant.
    e.pushCommand(noteOn(3, 0, 500, 1000, 0.002f, 0.002f));
    callback(e, 4);
    e.pushCommand(stop(3, 1.0f));  // 1 ms = 48 frames
    auto out = callback(e, 64);
    // Strictly falling over the fade, silent after it, reported once.
    CHECK(out[0] > out[2 * 20]);
    CHECK(out[2 * 20] > out[2 * 40]);
    CHECK_NEAR(out[2 * 60], 0.0f, 1e-7);
    const auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 1);
    if (!ids.empty()) CHECK_EQ(ids[0], 3);
}

TEST(pad_engine_all_off_fades_every_voice) {
    PadEngine& e = seeded();
    for (int32_t id = 10; id < 14; ++id) e.pushCommand(noteOn(id, 0, 500, 1000, 0.001f, 0.001f));
    callback(e, 4);
    e.pushCommand(allOff(1.0f));
    auto out = callback(e, 64);
    CHECK_NEAR(peak(std::vector<float>(out.begin() + 120, out.end())), 0.0f, 1e-7);
    auto ids = ended(e);
    std::sort(ids.begin(), ids.end());
    CHECK_EQ(static_cast<int>(ids.size()), 4);
    if (ids.size() == 4) { CHECK_EQ(ids[0], 10); CHECK_EQ(ids[3], 13); }
}

TEST(pad_engine_a_stale_command_never_plays_by_index) {
    PadEngine& e = seeded();
    // Queued against the seeded bank (sample 0 = the ramp) ...
    e.pushCommand(noteOn(20, 0, 0, 100));
    // ... then a new bank commits before the callback runs: index 0 is now a loud constant.
    e.beginBank();
    e.addSample(std::vector<float>(100, 0.9f), 1, kRate);
    e.commitBank();
    auto out = callback(e, 16);
    CHECK_NEAR(peak(out), 0.0f, 1e-7);  // honest silence
    const auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 1);  // and the allocator is told
    if (!ids.empty()) CHECK_EQ(ids[0], 20);
    // A command pushed after the commit is for the new bank and plays.
    e.pushCommand(noteOn(21, 0, 0, 100));
    out = callback(e, 4);
    CHECK_NEAR(out[0], 0.9f, 1e-6);
}

TEST(pad_engine_a_bank_swap_silences_and_reports_every_voice) {
    PadEngine& e = seeded();
    e.pushCommand(noteOn(30, 0, 0, 1000));
    e.pushCommand(noteOn(31, 1, 0, 100));
    callback(e, 4);
    CHECK_EQ(static_cast<int>(ended(e).size()), 0);
    e.beginBank();
    e.addSample(ramp(10), 1, kRate);
    e.commitBank();
    auto out = callback(e, 8);
    CHECK_NEAR(peak(out), 0.0f, 1e-7);
    auto ids = ended(e);
    std::sort(ids.begin(), ids.end());
    CHECK_EQ(static_cast<int>(ids.size()), 2);
    if (ids.size() == 2) { CHECK_EQ(ids[0], 30); CHECK_EQ(ids[1], 31); }
}

TEST(pad_engine_a_second_swap_waits_until_the_retiree_is_collected) {
    PadEngine& e = seeded();
    e.beginBank(); e.addSample(std::vector<float>(100, 0.1f), 1, kRate); e.commitBank();
    callback(e, 4);  // adopts bank B, retires the seeded one (uncollected: no commit since)
    e.beginBank(); e.addSample(std::vector<float>(100, 0.2f), 1, kRate);
    // commitBank collects the retiree, then parks C.
    e.commitBank();
    e.pushCommand(noteOn(40, 0, 0, 100));
    auto out = callback(e, 4);
    CHECK_NEAR(out[0], 0.2f, 1e-6);  // C adopted, the command was stamped for C
}

TEST(pad_engine_steals_at_the_cap_and_says_so) {
    PadEngine& e = seeded();
    for (int32_t id = 100; id < 100 + PadEngine::kMaxVoices; ++id) e.pushCommand(noteOn(id, 0, 0, 1000, 0.001f, 0.001f));
    callback(e, 4);
    CHECK_EQ(static_cast<int>(ended(e).size()), 0);
    e.pushCommand(noteOn(999, 0, 0, 1000, 0.001f, 0.001f));
    callback(e, 4);
    const auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 1);
    if (!ids.empty()) CHECK_EQ(ids[0], 100);  // the oldest
}

TEST(pad_engine_refuses_a_bad_index_or_empty_window_by_reporting_the_voice) {
    PadEngine& e = seeded();
    e.pushCommand(noteOn(50, 9, 0, 100));   // no such sample
    e.pushCommand(noteOn(51, 0, 900, 900)); // empty window
    e.pushCommand(noteOn(52, 0, 990, 5000)); // end past the sample: clamped, plays 10 frames
    auto out = callback(e, 16);
    CHECK_NEAR(out[0], 0.990f, 1e-6);
    auto ids = ended(e);
    std::sort(ids.begin(), ids.end());
    CHECK_EQ(static_cast<int>(ids.size()), 3);
}

TEST(pad_engine_a_looping_voice_sustains_and_wraps_to_its_loop_start) {
    PadEngine& e = seeded();
    // The ramp's first 10 frames, looping from frame 9 back to 4: 0..8, 4..8, 4..8 ...
    e.pushCommand(looping(60, 0, 10, 4));
    auto out = callback(e, 20);
    CHECK_NEAR(out[2 * 8], 0.008f, 1e-6);
    CHECK_NEAR(out[2 * 9], 0.004f, 1e-6);   // wrapped
    CHECK_NEAR(out[2 * 13], 0.008f, 1e-6);
    CHECK_NEAR(out[2 * 14], 0.004f, 1e-6);  // and again
    CHECK_EQ(static_cast<int>(ended(e).size()), 0);  // still sounding
    // A release is a Stop with the instrument's release as its fade.
    e.pushCommand(stop(60, 1.0f));
    callback(e, 64);
    const auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 1);
    // A loop that would be empty plays once and ends.
    e.pushCommand(looping(61, 0, 10, 9));
    callback(e, 20);
    CHECK_EQ(static_cast<int>(ended(e).size()), 1);
    // A fast voice over a two-frame loop (frames 8..9, 4x speed) crosses the
    // end more than once per frame and must keep sustaining, never end.
    PadCommand fast = looping(62, 0, 10, 8);
    fast.pitch = 4.0;
    e.pushCommand(fast);
    auto held = callback(e, 64);
    CHECK(peak(held) > 0.0f);
    CHECK_EQ(static_cast<int>(ended(e).size()), 0);
}

TEST(latency_is_minus_one_with_no_stream) {
    // The readout's "the device would not say" case: no stream open, so
    // neither engine may dereference one to answer.
    PadEngine pads(kRate);
    CHECK(pads.latencyMillis() < 0.0);
    SurfaceEngine surface(kRate);
    CHECK(surface.latencyMillis() < 0.0);
}

// ---- SurfaceEngine -------------------------------------------------------------

TEST(surface_engine_is_silent_until_gated_and_loops_its_sample) {
    SurfaceEngine e(kRate);
    std::vector<float> tone(100, 0.5f);
    e.loadSample(tone.data(), tone.size(), kRate);
    auto out = callback(e, 64);
    CHECK_NEAR(peak(out), 0.0f, 1e-7);  // gate closed, nothing loaded until adopted anyway
    ControlFrame f;
    f.mode = 0;
    f.gate = true;
    e.pushControl(f);
    float p = 0.0f;
    for (int i = 0; i < 200; ++i) p = std::max(p, peak(callback(e, 64)));  // the gate glides open
    CHECK(p > 0.05f);
    f.gate = false;
    e.pushControl(f);
    for (int i = 0; i < 400; ++i) out = callback(e, 64);
    CHECK(peak(out) < 1e-3f);  // and closed again
}

TEST(surface_engine_prints_the_mono_bus_and_finishes_on_the_callback) {
    SurfaceEngine e(kRate);
    std::vector<float> tone(100, 0.5f);
    e.loadSample(tone.data(), tone.size(), kRate);
    ControlFrame f;
    f.gate = true;
    e.pushControl(f);
    for (int i = 0; i < 100; ++i) callback(e, 64);
    CHECK(e.armPrint(200));
    CHECK(!e.armPrint(200));  // not while recording
    callback(e, 64);
    callback(e, 64);
    CHECK(e.printState() == PrintBuffer::State::Recording);
    e.requestStopPrint();
    callback(e, 64);
    CHECK(e.printState() == PrintBuffer::State::Done);
    CHECK_EQ(static_cast<int>(e.printFrames()), 128);
    // The print is the bus: a mono copy of what went to the left channel.
    auto out = callback(e, 1);
    CHECK(std::fabs(e.printData()[127]) > 0.01f);
    CHECK_NEAR(e.printData()[127], e.printData()[126], 0.05f);
    (void)out;
    e.clearPrint();
    CHECK(e.printState() == PrintBuffer::State::Idle);
}

TEST(surface_engine_morph_blends_the_corners) {
    SurfaceEngine e(kRate);
    // Corner A = full cutoff and no drive, corner D = no cutoff (dark): the
    // morphed macro at A must sound louder/brighter than at D on a square.
    e.setCorner(0, MacroState{0.5f, 1.0f, 0.0f, 0.0f});
    e.setCorner(3, MacroState{0.5f, 0.0f, 0.0f, 0.0f});
    std::vector<float> square(100);
    for (int i = 0; i < 100; ++i) square[i] = (i % 10 < 5) ? 0.5f : -0.5f;
    e.loadSample(square.data(), square.size(), kRate);
    ControlFrame atA; atA.mode = 2; atA.gate = true; atA.a = 1; atA.b = atA.c = atA.d = 0;
    e.pushControl(atA);
    float pa = 0.0f;
    for (int i = 0; i < 400; ++i) pa = std::max(pa, peak(callback(e, 64)));
    ControlFrame atD = atA; atD.a = 0; atD.d = 1;
    e.pushControl(atD);
    float pd = 0.0f;
    for (int i = 0; i < 400; ++i) callback(e, 64);  // let the cutoff glide
    for (int i = 0; i < 100; ++i) pd = std::max(pd, peak(callback(e, 64)));
    CHECK(pa > pd);
}

TEST(surface_engine_survives_a_reading_that_is_not_a_number) {
    // A gravity sensor may report NaN, and TILT is resonance in XYZ. Before
    // the door, one such frame was permanent: the smoothers latch NaN
    // (`v += k * (NaN - v)`), the filter's ic1eq_/ic2eq_ go with them, and
    // the surface plays NaN for the rest of the session even after the
    // reading comes back.
    SurfaceEngine e(kRate);
    std::vector<float> tone(100, 0.5f);
    e.loadSample(tone.data(), tone.size(), kRate);
    ControlFrame good;
    good.mode = 1;
    good.x = 0.5f; good.y = 1.0f; good.tilt = 0.2f; good.z = 0.0f;
    good.gate = true;
    e.pushControl(good);
    float before = 0.0f;
    for (int i = 0; i < 300; ++i) before = std::max(before, peak(callback(e, 64)));
    CHECK(before > 0.05f);

    const float nan = std::nanf("");
    ControlFrame bad = good;
    bad.tilt = nan;
    bad.x = nan;
    bad.y = std::numeric_limits<float>::infinity();
    e.pushControl(bad);
    for (int i = 0; i < 200; ++i) {
        for (float v : callback(e, 64)) CHECK(std::isfinite(v));
    }

    // And the good reading still works afterwards: nothing latched.
    e.pushControl(good);
    float after = 0.0f;
    for (int i = 0; i < 300; ++i) after = std::max(after, peak(callback(e, 64)));
    CHECK(after > 0.05f);
}

TEST(surface_engine_corner_that_is_not_a_number_falls_back) {
    // The same door on the corners: a bad one reads as its default rather
    // than poisoning every morph that touches it.
    SurfaceEngine e(kRate);
    std::vector<float> tone(100, 0.5f);
    e.loadSample(tone.data(), tone.size(), kRate);
    const float nan = std::nanf("");
    e.setCorner(0, MacroState{nan, nan, nan, nan});
    ControlFrame f;
    f.mode = 2;
    f.gate = true;
    f.a = 1.0f; f.b = f.c = f.d = 0.0f;
    e.pushControl(f);
    float p = 0.0f;
    for (int i = 0; i < 400; ++i) {
        auto out = callback(e, 64);
        for (float v : out) CHECK(std::isfinite(v));
        p = std::max(p, peak(out));
    }
    CHECK(p > 0.05f);  // cutoff fell back to wide open, not to NaN
}

TEST(pad_engine_refuses_a_speed_that_is_not_a_speed) {
    // render() only guards the far end of the read: a zero speed freezes a
    // voice on one frame for ever and a negative one walks pos off the
    // front of the buffer. Neither is a note.
    PadEngine& e = seeded();
    const double bad[3] = {0.0, -1.0, std::nan("")};
    int32_t id = 1;
    for (double p : bad) {
        e.pushCommand(noteOn(id, 0, 0, 1000, 1.0f, 1.0f, p));
        auto out = callback(e, 64);
        CHECK_NEAR(peak(out), 0.0f, 1e-7);
        auto ids = ended(e);
        CHECK_EQ(static_cast<int>(ids.size()), 1);
        CHECK_EQ(ids[0], id);
        ++id;
    }
    // A gain that is not a number would make the whole mix one.
    e.pushCommand(noteOn(9, 0, 500, 1000, std::nanf(""), std::nanf("")));
    for (float v : callback(e, 64)) CHECK(std::isfinite(v));
    // And an honest note still plays.
    e.pushCommand(noteOn(10, 0, 500, 1000));
    CHECK(peak(callback(e, 64)) > 0.4f);
}

TEST(pad_engine_a_closed_stream_takes_its_voices_with_it) {
    // A route change closes the stream and the UI reopens: without the
    // sweep in stop(), the reopened callback picks those notes up
    // mid-sample and fires every command queued while there was no stream.
    PadEngine& e = seeded();
    e.pushCommand(noteOn(3, 0, 500, 1000));
    CHECK(peak(callback(e, 64)) > 0.4f);
    e.pushCommand(noteOn(4, 0, 0, 1000));  // queued, never rendered
    e.stop();
    auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 2);
    CHECK(std::find(ids.begin(), ids.end(), 3) != ids.end());  // the voice that was sounding
    CHECK(std::find(ids.begin(), ids.end(), 4) != ids.end());  // and the one still queued
    CHECK_NEAR(peak(callback(e, 64)), 0.0f, 1e-7);
}

TEST(print_buffer_refuses_to_be_cleared_under_the_callback) {
    // `arm` has refused a live print since the first round; `clear` frees
    // the same frames, so it refuses on the same terms.
    PrintBuffer p;
    std::vector<float> block(32, 0.5f);
    CHECK(p.arm(1000));
    CHECK(p.record(block.data(), block.size()));
    CHECK(!p.clear());
    CHECK(p.state() == PrintBuffer::State::Recording);
    CHECK_EQ(static_cast<int>(p.framesWritten()), 32);
    p.requestStop();
    CHECK(!p.record(block.data(), block.size()));
    CHECK(p.state() == PrintBuffer::State::Done);
    CHECK(p.clear());
    CHECK(p.state() == PrintBuffer::State::Idle);
}

// ---- reverse and the group start -----------------------------------------------

TEST(pad_engine_plays_a_window_backwards) {
    // The ramp is i/1000, so the value read *is* the frame index: a
    // backwards read of frames 100..200 starts near 0.199 and falls.
    PadEngine& e = seeded();
    e.pushCommand(reversed(1, 0, 100, 200));
    auto out = callback(e, 8);
    CHECK_NEAR(out[0], 0.199f, 1e-4);   // the last frame of the window, first
    CHECK_NEAR(out[2], 0.198f, 1e-4);   // then down, one frame at a time
    CHECK(out[2] < out[0]);
    // It ends at the window's start rather than running off the front.
    for (int i = 0; i < 40; ++i) callback(e, 8);
    auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 1);
    CHECK_EQ(ids[0], 1);
    CHECK_NEAR(peak(callback(e, 8)), 0.0f, 1e-7);
}

TEST(pad_engine_a_backwards_loop_wraps_the_other_way) {
    // Forwards a loop falls off the top and returns to loopStart;
    // backwards it falls off the bottom at loopStart and returns to the
    // last frame. Either way it never ends.
    PadEngine& e = seeded();
    PadCommand c = reversed(2, 0, 0, 200);
    c.loopStart = 150;
    e.pushCommand(c);
    float lowest = 1.0f, highest = 0.0f;
    for (int i = 0; i < 60; ++i) {
        for (float v : callback(e, 64)) {
            if (v > 0.0f) {
                lowest = std::min(lowest, v);
                highest = std::max(highest, v);
            }
        }
    }
    // It stayed inside the loop: never below frame 150, never above 199.
    CHECK(lowest > 0.149f);
    CHECK(highest < 0.200f);
    CHECK(ended(e).empty());  // a sustaining loop reports no ending
}

TEST(pad_engine_a_group_reaches_the_callback_whole) {
    // Three layers of one sample must start in the same callback: pushed
    // one at a time, a callback landing between two of them starts one a
    // buffer late, which is heard as a flam.
    PadEngine& e = seeded();
    PadCommand group[3];
    for (int i = 0; i < 3; ++i) {
        group[i] = noteOn(10 + i, 0, 500, 1000, 0.25f, 0.25f);
    }
    group[2].reverse = true;  // one layer backwards, the rest forward
    CHECK(e.pushCommands(group, 3));
    auto out = callback(e, 4);
    // Three voices at a quarter each: two forward reads of frame 500 plus
    // one backward read of frame 999, all in the first frame of one buffer.
    CHECK_NEAR(out[0], 0.25f * (0.5f + 0.5f + 0.999f), 2e-3);
    CHECK(ended(e).empty());
}

TEST(ring_publishes_a_group_whole_or_not_at_all) {
    SpscRing<int, 8> ring;
    const int three[3] = {1, 2, 3};
    CHECK(ring.pushAll(three, 3));
    int got = 0;
    for (int expected : three) {
        CHECK(ring.pop(got));
        CHECK_EQ(got, expected);
    }
    CHECK(!ring.pop(got));
    // Seven usable slots: six taken, a group of two will not fit, and the
    // refusal writes nothing rather than half the group.
    const int six[6] = {1, 2, 3, 4, 5, 6};
    CHECK(ring.pushAll(six, 6));
    const int two[2] = {7, 8};
    CHECK(!ring.pushAll(two, 2));
    CHECK(ring.push(7));   // one still fits
    CHECK(!ring.push(8));  // and now it is full
    for (int i = 1; i <= 7; ++i) {
        CHECK(ring.pop(got));
        CHECK_EQ(got, i);  // nothing from the refused group is in here
    }
    CHECK(!ring.pop(got));
}

TEST(ring_never_hands_a_consumer_half_a_group) {
    // The property the callback actually depends on, and the only one a
    // single-threaded case cannot see: when the consumer drains everything
    // available - `while (pop(c))`, exactly what onAudioReady does - it
    // never stops in the middle of a group. Push one at a time instead and
    // this fails within a few thousand rounds.
    constexpr int kGroup = 4;
    constexpr int kGroups = 20000;
    SpscRing<int, 64> ring;
    std::atomic<bool> producerDone{false};

    std::thread producer([&] {
        for (int g = 0; g < kGroups; ++g) {
            int items[kGroup];
            for (int i = 0; i < kGroup; ++i) items[i] = g;
            while (!ring.pushAll(items, kGroup)) std::this_thread::yield();
        }
        producerDone.store(true, std::memory_order_release);
    });

    long long taken = 0;
    int split = 0;
    int value = 0;
    while (taken < static_cast<long long>(kGroups) * kGroup) {
        int got = 0;
        while (ring.pop(got)) {
            ++taken;
            value = got;
        }
        // The drain ended: whatever the producer had published was whole,
        // so the running count must sit on a group boundary.
        if (taken % kGroup != 0) ++split;
        if (producerDone.load(std::memory_order_acquire) && taken == 0) break;
    }
    producer.join();
    CHECK_EQ(split, 0);
    CHECK_EQ(static_cast<int>(taken), kGroups * kGroup);
    CHECK_EQ(value, kGroups - 1);  // and in order, to the last group
}

int main() { return check::runAll(); }
