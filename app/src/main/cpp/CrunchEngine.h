// CrunchEngine.h — full-duplex low-latency audio: mic in -> CrunchProcessor -> headphones.
#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include "Dsp.h"

class CrunchEngine : public oboe::AudioStreamDataCallback,
                     public oboe::AudioStreamErrorCallback {
public:
    CrunchEngine();

    bool start();
    void stop();
    void setParams(const crunch::Params& p);
    double outputLatencyMs();  // -1 if unknown
    int32_t sampleRate();

    // Called on the high-priority audio thread for the OUTPUT stream.
    // We pull mic samples from the input stream with a non-blocking read.
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    // Headphones unplugged / route changed: Oboe closed the stream, we reopen.
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    static constexpr int32_t kMaxChunk = 1024;

    bool openStreamsLocked();
    void closeStreamsLocked();
    void restartAsync();
    void drainInput(oboe::AudioStream* in);
    crunch::Params loadParams() const;

    std::mutex mLock;
    std::shared_ptr<oboe::AudioStream> mIn, mOut;
    bool mWantRunning = false;
    std::atomic<bool> mRestarting{false};

    // UI thread writes these; the audio thread picks them up when version changes.
    struct SharedParams {
        std::atomic<float> lowCutHz{180.0f}, crunchHz{4000.0f}, crunchDb{8.0f}, airDb{4.0f},
            transient{0.5f}, gateDb{-60.0f}, outputDb{6.0f};
        std::atomic<bool> bypass{false};
        std::atomic<uint32_t> version{0};
    } mShared;
    uint32_t mSeenVersion = 0;

    // Audio-thread-only state.
    crunch::CrunchProcessor mProcessor;
    std::vector<float> mInBuf, mScratch;
    int32_t mDrainCallbacks = 0;
};
