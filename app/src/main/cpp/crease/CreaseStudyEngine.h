#pragma once
#include "CreaseStudyReader.h"
#include <oboe/Oboe.h>
#include <memory>

namespace snipsnap::crease {
class Engine final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    Engine(Layout layout,std::vector<int16_t> pcm) : reader(std::move(layout),std::move(pcm)) {}
    ~Engine() { stop(); }
    bool start();
    void stop();
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*,void* data,int32_t frames) override {
        reader.process(static_cast<float*>(data),frames,rate_); return oboe::DataCallbackResult::Continue;
    }
    void onErrorAfterClose(oboe::AudioStream*,oboe::Result) override { reader.panic(); failed.store(true); }
    Reader reader;
    std::atomic<bool> failed{false};
private:
    std::shared_ptr<oboe::AudioStream> stream_;
    int rate_=44100;
};
}
