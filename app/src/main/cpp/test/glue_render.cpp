// SPDX-License-Identifier: MIT
// glue_render: a WAV through the real whole-kit glue (GlueBus.h), for
// listening checks and for comparing the native arithmetic against the
// Python model (prototype/borough/glue_recipe.py `model`). A host tool built
// by the native test harness only; it is not in the app's CMake and never
// ships in the APK.
//
//   glue_render <in.wav> <out.wav> <trimDb> <satDb|none> <driveDb>
//               [thresholdDbfs=-10] [ratio=4] [makeupDb=0] [ceilingDbfs=-1]
//   glue_render --self-test <scratch dir>
//
// In: mono or stereo RIFF WAV, 16-, 24- or 32-bit PCM or 32-bit float, at
// any rate the bus prepares for (8 to 192 kHz; the BOROUGH grooves are 44.1
// kHz). Mono is played as both channels and written back as one.
// Out: 32-bit float WAV, same rate and channels. The glue is on from the
// first frame (settings published before preparation, so there is no 20 ms
// fade in) and its 3 ms lookahead is IN the file: the output is delayed by
// the latency the tool prints, and is that many frames longer than the input
// so the last of the input is flushed out of the delay line rather than cut.
#include "GlueBus.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

namespace {

struct Wav {
    int channels = 0;
    int rate = 0;
    std::vector<float> samples;  // interleaved
    int frames() const { return channels > 0 ? static_cast<int>(samples.size() / static_cast<size_t>(channels)) : 0; }
};

uint32_t u32(const unsigned char* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24); }
uint16_t u16(const unsigned char* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }

bool readWav(const std::string& path, Wav& out, std::string& why) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { why = "cannot open " + path; return false; }
    std::vector<unsigned char> b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (b.size() < 12 || std::memcmp(b.data(), "RIFF", 4) != 0 || std::memcmp(b.data() + 8, "WAVE", 4) != 0) {
        why = "not a RIFF WAVE file"; return false;
    }
    int format = 0, bits = 0;
    const unsigned char* data = nullptr;
    size_t dataBytes = 0;
    for (size_t at = 12; at + 8 <= b.size();) {
        const uint32_t size = u32(&b[at + 4]);
        const size_t body = at + 8;
        if (body + size > b.size() && std::memcmp(&b[at], "data", 4) != 0) { why = "truncated chunk"; return false; }
        if (std::memcmp(&b[at], "fmt ", 4) == 0 && size >= 16) {
            format = u16(&b[body]);
            out.channels = u16(&b[body + 2]);
            out.rate = static_cast<int>(u32(&b[body + 4]));
            bits = u16(&b[body + 14]);
            if (format == 0xFFFE && size >= 40) format = u16(&b[body + 24]);  // WAVE_FORMAT_EXTENSIBLE's subformat
        } else if (std::memcmp(&b[at], "data", 4) == 0) {
            data = &b[body];
            dataBytes = std::min<size_t>(size, b.size() - body);
        }
        at = body + size + (size & 1u);
    }
    if (!data || out.channels == 0) { why = "no fmt or data chunk"; return false; }
    if (out.channels != 1 && out.channels != 2) { why = "only mono or stereo"; return false; }
    const bool pcm = format == 1 && (bits == 16 || bits == 24 || bits == 32);
    const bool flt = format == 3 && bits == 32;
    if (!pcm && !flt) { why = "only 16/24/32-bit PCM or 32-bit float"; return false; }
    const size_t width = static_cast<size_t>(bits / 8);
    const size_t n = dataBytes / width;
    out.samples.resize(n - n % static_cast<size_t>(out.channels));
    for (size_t i = 0; i < out.samples.size(); ++i) {
        const unsigned char* p = data + i * width;
        float v;
        if (flt) {
            uint32_t w = u32(p);
            std::memcpy(&v, &w, 4);
        } else if (bits == 16) {
            v = static_cast<float>(static_cast<int16_t>(u16(p))) / 32768.0f;
        } else if (bits == 24) {
            int32_t w = static_cast<int32_t>((p[0] << 8) | (p[1] << 16) | (static_cast<uint32_t>(p[2]) << 24)) >> 8;
            v = static_cast<float>(w) / 8388608.0f;
        } else {
            v = static_cast<float>(static_cast<double>(static_cast<int32_t>(u32(p))) / 2147483648.0);
        }
        out.samples[i] = v;
    }
    return true;
}

void put32(std::vector<unsigned char>& b, uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back(static_cast<unsigned char>(v >> (8 * i))); }
void put16(std::vector<unsigned char>& b, uint16_t v) { b.push_back(static_cast<unsigned char>(v)); b.push_back(static_cast<unsigned char>(v >> 8)); }

