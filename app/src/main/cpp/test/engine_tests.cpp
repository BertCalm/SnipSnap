// The native engines, driven by hand. See CMakeLists.txt.
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <thread>
#include <vector>

#include "Grain.h"
#include "LiveSnapEngine.h"
#include "PadEngine.h"
#include "ParameterSmoother.h"
#include "PrintBuffer.h"
#include "SpscRing.h"
#include "SurfaceEngine.h"
#include "check.h"
#include "measure.h"

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

std::vector<float> callback(LiveSnapEngine& e, int32_t frames) {
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

/** Frame i holds i*i/10000: a parabola, which a four-point cubic reproduces between frames and a straight line cannot. */
std::vector<float> parabola(int64_t frames) {
    std::vector<float> v(static_cast<size_t>(frames));
    for (int64_t i = 0; i < frames; ++i) v[static_cast<size_t>(i)] = static_cast<float>(i * i) / 10000.0f;
    return v;
}
PadEngine& banked(std::vector<float>&& frames, int32_t channels, int32_t rate) {
    static PadEngine* e = nullptr;
    delete e;
    e = new PadEngine(kRate);
    e->beginBank();
    e->addSample(std::move(frames), channels, rate);
    e->commitBank();
    callback(*e, 8);
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

TEST(print_buffer_stereo_counts_frames_and_cuts_on_a_frame_boundary) {
    // Two channels: the ceiling is in frames, the storage is interleaved,
    // and a copy that does not fit is cut between frames - never between
    // a frame's left and right, which would swap the channels for the
    // rest of the print.
    PrintBuffer p;
    CHECK(p.arm(3, 2));
    CHECK_EQ(p.channels(), 2);
    const float two[4] = {1.0f, -1.0f, 2.0f, -2.0f};
    CHECK(p.record(two, 2));
    CHECK_EQ(static_cast<int>(p.framesWritten()), 2);
    CHECK_EQ(static_cast<int>(p.samplesWritten()), 4);
    // Two more frames offered, one frame of room: the odd frame is dropped whole.
    CHECK(!p.record(two, 2));
    CHECK(p.state() == PrintBuffer::State::Done);
    CHECK_EQ(static_cast<int>(p.framesWritten()), 3);
    CHECK_EQ(static_cast<int>(p.samplesWritten()), 6);
    CHECK_NEAR(p.data()[4], 1.0f, 1e-6);
    CHECK_NEAR(p.data()[5], -1.0f, 1e-6);
    p.clear();
    // Generic callers can still arm a mono buffer.
    CHECK(p.arm(4));
    CHECK_EQ(p.channels(), 1);
    p.requestStop();
    p.record(two, 1);
    CHECK_EQ(static_cast<int>(p.framesWritten()), 0);
    p.clear();
}

TEST(print_buffer_arm_throws_rather_than_silently_truncating_an_impossible_reservation) {
    // What jni.cpp's armPrint try/catch actually guards against: `arm`
    // takes a plain size_t, unlike the bridge's jint-bounded parameter,
    // so it can be asked for more frames than a vector<float> can ever
    // hold. Proving the throw is real here is what makes that catch
    // load-bearing rather than defensive dead code - a JNI entry point
    // this jint-bounded can't itself manufacture the request, but
    // `PrintBuffer::arm` has no such ceiling, and nothing stops a future
    // caller (or an ABI change) from reaching it directly with one.
    PrintBuffer p;
    const size_t impossible = std::vector<float>().max_size() + 1;
    bool threw = false;
    try {
        p.arm(impossible, 2);
    } catch (const std::exception&) {
        threw = true;
    }
    CHECK(threw);
    // Refused, not half-armed: still Idle, nothing to clear or leak.
    CHECK(p.state() == PrintBuffer::State::Idle);
}

// ---- PadEngine -----------------------------------------------------------------

TEST(pad_engine_counts_the_frames_it_has_rendered) {
    PadEngine e(kRate);
    CHECK_EQ(e.framesRendered(), int64_t{0});
    callback(e, 128);
    callback(e, 64);
    CHECK_EQ(e.framesRendered(), int64_t{192});
}

TEST(pad_engine_frames_rendered_never_goes_backwards_across_stop_and_start) {
    PadEngine e(kRate);
    callback(e, 128);
    e.stop();
    const int64_t before = e.framesRendered();
    // start() has no stream here (the Oboe stub refuses to open one); the count must survive it either way.
    e.start();
    callback(e, 32);
    CHECK_EQ(e.framesRendered(), before + 32);
}

TEST(pad_engine_without_a_stream_has_no_hardware_position) {
    PadEngine e(kRate);
    int64_t frame = 7;
    int64_t nanos = 9;
    CHECK(!e.hardwarePosition(&frame, &nanos));
    CHECK_EQ(frame, int64_t{7});
    CHECK_EQ(nanos, int64_t{9});
    CHECK_EQ(e.framesPerBurst(), int32_t{0});
    CHECK_EQ(e.audioApi(), int32_t{-1});
}

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
    // The ramp's first 10 frames, looping [4, 10): 0..9, 4..9, 4..9 ... The
    // loop is the window's [loopStart, end), so the LAST frame is played and
    // the period is end - loopStart. (It used to wrap at end - 1: frame 9
    // was never read and every wrap slipped a frame.)
    e.pushCommand(looping(60, 0, 10, 4));
    auto out = callback(e, 20);
    CHECK_NEAR(out[2 * 8], 0.008f, 1e-6);
    CHECK_NEAR(out[2 * 9], 0.009f, 1e-6);   // the last frame is played
    CHECK_NEAR(out[2 * 10], 0.004f, 1e-6);  // then wrapped
    CHECK_NEAR(out[2 * 15], 0.009f, 1e-6);
    CHECK_NEAR(out[2 * 16], 0.004f, 1e-6);  // and again, a period of six
    CHECK_EQ(static_cast<int>(ended(e).size()), 0);  // still sounding
    // A release is a Stop with the instrument's release as its fade.
    e.pushCommand(stop(60, 1.0f));
    callback(e, 64);
    const auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 1);
    // A loop of fewer than two frames (loopStart on the last frame) plays once and ends.
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

TEST(pad_engine_note_off_fades_a_positive_sustain_loop_without_repeating_its_opening) {
    // PRISM's exported instrument plays an opening once, then loops to EOF.
    // InstrumentPlayer sends note-off through JNI as Stop(releaseSeconds * 1000).
    // A flat sustain isolates that release envelope from oscillator zero crossings.
    for (const int32_t sourceRate : {44100, 48000}) {
        for (const float releaseMs : {600.0f, 1000.0f}) {
            PadEngine e(kRate);
            // No stream is opened in this harness. The callback rate remains
            // 48 kHz; a 44.1 kHz exported sample is resampled into that clock.
            const int32_t outputRate = e.sampleRate();
            CHECK_EQ(outputRate, kRate);
            const int32_t openingFrames = sourceRate * 4 / 5;  // the accepted 0.8-second opening
            const int32_t loopFrames = sourceRate / 100;
            const int32_t sampleFrames = openingFrames + loopFrames;
            constexpr float opening = 0.8f;
            constexpr float sustain = 0.2f;
            std::vector<float> sample(static_cast<size_t>(sampleFrames), sustain);
            // The prefix already joins the kept sustain before the marker,
            // so the cubic's neighboring taps agree even at fractional reads.
            std::fill(sample.begin(), sample.begin() + openingFrames - 4, opening);
            e.beginBank();
            e.addSample(std::move(sample), 1, sourceRate);
            e.commitBank();
            callback(e, 8);
            ended(e);

            // Drive realistic callback blocks so the release must survive both
            // callback boundaries and many crossings of the stored sample's EOF.
            auto renderFrames = [&](int32_t frames) {
                std::vector<float> out;
                out.reserve(static_cast<size_t>(frames) * 2);
                while (frames > 0) {
                    const int32_t count = std::min(frames, int32_t{128});
                    const auto block = callback(e, count);
                    out.insert(out.end(), block.begin(), block.end());
                    frames -= count;
                }
                return out;
            };

            constexpr int32_t voice = 63;
            CHECK(e.pushCommand(looping(voice, 0, sampleFrames, openingFrames)));
            const double outputPerSource = static_cast<double>(outputRate) / sourceRate;
            const int32_t openingOutputFrames = static_cast<int32_t>(std::lround(openingFrames * outputPerSource));
            const int32_t sampleOutputFrames = static_cast<int32_t>(std::lround(sampleFrames * outputPerSource));
            const auto held = renderFrames(static_cast<int32_t>(std::lround((sampleFrames + 3 * loopFrames) * outputPerSource)));
            CHECK_NEAR(held[0], opening, 1e-6);
            CHECK_NEAR(held[2 * (openingOutputFrames / 2)], opening, 1e-6);
            CHECK_NEAR(held[2 * openingOutputFrames], sustain, 1e-6);
            for (size_t i = static_cast<size_t>(sampleOutputFrames) * 2; i < held.size(); ++i) {
                CHECK_NEAR(held[i], sustain, 1e-6);  // no return to the louder opening
            }
            CHECK(ended(e).empty());

            CHECK(e.pushCommand(stop(voice, releaseMs)));
            const int32_t releaseFrames = static_cast<int32_t>(std::lround(releaseMs * 0.001f * outputRate));
            const int32_t halfFrames = releaseFrames / 2;
            const auto firstHalf = renderFrames(halfFrames);
            CHECK(std::all_of(firstHalf.begin(), firstHalf.end(), [](float x) { return std::isfinite(x); }));
            CHECK(firstHalf[0] > sustain * 0.99f);  // note-off does not hard-stop the voice
            CHECK_NEAR(firstHalf[firstHalf.size() - 2], sustain * 0.5f, 0.002f);
            CHECK_NEAR(firstHalf.back(), sustain * 0.5f, 0.002f);
            CHECK(ended(e).empty());  // still audible halfway through the requested release

            const auto tail = renderFrames(releaseFrames - halfFrames + outputRate / 50);
            CHECK(std::all_of(tail.end() - outputRate / 100 * 2, tail.end(), [](float x) { return x == 0.0f; }));
            const auto ids = ended(e);
            CHECK_EQ(static_cast<int>(ids.size()), 1);
            if (!ids.empty()) CHECK_EQ(ids[0], voice);
            CHECK_NEAR(peak(renderFrames(128)), 0.0f, 1e-7);
            CHECK(ended(e).empty());
        }
    }
}

TEST(pad_engine_the_seam_of_a_loop_is_interpolated_into_the_loop_start) {
    // Half a frame per output frame (pitch 0.5): position 9.5 sits between
    // the last frame (9) and the loop's first (4), and reads their mean.
    PadEngine& e = seeded();
    PadCommand c = looping(70, 0, 10, 4);
    c.pitch = 0.5;
    e.pushCommand(c);
    auto out = callback(e, 24);
    CHECK_NEAR(out[2 * 18], 0.009f, 1e-6);    // position 9.0
    CHECK_NEAR(out[2 * 19], 0.0065f, 1e-6);   // 9.5: (0.009 + 0.004) / 2
    CHECK_NEAR(out[2 * 20], 0.004f, 1e-6);    // 10 wraps to 4
    CHECK_NEAR(out[2 * 21], 0.0045f, 1e-6);   // 4.5
}

TEST(pad_engine_a_loop_that_closes_exactly_plays_as_one_unbroken_sine) {
    // 2000 frames, looping [1000, 2000): ten periods of a 100-frame sine, so
    // the file closes exactly and a held note is the sine for as long as it
    // is held. With the loop one frame short each wrap slipped the phase by a
    // frame (0.063 rad here) and the note walked out of the sine it was cut from.
    const double pi = 3.14159265358979323846;
    std::vector<float> sine(2000);
    for (int i = 0; i < 2000; ++i) sine[static_cast<size_t>(i)] = 0.5f * static_cast<float>(std::sin(2.0 * pi * i / 100.0));
    PadEngine e(kRate);
    e.beginBank();
    e.addSample(std::move(sine), 1, kRate);
    e.commitBank();
    callback(e, 8);
    ended(e);
    e.pushCommand(looping(71, 0, 2000, 1000));
    float worst = 0.0f;
    for (int block = 0; block < 5; ++block) {  // 5000 frames: two wraps and more
        const auto out = callback(e, 1000);
        for (int i = 0; i < 1000; ++i) {
            const int n = block * 1000 + i;
            const float want = 0.5f * static_cast<float>(std::sin(2.0 * pi * n / 100.0));
            worst = std::max(worst, std::fabs(out[static_cast<size_t>(2 * i)] - want));
        }
    }
    CHECK(worst < 2e-5f);
    CHECK(ended(e).empty());
}

TEST(pad_engine_a_fast_voice_over_a_short_loop_stays_inside_it) {
    // A two-frame loop [8, 10) at 4x speed: after the first pass every read
    // is one of frames 8 and 9 or between them.
    PadEngine& e = seeded();
    PadCommand c = looping(72, 0, 10, 8);
    c.pitch = 4.0;
    e.pushCommand(c);
    const auto out = callback(e, 64);
    for (int i = 3; i < 64; ++i) {
        const float v = out[static_cast<size_t>(2 * i)];
        CHECK(v >= 0.00799f && v <= 0.00901f);
    }
    CHECK(ended(e).empty());
}

TEST(pad_engine_a_loop_from_frame_zero_repeats_its_whole_window) {
    // loopStart = 0 is a loop (SPLIT's stems): the window's end frames, not end - 1.
    PadEngine& e = seeded();
    e.pushCommand(looping(82, 0, 10, 0));
    const auto out = callback(e, 25);
    for (int i = 0; i < 25; ++i) CHECK_NEAR(out[static_cast<size_t>(2 * i)], static_cast<float>(i % 10) / 1000.0f, 1e-6);
    CHECK(ended(e).empty());
}

TEST(pad_engine_a_stereo_loop_interpolates_both_channels_across_the_seam) {
    // L = i/1000 and R = -i/500 over ten frames, looping [4, 10) at half speed:
    // position 9.5 is the mean of the last frame and the loop's first, per channel.
    std::vector<float> st(20);
    for (int i = 0; i < 10; ++i) { st[static_cast<size_t>(2 * i)] = static_cast<float>(i) / 1000.0f; st[static_cast<size_t>(2 * i + 1)] = -static_cast<float>(i) / 500.0f; }
    PadEngine e(kRate);
    e.beginBank();
    e.addSample(std::move(st), 2, kRate);
    e.commitBank();
    callback(e, 8);
    ended(e);
    PadCommand c = looping(84, 0, 10, 4);
    c.pitch = 0.5;
    e.pushCommand(c);
    const auto out = callback(e, 24);
    CHECK_NEAR(out[2 * 18], 0.009f, 1e-6);        // position 9.0
    CHECK_NEAR(out[2 * 18 + 1], -0.018f, 1e-6);
    CHECK_NEAR(out[2 * 19], 0.0065f, 1e-6);       // 9.5: (0.009 + 0.004) / 2
    CHECK_NEAR(out[2 * 19 + 1], -0.013f, 1e-6);   // (-0.018 + -0.008) / 2
    CHECK_NEAR(out[2 * 20 + 1], -0.008f, 1e-6);   // 10 wraps to 4
}

// ---- the cubic read ------------------------------------------------------------

TEST(pad_engine_an_unpitched_read_at_the_stream_rate_is_the_samples_exactly) {
    std::vector<float> noise(4000);
    uint32_t x = 12345u;
    for (auto& s : noise) { x = x * 1664525u + 1013904223u; s = static_cast<float>(static_cast<int32_t>(x)) / 2147483648.0f; }
    const std::vector<float> want = noise;
    for (int pass = 0; pass < 3; ++pass) {
        PadEngine& e = banked(std::vector<float>(want), 2, kRate);  // 2000 stereo frames
        PadCommand c = noteOn(1, 0, 0, 2000);
        if (pass == 1) c.reverse = true;
        if (pass == 2) c.loopStart = 500;
        e.pushCommand(c);
        const auto out = callback(e, 1500);
        int bad = 0;
        for (int i = 0; i < 1500; ++i) {
            const int64_t at = pass == 1 ? 1999 - i : i;
            if (out[static_cast<size_t>(2 * i)] != want[static_cast<size_t>(2 * at)] || out[static_cast<size_t>(2 * i + 1)] != want[static_cast<size_t>(2 * at + 1)]) ++bad;
        }
        CHECK_EQ(bad, 0);
    }
}

TEST(pad_engine_reads_between_frames_through_four_neighbours) {
    PadEngine& e = banked(parabola(40), 1, kRate);
    e.pushCommand(noteOn(1, 0, 0, 30, 1.0f, 1.0f, 0.25));
    const auto out = callback(e, 48);
    CHECK_NEAR(out[2 * 40], 0.0100f, 1e-7);       // position 10: frame 10 itself
    CHECK_NEAR(out[2 * 41], 0.01050625f, 1e-7);   // 10.25 on the parabola (a straight line reads 0.010525)
    CHECK_NEAR(out[2 * 42], 0.011025f, 1e-7);     // 10.5 (0.01105)
    CHECK_NEAR(out[2 * 43], 0.01155625f, 1e-7);   // 10.75 (0.011575)
}

TEST(pad_engine_a_cubic_read_never_reaches_past_its_window) {
    // A chain slice: frames 10..19 hold 0.10..0.19, every frame outside holds 0.9.
    std::vector<float> slab(40, 0.9f);
    for (int i = 10; i < 20; ++i) slab[static_cast<size_t>(i)] = 0.1f + static_cast<float>(i - 10) / 100.0f;
    {
        PadEngine& e = banked(std::vector<float>(slab), 1, kRate);
        e.pushCommand(noteOn(1, 0, 10, 20, 1.0f, 1.0f, 0.5));
        const auto out = callback(e, 24);
        CHECK_NEAR(out[2 * 1], 0.104375f, 1e-6);   // 10.5: taps 10, 10, 11, 12 - the first frame held, never frame 9
        CHECK_NEAR(out[2 * 17], 0.185625f, 1e-6);  // 18.5: taps 17, 18, 19, 19 - the far tap held at the last frame
        CHECK_NEAR(out[2 * 18], 0.19f, 1e-6);      // 19.0: the last frame
        CHECK_NEAR(out[2 * 19], 0.190625f, 1e-6);  // 19.5: taps 18, 19, 19, 19 - the last frame held, never 20
        CHECK_NEAR(out[2 * 20], 0.0f, 1e-7);       // and then it ends
    }
    {
        PadEngine& e = banked(std::vector<float>(slab), 1, kRate);
        PadCommand c = reversed(2, 0, 10, 20);
        c.pitch = 0.5;
        e.pushCommand(c);
        const auto out = callback(e, 24);
        CHECK_NEAR(out[2 * 0], 0.19f, 1e-6);       // starts on the last frame
        CHECK_NEAR(out[2 * 1], 0.185625f, 1e-6);   // 18.5: taps 17, 18, 19, 19
        CHECK_NEAR(out[2 * 17], 0.104375f, 1e-6);  // 10.5
        CHECK_NEAR(out[2 * 18], 0.10f, 1e-6);      // 10.0
        CHECK_NEAR(out[2 * 19], 0.0f, 1e-7);       // 9.5 is below start: ended
    }
}

TEST(pad_engine_a_cubic_reads_a_loop_seam_by_its_own_frames_both_ways) {
    // Stereo parabola (L) and minus twice it (R) over 20 frames, the window [0, 10), looping [4, 10), half speed.
    std::vector<float> st(40);
    for (int i = 0; i < 20; ++i) { st[static_cast<size_t>(2 * i)] = static_cast<float>(i * i) / 10000.0f; st[static_cast<size_t>(2 * i + 1)] = -2.0f * static_cast<float>(i * i) / 10000.0f; }
    {
        PadEngine& e = banked(std::vector<float>(st), 2, kRate);
        PadCommand c = looping(1, 0, 10, 4);
        c.pitch = 0.5;
        e.pushCommand(c);
        const auto out = callback(e, 24);
        CHECK_NEAR(out[2 * 19], 0.0049f, 1e-7);        // 9.5: taps 8, 9, 4, 5
        CHECK_NEAR(out[2 * 19 + 1], -0.0098f, 1e-7);
        CHECK_NEAR(out[2 * 20], 0.0016f, 1e-7);        // 10 wraps to 4
        CHECK_NEAR(out[2 * 21], 0.002025f, 1e-7);      // 4.5: taps 3, 4, 5, 6 - the parabola itself
        CHECK_NEAR(out[2 * 21 + 1], -0.00405f, 1e-7);
    }
    {
        PadEngine& e = banked(std::vector<float>(st), 2, kRate);
        PadCommand c = reversed(2, 0, 0, 10);
        c.loopStart = 4;
        c.pitch = 0.5;
        e.pushCommand(c);
        const auto out = callback(e, 24);
        // 9.0, 8.5 ... 4.0 (out 10), 3.5 wraps to 9.5 (out 11), 9.0, 8.5.
        CHECK_NEAR(out[2 * 1], 0.00775f, 1e-7);        // 8.5: taps 7, 8, 9 and the loop's first (4)
        CHECK_NEAR(out[2 * 9], 0.002025f, 1e-7);       // 4.5
        CHECK_NEAR(out[2 * 10], 0.0016f, 1e-7);        // 4.0
        CHECK_NEAR(out[2 * 11], 0.0049f, 1e-7);        // 9.5: taps 8, 9, 4, 5
        CHECK_NEAR(out[2 * 11 + 1], -0.0098f, 1e-7);
    }
}

TEST(pad_engine_a_loop_that_closes_is_as_smooth_across_its_seam_off_the_root) {
    const double pi = 3.14159265358979323846;
    for (int which = 0; which < 2; ++which) {
        std::vector<float> sine(2000);
        for (int i = 0; i < 2000; ++i) sine[static_cast<size_t>(i)] = 0.5f * static_cast<float>(std::sin(2.0 * pi * i / 100.0));
        PadEngine& e = banked(std::move(sine), 1, kRate);
        PadCommand c = looping(1, 0, 2000, 1000);
        c.pitch = which == 0 ? 1.5 : 0.75;
        e.pushCommand(c);
        double pos = 0.0;
        float seam = 0.0f, inside = 0.0f;
        for (int block = 0; block < 8; ++block) {
            const auto out = callback(e, 1000);
            for (int i = 0; i < 1000; ++i) {
                const float want = 0.5f * static_cast<float>(std::sin(2.0 * pi * pos / 100.0));
                const float err = std::fabs(out[static_cast<size_t>(2 * i)] - want);
                double p = pos; while (p >= 2000.0) p -= 1000.0;
                const bool nearSeam = p >= 1997.0 || (pos >= 2000.0 && p < 1002.0);
                // The window's first two frames are left out: frame 0 has no frame before it, so the
                // cubic holds it there, by design, and the read is not the ideal sine.
                if (pos >= 2.0) {
                    if (nearSeam) seam = std::max(seam, err);
                    else inside = std::max(inside, err);
                }
                pos += c.pitch;
            }
        }
        CHECK(inside < 1e-4f);
        CHECK(seam <= inside * 1.5f + 1e-6f);
    }
}

// ---- PadEngine: a command lands on its frame, not on the block ----------------

namespace {
PadCommand at(PadCommand c, int32_t frameOffset) {
    c.frameOffset = frameOffset;
    return c;
}
}  // namespace

TEST(pad_engine_a_note_at_frame_37_begins_at_frame_37_of_the_block) {
    PadEngine& e = seeded();
    // The ramp from frame 100: the first frame played reads 0.100, the next 0.101.
    CHECK(e.pushCommand(at(noteOn(37, 0, 100, 1000), 37)));
    auto out = callback(e, 128);
    for (int32_t i = 0; i < 37; ++i) {
        CHECK_NEAR(out[2 * i], 0.0f, 1e-7);
        CHECK_NEAR(out[2 * i + 1], 0.0f, 1e-7);
    }
    CHECK_NEAR(out[2 * 37], 0.100f, 1e-6);
    CHECK_NEAR(out[2 * 37 + 1], 0.100f, 1e-6);
    CHECK_NEAR(out[2 * 38], 0.101f, 1e-6);
    // Frame 127 is the note's 91st frame: ramp frame 190.
    CHECK_NEAR(out[2 * 127], 0.190f, 1e-6);
    CHECK_EQ(static_cast<int>(ended(e).size()), 0);
}

TEST(pad_engine_a_command_past_the_block_waits_its_turn_in_the_next_one) {
    PadEngine& e = seeded();
    // Due at frame 150: silence for all of a 128-frame block, then frame 22 of the next.
    CHECK(e.pushCommand(at(noteOn(8, 0, 100, 1000), 150)));
    auto first = callback(e, 128);
    CHECK_NEAR(peak(first), 0.0f, 1e-7);
    auto second = callback(e, 128);
    CHECK_NEAR(second[2 * 21], 0.0f, 1e-7);
    CHECK_NEAR(second[2 * 22], 0.100f, 1e-6);
    CHECK_NEAR(second[2 * 23], 0.101f, 1e-6);
    // Block sizes need not match: the wait is counted in frames, not blocks.
    PadEngine& f = seeded();
    CHECK(f.pushCommand(at(noteOn(9, 0, 100, 1000), 100)));
    callback(f, 64);
    callback(f, 32);
    auto third = callback(f, 16);
    CHECK_NEAR(third[2 * 3], 0.0f, 1e-7);
    CHECK_NEAR(third[2 * 4], 0.100f, 1e-6);
}

TEST(pad_engine_commands_apply_by_frame_first_and_push_order_second) {
    PadEngine& e = seeded();
    // The Stop was pushed after the NoteOn but is due before it: it finds
    // nothing to stop, and the note that follows plays untouched.
    CHECK(e.pushCommand(at(noteOn(5, 0, 100, 1000), 20)));
    CHECK(e.pushCommand(at(stop(5, 1.0f), 10)));
    // Due on the same frame as another Stop pushed later: push order holds,
    // so the second NoteOn's own Stop (pushed after it) stops it.
    CHECK(e.pushCommand(at(noteOn(6, 0, 500, 1000), 20)));
    CHECK(e.pushCommand(at(stop(6, 1.0f), 20)));  // 1 ms = 48 frames
    auto out = callback(e, 128);
    CHECK_NEAR(out[2 * 19], 0.0f, 1e-7);
    // Frame 20: voice 5 at full (0.100) plus voice 6 at the first step of its fade.
    CHECK(out[2 * 20] > 0.100f);
    CHECK(out[2 * 20] < 0.600f);
    // By frame 20 + 48 voice 6 has faded out and only voice 5 remains, still at full.
    CHECK_NEAR(out[2 * 70], 0.150f, 1e-6);
    CHECK_NEAR(out[2 * 127], 0.207f, 1e-6);
    const auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 1);
    if (!ids.empty()) CHECK_EQ(ids[0], 6);
}

