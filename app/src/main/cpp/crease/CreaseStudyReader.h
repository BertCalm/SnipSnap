#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace snipsnap::crease {
// Immutable PCM16 belongs to the reader, never to a voice. Construction and
// validation run before opening the stream; process() cannot allocate or free.
struct Layout {
    int held, loopStart, loopFrames, rootCycles, lead, tail, blend, release;
    std::vector<int> cuts;
    std::size_t samples() const { return std::size_t(held) + cuts.size() * std::size_t(tail); }
    bool valid() const {
        if (held < 2 || held > 44100 * 64 || loopStart < 0 || loopFrames < 2 ||
            int64_t(loopStart) + loopFrames != held || rootCycles < 8 || rootCycles > loopFrames ||
            lead < 4 || lead > 512 || tail < 44100 || tail > 60000 || blend != 221 ||
            release < 52900 || release > 53000 || cuts.empty() || cuts.size() > 400 ||
            cuts.front() != 0 || !std::is_sorted(cuts.begin(), cuts.end()) || !std::binary_search(cuts.begin(), cuts.end(), loopStart)) return false;
        const double period = double(loopFrames) / rootCycles;
        if (period < 168 || period > 169 || lead < std::ceil(period / 2) + 3 ||
            tail < lead + release + std::ceil(period / 2) + 3 || samples() * 2 > 32 * 1024 * 1024) return false;
        for (std::size_t i=0; i<cuts.size(); ++i)
            if (cuts[i] < 0 || cuts[i] >= held || (i && cuts[i] <= cuts[i-1])) return false;
        return true;
    }
};

class Reader {
public:
    Reader(Layout layout, std::vector<int16_t> pcm) : m_(std::move(layout)), pcm_(std::move(pcm)) {
        if (!m_.valid() || pcm_.size() != m_.samples()) throw std::invalid_argument("Crease study layout/PCM mismatch");
    }
    // A single atomic latest gesture cannot fill a command queue. Release is
    // conditional on its token, so a late lift cannot release the next press.
    uint64_t press(bool unfold) {
        const auto token = next_.fetch_add(1, std::memory_order_relaxed) + 1;
        command_.store((token << 2) | (unfold ? 2 : 0) | 1, std::memory_order_release);
        return token;
    }
    void release(uint64_t token) {
        auto old = command_.load(std::memory_order_acquire);
        while ((old >> 2) == token && (old & 1) &&
               !command_.compare_exchange_weak(old, old & ~uint64_t(1), std::memory_order_acq_rel)) {}
    }
    void panic() { command_.store(0, std::memory_order_release); }
    // Stream must be closed before this off-callback reset.
    void clear() { panic(); for (auto& v : voices_) v = {}; lastToken_ = 0; }

    double held(double pos) const { return sample(0, m_.held, pos, m_.loopStart); }
    double sample(std::size_t offset, int length, double pos, int loop=-1) const {
        if (loop >= 0 && pos >= length) pos = loop + std::fmod(pos-loop, length-loop);
        const auto i = int64_t(std::floor(pos)); const double t = pos-i, t2=t*t, t3=t2*t;
        auto read = [&](int64_t k) {
            if (loop >= 0 && k >= length) k = loop + (k-loop) % (length-loop);
            k = std::clamp<int64_t>(k, 0, length-1);
            return pcm_[offset+std::size_t(k)] / 32767.0;
        };
        return .5*(2*t2-t3-t)*read(i-1)+(1-2.5*t2+1.5*t3)*read(i)+
            (.5*t+2*t2-1.5*t3)*read(i+1)+.5*(t3-t2)*read(i+2);
    }
    void process(float* stereo, int frames, int rate) {
        if (!stereo || frames<=0) return;
        std::fill(stereo, stereo + std::size_t(frames)*2, 0.f);
        if (rate < 8000 || rate > 192000) return;
        const auto request = command_.load(std::memory_order_acquire);
        const auto token = request >> 2;
        if (!token) { for (auto& v : voices_) retire(v); }
        else if (token != lastToken_) {
            for (auto& v : voices_) retire(v);
            auto* slot = &voices_[0];
            for (auto& v : voices_) {
                if (!v.active) { slot=&v; break; }
                if (v.token < slot->token) slot=&v;
            }
            *slot = {}; slot->active=true; slot->token=token;
            slot->unfold=(request & 2) != 0; slot->level=1;
            lastToken_=token;
        }
        if (token && !(request & 1))
            for (auto& v : voices_) if (v.active && v.token==token && !v.releasing && !v.forced) keyUp(v);
        const double step = 44100.0 / rate;
        const float decrement = 1.f / (1.2f * float(rate));
        for (auto& v : voices_) if (v.active) {
            for (int i=0; i<frames && v.level>0; ++i) {
                double x = held(v.pos);
                if (v.releasing) {
                    if (v.unfold) {
                        const double a=tail(v.left,m_.lead+v.shiftLeft+v.age);
                        const double b=tail(v.right,m_.lead+v.shiftRight+v.age);
                        const double q=std::min(1.0,v.age/m_.blend), blend=q*q*(3-2*q);
                        x += ((1-v.weight)*a+v.weight*b-x)*blend;
                    }
                    v.age += step;
                }
                if (v.forced) v.level=std::max(0.0,v.level-v.stopLevel/(.005*rate));
                else if (v.releasing) v.level=std::max(0.f,float(v.level)-decrement);
                const float out=float(x*.12*v.level);
                stereo[2*i]+=out; stereo[2*i+1]+=out;
                v.pos+=step;
            }
            if (v.level<=0) v.active=false;
        }
    }
    int active() const { int n=0; for(const auto& v:voices_) n+=v.active; return n; }
private:
    struct Voice {
        bool active=false, unfold=false, releasing=false, forced=false;
        uint64_t token=0;
        double pos=0, age=0, level=0, stopLevel=0, shiftLeft=0, shiftRight=0, weight=0;
        int left=0, right=0;
    };
    void retire(Voice& v) { if (v.active && !v.forced) { v.forced=true; v.stopLevel=v.level; } }
    double tail(int index,double pos) const { return sample(m_.held+std::size_t(index)*m_.tail,m_.tail,pos); }
    void keyUp(Voice& v) {
        v.releasing=true;
        const double position = v.pos<m_.loopStart ? v.pos : m_.loopStart+std::fmod(v.pos-m_.loopStart,m_.loopFrames);
        auto it=std::upper_bound(m_.cuts.begin(),m_.cuts.end(),position);
        v.left=std::max(0,int(it-m_.cuts.begin())-1);
        v.right=v.left+1<int(m_.cuts.size()) ? v.left+1 :
            int(std::lower_bound(m_.cuts.begin(),m_.cuts.end(),m_.loopStart)-m_.cuts.begin());
        const double coordinate=v.left+1<int(m_.cuts.size()) ? m_.cuts[v.right] : m_.held;
        v.weight=(position-m_.cuts[v.left])/std::max(1.0,coordinate-m_.cuts[v.left]);
        const double period=double(m_.loopFrames)/m_.rootCycles;
        auto shift=[&](int index) { double r=std::fmod(v.pos-m_.cuts[index]+period/2,period); if(r<0) r+=period; return r-period/2; };
        v.shiftLeft=shift(v.left); v.shiftRight=shift(v.right);
    }
    const Layout m_;
    const std::vector<int16_t> pcm_;
    std::array<Voice,8> voices_{};
    static_assert(std::atomic<uint64_t>::is_always_lock_free,"study requires lock-free gesture atomics");
    std::atomic<uint64_t> command_{0},next_{0};
    uint64_t lastToken_=0;
};
} // namespace snipsnap::crease