/** [bits] 32 with [asFloat] writes IEEE float; 16 or 32 without writes PCM (the self-test's inputs). */
bool writeWav(const std::string& path, const Wav& w, int bits = 32, bool asFloat = true) {
    std::vector<unsigned char> b;
    const uint32_t dataBytes = static_cast<uint32_t>(w.samples.size() * static_cast<size_t>(bits / 8));
    b.insert(b.end(), {'R', 'I', 'F', 'F'});
    put32(b, 36 + dataBytes);
    b.insert(b.end(), {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
    put32(b, 16);
    put16(b, asFloat ? 3 : 1);
    put16(b, static_cast<uint16_t>(w.channels));
    put32(b, static_cast<uint32_t>(w.rate));
    put32(b, static_cast<uint32_t>(w.rate * w.channels * bits / 8));
    put16(b, static_cast<uint16_t>(w.channels * bits / 8));
    put16(b, static_cast<uint16_t>(bits));
    b.insert(b.end(), {'d', 'a', 't', 'a'});
    put32(b, dataBytes);
    for (float v : w.samples) {
        if (asFloat) {
            uint32_t u;
            std::memcpy(&u, &v, 4);
            put32(b, u);
        } else if (bits == 16) {
            put16(b, static_cast<uint16_t>(static_cast<int16_t>(std::lround(std::fmax(-1.0f, std::fmin(v, 32767.0f / 32768.0f)) * 32768.0f))));
        } else {
            put32(b, static_cast<uint32_t>(static_cast<int32_t>(std::llround(std::fmax(-1.0, std::fmin(static_cast<double>(v), 2147483647.0 / 2147483648.0)) * 2147483648.0))));
        }
    }
    std::ofstream f(path, std::ios::binary);
    f.write(reinterpret_cast<const char*>(b.data()), static_cast<std::streamsize>(b.size()));
    return static_cast<bool>(f);
}

struct Result {
    Wav out;
    int latency = 0;
    uint64_t clamped = 0;
};

/** The input through one GlueBus, enabled from its first frame, in callback-sized blocks. */
bool render(const Wav& in, const snipsnap::GlueSettings& s, Result& r, std::string& why) {
    snipsnap::GlueBus bus;
    // Published before preparation: prepare() adopts it whole, the stage is
    // fully in from frame 0 (a publication after it would fade in over 20 ms).
    if (!bus.publish(s)) { why = "settings out of range (GlueBus validGlueSettings)"; return false; }
    if (!bus.prepare(static_cast<double>(in.rate))) { why = "the bus will not prepare at " + std::to_string(in.rate) + " Hz"; return false; }
    r.latency = bus.latencyFrames();
    const int frames = in.frames() + r.latency;  // flush the lookahead
    std::vector<float> stereo(2 * static_cast<size_t>(frames), 0.0f);
    for (int i = 0; i < in.frames(); ++i) {
        stereo[2 * i] = in.samples[static_cast<size_t>(i * in.channels)];
        stereo[2 * i + 1] = in.samples[static_cast<size_t>(i * in.channels + in.channels - 1)];
    }
    constexpr int kBlock = 192;
    for (int at = 0; at < frames; at += kBlock) {
        bus.beginCallback();
        bus.process(stereo.data() + 2 * at, std::min(kBlock, frames - at));
    }
    r.clamped = bus.clampedSamples();
    r.out.channels = in.channels;
    r.out.rate = in.rate;
    r.out.samples.resize(static_cast<size_t>(frames) * static_cast<size_t>(in.channels));
    for (int i = 0; i < frames; ++i)
        for (int c = 0; c < in.channels; ++c) r.out.samples[static_cast<size_t>(i * in.channels + c)] = stereo[2 * i + c];
    return true;
}

bool number(const char* text, float& v) {
    char* end = nullptr;
    v = std::strtof(text, &end);
    return end != text && *end == '\0' && std::isfinite(v);
}

int selfTest(const std::string& dir) {
    // Each input format read back to the same signal, and the render held to
    // GlueBus's own arithmetic: below threshold with no saturation it is the
    // trim, latency frames late; with saturation, tanh(g x)/tanh(g).
    int failures = 0;
    auto expect = [&](bool ok, const char* what) { if (!ok) { std::fprintf(stderr, "FAIL %s\n", what); ++failures; } };
    Wav src;
    src.channels = 2;
    src.rate = 44100;
    for (int i = 0; i < 4410; ++i) {
        src.samples.push_back(static_cast<float>(0.25 * std::sin(0.031 * i)));
        src.samples.push_back(static_cast<float>(0.1 * std::sin(0.047 * i)));
    }
    struct Fmt { int bits; bool flt; double tol; const char* name; };
    for (const Fmt f : {Fmt{16, false, 1.0 / 32768.0, "16-bit"}, Fmt{32, false, 1e-7, "32-bit"}, Fmt{32, true, 0.0, "float"}}) {
        const std::string path = dir + "/glue_render_self_" + f.name + ".wav";
        expect(writeWav(path, src, f.bits, f.flt), "write input");
        Wav back;
        std::string why;
        expect(readWav(path, back, why), why.c_str());
        expect(back.channels == 2 && back.rate == 44100 && back.samples.size() == src.samples.size(), "shape read back");
        double worst = 0.0;
        for (size_t i = 0; i < back.samples.size() && i < src.samples.size(); ++i) worst = std::fmax(worst, std::fabs(back.samples[i] - src.samples[i]));
        expect(worst <= f.tol, f.name);
    }
    snipsnap::GlueSettings s;
    s.enabled = true;
    s.inputTrimDb = -6.0f;
    Result r;
    std::string why;
    expect(render(src, s, r, why), why.c_str());
    expect(r.latency == 132, "3 ms at 44.1 kHz is 132 frames");
    expect(r.out.frames() == src.frames() + r.latency, "the lookahead flushed, nothing cut");
    const double k = std::pow(10.0, -6.0 / 20.0);
    double worst = 0.0;
    for (int i = 0; i < src.frames(); ++i)
        for (int c = 0; c < 2; ++c)
            worst = std::fmax(worst, std::fabs(r.out.samples[static_cast<size_t>(2 * (i + r.latency) + c)] - k * src.samples[static_cast<size_t>(2 * i + c)]));
    expect(worst < 1e-6, "trim alone, latency frames late, from the first frame");
    s.inputTrimDb = 0.0f;
    s.saturate = true;
    s.saturationDb = 6.0f;
    s.thresholdDbfs = 0.0f;
    s.ratio = 1.0f;
    s.ceilingDbfs = 0.0f;
    Wav mono;
    mono.channels = 1;
    mono.rate = 44100;
    for (int i = 0; i < 4410; ++i) mono.samples.push_back(src.samples[static_cast<size_t>(2 * i)]);
    expect(render(mono, s, r, why), why.c_str());
    expect(r.out.channels == 1, "mono stays mono");
    const double g = std::pow(10.0, 0.3);
    worst = 0.0;
    for (int i = 0; i < mono.frames(); ++i)
        worst = std::fmax(worst, std::fabs(r.out.samples[static_cast<size_t>(i + r.latency)] - std::tanh(g * mono.samples[static_cast<size_t>(i)]) / std::tanh(g)));
    expect(worst < 1e-6, "saturation from the first frame");
    expect(r.clamped == 0, "no clamping");
    std::printf("glue_render self-test: %s\n", failures ? "FAILED" : "ok");
    return failures ? 1 : 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc == 3 && std::string(argv[1]) == "--self-test") return selfTest(argv[2]);
    if (argc < 6 || argc > 10) {
        std::fprintf(stderr,
                     "usage: %s <in.wav> <out.wav> <trimDb> <satDb|none> <driveDb> [thresholdDbfs=-10] [ratio=4] [makeupDb=0] [ceilingDbfs=-1]\n"
                     "       %s --self-test <scratch dir>\n", argv[0], argv[0]);
        return 2;
    }
    snipsnap::GlueSettings s;
    s.enabled = true;
    bool ok = number(argv[3], s.inputTrimDb) && number(argv[5], s.driveDb);
    if (std::string(argv[4]) == "none") {
        s.saturate = false;
    } else {
        s.saturate = true;
        ok = ok && number(argv[4], s.saturationDb);
    }
    if (argc > 6) ok = ok && number(argv[6], s.thresholdDbfs);
    if (argc > 7) ok = ok && number(argv[7], s.ratio);
    if (argc > 8) ok = ok && number(argv[8], s.makeupDb);
    if (argc > 9) ok = ok && number(argv[9], s.ceilingDbfs);
    if (!ok) { std::fprintf(stderr, "glue_render: a number did not parse\n"); return 2; }
    Wav in;
    std::string why;
    if (!readWav(argv[1], in, why)) { std::fprintf(stderr, "glue_render: %s: %s\n", argv[1], why.c_str()); return 1; }
    Result r;
    if (!render(in, s, r, why)) { std::fprintf(stderr, "glue_render: %s\n", why.c_str()); return 1; }
    if (!writeWav(argv[2], r.out)) { std::fprintf(stderr, "glue_render: cannot write %s\n", argv[2]); return 1; }
    float peak = 0.0f;
    for (float v : r.out.samples) peak = std::fmax(peak, std::fabs(v));
    std::printf("glue_render: %d frames, %d ch, %d Hz; trim %.2f dB, saturation %s, threshold %.1f dBFS, ratio %.1f, "
                "makeup %.2f dB, drive %.2f dB, ceiling %.1f dBFS; latency %d frames (in the file: output delayed by it, "
                "and that much longer); peak %.4f; clamped %llu\n",
                in.frames(), in.channels, in.rate, s.inputTrimDb, s.saturate ? argv[4] : "none", s.thresholdDbfs, s.ratio,
                s.makeupDb, s.driveDb, s.ceilingDbfs, r.latency, peak, static_cast<unsigned long long>(r.clamped));
    return 0;
}