TEST(pad_engine_a_stop_due_mid_block_starts_the_fade_there) {
    PadEngine& e = seeded();
    e.pushCommand(noteOn(3, 0, 500, 1000, 0.002f, 0.002f));
    callback(e, 4);
    e.pushCommand(at(stop(3, 1.0f), 32));  // 1 ms = 48 frames, from frame 32
    auto out = callback(e, 128);
    CHECK_NEAR(out[2 * 31], 0.535f * 0.002f, 1e-6);  // ramp frame 504 + 31, untouched before the fade
    CHECK(out[2 * 40] < out[2 * 31]);                 // falling after it
    CHECK_NEAR(out[2 * 90], 0.0f, 1e-7);               // gone by 32 + 48
    CHECK_EQ(static_cast<int>(ended(e).size()), 1);
}

TEST(pad_engine_stop_reports_a_note_still_waiting_for_its_frame) {
    PadEngine& e = seeded();
    e.pushCommand(at(noteOn(11, 0, 100, 1000), 10000));
    callback(e, 8);  // taken off the ring, held for a frame that never comes
    CHECK_EQ(static_cast<int>(ended(e).size()), 0);
    e.stop();
    const auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 1);
    if (!ids.empty()) CHECK_EQ(ids[0], 11);
    // And it is gone: a later callback does not play it.
    e.pushCommand(allOff(1.0f));
    auto out = callback(e, 128);
    CHECK_NEAR(peak(out), 0.0f, 1e-7);
}

TEST(pad_engine_a_command_that_finds_the_hold_full_plays_now_rather_than_never) {
    PadEngine& e = seeded();
    // A bad sample index is refused by apply() with an ended report, so a
    // report says "applied" and no report says "still held".
    const int32_t far = 1 << 20;
    for (int32_t id = 0; id < 255; ++id) CHECK(e.pushCommand(at(noteOn(id, 99, 0, 10), far)));
    callback(e, 8);
    CHECK_EQ(static_cast<int>(ended(e).size()), 0);  // 255 held
    for (int32_t id = 1000; id < 1255; ++id) CHECK(e.pushCommand(at(noteOn(id, 99, 0, 10), far)));
    callback(e, 8);
    // One more fits the hold of 256; the other 254 could not wait and were applied at once.
    CHECK_EQ(static_cast<int>(ended(e).size()), 254);
}

TEST(latency_is_minus_one_with_no_stream) {
    // The readout's "the device would not say" case: no stream open, so
    // neither engine may dereference one to answer.
    PadEngine pads(kRate);
    CHECK(pads.latencyMillis() < 0.0);
    SurfaceEngine surface(kRate);
    CHECK(surface.latencyMillis() < 0.0);
}

TEST(pad_engine_prints_the_stereo_bus_it_just_played) {
    // The point of printing the bus rather than rendering the kit again:
    // the print cannot disagree with what was heard. So the assertion is
    // literally that - the frames the callback wrote and the frames the
    // print kept are the same frames, both channels of them.
    PadEngine& e = seeded();
    CHECK(e.armPrint(200));
    CHECK(!e.armPrint(200));  // not while recording
    e.pushCommand(noteOn(1, 0, 0, 900, 1.0f, 0.25f));
    const std::vector<float> heard = callback(e, 128);
    CHECK(e.printState() == PrintBuffer::State::Recording);
    CHECK_EQ(static_cast<int>(e.printFrames()), 128);
    CHECK_EQ(static_cast<int>(e.printSamples()), 256);
    CHECK(peak(heard) > 0.01f);  // something was actually playing
    for (size_t i = 0; i < heard.size(); ++i) CHECK_NEAR(e.printData()[i], heard[i], 1e-6);
    // Stereo, not a mono copy: the note was panned four to one, so the
    // print's left carries plainly more than its right. Measured over the
    // whole print rather than one frame, because the first frames of the
    // ramp are near silence and a gain glide is still settling there.
    float left = 0.0f, right = 0.0f;
    for (int f = 0; f < 128; ++f) {
        left += std::fabs(e.printData()[2 * f]);
        right += std::fabs(e.printData()[2 * f + 1]);
    }
    CHECK(left > right * 2.0f);

    e.requestStopPrint();
    callback(e, 64);
    CHECK(e.printState() == PrintBuffer::State::Done);
    CHECK_EQ(static_cast<int>(e.printFrames()), 128);  // the stop pass added nothing
    CHECK(e.clearPrint());
    CHECK(e.printState() == PrintBuffer::State::Idle);
}

TEST(pad_engine_with_no_print_armed_records_nothing) {
    // The tap sits in the callback of every kit hit anyone ever plays, so
    // an unarmed engine must stay at Idle no matter how much runs through
    // it - that is what makes it free when nobody is bouncing.
    PadEngine& e = seeded();
    e.pushCommand(noteOn(1, 0, 0, 900));
    for (int i = 0; i < 20; ++i) callback(e, 64);
    CHECK(e.printState() == PrintBuffer::State::Idle);
    CHECK_EQ(static_cast<int>(e.printFrames()), 0);
}

TEST(pad_engine_print_stops_at_its_ceiling_mid_callback) {
    // A bounce asks for a fixed number of frames; the callback that
    // crosses the ceiling keeps the frames that fit and finishes there,
    // rather than running past the buffer.
    PadEngine& e = seeded();
    CHECK(e.armPrint(100));
    e.pushCommand(noteOn(1, 0, 0, 900));
    callback(e, 64);
    CHECK(e.printState() == PrintBuffer::State::Recording);
    callback(e, 64);
    CHECK(e.printState() == PrintBuffer::State::Done);
    CHECK_EQ(static_cast<int>(e.printFrames()), 100);
    CHECK_EQ(static_cast<int>(e.printSamples()), 200);
    callback(e, 64);  // and stays there
    CHECK_EQ(static_cast<int>(e.printFrames()), 100);
    e.clearPrint();
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

TEST(surface_engine_prints_the_stereo_bus_and_finishes_on_the_callback) {
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
    // Default dry output is duplicated mono, captured as complete stereo frames.
    CHECK_EQ(e.printSamples(), 256u);
    for (size_t i = 0; i < e.printFrames(); ++i) CHECK_EQ(e.printData()[2 * i], e.printData()[2 * i + 1]);
    auto out = callback(e, 1);
    CHECK(std::fabs(e.printData()[127]) > 0.01f);
    CHECK_NEAR(e.printData()[127], e.printData()[126], 0.05f);
    (void)out;
    e.clearPrint();
    CHECK(e.printState() == PrintBuffer::State::Idle);
}

TEST(surface_engine_blends_samples_at_each_vertex_by_weight) {
    // Three constant sources, deliberately at different signs *and*
    // magnitudes - loud positive, loud negative, quiet negative - so a
    // weight wired to the wrong slot (B and C swapped, say) shows up as
    // the wrong magnitude, not just the wrong sign. A lowpass's DC gain
    // and tanh's odd symmetry both preserve sign, so this is still a
    // magnitude/sign check, not an exact-value one - except at an even
    // A/B split, which is an exact +0.9/-0.9 average computed before the
    // filter ever sees it and must land on exactly zero.
    auto settle = [](float a, float b, float c) {
        SurfaceEngine e(kRate);
        std::vector<float> hi(100, 0.9f), lo(100, -0.9f), quiet(100, -0.3f);
        e.loadSample(hi.data(), hi.size(), kRate, 0);
        e.loadSample(lo.data(), lo.size(), kRate, 1);
        e.loadSample(quiet.data(), quiet.size(), kRate, 2);
        ControlFrame f;
        f.mode = 0;
        f.gate = true;
        f.x = 0.5f;
        f.y = 1.0f;  // cutoff wide open
        f.sampleA = a; f.sampleB = b; f.sampleC = c;
        e.pushControl(f);
        std::vector<float> out;
        for (int i = 0; i < 400; ++i) out = callback(e, 64);
        float sum = 0.0f;
        for (float v : out) sum += v;
        return sum / static_cast<float>(out.size());
    };

    CHECK(settle(1.0f, 0.0f, 0.0f) > 0.5f);              // slot 0 alone: loud positive
    CHECK(settle(0.0f, 1.0f, 0.0f) < -0.5f);              // slot 1 alone: loud negative
    const float thirdAlone = settle(0.0f, 0.0f, 1.0f);    // slot 2 alone: quiet negative
    CHECK(thirdAlone < -0.02f && thirdAlone > -0.35f);
    CHECK(std::fabs(settle(0.5f, 0.5f, 0.0f)) < 0.01f);   // slot 0/1 even split cancels exactly
}

TEST(surface_engine_blends_the_fourth_vertex_too_and_renormalises_over_all_four) {
    // Same idea as the three-vertex test above, extended to slot 3
    // (sampleD, the pad's base-mid vertex) - proving the fourth slot is
    // actually wired into renderMono's blend and its renormalisation,
    // not just accepted by applyControl and then dropped on the floor.
    SurfaceEngine e(kRate);
    std::vector<float> hi(100, 0.9f), lo(100, -0.9f);
    e.loadSample(hi.data(), hi.size(), kRate, 0);
    e.loadSample(lo.data(), lo.size(), kRate, 3);
    ControlFrame f;
    f.mode = 0;
    f.gate = true;
    f.x = 0.5f;
    f.y = 1.0f;  // cutoff wide open
    f.sampleA = 0.0f; f.sampleD = 1.0f;  // slot 3 alone
    e.pushControl(f);
    std::vector<float> out;
    for (int i = 0; i < 400; ++i) out = callback(e, 64);
    float sum = 0.0f;
    for (float v : out) sum += v;
    CHECK(sum / static_cast<float>(out.size()) < -0.5f);  // loud negative, full level

    // All four loaded, all four weighted evenly: a loud positive (slot 0)
    // and a loud negative (slot 3) at equal weight cancel toward zero the
    // same way the three-vertex even split does above.
    SurfaceEngine four(kRate);
    std::vector<float> quietA(100, 0.3f), quietB(100, -0.3f);
    four.loadSample(hi.data(), hi.size(), kRate, 0);
    four.loadSample(quietA.data(), quietA.size(), kRate, 1);
    four.loadSample(quietB.data(), quietB.size(), kRate, 2);
    four.loadSample(lo.data(), lo.size(), kRate, 3);
    ControlFrame even;
    even.mode = 0;
    even.gate = true;
    even.x = 0.5f;
    even.y = 1.0f;
    even.sampleA = even.sampleB = even.sampleC = even.sampleD = 1.0f;  // a quarter each
    four.pushControl(even);
    std::vector<float> evenOut;
    for (int i = 0; i < 400; ++i) evenOut = callback(four, 64);
    float evenSum = 0.0f;
    for (float v : evenOut) evenSum += v;
    // 0.9 + 0.3 - 0.3 - 0.9, each at a quarter weight, is exactly zero.
    CHECK(std::fabs(evenSum / static_cast<float>(evenOut.size())) < 0.01f);
}

TEST(surface_engine_treats_sample_weights_as_a_ratio_not_absolute_level) {
    // renderMono renormalises sampleA/B/C every frame, so a caller sending
    // {2, 0, 0} must sound identical to {1, 0, 0} - the blend is a ratio
    // between slots, never an absolute level a UI has to keep under 1.
    // With only one weight nonzero the ratio is 1.0 from the very first
    // sample (nothing else to divide by), so the two runs are
    // bit-identical from frame one, not just once a glide has settled.
    auto run = [](float weight) {
        SurfaceEngine e(kRate);
        std::vector<float> tone(100, 0.9f);
        e.loadSample(tone.data(), tone.size(), kRate, 0);
        ControlFrame f;
        f.mode = 0;
        f.gate = true;
        f.x = 0.5f;
        f.y = 1.0f;
        f.sampleA = weight;
        e.pushControl(f);
        std::vector<float> out;
        for (int i = 0; i < 200; ++i) out = callback(e, 64);
        return out;
    };

    CHECK(run(1.0f) == run(5.0f));
}

TEST(surface_engine_one_loaded_slot_plays_full_level_at_any_touch_weight) {
    // With only slot 0 loaded, the other two vertices' touch weight is
    // never really "requesting" anything - there is nothing there to
    // read. Before this fix, wSum summed every weight the touch sent
    // regardless of what was loaded, so a touch anywhere but the exact
    // apex (weights 0.25/0.25 on the unloaded base vertices at the pad's
    // own centre, say - TouchSurface.sampleWeights(0.5, 0.5)) silently
    // halved the one loaded sample's level: the opposite of the "no dead
    // zone" the vertex blend exists for. Settling at the apex and at the
    // pad's centre must sound the same.
    auto settle = [](float a, float b, float c) {
        SurfaceEngine e(kRate);
        std::vector<float> tone(100, 0.9f);
        e.loadSample(tone.data(), tone.size(), kRate, 0);
        ControlFrame f;
        f.mode = 0;
        f.gate = true;
        f.x = 0.5f;
        f.y = 1.0f;  // cutoff wide open
        f.sampleA = a; f.sampleB = b; f.sampleC = c;
        e.pushControl(f);
        std::vector<float> out;
        for (int i = 0; i < 400; ++i) out = callback(e, 64);
        float sum = 0.0f;
        for (float v : out) sum += v;
        return sum / static_cast<float>(out.size());
    };

    const float atApex = settle(1.0f, 0.0f, 0.0f);
    const float atCentre = settle(0.5f, 0.25f, 0.25f);  // weight split across two unloaded vertices too
    CHECK_NEAR(atApex, atCentre, 0.02f);
}

TEST(surface_engine_unloaded_pad4_falls_back_to_the_pad2_pad3_blend_at_the_seam) {
    // Copilot review finding on PR #192: TouchSurface.sampleWeights splits
    // the pad into two half-triangles meeting at PAD4's own vertex, so
    // PAD2 and PAD3's raw weights both collapse toward zero approaching
    // it - unlike an ordinary unloaded slot, there is no third loaded
    // neighbour left there for the plain renormalisation to fall back on,
    // so without this fallback an unloaded PAD4 (the common case - it is
    // brand new) leaves a real hole at the bottom-centre of the pad, not
    // just the single point PAD4's own vertex sits at. At that exact
    // point (TouchSurface.sampleWeights(0.5, 0) = {0, 0, 0, 1}), the fix
    // must recover the even PAD2/PAD3 split this seam gave before PAD4
    // existed, at full level - not near silence.
    //
    // Two different *same-sign* DC levels, not a cancelling +/- pair: a
    // constant source has no peak distinct from its average, so an evenly
    // *cancelling* pair would read back at 0 whether the fallback fired or
    // the point were genuinely silent - indistinguishable, and no test at
    // all. Rather than predict the exact level through the drive stage's
    // tanh and the filter (both nonlinear/dynamic), this compares against
    // each source played alone: monotonic, DC-preserving stages keep an
    // even blend of the two strictly between them - nowhere near the 0.0
    // true silence would settle to.
    // loadPad2/loadPad3 name which of PAD2 (always sourced from the 0.8
    // buffer, slot 1) and PAD3 (always the 0.2 buffer, slot 2) is loaded -
    // rather than generic slot-index arguments, so a transposed call can't
    // quietly load the wrong level into the wrong slot the way it did the
    // first time this test was written (caught by CI, not by this file).
    auto settle = [](float sampleA, float sampleB, float sampleC, float sampleD, bool loadPad2, bool loadPad3) {
        SurfaceEngine e(kRate);
        std::vector<float> hi(100, 0.8f), lo(100, 0.2f);
        if (loadPad2) e.loadSample(hi.data(), hi.size(), kRate, 1);
        if (loadPad3) e.loadSample(lo.data(), lo.size(), kRate, 2);
        ControlFrame f;
        f.mode = 0;
        f.gate = true;
        f.x = 0.5f;
        f.y = 1.0f;  // cutoff wide open
        f.sampleA = sampleA; f.sampleB = sampleB; f.sampleC = sampleC; f.sampleD = sampleD;
        e.pushControl(f);
        std::vector<float> out;
        for (int i = 0; i < 400; ++i) out = callback(e, 64);
        float sum = 0.0f;
        for (float v : out) sum += v;
        return sum / static_cast<float>(out.size());
    };

    // PAD2 (0.8) and PAD3 (0.2) each alone, slot 0/3 unloaded either way -
    // the same door every other case in this file already plays through.
    const float pad2Alone = settle(0.0f, 1.0f, 0.0f, 0.0f, true, false);
    const float pad3Alone = settle(0.0f, 0.0f, 1.0f, 0.0f, false, true);
    CHECK(pad2Alone > pad3Alone + 0.05f);  // sanity: the levels are actually different

    // At the seam (per sampleWeights(0.5, 0) = {0, 0, 0, 1}), with slot 3
    // unloaded: the fallback must land strictly between the two, not at
    // the 0.0 a broken (or missing) fallback would settle to.
    const float atSeam = settle(0.0f, 0.0f, 0.0f, 1.0f, true, true);
    CHECK(atSeam > pad3Alone + 0.02f);
    CHECK(atSeam < pad2Alone - 0.02f);

    // With only PAD2 loaded (PAD3 also empty), the whole fallback share
    // goes to PAD2 alone - the same level as PAD2 played directly.
    const float onlyPad2AtSeam = settle(0.0f, 0.0f, 0.0f, 1.0f, true, false);
    CHECK_NEAR(onlyPad2AtSeam, pad2Alone, 0.02f);
}

TEST(surface_engine_unloaded_slots_are_silent_until_loaded) {
    // A weight aimed entirely at a slot nothing was ever loaded into must
    // settle to honest silence, not NaN. "Settle" matters here as much as
    // anywhere else in this file: sampleWeight_ glides toward its target
    // exactly like every other control, so right after gate=true the
    // still-audible slot 0 legitimately leaks through the transient - that
    // is the de-zippering working as intended, not a bug, and the
    // finiteness check (never NaN) has to hold all through it. Silence is
    // the claim only once the glide has actually arrived. Checked for both
    // slot 1 and slot 2, since each is its own weight and its own bug to have.
    auto checkSlotSilentUntilLoaded = [](float ControlFrame::*weight) {
        SurfaceEngine e(kRate);
        std::vector<float> tone(100, 0.5f);
        e.loadSample(tone.data(), tone.size(), kRate, 0);
        ControlFrame f;
        f.mode = 0;
        f.gate = true;
        f.x = 0.5f;
        f.y = 1.0f;
        f.sampleA = 0.0f;  // ControlFrame defaults sampleA to 1 - clear it so *weight alone carries the blend
        f.*weight = 1.0f;
        e.pushControl(f);
        for (int i = 0; i < 300; ++i) {
            auto out = callback(e, 64);
            for (float v : out) CHECK(std::isfinite(v));
        }  // let sampleWeight_ (and the gain envelope) glide all the way to their targets
        float p = 0.0f;
        for (int i = 0; i < 100; ++i) p = std::max(p, peak(callback(e, 64)));
        // A one-pole glide never reaches its target bit-exactly in finite time
        // (CI measured a ~2e-6 residual against a 1e-6 tolerance here) - 1e-4
        // is still four orders of magnitude tighter than the ~0.14 this test
        // catches when the glide hasn't happened at all, so it stays a real
        // assertion without depending on exactly how many samples a given
        // compiler's float rounding takes to underflow the rest of the way.
        CHECK_NEAR(p, 0.0f, 1e-4f);
    };
    checkSlotSilentUntilLoaded(&ControlFrame::sampleB);
    checkSlotSilentUntilLoaded(&ControlFrame::sampleC);
    checkSlotSilentUntilLoaded(&ControlFrame::sampleD);
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

TEST(surface_engine_vector_mode_uses_morphs_exact_corner_blend) {
    // VECTOR shares MORPH's formula exactly (see applyControl's switch) -
    // the same corner state, the same tilt nudge, at the same touch
    // reading, must produce byte-identical output whichever of the two
    // mode numbers is sent.
    std::vector<float> square(100);
    for (int i = 0; i < 100; ++i) square[i] = (i % 10 < 5) ? 0.5f : -0.5f;

    auto run = [&](int32_t mode) {
        SurfaceEngine engine(kRate);
        engine.setCorner(0, MacroState{0.5f, 0.6f, 0.5f, 0.0f});
        engine.loadSample(square.data(), square.size(), kRate);
        ControlFrame f;
        f.mode = mode;
        f.gate = true;
        f.a = 1; f.b = f.c = f.d = 0;
        f.tilt = 0.75f;
        engine.pushControl(f);
        std::vector<float> out;
        for (int i = 0; i < 400; ++i) out = callback(engine, 64);
        return out;
    };

    CHECK(run(2) == run(3));  // MORPH and VECTOR
}

TEST(surface_engine_vector_mode_also_drives_the_sample_blend) {
    // VECTOR is the one mode where the sample vertices and the corner
    // blend both matter at once - proving the sample side still reaches
    // the DSP under mode 3, not just under mode 0 (XY) as every other
    // sample-blend test in this file uses. Corner A (clean: cutoff wide
    // open, no drive) keeps the filter out of the way of the sign check.
    SurfaceEngine e(kRate);
    std::vector<float> hi(100, 0.9f), lo(100, -0.9f);
    e.loadSample(hi.data(), hi.size(), kRate, 0);
    e.loadSample(lo.data(), lo.size(), kRate, 1);
    ControlFrame f;
    f.mode = 3;
    f.gate = true;
    f.a = 1.0f; f.b = f.c = f.d = 0.0f;
    f.sampleA = 0.0f; f.sampleB = 1.0f; f.sampleC = 0.0f;
    e.pushControl(f);
    std::vector<float> out;
    for (int i = 0; i < 400; ++i) out = callback(e, 64);
    float sum = 0.0f;
    for (float v : out) sum += v;
    CHECK(sum / static_cast<float>(out.size()) < -0.5f);  // slot 1 alone: negative, full level
}

TEST(surface_engine_retriggers_the_loop_on_touch_down) {
    // Without a reset, phase_ keeps advancing even while ungated - the loop
    // is muted, not paused - so a second touch lands wherever it would
    // naturally have drifted to by then, not at the loop's head. Two runs
    // that differ only in how many samples the pad sat released before the
    // second touch must land at the exact same output once retriggered and
    // settled: if phase_ actually resets on touch-down, both runs read the
    // ramp from the same starting point and every later control frame lines
    // up the same way, so the two waveforms are bit-identical. A ramp
    // (not a short repeating wave) makes any leftover drift visible - it
    // has no periodicity shorter than its own length for a coincidental
    // match to hide behind.
    auto run = [](int32_t releaseCallbacks) {
        SurfaceEngine e(kRate);
        std::vector<float> ramp(1000);
        for (size_t i = 0; i < ramp.size(); ++i) ramp[i] = static_cast<float>(i) / 1000.0f - 0.5f;
        e.loadSample(ramp.data(), ramp.size(), kRate);

        ControlFrame on;
        on.mode = 0;
        on.gate = true;
        on.x = 0.5f;
        on.y = 1.0f;
        e.pushControl(on);
        for (int i = 0; i < 137; ++i) callback(e, 64);  // hold for an arbitrary stretch

        ControlFrame off = on;
        off.gate = false;
        e.pushControl(off);
        for (int i = 0; i < releaseCallbacks; ++i) callback(e, 64);  // sit released, drifting if unfixed

        e.pushControl(on);  // touch down again
        std::vector<float> tail;
        for (int i = 0; i < 400; ++i) tail = callback(e, 64);  // let the envelope and filter settle
        return tail;
    };

    CHECK(run(50) == run(311));
}

TEST(surface_engine_retriggers_even_when_release_and_touch_share_one_drain) {
    // onAudioReady used to drain the ring straight to its newest frame,
    // treating the control stream as a position and nothing else. But the
    // gate's *edge* is an event: if a lift and a fast retouch both land in
    // the ring before the next audio callback (entirely possible - it
    // holds up to 64 frames, and the UI can push faster than one callback
    // drains), jumping to "newest" collapses them into a single gate=true
    // apply against a gated_ that was never told about the intervening
    // false, and the retrigger is missed. Forcing the release into its own
    // drain (a plain callback() in between) must land on the exact same
    // settled output as leaving them queued together.
    auto run = [](bool sameDrain) {
        SurfaceEngine e(kRate);
        std::vector<float> ramp(1000);
        for (size_t i = 0; i < ramp.size(); ++i) ramp[i] = static_cast<float>(i) / 1000.0f - 0.5f;
        e.loadSample(ramp.data(), ramp.size(), kRate);

        ControlFrame on;
        on.mode = 0;
        on.gate = true;
        on.x = 0.5f;
        on.y = 1.0f;
        e.pushControl(on);
        for (int i = 0; i < 137; ++i) callback(e, 64);

        ControlFrame off = on;
        off.gate = false;
        e.pushControl(off);
        if (!sameDrain) callback(e, 64);  // force the release into a drain of its own
        e.pushControl(on);

        std::vector<float> tail;
        for (int i = 0; i < 400; ++i) tail = callback(e, 64);
        return tail;
    };

    CHECK(run(true) == run(false));
}

TEST(surface_engine_does_not_retrigger_while_the_touch_is_only_held) {
    // The edge, not the level: a second gate=true frame while already
    // gated - a moved finger re-sending its position, say - must not yank
    // phase_ back to the head mid-note. An implementation that reset on
    // every true frame instead of the false -> true edge would still pass
    // the touch-down tests above (they only ever push one true frame per
    // touch) but would fail this one.
    auto run = [](bool resendWhileHeld) {
        SurfaceEngine e(kRate);
        std::vector<float> ramp(1000);
        for (size_t i = 0; i < ramp.size(); ++i) ramp[i] = static_cast<float>(i) / 1000.0f - 0.5f;
        e.loadSample(ramp.data(), ramp.size(), kRate);

        ControlFrame on;
        on.mode = 0;
        on.gate = true;
        on.x = 0.5f;
        on.y = 1.0f;
        e.pushControl(on);
        for (int i = 0; i < 137; ++i) callback(e, 64);
        if (resendWhileHeld) e.pushControl(on);  // still gate=true - a level, not an edge

        std::vector<float> tail;
        for (int i = 0; i < 400; ++i) tail = callback(e, 64);
        return tail;
    };

    CHECK(run(true) == run(false));
}

TEST(surface_engine_retrigger_resets_every_loaded_slot) {
    // A touch-down must restart every loaded source, not just whichever
    // ones currently dominate the blend - otherwise moving the puck after
    // a retrigger could reveal a source that quietly kept drifting the
    // whole time it sat inaudible. Four different ramps (not copies of
    // one) so a bug that only resets some of the slots still shows up
    // even though a drifting slot's own weight is small - the two runs'
    // tails just fail to match.
    auto run = [](int32_t releaseCallbacks) {
        SurfaceEngine e(kRate);
        std::vector<float> rampA(1000), rampB(1000), rampC(1000), rampD(1000);
        for (size_t i = 0; i < rampA.size(); ++i) {
            rampA[i] = static_cast<float>(i) / 1000.0f - 0.5f;
            rampB[i] = 0.5f - static_cast<float>(i) / 1000.0f;
            rampC[i] = std::fmod(static_cast<float>(i) / 333.0f, 1.0f) - 0.5f;
            rampD[i] = std::fmod(static_cast<float>(i) / 177.0f, 1.0f) - 0.5f;
        }
        e.loadSample(rampA.data(), rampA.size(), kRate, 0);
        e.loadSample(rampB.data(), rampB.size(), kRate, 1);
        e.loadSample(rampC.data(), rampC.size(), kRate, 2);
        e.loadSample(rampD.data(), rampD.size(), kRate, 3);

        ControlFrame on;
        on.mode = 0;
        on.gate = true;
        on.x = 0.5f;
        on.y = 1.0f;
        on.sampleA = on.sampleB = on.sampleC = on.sampleD = 1.0f;  // renormalised to a quarter each
        e.pushControl(on);
        for (int i = 0; i < 137; ++i) callback(e, 64);

        ControlFrame off = on;
        off.gate = false;
        e.pushControl(off);
        for (int i = 0; i < releaseCallbacks; ++i) callback(e, 64);

        e.pushControl(on);
        std::vector<float> tail;
        for (int i = 0; i < 400; ++i) tail = callback(e, 64);
        return tail;
    };

    CHECK(run(50) == run(311));
}

TEST(surface_engine_morph_tilt_reaches_the_filter) {
    // Two frames identical but for tilt, both weighted fully onto corner A:
    // if the tilt nudge in morphed() reaches applyControl (as it should -
    // SurfaceStore.Corner.from mirrors the same arithmetic in Kotlin, and
    // that side already proves the numbers), the two tilts land on
    // different resonance targets, which land on different SVF
    // coefficients, which cannot produce byte-identical output over
    // hundreds of callbacks. This does not re-derive the filter's theory,
    // only that the wire from tilt to the DSP is actually connected.
    SurfaceEngine e(kRate);
    e.setCorner(0, MacroState{0.5f, 0.6f, 0.5f, 0.0f});
    std::vector<float> square(100);
    for (int i = 0; i < 100; ++i) square[i] = (i % 10 < 5) ? 0.5f : -0.5f;
    e.loadSample(square.data(), square.size(), kRate);

    ControlFrame lowTilt;
    lowTilt.mode = 2;
    lowTilt.gate = true;
    lowTilt.a = 1;
    lowTilt.b = lowTilt.c = lowTilt.d = 0;
    lowTilt.tilt = 0.0f;
    e.pushControl(lowTilt);
    std::vector<float> lo;
    for (int i = 0; i < 400; ++i) lo = callback(e, 64);  // let the coefficients settle

    ControlFrame hiTilt = lowTilt;
    hiTilt.tilt = 1.0f;
    e.pushControl(hiTilt);
    std::vector<float> hi;
    for (int i = 0; i < 400; ++i) hi = callback(e, 64);

    CHECK(lo != hi);
}

TEST(surface_engine_crush_quantises_a_dc_level_the_more_it_is_turned_up) {
    // A DC level that is not a "nice" fraction at a coarse quantisation
    // step, so crush moving it is measurable rather than a coincidence.
    // A flat, unchanging signal isolates CRUSH's *quantisation* half from
    // its sample-and-hold half - sample-and-hold has nothing to do to a
    // signal that never changes anyway (see renderMono's own comment).
    auto settle = [](float crush) {
        SurfaceEngine e(kRate);
        std::vector<float> tone(100, 0.33f);
        e.loadSample(tone.data(), tone.size(), kRate);
        e.setCorner(0, MacroState{0.5f, 1.0f, 0.0f, 0.0f, crush, 0.0f});
        ControlFrame f;
        f.mode = 2;
        f.gate = true;
        f.a = 1.0f; f.b = f.c = f.d = 0.0f;
        e.pushControl(f);
        std::vector<float> out;
        for (int i = 0; i < 400; ++i) out = callback(e, 64);
        float sum = 0.0f;
        for (float v : out) sum += v;
        return sum / static_cast<float>(out.size());
    };

    const float transparent = settle(0.0f);
    const float crushed = settle(1.0f);
    // crush=1's ~8-level step is coarse enough to snap 0.33 measurably;
    // crush=0 bypasses quantisation entirely (see renderMono), so
    // `transparent` is exactly 0.33 run through drive/filter/gain, not
    // merely close to it.
    CHECK(std::fabs(crushed - transparent) > 0.005f);
}

TEST(surface_engine_crush_holds_samples_and_kills_alternation_above_the_hold_rate) {
    // The quantisation test above deliberately uses a signal that never
    // changes, so it cannot see CRUSH's *other* half - sample-and-hold.
    // A square wave that flips sign every 10 samples does: at crush = 1
    // each hold spans ~25 samples, more than two full periods of the
    // wave, so most of its sign flips get eaten inside a single held
    // value and never reach the filter at all. Counting sign changes in
    // the settled output - not amplitude, which a pure hold does not
    // change - isolates exactly that effect: an implementation that
    // dropped crushPhase_ and only quantised would still flip on every
    // sample and fail this test even while passing the one above.
    auto countCrossings = [](const std::vector<float>& v) {
        int crossings = 0;
        for (size_t i = 1; i < v.size(); ++i) {
            if ((v[i] > 0.0f) != (v[i - 1] > 0.0f)) ++crossings;
        }
        return crossings;
    };
    auto settle = [](float crush) {
        SurfaceEngine e(kRate);
        std::vector<float> square(100);
        for (int i = 0; i < 100; ++i) square[i] = (i % 10 < 5) ? 0.5f : -0.5f;
        e.loadSample(square.data(), square.size(), kRate);
        e.setCorner(0, MacroState{0.5f, 1.0f, 0.0f, 0.0f, crush, 0.0f});
        ControlFrame f;
        f.mode = 2;
        f.gate = true;
        f.a = 1.0f; f.b = f.c = f.d = 0.0f;
        e.pushControl(f);
        for (int i = 0; i < 300; ++i) callback(e, 64);  // let gain/filter settle
        // Accumulate the left channel only across every callback in the
        // measurement window - callback's own output is stereo
        // interleaved, and both a bare `out = callback(...)` (keeping
        // only the last 64-frame chunk) and a raw walk through L,R,L,R
        // (comparing each frame's duplicated pair against itself, never
        // a crossing) would silently starve this count.
        std::vector<float> mono;
        for (int i = 0; i < 100; ++i) {
            auto out = callback(e, 64);
            for (size_t j = 0; j < out.size(); j += 2) mono.push_back(out[j]);
        }
        return mono;
    };

    const int transparentCrossings = countCrossings(settle(0.0f));
    const int crushedCrossings = countCrossings(settle(1.0f));
    CHECK(transparentCrossings > 500);              // the wave's own ~5-sample half-period, largely intact
    CHECK(crushedCrossings < transparentCrossings / 2);  // most flips eaten by the ~25-sample hold
}

TEST(surface_engine_echo_repeats_after_the_delay_and_only_when_wet) {
    // A tone held open, then released - once truly silent (the gate fully
    // closed), anything still audible can only be the delay line's own
    // stored tail, fed by the *gated* signal while the note was actually
    // sounding (see renderMono's own reasoning: this is why a released
    // touch's echoes keep ringing instead of cutting off with the gate,
    // and also why an unplayed pad never bleeds a phantom loop into it).
    // 400 callbacks (25600 samples) comfortably exceeds one full delay
    // length (220 ms => 10560 samples at kRate) so the ring buffer's
    // write pointer is guaranteed to wrap back through the loud segment
    // it recorded at least once inside the window this test checks.
    auto run = [](float echo) {
        SurfaceEngine e(kRate);
        std::vector<float> tone(2000, 0.5f);
        e.loadSample(tone.data(), tone.size(), kRate);
        e.setCorner(0, MacroState{0.5f, 1.0f, 0.0f, 0.0f, 0.0f, echo});
        ControlFrame on;
        on.mode = 2;
        on.gate = true;
        on.a = 1.0f; on.b = on.c = on.d = 0.0f;
        e.pushControl(on);
        for (int i = 0; i < 20; ++i) callback(e, 64);  // long enough for the gain envelope to fully open

        ControlFrame off = on;
        off.gate = false;
        e.pushControl(off);
        for (int i = 0; i < 40; ++i) callback(e, 64);  // and fully close again

        float peakAfterRelease = 0.0f;
        for (int i = 0; i < 400; ++i) peakAfterRelease = std::max(peakAfterRelease, peak(callback(e, 64)));
        return peakAfterRelease;
    };

    CHECK(run(0.0f) < 1e-4f);   // dry: released is released, nothing left to hear
    CHECK(run(1.0f) > 0.01f);   // wet: the tail is still there, ringing on its own
}

TEST(surface_engine_echo_repeats_specifically_after_the_fixed_delay_time) {
    // The test above only proves *something* is audible somewhere in a
    // wide window - a materially different delay length would still
    // pass it. This one finds exactly where the repeat lands: a brief
    // blip (gate open a handful of callbacks, then fully released)
    // rather than a long tone gives one localised feature to look for a
    // delayed copy of.
    SurfaceEngine e(kRate);
    std::vector<float> tone(200, 0.9f);
    e.loadSample(tone.data(), tone.size(), kRate);
    e.setCorner(0, MacroState{0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f});  // fully wet
    ControlFrame on;
    on.mode = 2;
    on.gate = true;
    on.a = 1.0f; on.b = on.c = on.d = 0.0f;
    e.pushControl(on);
    for (int i = 0; i < 10; ++i) callback(e, 64);  // a short, mostly gain-open blip

    ControlFrame off = on;
    off.gate = false;
    e.pushControl(off);

    // Render continuously from here (elapsed = 0 at the moment of
    // release) far enough to bracket the expected repeat at 10560
    // samples (220 ms at kRate); extract the left channel only -
    // callback's own output is stereo-interleaved.
    std::vector<float> elapsed;
    while (elapsed.size() < 16000) {
        auto chunk = callback(e, 64);
        for (size_t i = 0; i < chunk.size(); i += 2) elapsed.push_back(chunk[i]);
    }

    // Skip the first 1500 samples (~31 ms) - the blip's own release
    // tail, not its echo - before looking for the repeat's peak. Between
    // there and the repeat the delay line is reading back pure silence
    // (this is the first note this engine has ever played, so a full
    // 220 ms ago is still the buffer's original zero-fill), so there is
    // nothing else in this window for the search to mistake for it.
    size_t peakIndex = 1500;
    float peakValue = 0.0f;
    for (size_t i = 1500; i < elapsed.size(); ++i) {
        if (std::fabs(elapsed[i]) > peakValue) { peakValue = std::fabs(elapsed[i]); peakIndex = i; }
    }
    CHECK(peakValue > 0.01f);  // the repeat is actually audible
    // ±1500 samples (~31 ms) absorbs the gain envelope's own
    // attack/release shaping the blip, while still failing on a
    // materially different delay length.
    CHECK(peakIndex > 10560 - 1500);
    CHECK(peakIndex < 10560 + 1500);
}

// Where a fully wet ECHO's repeat of a short blip lands, in samples after
// the release - the search surface_engine_echo_repeats_specifically_after_
// the_fixed_delay_time does, as a function of the time asked for, so the
// synced times can be checked against the same yardstick.
static size_t echoRepeatAt(float seconds, size_t renderSamples) {
    SurfaceEngine e(kRate);
    e.setEchoTime(seconds);
    std::vector<float> tone(200, 0.9f);
    e.loadSample(tone.data(), tone.size(), kRate);
    e.setCorner(0, MacroState{0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f});
    ControlFrame on;
    on.mode = 2;
    on.gate = true;
    on.a = 1.0f; on.b = on.c = on.d = 0.0f;
    e.pushControl(on);
    for (int i = 0; i < 10; ++i) callback(e, 64);
    ControlFrame off = on;
    off.gate = false;
    e.pushControl(off);
    std::vector<float> elapsed;
    while (elapsed.size() < renderSamples) {
        auto chunk = callback(e, 64);
        for (size_t i = 0; i < chunk.size(); i += 2) elapsed.push_back(chunk[i]);
    }
    size_t peakIndex = 1500;
    float peakValue = 0.0f;
    for (size_t i = 1500; i < elapsed.size(); ++i) {
        if (std::fabs(elapsed[i]) > peakValue) { peakValue = std::fabs(elapsed[i]); peakIndex = i; }
    }
    CHECK(peakValue > 0.01f);
    return peakIndex;
}

TEST(surface_engine_echo_time_moves_the_repeat_to_the_time_asked_for) {
    // A tenth of a second is 4800 samples at kRate: the repeat lands there,
    // not at the free 10560. The ±1500 window is the fixed-time test's own.
    const size_t at = echoRepeatAt(0.1f, 12000);
    CHECK(at > 4800 - 1500);
    CHECK(at < 4800 + 1500);
    // A quarter of a bar at 120 BPM (0.5 s) - the sort of time EchoTime in
    // :shell hands over - lands at 24000.
    const size_t quarter = echoRepeatAt(0.5f, 30000);
    CHECK(quarter > 24000 - 1500);
    CHECK(quarter < 24000 + 1500);
}

TEST(surface_engine_echo_time_that_is_not_a_time_is_the_free_time) {
    // Zero, negative, NaN and more than the line holds are all the 220 ms
    // ECHO always had - a kit asking for the impossible keeps its echo.
    for (float bad : {0.0f, -1.0f, std::nanf(""), SurfaceEngine::kMaxEchoSeconds * 1.5f}) {
        const size_t at = echoRepeatAt(bad, 16000);
        CHECK(at > 10560 - 1500);
        CHECK(at < 10560 + 1500);
    }
    // And the ceiling itself is honoured: six seconds is 288000 samples.
    const size_t ceiling = static_cast<size_t>(SurfaceEngine::kMaxEchoSeconds * kRate);
    const size_t longest = echoRepeatAt(SurfaceEngine::kMaxEchoSeconds, ceiling + 12000);
    CHECK(longest > ceiling - 1500);
    CHECK(longest < ceiling + 1500);
}

TEST(surface_engine_echo_time_change_crossfades_rather_than_cutting) {
    // A steady tone through a fully wet echo, then the time changed under
    // it: the output's largest sample-to-sample step after the change is
    // no larger than the tone's own, because the old tap fades into the
    // new over kEchoFadeMs. A hard cut between two taps of a sine at
    // different phases would step by up to the whole amplitude at once.
    SurfaceEngine e(kRate);
    e.setEchoTime(0.1f);
    const auto tone = measure::sine(100.0f, kRate, kRate);
    e.loadSample(tone.data(), tone.size(), kRate);
    e.setCorner(0, MacroState{0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f});
    ControlFrame on;
    on.mode = 2;
    on.gate = true;
    on.a = 1.0f; on.b = on.c = on.d = 0.0f;
    e.pushControl(on);
    auto maxStep = [&](int callbacks) {
        float worst = 0.0f;
        float last = 0.0f;
        bool have = false;
        for (int i = 0; i < callbacks; ++i) {
            for (float v : measure::left(callback(e, 64))) {
                if (have) worst = std::max(worst, std::fabs(v - last));
                last = v;
                have = true;
            }
        }
        return worst;
    };
    maxStep(750);                       // a second: the gain open, the line full of repeats
    const float steady = maxStep(225);  // 0.3 s of steady state
    e.setEchoTime(0.15f);
    const float changed = maxStep(375); // 0.5 s spanning the change and its fade
    CHECK(steady > 1e-4f);              // there is a tone to measure
    CHECK(changed < steady * 1.5f);
}

TEST(surface_engine_spring_rings_a_tail_and_only_when_wet) {
    // Exactly ECHO's own transparency test, above, but for the reverb:
    // once the gate is fully closed, anything still audible can only be
    // the comb/allpass network's own stored energy, fed by the *gated*
    // signal while the note was sounding (see renderMono's own reasoning -
    // spring shares ECHO's exact logic here, right down to why a released
    // touch's tail keeps ringing instead of cutting off with the gate).
    // The network runs every sample regardless of `spring`'s own value
    // (see renderMono); multiplying its output by spring = 0 in the final
    // sum is what must silence it completely, not the network switching
    // off - so this doubles as spring's own bit-exact-at-zero proof.
    auto run = [](float spring) {
        SurfaceEngine e(kRate);
        std::vector<float> tone(2000, 0.5f);
        e.loadSample(tone.data(), tone.size(), kRate);
        e.setCorner(0, MacroState{0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, spring});
        ControlFrame on;
        on.mode = 2;
        on.gate = true;
        on.a = 1.0f; on.b = on.c = on.d = 0.0f;
        e.pushControl(on);
        for (int i = 0; i < 20; ++i) callback(e, 64);  // long enough for the gain envelope to fully open

        ControlFrame off = on;
        off.gate = false;
        e.pushControl(off);
        for (int i = 0; i < 40; ++i) callback(e, 64);  // and fully close again

        float peakAfterRelease = 0.0f;
        for (int i = 0; i < 400; ++i) peakAfterRelease = std::max(peakAfterRelease, peak(callback(e, 64)));
        return peakAfterRelease;
    };

    CHECK(run(0.0f) < 1e-4f);   // dry: released is released, nothing left to hear
    CHECK(run(1.0f) > 0.01f);   // wet: the room is still ringing on its own
}

TEST(surface_engine_spring_tail_decays_rather_than_looping_forever) {
    // Every comb's own feedback gain is under 1 (derived from
    // kSpringRt60Seconds - see configureSpring), so the tail must
    // actually die down rather than ringing at a fixed level or growing -
    // unlike ECHO's single repeat, there is no one moment to look for,
    // only whether the energy
    // shrinks between an early window and a much later one.
    SurfaceEngine e(kRate);
    std::vector<float> tone(2000, 0.5f);
    e.loadSample(tone.data(), tone.size(), kRate);
    e.setCorner(0, MacroState{0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f});  // fully wet
    ControlFrame on;
    on.mode = 2;
    on.gate = true;
    on.a = 1.0f; on.b = on.c = on.d = 0.0f;
    e.pushControl(on);
    for (int i = 0; i < 20; ++i) callback(e, 64);
    ControlFrame off = on;
    off.gate = false;
    e.pushControl(off);
    for (int i = 0; i < 40; ++i) callback(e, 64);

    float peakEarly = 0.0f;
    for (int i = 0; i < 100; ++i) peakEarly = std::max(peakEarly, peak(callback(e, 64)));  // the first ~130 ms of tail
    // Several RT60s further on (kSpringRt60Seconds ~= 0.457 s): skip ahead
    // without measuring, then measure a late window.
    for (int i = 0; i < 2000; ++i) callback(e, 64);  // ~2.7 s of silence into the room
    float peakLate = 0.0f;
    for (int i = 0; i < 100; ++i) peakLate = std::max(peakLate, peak(callback(e, 64)));

    CHECK(peakEarly > 0.01f);        // the room was actually excited
    CHECK(peakLate < peakEarly / 4);  // and has decayed well down by ~2.7 s later, not sustained or growing
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
    e.setCorner(0, MacroState{nan, nan, nan, nan, nan, nan, nan});
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
    // last frame. Either way it never ends, and either way the loop is the
    // window's [loopStart, end): frame loopStart is played and the period
    // is end - loopStart.
    PadEngine& e = seeded();
    PadCommand c = reversed(2, 0, 0, 200);
    c.loopStart = 150;
    e.pushCommand(c);
    // 199, 198 ... 150, then 199 again: a period of fifty.
    const auto first = callback(e, 120);
    for (int i = 0; i < 120; ++i) {
        const float want = static_cast<float>(199 - (i % 50)) / 1000.0f;
        CHECK_NEAR(first[static_cast<size_t>(2 * i)], want, 1e-6);
    }
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

TEST(pad_engine_a_backwards_loop_interpolates_the_seam_and_survives_a_fast_voice) {
    // Half speed backwards over [150, 200): position 199.5 sits between the last frame (199) and the
    // loop's first (150), and reads their mean; the walk down is 150.0, then 199.5 (wrapped), 199.0, 198.5.
    // At 198.5 the cubic's far tap is two frames on from 198: inside the loop that is its first frame (150),
    // never frame 200 past the window (which would read 0.1985) nor the last frame held (0.1985625).
    PadEngine& e = seeded();
    PadCommand c = reversed(80, 0, 0, 200);
    c.loopStart = 150;
    c.pitch = 0.5;
    e.pushCommand(c);
    const auto out = callback(e, 104);
    CHECK_NEAR(out[2 * 98], 0.150f, 1e-6);
    CHECK_NEAR(out[2 * 99], 0.1745f, 1e-6);
    CHECK_NEAR(out[2 * 100], 0.199f, 1e-6);
    CHECK_NEAR(out[2 * 101], 0.201625f, 1e-6);  // (-0.197 + 9 * 0.198 + 9 * 0.199 - 0.150) / 16
    CHECK(ended(e).empty());
    e.pushCommand(stop(80, 1.0f));
    callback(e, 64);
    ended(e);
    // A two-frame loop [8, 10) walked backwards at 4x crosses its bottom more than once per frame.
    PadCommand fast = reversed(83, 0, 0, 10);
    fast.loopStart = 8;
    fast.pitch = 4.0;
    e.pushCommand(fast);
    const auto held = callback(e, 64);
    for (int i = 0; i < 64; ++i) {
        const float v = held[static_cast<size_t>(2 * i)];
        CHECK(v >= 0.00799f && v <= 0.00901f);
    }
    CHECK(ended(e).empty());
}

TEST(pad_engine_a_backwards_loop_whose_wrap_rounds_up_to_the_end_keeps_sounding) {
    // 604 frames at 44.1 kHz in a 48 kHz engine, looping from 0, backwards: the step is 44100/48000, not a
    // dyadic fraction, so the read reaches integer positions with rounding error, and at output frame 30240
    // the add that wraps it back lands on exactly `end`. That is the loop's first frame, not a frame past the
    // window: the voice must keep sounding (it used to be ended, silently).
    std::vector<float> r(604);
    for (int i = 0; i < 604; ++i) r[static_cast<size_t>(i)] = 0.1f + static_cast<float>(i) / 10000.0f;
    PadEngine e(kRate);
    e.beginBank();
    e.addSample(std::move(r), 1, 44100);
    e.commitBank();
    callback(e, 8);
    ended(e);
    PadCommand c = reversed(85, 0, 0, 604);
    c.loopStart = 0;
    e.pushCommand(c);
    float quietest = 1.0f;
    for (int block = 0; block < 75; ++block) {  // 60000 frames: past frame 30240
        const auto out = callback(e, 800);
        for (int i = 0; i < 800; ++i) quietest = std::min(quietest, out[static_cast<size_t>(2 * i)]);
    }
    CHECK(quietest > 0.09f);
    CHECK(ended(e).empty());
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

TEST(pad_engine_a_fader_glides_and_arrives_exactly) {
    // Sample 1 is a flat stereo 0.5 / -0.25, so the output *is* the gain.
    // A fader sends one of these per screen frame while a finger drags:
    // stepping would zipper, and retriggering would click.
    PadEngine& e = seeded();
    e.pushCommand(noteOn(5, 1, 0, 100, 1.0f, 1.0f));
    auto out = callback(e, 1);
    CHECK_NEAR(out[0], 0.5f, 1e-5);

    PadCommand fader;
    fader.type = PadCommand::Type::SetGain;
    fader.voiceId = 5;
    fader.gainL = fader.gainR = 0.2f;
    fader.fadeMs = 1.0f;  // 48 frames at 48 kHz
    e.pushCommand(fader);

    // Half way through the glide it is half way there - the arrival is a
    // straight line, not an ever-slowing curve.
    out = callback(e, 24);
    CHECK_NEAR(out[0], 0.5f * (1.0f - 0.8f / 48.0f), 1e-4);  // one sample in
    CHECK_NEAR(out[2 * 23], 0.5f * 0.6f, 1e-4);              // twenty-four in

    // And it lands on the number asked for, then stays there.
    out = callback(e, 48);
    CHECK_NEAR(out[2 * 47], 0.5f * 0.2f, 1e-4);
    out = callback(e, 8);
    CHECK_NEAR(out[0], 0.5f * 0.2f, 1e-4);
    CHECK_NEAR(out[1], -0.25f * 0.2f, 1e-4);
}

TEST(pad_engine_a_fader_on_a_voice_that_is_gone_is_ignored) {
    PadEngine& e = seeded();
    PadCommand fader;
    fader.type = PadCommand::Type::SetGain;
    fader.voiceId = 99;  // never started
    fader.gainL = fader.gainR = 1.0f;
    e.pushCommand(fader);
    CHECK_NEAR(peak(callback(e, 32)), 0.0f, 1e-7);
    CHECK(ended(e).empty());  // and it is not an ending, either
}

namespace {

/** A mono ramp i/100000 over 60000 frames: the output is the read position, scaled, well inside full scale. */
std::vector<float> slowRamp() {
    std::vector<float> v(60000);
    for (size_t i = 0; i < v.size(); ++i) v[i] = static_cast<float>(i) / 100000.0f;
    return v;
}

PadCommand bend(double ratio, float glideMs) {
    PadCommand c;
    c.type = PadCommand::Type::SetPitch;
    c.pitch = ratio;
    c.fadeMs = glideMs;
    return c;
}

PadCommand master(float gain, float glideMs) {
    PadCommand c;
    c.type = PadCommand::Type::SetMaster;
    c.gainL = gain;
    c.fadeMs = glideMs;
    return c;
}

/** Frames read per output frame between out[i-1] and out[i], on [slowRamp]. */
double speedAt(const std::vector<float>& out, size_t i) {
    return (static_cast<double>(out[2 * i]) - static_cast<double>(out[2 * (i - 1)])) * 100000.0;
}

}  // namespace

TEST(pad_engine_a_bend_slides_a_held_note_without_restarting_it) {
    // The output is the read position, so a restart would show as a drop
    // back to frame 0 and a jump as a gap: a bend must be neither, only the
    // read going faster.
    PadEngine& e = banked(slowRamp(), 1, kRate);
    e.pushCommand(noteOn(1, 0, 0, 60000));
    auto out = callback(e, 64);
    CHECK_NEAR(speedAt(out, 63), 1.0, 1e-2);
    const float before = out[2 * 63];

    e.pushCommand(bend(2.0, 1.0f));  // an octave up over 48 frames
    out = callback(e, 96);
    // The first frame carries on from the last one at the old speed (the
    // step into it was taken before the bend arrived): no restart, no jump.
    // The glide's first step is the next one.
    CHECK_NEAR((out[0] - before) * 100000.0, 1.0, 1e-2);
    CHECK_NEAR(speedAt(out, 1), 1.0 + 1.0 / 48.0, 1e-2);
    // Half way through the glide it reads half way between the speeds, and
    // at its end exactly twice as fast; every step on the way is in between.
    CHECK_NEAR(speedAt(out, 24), 1.5, 3e-2);
    for (size_t i = 1; i < 96; ++i) {
        CHECK(speedAt(out, i) > 0.97);
        CHECK(speedAt(out, i) < 2.03);
    }
    CHECK_NEAR(speedAt(out, 95), 2.0, 2e-2);

    // Let go of the wheel: back to the note's own speed, the same way.
    e.pushCommand(bend(1.0, 1.0f));
    out = callback(e, 96);
    CHECK_NEAR(speedAt(out, 95), 1.0, 2e-2);
    CHECK(ended(e).empty());
}

TEST(pad_engine_a_bend_holds_for_notes_started_under_it_and_scales_their_own_tune) {
    // A note struck while the wheel is up starts bent, as on any synth, and
    // the bend multiplies the speed its key and tune already gave it.
    PadEngine& e = banked(slowRamp(), 1, kRate);
    e.pushCommand(bend(1.5, 1.0f));
    callback(e, 64);
    e.pushCommand(noteOn(2, 0, 0, 60000, 1.0f, 1.0f, 0.5));
    auto out = callback(e, 32);
    CHECK_NEAR(speedAt(out, 31), 0.75, 1e-2);
}

TEST(pad_engine_a_bent_sine_has_no_click) {
    // The plan's test: a held note bent up two semitones over the glide a
    // strip's stream gets, and no sample-to-sample step bigger than the
    // fastest the bent sine itself can move. A restart, a skipped read or
    // a discontinuous speed would each be a step far past that bound.
    std::vector<float> sine(48000);
    const double w = 2.0 * 3.14159265358979 * 440.0 / kRate;
    for (size_t i = 0; i < sine.size(); ++i) sine[i] = static_cast<float>(0.5 * std::sin(w * static_cast<double>(i)));
    PadEngine& e = banked(std::move(sine), 1, kRate);
    e.pushCommand(noteOn(3, 0, 0, 48000));
    auto first = callback(e, 100);
    const double up = std::pow(2.0, 2.0 / 12.0);
    e.pushCommand(bend(up, 8.0f));
    auto out = callback(e, 1000);
    float prev = first[2 * 99];
    const double bound = 0.5 * w * up * 1.05;
    double worst = 0.0;
    for (size_t i = 0; i < 1000; ++i) {
        worst = std::max(worst, static_cast<double>(std::fabs(out[2 * i] - prev)));
        prev = out[2 * i];
    }
    CHECK(worst < bound);
    CHECK(worst > 0.5 * w);  // and it did get faster than the unbent sine
}

TEST(pad_engine_a_bend_that_is_not_a_speed_is_ignored_and_a_huge_one_clamped) {
    PadEngine& e = banked(slowRamp(), 1, kRate);
    e.pushCommand(noteOn(4, 0, 0, 60000));
    callback(e, 16);
    e.pushCommand(bend(std::nan(""), 1.0f));
    e.pushCommand(bend(0.0, 1.0f));
    e.pushCommand(bend(-2.0, 1.0f));
    auto out = callback(e, 32);
    CHECK_NEAR(speedAt(out, 31), 1.0, 1e-2);
    e.pushCommand(bend(1e9, 0.0f));
    out = callback(e, 32);
    CHECK_NEAR(speedAt(out, 31), PadEngine::kMaxBend, 4e-2);
}

namespace {
/** The speed over the 16 frames to i: a float ramp's rounding is ~3e-3 a frame, which a window of 16 divides down. */
double speedWin(const std::vector<float>& out, size_t i) {
    return (static_cast<double>(out[2 * i]) - static_cast<double>(out[2 * (i - 16)])) * 100000.0 / 16.0;
}

PadCommand vibrato(double semitones, float glideMs) {
    PadCommand c;
    c.type = PadCommand::Type::SetVibrato;
    c.pitch = semitones;
    c.fadeMs = glideMs;
    return c;
}
}  // namespace

TEST(pad_engine_vibrato_wobbles_a_held_note_around_its_own_speed_at_the_stated_depth_and_rate) {
    PadEngine& e = banked(slowRamp(), 1, kRate);
    e.pushCommand(noteOn(1, 0, 0, 60000));
    callback(e, 16);
    e.pushCommand(vibrato(0.5, 0.0f));  // half a semitone either way, at once
    // One period at 5.5 Hz is 8727 frames; read two.
    std::vector<float> all;
    for (int block = 0; block < 4; ++block) {
        auto out = callback(e, 4400);
        all.insert(all.end(), out.begin(), out.end());
    }
    double lo = 1e9, hi = -1e9, mean = 0.0;
    size_t n = 0;
    for (size_t i = 16; i < all.size() / 2; ++i, ++n) {
        const double sp = speedWin(all, i);
        lo = std::min(lo, sp);
        hi = std::max(hi, sp);
        mean += sp;
    }
    mean /= static_cast<double>(n);
    const double top = std::pow(2.0, 0.5 / 12.0);
    CHECK_NEAR(hi, top, 2e-3);
    CHECK_NEAR(lo, 1.0 / top, 2e-3);
    CHECK_NEAR(mean, 1.0, 2e-3);
    // The rate: one swing up past +1% per period, with hysteresis so the ramp's rounding noise is not counted.
    int ups = 0;
    bool armed = true;
    for (size_t i = 16; i < all.size() / 2; ++i) {
        const double sp = speedWin(all, i);
        if (sp < 0.99) armed = true;
        if (armed && sp > 1.01) { ++ups; armed = false; }
    }
    CHECK(ups == 2);  // 17600 frames is just over two periods (8727 each): the swings up peak at 2182 and 10909
    CHECK(ended(e).empty());
}

TEST(pad_engine_vibrato_glides_in_and_out_and_leaves_the_note_exactly_as_it_was) {
    PadEngine& e = banked(slowRamp(), 1, kRate);
    e.pushCommand(noteOn(1, 0, 0, 60000));
    callback(e, 64);
    e.pushCommand(vibrato(1.0, 100.0f));  // in over 4800 frames
    auto out = callback(e, 1200);
    double early = 0.0;
    for (size_t i = 16; i < 200; ++i) early = std::max(early, std::fabs(speedWin(out, i) - 1.0));
    double later = 0.0;
    for (size_t i = 1000; i < 1200; ++i) later = std::max(later, std::fabs(speedWin(out, i) - 1.0));
    CHECK(early < 0.002);   // it does not start with a jump
    CHECK(later > early);   // and it grows
    // Once the glide is over it is at the full depth asked for, not stuck part way.
    callback(e, 4000);
    double full = 0.0;
    for (int block = 0; block < 3; ++block) {
        out = callback(e, 3000);
        for (size_t i = 16; i < 3000; ++i) full = std::max(full, std::fabs(speedWin(out, i) - 1.0));
    }
    CHECK_NEAR(full, std::pow(2.0, 1.0 / 12.0) - 1.0, 4e-3);
    e.pushCommand(vibrato(0.0, 10.0f));
    callback(e, 960);
    out = callback(e, 512);
    for (size_t i = 16; i < 512; ++i) CHECK_NEAR(speedWin(out, i), 1.0, 1e-3);
}

TEST(pad_engine_vibrato_and_bend_multiply_and_a_bad_depth_is_ignored_or_clamped) {
    PadEngine& e = banked(slowRamp(), 1, kRate);
    e.pushCommand(noteOn(1, 0, 0, 60000));
    callback(e, 16);
    e.pushCommand(bend(2.0, 0.0f));
    e.pushCommand(vibrato(std::nan(""), 0.0f));
    e.pushCommand(vibrato(-1.0, 0.0f));
    auto out = callback(e, 64);
    CHECK_NEAR(speedAt(out, 63), 2.0, 2e-2);  // the bend alone: NaN ignored, negative clamped to off
    e.pushCommand(vibrato(50.0, 0.0f));
    double hi = 0.0;
    for (int block = 0; block < 4; ++block) {
        out = callback(e, 2400);
        for (size_t i = 16; i < 2400; ++i) hi = std::max(hi, speedWin(out, i));
    }
    CHECK_NEAR(hi, 2.0 * std::pow(2.0, PadEngine::kMaxVibratoSemitones / 12.0), 5e-3);
}

TEST(pad_engine_the_master_gain_glides_the_whole_mix_and_outlives_a_kit_swap) {
    // Sample 1 is a flat 0.5 / -0.25, so the output is the gain.
    PadEngine& e = seeded();
    e.pushCommand(noteOn(5, 1, 0, 100));
    e.pushCommand(noteOn(6, 1, 0, 100, 0.5f, 0.5f));
    auto out = callback(e, 1);
    CHECK_NEAR(out[0], 0.75f, 1e-5);
    e.pushCommand(master(0.2f, 1.0f));
    out = callback(e, 48);
    CHECK_NEAR(out[2 * 23], 0.75f * 0.6f, 1e-3);  // half way, a straight line
    CHECK_NEAR(out[2 * 47], 0.75f * 0.2f, 1e-4);
    CHECK_NEAR(out[2 * 47 + 1], -0.375f * 0.2f, 1e-4);

    // Not a number leaves it; louder than made is held at made.
    e.pushCommand(master(std::nanf(""), 1.0f));
    out = callback(e, 4);
    CHECK_NEAR(out[0], 0.75f * 0.2f, 1e-4);

    // A new kit keeps the volume: it is the engine's, not the bank's.
    e.beginBank();
    std::vector<float> flat(200, 0.5f);
    e.addSample(std::move(flat), 1, kRate);
    e.commitBank();
    callback(e, 8);
    e.pushCommand(noteOn(7, 0, 0, 200));
    out = callback(e, 4);
    CHECK_NEAR(out[0], 0.5f * 0.2f, 1e-4);
    e.pushCommand(master(3.0f, 0.0f));
    out = callback(e, 4);
    CHECK_NEAR(out[2 * 3], 0.5f, 1e-4);
}

// ---- SPLIT: the engine and the offline render agree -----------------------------

namespace {

/**
 * `Layers.render`'s rule, transcribed from the Kotlin doc rather than read
 * off the engine: every part at its gain, frames reversed where its strip
 * says so, summed. Written here independently on purpose - if this were
 * derived from `render()` below it would agree with anything.
 */
std::vector<float> deskRender(const std::vector<std::vector<float>>& parts,
                              const std::vector<float>& gains,
                              const std::vector<bool>& reverse) {
    // One strip per part, and every part one length - the shapes this
    // indexes on. A caller that disagrees has drifted, and should say so
    // rather than read off the end of a buffer and take the whole suite
    // down with it. Arity is deliberately not checked: SPLIT's desk is
    // three (Layers.Part has exactly three entries), but a two-part
    // variant would be a fair test to write and nothing here would be
    // unsafe for it.
    CHECK(!parts.empty());
    CHECK(gains.size() == parts.size());
    CHECK(reverse.size() == parts.size());
    if (parts.empty() || gains.size() != parts.size() || reverse.size() != parts.size()) return {};
    const size_t frames = parts[0].size();
    for (const auto& p : parts) CHECK(p.size() == frames);
    for (const auto& p : parts) if (p.size() != frames) return {};
    std::vector<float> out(frames, 0.0f);
    for (size_t p = 0; p < parts.size(); ++p) {
        if (gains[p] <= 0.0f) continue;
        for (size_t f = 0; f < frames; ++f) {
            out[f] += parts[p][reverse[p] ? frames - 1 - f : f] * gains[p];
        }
    }
    return out;
}

}  // namespace

TEST(pad_engine_a_split_mix_is_the_offline_render_sample_for_sample) {
    // SPLIT's whole promise, and the one thing nothing checked: what the
    // phone plays and what PRINT writes are the same mix. PRINT is
    // `Layers.render` on the JVM; playback is three voices here. The
    // existing group case proves they *start* together by reading one
    // sample - this reads the whole window, which is where a reversed
    // read that is off by a frame, or a gain applied to the wrong layer,
    // actually shows.
    constexpr int64_t kFrames = 64;
    std::vector<std::vector<float>> parts(3, std::vector<float>(kFrames));
    for (int64_t i = 0; i < kFrames; ++i) {
        const auto f = static_cast<size_t>(i);
        parts[0][f] = static_cast<float>(i) / 1000.0f;             // a ramp: the value is the frame
        parts[1][f] = static_cast<float>(i % 7) / 100.0f;          // a short cycle
        parts[2][f] = static_cast<float>((i * 13) % 11) / 100.0f;  // a longer, coprime one
    }
    // A desk somebody would actually set: the body backwards under a
    // forward transient, the air lifted above unity (Layers allows 2).
    const std::vector<float> gains = {1.0f, 0.5f, 1.5f};
    const std::vector<bool> reverse = {true, false, false};

    PadEngine e(kRate);
    e.beginBank();
    for (const auto& p : parts) e.addSample(std::vector<float>(p), 1, kRate);
    e.commitBank();
    callback(e, 8);  // adopt
    ended(e);

    PadCommand group[3];
    for (int i = 0; i < 3; ++i) {
        group[i] = noteOn(20 + i, i, 0, kFrames, gains[static_cast<size_t>(i)], gains[static_cast<size_t>(i)]);
        group[i].reverse = reverse[static_cast<size_t>(i)];
    }
    CHECK(e.pushCommands(group, 3));

    // Drained in two callbacks, because a buffer boundary is exactly where
    // a per-callback slip would hide. Both channels are kept: SPLIT sends
    // one level per strip to both, so a gain applied to the left alone -
    // or an interleave off by one - is a mix nobody asked for, and reading
    // only `out[2 * f]` would never see it.
    std::vector<float> heardL, heardR;
    for (int c = 0; c < 2; ++c) {
        auto out = callback(e, static_cast<int32_t>(kFrames / 2));
        for (size_t i = 0; i < out.size(); i += 2) {
            heardL.push_back(out[i]);
            heardR.push_back(out[i + 1]);
        }
    }

    const std::vector<float> printed = deskRender(parts, gains, reverse);
    CHECK_EQ(static_cast<int>(heardL.size()), static_cast<int>(printed.size()));
    CHECK_EQ(static_cast<int>(heardR.size()), static_cast<int>(printed.size()));
    for (size_t f = 0; f < printed.size(); ++f) {
        CHECK_NEAR(heardL[f], printed[f], 1e-6);
        CHECK_NEAR(heardR[f], printed[f], 1e-6);
    }

    // The window is the window: nothing sounds past it, and all three
    // layers report their ending rather than leaving ids with the allocator.
    CHECK_NEAR(peak(callback(e, 16)), 0.0f, 1e-7);
    auto ids = ended(e);
    CHECK_EQ(static_cast<int>(ids.size()), 3);
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

// ---- GRAIN -------------------------------------------------------------------

namespace {

// C major, A minor and C minor pentatonic as 12-bit degree masks - bit d
// set means d semitones above the root is in the scale. Spelled out as
// the degrees rather than as hex so a reader can check them against
// Scales.kt in :audio, which is where the app's own copy lives.
uint32_t maskOf(std::initializer_list<int> degrees) {
    uint32_t m = 0;
    for (int d : degrees) m |= 1u << d;
    return m;
}
const uint32_t kMajor = maskOf({0, 2, 4, 5, 7, 9, 11});
const uint32_t kMinor = maskOf({0, 2, 3, 5, 7, 8, 10});
const uint32_t kMajorPentatonic = maskOf({0, 2, 4, 7, 9});
const uint32_t kMinorPentatonic = maskOf({0, 3, 5, 7, 10});

/** A GRAIN control frame: finger at (x, y), phone flat, gate as given. */
ControlFrame grainFrame(float x, float y, bool gate) {
    ControlFrame f;
    f.mode = 4;
    f.x = x;
    f.y = y;
    f.tilt = 0.5f;
    f.gate = gate;
    return f;
}

/** The left channel of `frames` frames of callbacks, 64 at a time, appended in order. */
std::vector<float> render(SurfaceEngine& e, int32_t frames) {
    std::vector<float> mono;
    mono.reserve(static_cast<size_t>(frames));
    int32_t done = 0;
    while (done < frames) {
        const int32_t n = std::min(64, frames - done);
        for (float v : measure::left(callback(e, n))) mono.push_back(v);
        done += n;
    }
    return mono;
}

/**
 * One 250 ms grain, alone, of a pad whose own note is MIDI 70.4 (an A#
 * forty cents sharp), read at the pitch axis's centre - so the only thing
 * deciding the pitch heard is what the key snaps 70.4 to. DENSITY at its
 * floor keeps the next grain half a second away, SPRAY 0 starts it at
 * frame 0, and the measurement window sits in the middle of the window
 * where the Hann envelope is healthy and the gate and filter have long
 * settled.
 */
double grainHzUnderKey(int32_t root, uint32_t mask) {
    SurfaceEngine e(kRate);
    const float sourceMidi = 70.4f;
    const float sourceHz = 440.0f * std::exp2((sourceMidi - 69.0f) / 12.0f);
    auto tone = measure::sine(sourceHz, kRate, kRate);
    e.loadSample(tone.data(), tone.size(), kRate);
    e.setGrain(GrainSettings{1.0f, 0.0f, 0.0f});
    e.setKey(KeySnap{root, mask, sourceMidi});
    e.pushControl(grainFrame(0.0f, 0.5f, true));
    const auto mono = render(e, 12000);
    return measure::hz(mono, 3000, 9000, kRate);
}

double midiHz(double midi) { return 440.0 * std::exp2((midi - 69.0) / 12.0); }

}  // namespace

TEST(grain_knobs_read_as_lengths_and_rates) {
    CHECK_NEAR(grain::lengthMs(0.0f), grain::kMinLengthMs, 1e-4);
    CHECK_NEAR(grain::lengthMs(1.0f), grain::kMaxLengthMs, 1e-3);
    CHECK(grain::lengthMs(0.5f) > grain::lengthMs(0.25f));
    CHECK_NEAR(grain::rateHz(0.0f), grain::kMinRateHz, 1e-4);
    CHECK_NEAR(grain::rateHz(1.0f), grain::kMaxRateHz, 1e-3);
    CHECK(grain::rateHz(0.75f) > grain::rateHz(0.5f));
    // Exponential, not linear: the middle of the knob is the geometric
    // middle of the range, the way a length or a rate is heard.
    CHECK_NEAR(grain::lengthMs(0.5f), std::sqrt(grain::kMinLengthMs * grain::kMaxLengthMs), 1e-3);
    // A knob that is not a number is its floor, never a NaN length.
    CHECK_NEAR(grain::lengthMs(std::nanf("")), grain::kMinLengthMs, 1e-4);
    CHECK_NEAR(grain::rateHz(std::nanf("")), grain::kMinRateHz, 1e-4);
}

TEST(grain_snap_lands_on_the_nearest_degree_and_holds_the_lower_note_on_a_tie) {
    // C major: C# sits exactly between C and D, and the lower note wins
    // the tie every time rather than depending on float noise.
    CHECK_EQ(grain::snapToKey(61.0f, 0, kMajor), 60);
    CHECK_EQ(grain::snapToKey(61.4f, 0, kMajor), 62);
    CHECK_EQ(grain::snapToKey(60.6f, 0, kMajor), 60);
    CHECK_EQ(grain::snapToKey(59.9f, 0, kMajor), 60);
    CHECK_EQ(grain::snapToKey(66.0f, 0, kMajor), 65);  // F# to F: 65 and 67 tie, lower wins
    // A minor, root 9: A# (70) is a semitone from both A and B, so the
    // tie goes to A - degree 0 of the *root*, which is what makes the
    // root matter at all.
    CHECK_EQ(grain::snapToKey(70.0f, 9, kMinor), 69);
    CHECK_EQ(grain::snapToKey(70.3f, 9, kMinor), 71);
    // A pentatonic has three-semitone gaps; the middle of one still finds
    // a note, and the lower one on the tie.
    CHECK_EQ(grain::snapToKey(61.5f, 0, kMinorPentatonic), 60);
    CHECK_EQ(grain::snapToKey(62.0f, 0, kMinorPentatonic), 63);
    // No mask is no key: every note allowed, so this is plain rounding.
    CHECK_EQ(grain::snapToKey(61.4f, 0, 0u), 61);
    CHECK_EQ(grain::snapToKey(61.4f, 0, grain::kChromaticMask), 61);
    // A root outside 0..11 wraps rather than shifting the scale off the
    // pitch classes: 21 is A.
    CHECK_EQ(grain::snapToKey(70.0f, 21, kMinor), grain::snapToKey(70.0f, 9, kMinor));
    CHECK_EQ(grain::snapToKey(70.0f, -3, kMinor), grain::snapToKey(70.0f, 9, kMinor));
    // A target that is not a number does not throw the search off: it
    // reads as 0 and snaps to a real note near it.
    CHECK(std::abs(grain::snapToKey(std::nanf(""), 0, kMajor)) <= 12);
}

TEST(grain_pitch_ratio_retunes_the_source_onto_the_key) {
    // A pad 40 cents flat of C, at the axis's centre in C major: it comes
    // out *on* C, which is a ratio a little above one - the snap retunes,
    // it does not only quantise the transposition.
    CHECK_NEAR(grain::pitchRatio(0.5f, 59.6f, 0, kMajor), std::exp2(0.4f / 12.0f), 1e-5);
    // No key, no known source: the centre is exactly as recorded, and the
    // ends of the axis are exactly an octave either way.
    CHECK_NEAR(grain::pitchRatio(0.5f, 0.0f, 0, grain::kChromaticMask), 1.0f, 1e-6);
    CHECK_NEAR(grain::pitchRatio(1.0f, 0.0f, 0, grain::kChromaticMask), 2.0f, 1e-5);
    CHECK_NEAR(grain::pitchRatio(0.0f, 0.0f, 0, grain::kChromaticMask), 0.5f, 1e-5);
    // Between two semitones the axis holds the nearer one: a semitone
    // quantiser, not a glide.
    CHECK_NEAR(grain::pitchRatio(0.5f + 1.4f / 24.0f, 0.0f, 0, grain::kChromaticMask), std::exp2(1.0f / 12.0f), 1e-5);
    // In key, from a known source: A4 nudged up 1.6 semitones asks for
    // 70.6, and C major answers B (71) - two semitones up, not 1.6.
    CHECK_NEAR(grain::pitchRatio(0.5f + 1.6f / 24.0f, 69.0f, 0, kMajor), std::exp2(2.0f / 12.0f), 1e-5);
    // A source note that is not a number reads as 0 rather than poisoning the ratio.
    CHECK(std::isfinite(grain::pitchRatio(0.5f, std::nanf(""), 0, kMajor)));
}

TEST(grain_start_is_position_at_zero_spray_and_wraps_at_full) {
    // SPRAY 0 is exactly POSITION whatever the die says - the cloud can be
    // frozen on one spot, and the engine test below holds it to that.
    CHECK_NEAR(grain::startFraction(0.3f, 0.0f, 0.9f), 0.3f, 1e-6);
    CHECK_NEAR(grain::startFraction(0.3f, 0.0f, 0.1f), 0.3f, 1e-6);
    // Full SPRAY reaches half the source either way, and comes round the
    // loop rather than piling up at the ends.
    CHECK_NEAR(grain::startFraction(0.9f, 1.0f, 1.0f), 0.4f, 1e-5);
    CHECK_NEAR(grain::startFraction(0.1f, 1.0f, 0.0f), 0.6f, 1e-5);
    CHECK_NEAR(grain::startFraction(0.5f, 1.0f, 0.5f), 0.5f, 1e-5);
    // Always inside the source, never on its far edge.
    for (int i = 0; i <= 20; ++i) {
        for (int r = 0; r <= 10; ++r) {
            const float s = grain::startFraction(static_cast<float>(i) / 20.0f, 1.0f, static_cast<float>(r) / 10.0f);
            CHECK(s >= 0.0f && s < 1.0f);
        }
    }
    // The die itself: 0..1, and a state of 0 does not stick there.
    uint32_t state = 0;
    for (int i = 0; i < 1000; ++i) {
        const float r = grain::random01(state);
        CHECK(r >= 0.0f && r < 1.0f);
        CHECK(state != 0u);
    }
}

TEST(surface_engine_grain_mode_is_silent_until_gated_and_fires_on_the_touch) {
    SurfaceEngine e(kRate);
    std::vector<float> tone(1000, 0.5f);
    e.loadSample(tone.data(), tone.size(), kRate);
    // DENSITY at its floor: two grains a second, so a cloud that only
    // fired on the clock would leave the first half second of a touch
    // silent. SIZE at its floor keeps each grain to 10 ms.
    e.setGrain(GrainSettings{0.0f, 0.0f, 0.0f});
    e.pushControl(grainFrame(0.5f, 0.5f, false));
    CHECK_NEAR(peak(callback(e, 512)), 0.0f, 1e-7);  // ungated: nothing, not even a first grain
    e.pushControl(grainFrame(0.5f, 0.5f, true));
    // The touch-down arms the clock: the first grain is in the first 512
    // frames (10 ms of grain, the gate gliding open over it), not 24000
    // frames later.
    CHECK(peak(callback(e, 512)) > 0.02f);
    // And between grains the cloud is genuinely quiet - the source is DC,
    // so anything left here would be a grain that failed to end.
    render(e, 2000);
    CHECK(peak(callback(e, 4096)) < 1e-4f);
}

TEST(surface_engine_grain_pitch_follows_the_key) {
    // The same pad, the same touch, three keys: the note heard is the one
    // the key snaps the pad's own note to. 70.4 is A# forty cents sharp,
    // so no key rounds it to A# (466 Hz); C major has no A#, and pulls it
    // up to B (494 Hz, 0.6 away, against A at 1.4); C major pentatonic
    // has neither A# nor B, and falls back to A (440 Hz, 1.4 away against
    // C at 1.6). Three answers six percent apart, each read to well under
    // one.
    const double chromatic = grainHzUnderKey(0, grain::kChromaticMask);
    const double major = grainHzUnderKey(0, kMajor);
    const double pentatonic = grainHzUnderKey(0, kMajorPentatonic);
    CHECK_NEAR(chromatic, midiHz(70), midiHz(70) * 0.01);
    CHECK_NEAR(major, midiHz(71), midiHz(71) * 0.01);
    CHECK_NEAR(pentatonic, midiHz(69), midiHz(69) * 0.01);
    // And none of them is the pad as recorded: the snap always moved it.
    const double asRecorded = midiHz(70.4);
    CHECK(std::fabs(chromatic - asRecorded) > asRecorded * 0.015);
    CHECK(std::fabs(major - asRecorded) > asRecorded * 0.015);
}

TEST(surface_engine_grain_spray_zero_freezes_one_spot_and_spray_scatters) {
    // A ramp source names its own frame by its value, so a grain's peak
    // says where it started. SIZE at its floor (480 frames) under DENSITY
    // at its ceiling (one grain per 750 frames) gives one grain per
    // period with clear air between - each period's own peak is that
    // grain's start, in the source's own units.
    auto periodPeaks = [](float spray) {
        SurfaceEngine e(kRate);
        auto src = ramp(kRate);
        for (float& v : src) v *= 1000.0f / static_cast<float>(kRate);  // 0..1 over the whole source
        e.loadSample(src.data(), src.size(), kRate);
        e.setGrain(GrainSettings{0.0f, 1.0f, spray});
        e.pushControl(grainFrame(0.5f, 0.5f, true));
        render(e, 750 * 6);  // the gate and filter settle, the first grains go by
        const auto mono = render(e, 750 * 12);
        std::vector<float> peaks;
        for (size_t p = 0; p + 750 <= mono.size(); p += 750) {
            float m = 0.0f;
            for (size_t i = p; i < p + 750; ++i) m = std::max(m, mono[i]);
            peaks.push_back(m);
        }
        return peaks;
    };
    const auto frozen = periodPeaks(0.0f);
    CHECK_EQ(static_cast<int>(frozen.size()), 12);
    for (float p : frozen) {
        CHECK(p > 0.05f);                        // every period has its grain
        CHECK_NEAR(p, frozen.front(), 1e-3);     // and every grain read the same spot
    }
    const auto scattered = periodPeaks(1.0f);
    float lo = 1.0f, hi = 0.0f;
    for (float p : scattered) {
        lo = std::min(lo, p);
        hi = std::max(hi, p);
    }
    CHECK(hi - lo > 0.05f);  // the starts wander; the peaks with them
}

TEST(surface_engine_grain_density_and_size_fill_or_thin_the_cloud) {
    std::vector<float> dc(1000, 0.5f);
    // Both knobs at their floor: a 10 ms grain every half second, and
    // silence between - the filter's ringdown at a wide-open cutoff is
    // gone within a few samples of the grain ending.
    SurfaceEngine sparse(kRate);
    sparse.loadSample(dc.data(), dc.size(), kRate);
    sparse.setGrain(GrainSettings{0.0f, 0.0f, 0.0f});
    sparse.pushControl(grainFrame(0.5f, 0.5f, true));
    render(sparse, 2000);
    const auto gap = render(sparse, 18000);
    float gapPeak = 0.0f;
    for (float v : gap) gapPeak = std::max(gapPeak, std::fabs(v));
    CHECK(gapPeak < 1e-4f);
    // Both at their ceiling: 250 ms grains, 64 a second, sixteen sounding
    // at once - Hann windows at a sixteenth of their length apart sum to
    // a constant, so the cloud never dips, and each grain's own gain
    // (1/sqrt(16)) keeps the sum from piling up as the overlap grows.
    SurfaceEngine dense(kRate);
    dense.loadSample(dc.data(), dc.size(), kRate);
    dense.setGrain(GrainSettings{1.0f, 1.0f, 0.0f});
    dense.pushControl(grainFrame(0.5f, 0.5f, true));
    render(dense, 20000);
    const auto steady = render(dense, 20000);
    float lo = 10.0f, hi = 0.0f;
    for (float v : steady) {
        lo = std::min(lo, v);
        hi = std::max(hi, v);
    }
    CHECK(lo > 0.3f);
    CHECK(hi < 1.0f);
}

TEST(surface_engine_leaving_grain_mode_returns_to_the_loop) {
    std::vector<float> dc(1000, 0.5f);
    SurfaceEngine e(kRate);
    e.loadSample(dc.data(), dc.size(), kRate);
    e.setGrain(GrainSettings{1.0f, 1.0f, 1.0f});
    e.pushControl(grainFrame(0.5f, 0.5f, true));
    render(e, 20000);
    // Back to XY at the centre of the pad, still gated: the loop reads
    // its DC through the same chain, so after the macros glide the output
    // is what a fresh XY engine settles to - the cloud has gone.
    ControlFrame xy;
    xy.mode = 0;
    xy.x = 0.5f;
    xy.y = 1.0f;
    xy.tilt = 0.5f;
    xy.gate = true;
    e.pushControl(xy);
    render(e, 20000);
    const auto after = render(e, 4096);
    SurfaceEngine fresh(kRate);
    fresh.loadSample(dc.data(), dc.size(), kRate);
    fresh.pushControl(xy);
    render(fresh, 40000);
    const auto reference = render(fresh, 4096);
    double meanAfter = 0.0, meanReference = 0.0;
    for (float v : after) meanAfter += v;
    for (float v : reference) meanReference += v;
    meanAfter /= static_cast<double>(after.size());
    meanReference /= static_cast<double>(reference.size());
    CHECK(meanReference > 0.3);
    CHECK_NEAR(meanAfter, meanReference, 0.02);
}

TEST(surface_engine_grain_survives_knobs_a_key_and_a_finger_that_are_not_numbers) {
    // The same door the corners and the other macros have: a NaN knob is
    // its default, a NaN source note is 0, an empty mask is chromatic, a
    // root off the wheel wraps - and a NaN finger neither latches nor
    // silences the cloud.
    SurfaceEngine e(kRate);
    std::vector<float> tone(1000, 0.5f);
    e.loadSample(tone.data(), tone.size(), kRate);
    const float nan = std::nanf("");
    e.setGrain(GrainSettings{nan, nan, nan});
    e.setKey(KeySnap{-7, 0u, nan});
    ControlFrame bad = grainFrame(nan, std::numeric_limits<float>::infinity(), true);
    bad.tilt = nan;
    e.pushControl(bad);
    float p = 0.0f;
    for (int i = 0; i < 400; ++i) {
        auto out = callback(e, 64);
        for (float v : out) CHECK(std::isfinite(v));
        p = std::max(p, peak(out));
    }
    CHECK(p > 0.02f);
}

// ---- modulation ---------------------------------------------------------------

/**
 * The loop (XY, not GRAIN) of a pad whose own note is MIDI 70.4, with the
 * finger at [x] across the pad and KEY on or off, after the pitch smoother
 * has long settled: what the loop is heard playing. The window sits
 * before the one-second source wraps at any ratio the tests ask for.
 */
double loopHzUnderKey(int32_t root, uint32_t mask, float x, bool keySnap) {
    SurfaceEngine e(kRate);
    const float sourceMidi = 70.4f;
    const float sourceHz = 440.0f * std::exp2((sourceMidi - 69.0f) / 12.0f);
    auto tone = measure::sine(sourceHz, kRate, kRate);
    e.loadSample(tone.data(), tone.size(), kRate);
    e.setKey(KeySnap{root, mask, sourceMidi});
    e.setKeySnap(keySnap);
    ControlFrame f;
    f.mode = 0;
    f.x = x;
    f.y = 1.0f;
    f.tilt = 0.5f;
    f.gate = true;
    e.pushControl(f);
    const auto mono = render(e, 24000);
    return measure::hz(mono, 14000, 22000, kRate);
}

TEST(surface_engine_key_snaps_the_loop_pitch_and_off_plays_as_recorded) {
    // KEY off: the pad's own note, a little sharp, exactly as recorded.
    // KEY on with no key (chromatic): the nearest semitone, A# (70). Under
    // C major: B (71), the same answer the cloud gives (see
    // surface_engine_grain_pitch_follows_the_key) - one snap, in Grain.h.
    const double asRecorded = midiHz(70.4);
    CHECK_NEAR(loopHzUnderKey(0, grain::kChromaticMask, 0.5f, false), asRecorded, asRecorded * 0.01);
    CHECK_NEAR(loopHzUnderKey(0, kMajor, 0.5f, false), asRecorded, asRecorded * 0.01);
    CHECK_NEAR(loopHzUnderKey(0, grain::kChromaticMask, 0.5f, true), midiHz(70), midiHz(70) * 0.01);
    CHECK_NEAR(loopHzUnderKey(0, kMajor, 0.5f, true), midiHz(71), midiHz(71) * 0.01);
    // A semitone across the pad (1/24 of it) with KEY on: chromatic steps
    // to B (71); C major pentatonic has no B and lands on C (72), the
    // nearer of A (69) and C. KEY off just slides 71.4.
    const float upOne = 0.5f + 1.0f / 24.0f;
    CHECK_NEAR(loopHzUnderKey(0, grain::kChromaticMask, upOne, true), midiHz(71), midiHz(71) * 0.01);
    CHECK_NEAR(loopHzUnderKey(0, kMajorPentatonic, upOne, true), midiHz(72), midiHz(72) * 0.01);
    CHECK_NEAR(loopHzUnderKey(0, kMajorPentatonic, upOne, false), midiHz(71.4), midiHz(71.4) * 0.01);
}

TEST(surface_engine_key_snap_holds_a_note_across_the_pad_until_the_next_degree) {
    // Between two degrees the loop holds the lower one rather than sweeping:
    // a third of a semitone up from A# under C major is still B (the
    // nearest degree), a full step up is C# (73) - the ladder, not a slide.
    CHECK_NEAR(loopHzUnderKey(0, kMajor, 0.5f + 0.33f / 24.0f, true), midiHz(71), midiHz(71) * 0.01);
    CHECK_NEAR(loopHzUnderKey(0, kMajor, 0.5f + 2.0f / 24.0f, true), midiHz(72), midiHz(72) * 0.01);
}

/** A quiet A# sine as the loop in XY, wide open, held: what SWARM renders after the smoothers settle. */
std::vector<float> swarmRender(const SwarmSettings& swarm, int32_t frames) {
    SurfaceEngine e(kRate);
    auto tone = measure::sine(466.16f, kRate, kRate);
    for (float& v : tone) v *= 0.1f;
    e.loadSample(tone.data(), tone.size(), kRate);
    e.setSwarm(swarm);
    ControlFrame f;
    f.mode = 0;
    f.x = 0.5f;
    f.y = 1.0f;
    f.tilt = 0.5f;
    f.gate = true;
    e.pushControl(f);
    return render(e, frames);
}

/** The quietest and loudest RMS over 480-frame windows of [mono] between [from] and [to], as a ratio quiet/loud. */
double envelopeRatio(const std::vector<float>& mono, size_t from, size_t to) {
    double lo = 1e9, hi = 0.0;
    for (size_t start = from; start + 480 <= to; start += 480) {
        double acc = 0.0;
        for (size_t i = start; i < start + 480; ++i) acc += static_cast<double>(mono[i]) * static_cast<double>(mono[i]);
        const double rms = std::sqrt(acc / 480.0);
        lo = std::min(lo, rms);
        hi = std::max(hi, rms);
    }
    return hi > 0.0 ? lo / hi : 0.0;
}

TEST(surface_engine_swarm_of_one_is_the_plain_loop_sample_for_sample) {
    // One voice at any DETUNE is the loop as it always was - the untouched
    // surface must not change by a bit when SWARM exists but is off.
    const auto plain = swarmRender(SwarmSettings{}, 12000);
    const auto one = swarmRender(SwarmSettings{1, 0.7f}, 12000);
    CHECK_EQ(plain.size(), one.size());
    bool same = true;
    for (size_t i = 0; i < plain.size(); ++i) same = same && plain[i] == one[i];
    CHECK(same);
    // And a swarm that asks for more voices than exist clamps to the most.
    const auto many = swarmRender(SwarmSettings{99, 0.0f}, 12000);
    const auto four = swarmRender(SwarmSettings{4, 0.0f}, 12000);
    bool clamped = true;
    for (size_t i = 0; i < many.size(); ++i) clamped = clamped && many[i] == four[i];
    CHECK(clamped);
}

TEST(surface_engine_swarm_voice_joining_under_a_held_note_starts_where_the_loop_is) {
    // Two engines on the same one-second noise loop, run in lockstep: A
    // keeps one voice, B steps VOICES to 2 at DETUNE 0 a quarter second
    // into a held note. A joining voice that takes voice 0's phase reads
    // the same frames as voice 0, so B is sqrt(2) times A sample for
    // sample from the next control interval on. One that started from
    // the loop's head, or resumed from wherever it was parked, reads
    // frames from elsewhere in the loop, and noise from elsewhere is
    // uncorrelated - the residual against sqrt(2) A is then of the order
    // of the signal itself. Noise, not a tone: a tone cannot tell "the
    // same phase" from "any whole number of cycles apart", and the
    // offset a head-start gives is whatever the pitch glide left it.
    std::vector<float> noise(static_cast<size_t>(kRate));
    uint32_t lcg = 12345u;
    for (float& v : noise) {
        lcg = lcg * 1664525u + 1013904223u;
        v = (static_cast<float>(lcg >> 8) / 16777216.0f - 0.5f) * 0.2f;
    }
    auto held = [&]() {
        auto e = std::make_unique<SurfaceEngine>(kRate);
        e->loadSample(noise.data(), noise.size(), kRate);
        ControlFrame f;
        f.mode = 0;
        f.x = 0.5f;
        f.y = 1.0f;
        f.tilt = 0.5f;
        f.gate = true;
        e->pushControl(f);
        return e;
    };
    auto a = held();
    auto b = held();
    auto residual = [&](const std::vector<float>& one, const std::vector<float>& two, size_t from) {
        double num = 0.0, den = 0.0;
        for (size_t i = from; i < one.size() && i < two.size(); ++i) {
            const double ref = std::sqrt(2.0) * static_cast<double>(one[i]);
            const double d = static_cast<double>(two[i]) - ref;
            num += d * d;
            den += ref * ref;
        }
        return den > 0.0 ? std::sqrt(num / den) : 1.0;
    };
    render(*a, 12000);
    render(*b, 12000);
    b->setSwarm(SwarmSettings{2, 0.0f});
    const auto a1 = render(*a, 4800);
    const auto b1 = render(*b, 4800);
    CHECK(residual(a1, b1, 2400) < 0.1);
    // Dropped to one and re-joined a while later: the re-joining voice
    // takes voice 0's phase again rather than the phase it was parked at.
    b->setSwarm(SwarmSettings{1, 0.0f});
    render(*a, 7000);
    render(*b, 7000);
    b->setSwarm(SwarmSettings{2, 0.0f});
    const auto a2 = render(*a, 4800);
    const auto b2 = render(*b, 4800);
    CHECK(residual(a2, b2, 2400) < 0.1);
}

TEST(surface_engine_swarm_detune_beats_and_a_coherent_swarm_is_louder) {
    // Two voices a quarter tone apart at full DETUNE (±25 cents around
    // 466 Hz, 13.5 Hz apart) beat: the envelope swings from full to near
    // nothing every 74 ms. One voice holds a flat envelope. Three voices
    // at DETUNE 0 are coherent and sum to sqrt(3) the single's level - a
    // unison is louder, and the gain is 1/sqrt(n), not 1/n.
    const auto single = swarmRender(SwarmSettings{1, 0.0f}, 30000);
    const auto pair = swarmRender(SwarmSettings{2, 1.0f}, 30000);
    const auto trio = swarmRender(SwarmSettings{3, 0.0f}, 30000);
    CHECK(envelopeRatio(single, 6000, 30000) > 0.9);
    CHECK(envelopeRatio(pair, 6000, 30000) < 0.5);
    float peakSingle = 0.0f, peakTrio = 0.0f;
    for (size_t i = 6000; i < 30000; ++i) {
        peakSingle = std::max(peakSingle, std::fabs(single[i]));
        peakTrio = std::max(peakTrio, std::fabs(trio[i]));
    }
    CHECK_NEAR(peakTrio / peakSingle, std::sqrt(3.0f), 0.1);
}

TEST(surface_engine_swarm_survives_a_detune_that_is_not_a_number) {
    const auto plain = swarmRender(SwarmSettings{2, 0.0f}, 12000);
    const auto nan = swarmRender(SwarmSettings{2, std::nanf("")}, 12000);
    bool same = true;
    for (size_t i = 0; i < plain.size(); ++i) same = same && plain[i] == nan[i];
    CHECK(same);
}

TEST(surface_engine_modulation_nudges_a_macro_in_every_mode_and_clamps_at_the_rail) {
    // A 2 kHz sine through XY with the finger at cutoff 1.0 (16 kHz: it
    // passes untouched): a -1 offset on CUTOFF (index 1) closes the filter
    // to 80 Hz, twenty-five times below the tone, and the output all but
    // vanishes; +1 past an already-open cutoff clamps at the rail and
    // changes nothing. The same offset reaches MORPH, where the macro came
    // from a corner rather than the finger. Ten whole periods, so the
    // loop's seam is silent.
    const auto bright = measure::sine(2000.0f, 240, kRate);
    auto peakIn = [&](int32_t mode, float cutoffOffset) {
        SurfaceEngine e(kRate);
        e.loadSample(bright.data(), bright.size(), kRate);
        float offsets[SurfaceEngine::kModTargets] = {};
        offsets[1] = cutoffOffset;
        e.setModulation(offsets, SurfaceEngine::kModTargets);
        ControlFrame f;
        f.mode = mode;
        f.x = 0.5f; f.y = 1.0f; f.tilt = 0.5f;
        f.a = 1.0f; f.b = f.c = f.d = 0.0f;  // MORPH: corner A alone, which is CLEAN - cutoff 1.0
        f.gate = true;
        e.pushControl(f);
        render(e, 400 * 64);
        float p = 0.0f;
        for (float v : render(e, 50 * 64)) p = std::max(p, std::fabs(v));
        return p;
    };
    const float xyOpen = peakIn(0, 0.0f);
    CHECK(xyOpen > 0.1f);
    CHECK(peakIn(0, -1.0f) < xyOpen * 0.2f);
    CHECK_NEAR(peakIn(0, 1.0f), xyOpen, 1e-3);
    const float morphOpen = peakIn(2, 0.0f);
    CHECK(morphOpen > 0.1f);
    CHECK(peakIn(2, -1.0f) < morphOpen * 0.2f);
}

TEST(surface_engine_modulation_moves_the_cloud_and_a_nan_offset_is_nothing) {
    // A ramp source names its frame by its value, and a frozen cloud
    // (SPRAY 0) at POSITION 0.25 reads there; +0.5 on POSITION (index 10)
    // moves every grain to 0.75, so the per-period peaks (see the SPRAY
    // test) roughly triple. A NaN offset reads as 0 and leaves the cloud
    // where the finger put it.
    auto peakAt = [](float positionOffset) {
        SurfaceEngine e(kRate);
        auto src = ramp(kRate);
        for (float& v : src) v *= 1000.0f / static_cast<float>(kRate);
        e.loadSample(src.data(), src.size(), kRate);
        e.setGrain(GrainSettings{0.0f, 1.0f, 0.0f});
        float offsets[SurfaceEngine::kModTargets] = {};
        offsets[10] = positionOffset;
        e.setModulation(offsets, SurfaceEngine::kModTargets);
        e.pushControl(grainFrame(0.25f, 0.5f, true));
        render(e, 750 * 8);  // the position smoother and the gate settle
        float m = 0.0f;
        for (float v : render(e, 750 * 4)) m = std::max(m, v);
        return m;
    };
    const float here = peakAt(0.0f);
    const float moved = peakAt(0.5f);
    const float nan = peakAt(std::nanf(""));
    CHECK(here > 0.05f);
    CHECK(moved > here * 2.0f);
    CHECK_NEAR(nan, here, 1e-3);
}

TEST(surface_engine_modulation_reaches_grain_size_density_and_spray) {
    // DENSITY at its floor with +1 on DENSITY (index 8) is DENSITY at its
    // ceiling: the gaps between grains close (see the density test).
    std::vector<float> dc(1000, 0.5f);
    SurfaceEngine e(kRate);
    e.loadSample(dc.data(), dc.size(), kRate);
    e.setGrain(GrainSettings{0.0f, 0.0f, 0.0f});
    float offsets[SurfaceEngine::kModTargets] = {};
    offsets[7] = 1.0f;  // SIZE to its ceiling too, so sixteen overlap
    offsets[8] = 1.0f;
    e.setModulation(offsets, SurfaceEngine::kModTargets);
    e.pushControl(grainFrame(0.5f, 0.5f, true));
    render(e, 20000);
    float lo = 10.0f;
    for (float v : render(e, 20000)) lo = std::min(lo, v);
    CHECK(lo > 0.3f);
    // And SPRAY (index 9) from 0 to 1 scatters a frozen cloud: on a ramp,
    // the per-period peaks stop agreeing.
    SurfaceEngine s(kRate);
    auto src = ramp(kRate);
    for (float& v : src) v *= 1000.0f / static_cast<float>(kRate);
    s.loadSample(src.data(), src.size(), kRate);
    s.setGrain(GrainSettings{0.0f, 1.0f, 0.0f});
    float sprayOnly[SurfaceEngine::kModTargets] = {};
    sprayOnly[9] = 1.0f;
    s.setModulation(sprayOnly, SurfaceEngine::kModTargets);
    s.pushControl(grainFrame(0.5f, 0.5f, true));
    render(s, 750 * 6);
    const auto mono = render(s, 750 * 12);
    float pLo = 1.0f, pHi = 0.0f;
    for (size_t p = 0; p + 750 <= mono.size(); p += 750) {
        float m = 0.0f;
        for (size_t i = p; i < p + 750; ++i) m = std::max(m, mono[i]);
        pLo = std::min(pLo, m);
        pHi = std::max(pHi, m);
    }
    CHECK(pHi - pLo > 0.05f);
}

// ---------- LiveSnapEngine (PHOTO_SPECS.md §8) ----------

TEST(live_snap_engine_is_silent_with_no_table) {
    LiveSnapEngine e(kRate);
    const std::vector<float> out = callback(e, 512);
    for (float v : out) CHECK(v == 0.0f);
}

TEST(live_snap_engine_plays_once_a_table_arrives) {
    LiveSnapEngine e(kRate);
    float table[LiveSnapEngine::kTableSize];
    for (int32_t i = 0; i < LiveSnapEngine::kTableSize; ++i) {
        const float x = 2.0f * 3.14159265f * static_cast<float>(i) / static_cast<float>(LiveSnapEngine::kTableSize);
        table[i] = std::sin(x);
    }
    e.pushFrame(table, LiveSnapEngine::kTableSize);
    const std::vector<float> out = callback(e, 4096);
    CHECK(peak(out) > 0.05f);
}

TEST(live_snap_engine_pushFrame_clamps_a_mismatched_length) {
    LiveSnapEngine e(kRate);
    float shortTable[10];
    for (float& v : shortTable) v = 0.9f;
    // Far fewer than kTableSize: the rest of the adopted table reads as
    // rest (0), never whatever `new` left in that memory - if pushFrame
    // read past the 10 given, this would be the crash that proves it.
    e.pushFrame(shortTable, 10);
    const std::vector<float> out = callback(e, 4096);
    CHECK(peak(out) >= 0.0f);
}

TEST(live_snap_engine_crossfade_never_clicks) {
    LiveSnapEngine e(kRate);
    e.setMacros(0.5f, 0.6f, 0.0f);  // GRIT 0: the drive stage's own nonlinearity would obscure the bound below
    float tableA[LiveSnapEngine::kTableSize];
    float tableB[LiveSnapEngine::kTableSize];
    for (int32_t i = 0; i < LiveSnapEngine::kTableSize; ++i) {
        const float x = 2.0f * 3.14159265f * static_cast<float>(i) / static_cast<float>(LiveSnapEngine::kTableSize);
        tableA[i] = std::sin(x);
        tableB[i] = std::sin(x + 3.14159265f);  // = -tableA[i]: opposite everywhere, so an un-crossfaded swap would jump hard
    }
    e.pushFrame(tableA, LiveSnapEngine::kTableSize);
    callback(e, 4096);  // past the initial filter/phase settle, so what follows is the swap alone
    e.pushFrame(tableB, LiveSnapEngine::kTableSize);
    // Covers the whole ~50ms crossfade window (a bit over 2000 frames at kRate) in one capture.
    const std::vector<float> out = callback(e, 4096);
    float maxDelta = 0.0f;
    for (size_t i = 2; i < out.size(); i += 2) {  // stereo-interleaved; one channel, consecutive frames
        maxDelta = std::max(maxDelta, std::fabs(out[i] - out[i - 2]));
    }
    CHECK(maxDelta < 0.3f);
}

TEST(live_snap_engine_bright_opens_the_filter) {
    auto run = [](float bright) {
        LiveSnapEngine e(kRate);
        e.setMacros(0.5f, bright, 0.0f);
        float table[LiveSnapEngine::kTableSize];
        for (int32_t i = 0; i < LiveSnapEngine::kTableSize; ++i) {
            // A harmonically rich line - BRIGHT's own lowpass has real
            // high content to remove, or not.
            const float x = 2.0f * 3.14159265f * static_cast<float>(i) / static_cast<float>(LiveSnapEngine::kTableSize);
            table[i] = std::sin(x) + 0.5f * std::sin(3.0f * x) + 0.33f * std::sin(5.0f * x);
        }
        e.pushFrame(table, LiveSnapEngine::kTableSize);
        callback(e, 8192);  // past the filter's own settle and BRIGHT's own glide
        const std::vector<float> out = callback(e, 4096);
        double diffEnergy = 0.0;
        for (size_t i = 2; i < out.size(); i += 2) {
            const float d = out[i] - out[i - 2];
            diffEnergy += static_cast<double>(d) * d;
        }
        return diffEnergy;
    };
    const double dark = run(0.0f);
    const double lit = run(1.0f);
    CHECK(dark < lit);
}

static std::vector<float> aquariumIdle(const aquarium::PerformanceSettings& settings, int frames) {
    aquarium::PerformanceBus reference;
    CHECK(reference.publish(settings));
    CHECK(reference.prepare(kRate));
    std::vector<float> idle(static_cast<size_t>(frames) * 2, 0);
    reference.beginCallback();
    reference.process(idle.data(), frames);
    return idle;
}

TEST(pads_aquarium_processes_the_stereo_bus_once_and_prints_exactly_that_output) {
    PadEngine e(kRate);
    aquarium::PerformanceSettings s;
    CHECK(aquarium::setParameter(s.parameters, "aqua_tide_mix", 1));
    CHECK(aquarium::setParameter(s.parameters, "aqua_tide_depth", 0));
    s.enabled = true;
    s.outputGain = 0.25f;
    CHECK(e.configureAquarium(s));
    e.beginBank();
    CHECK_EQ(e.addSample(std::vector<float>(20000, 0.4f), 1, kRate), 0);
    e.commitBank();
    PadCommand c;
    c.sample = 0; c.voiceId = 1; c.end = 20000; c.gainL = 1; c.gainR = 0.5f;
    CHECK(e.pushCommand(c));
    callback(e, 4096);
    CHECK(e.armPrint(1024));
    const auto out = callback(e, 1024);
    CHECK_NEAR(out[0], 0.1f, 0.000001f);
    CHECK_NEAR(out[1], 0.05f, 0.000001f);
    CHECK_EQ(e.printFrames(), 1024u);
    CHECK_EQ(e.printSamples(), out.size());
    CHECK(std::memcmp(out.data(), e.printData(), out.size() * sizeof(float)) == 0);
    CHECK(e.printState() == PrintBuffer::State::Done);
    CHECK(!e.aquariumFaulted());
}

TEST(pads_aquarium_shared_nonlinear_processing_matches_one_premixed_voice) {
    PadEngine separate(kRate), summed(kRate);
    aquarium::PerformanceSettings s;
    s.parameters = aquarium::parametersForPreset(aquarium::Preset::Fathom);
    s.enabled = true;
    s.outputGain = 0.25f;
    CHECK(separate.configureAquarium(s));
    CHECK(summed.configureAquarium(s));
    separate.beginBank(); summed.beginBank();
    separate.addSample(std::vector<float>(16000, 0.25f), 1, kRate);
    summed.addSample(std::vector<float>(16000, 0.5f), 1, kRate);
    separate.commitBank(); summed.commitBank();
    PadCommand c;
    c.sample = 0; c.voiceId = 1; c.end = 16000; c.gainL = 1; c.gainR = 0.5f;
    CHECK(separate.pushCommand(c)); CHECK(summed.pushCommand(c));
    c.voiceId = 2;
    CHECK(separate.pushCommand(c));
    const auto a = callback(separate, 12000);
    const auto b = callback(summed, 12000);
    CHECK(std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0);
    CHECK(peak(a) > 0.01f);
}

TEST(pads_aquarium_panic_clears_effect_tails_and_a_new_note_resumes_fresh_history) {
    PadEngine e(kRate);
    aquarium::PerformanceSettings s;
    s.parameters = aquarium::parametersForPreset(aquarium::Preset::Reef);
    s.enabled = true;
    CHECK(e.configureAquarium(s));
    e.beginBank();
    e.addSample(std::vector<float>(kRate, 0.4f), 1, kRate);
    e.addSample(std::vector<float>(kRate, 0.0f), 1, kRate);
    e.commitBank();
    PadCommand c;
    c.sample = 0; c.voiceId = 1; c.end = kRate; c.gainL = c.gainR = 1;
    CHECK(e.pushCommand(c));
    callback(e, 16000);
    PadCommand off;
    off.type = PadCommand::Type::AllOff; off.fadeMs = 20; off.frameOffset = 64;
    CHECK(e.pushCommand(off));
    const auto quiet = callback(e, 5000);
    CHECK(peak(std::vector<float>(quiet.begin() + 2 * 1100, quiet.end())) == 0);
    c.sample = 1; c.voiceId = 2;
    CHECK(e.pushCommand(c));
    const auto fresh = aquariumIdle(s, 5000);
    const auto resumed = callback(e, 5000);
    CHECK(std::memcmp(resumed.data(), fresh.data(), resumed.size() * sizeof(float)) == 0);
}

TEST(pads_aquarium_bank_swap_never_carries_the_previous_banks_room_into_new_notes) {
    PadEngine e(kRate);
    aquarium::PerformanceSettings s;
    s.parameters = aquarium::parametersForPreset(aquarium::Preset::Reef);
    s.enabled = true;
    CHECK(e.configureAquarium(s));
    e.beginBank(); e.addSample(std::vector<float>(kRate, 0.4f), 1, kRate); e.commitBank();
    PadCommand c;
    c.sample = 0; c.voiceId = 1; c.end = kRate; c.gainL = c.gainR = 1;
    CHECK(e.pushCommand(c));
    callback(e, 16000);
    e.beginBank(); e.addSample(std::vector<float>(kRate, 0.0f), 1, kRate); e.commitBank();
    c.voiceId = 2;
    CHECK(e.pushCommand(c));
    const auto replacement = callback(e, 5000);
    CHECK(peak(std::vector<float>(replacement.begin() + 2 * 2000, replacement.end())) <= peak(aquariumIdle(s, 5000)) + 1e-30f);
}

int main() { return check::runAll(); }
