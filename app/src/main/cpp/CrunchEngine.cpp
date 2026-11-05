#include "CrunchEngine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <thread>

#define LOG_TAG "CrunchEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace oboe;

CrunchEngine::CrunchEngine() : mInBuf(kMaxChunk), mScratch(kMaxChunk) {}

bool CrunchEngine::start() {
    std::lock_guard<std::mutex> lock(mLock);
    if (mWantRunning) return true;
    mWantRunning = true;
    if (!openStreamsLocked()) {
        closeStreamsLocked();
        mWantRunning = false;
        return false;
    }
    return true;
}

void CrunchEngine::stop() {
    std::lock_guard<std::mutex> lock(mLock);
    mWantRunning = false;
    closeStreamsLocked();
}

void CrunchEngine::setParams(const crunch::Params& p) {
    mShared.lowCutHz.store(p.lowCutHz, std::memory_order_relaxed);
    mShared.crunchHz.store(p.crunchHz, std::memory_order_relaxed);
    mShared.crunchDb.store(p.crunchDb, std::memory_order_relaxed);
    mShared.airDb.store(p.airDb, std::memory_order_relaxed);
    mShared.transient.store(p.transient, std::memory_order_relaxed);
    mShared.gateDb.store(p.gateDb, std::memory_order_relaxed);
    mShared.outputDb.store(p.outputDb, std::memory_order_relaxed);
    mShared.bypass.store(p.bypass, std::memory_order_relaxed);
    mShared.version.fetch_add(1, std::memory_order_release);
}

crunch::Params CrunchEngine::loadParams() const {
    crunch::Params p;
    p.lowCutHz = mShared.lowCutHz.load(std::memory_order_relaxed);
    p.crunchHz = mShared.crunchHz.load(std::memory_order_relaxed);
    p.crunchDb = mShared.crunchDb.load(std::memory_order_relaxed);
    p.airDb = mShared.airDb.load(std::memory_order_relaxed);
    p.transient = mShared.transient.load(std::memory_order_relaxed);
    p.gateDb = mShared.gateDb.load(std::memory_order_relaxed);
    p.outputDb = mShared.outputDb.load(std::memory_order_relaxed);
    p.bypass = mShared.bypass.load(std::memory_order_relaxed);
    return p;
}

double CrunchEngine::outputLatencyMs() {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mOut) return -1.0;
    auto r = mOut->calculateLatencyMillis();
    return r ? r.value() : -1.0;
}

int32_t CrunchEngine::sampleRate() {
    std::lock_guard<std::mutex> lock(mLock);
    return mOut ? mOut->getSampleRate() : 0;
}

bool CrunchEngine::openStreamsLocked() {
    // Output first: it decides the sample rate and drives the callback.
    AudioStreamBuilder outBuilder;
    outBuilder.setDirection(Direction::Output)
        ->setPerformanceMode(PerformanceMode::LowLatency)
        ->setSharingMode(SharingMode::Exclusive)
        ->setFormat(AudioFormat::Float)
        ->setChannelCount(ChannelCount::Stereo)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    Result r = outBuilder.openStream(mOut);
    if (r != Result::OK) {
        LOGE("Failed to open output: %s", convertToText(r));
        return false;
    }
    const int32_t burst = mOut->getFramesPerBurst();
    mOut->setBufferSizeInFrames(burst * 2);  // as small as is usually glitch-free
    const int32_t sr = mOut->getSampleRate();

    AudioStreamBuilder inBuilder;
    inBuilder.setDirection(Direction::Input)
        ->setPerformanceMode(PerformanceMode::LowLatency)
        ->setSharingMode(SharingMode::Exclusive)
        ->setFormat(AudioFormat::Float)
        ->setChannelCount(ChannelCount::Mono)
        ->setSampleRate(sr)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setSampleRateConversionQuality(SampleRateConversionQuality::Fastest)
        // VoiceRecognition avoids the noise suppression / AGC of the default
        // "voice" presets, which would treat crunching as noise and remove it.
        ->setInputPreset(InputPreset::VoiceRecognition);
    r = inBuilder.openStream(mIn);
    if (r != Result::OK) {
        LOGE("Failed to open input: %s", convertToText(r));
        return false;
    }

    mProcessor.prepare(static_cast<float>(sr));
    mSeenVersion = mShared.version.load(std::memory_order_acquire);
    mProcessor.setParams(loadParams());
    mDrainCallbacks = 8;

    if ((r = mIn->requestStart()) != Result::OK) {
        LOGE("Failed to start input: %s", convertToText(r));
        return false;
    }
    if ((r = mOut->requestStart()) != Result::OK) {
        LOGE("Failed to start output: %s", convertToText(r));
        return false;
    }
    LOGI("Running: %d Hz, burst %d, out sharing %s / in sharing %s", sr, burst,
         mOut->getSharingMode() == SharingMode::Exclusive ? "exclusive" : "shared",
         mIn->getSharingMode() == SharingMode::Exclusive ? "exclusive" : "shared");
    return true;
}

void CrunchEngine::closeStreamsLocked() {
    // Close output first so the callback (which reads mIn) has stopped.
    if (mOut) { mOut->stop(); mOut->close(); mOut.reset(); }
    if (mIn)  { mIn->stop();  mIn->close();  mIn.reset(); }
}

void CrunchEngine::restartAsync() {
    bool expected = false;
    if (!mRestarting.compare_exchange_strong(expected, true)) return;
    std::thread([this] {
        std::this_thread::sleep_for(std::chrono::milliseconds(250));  // let routing settle
        {
            std::lock_guard<std::mutex> lock(mLock);
            if (mWantRunning) {
                closeStreamsLocked();
                if (!openStreamsLocked()) {
                    LOGE("Restart failed");
                    closeStreamsLocked();
                }
            }
        }
        mRestarting = false;
    }).detach();
}

void CrunchEngine::onErrorAfterClose(AudioStream* /*stream*/, Result error) {
    LOGI("Stream closed (%s), restarting", convertToText(error));
    restartAsync();
}

void CrunchEngine::drainInput(AudioStream* in) {
    for (int i = 0; i < 64; ++i) {
        auto r = in->read(mScratch.data(), kMaxChunk, 0);
        if (!r || r.value() <= 0) break;
    }
}

DataCallbackResult CrunchEngine::onAudioReady(AudioStream* stream, void* audioData,
                                              int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);
    const int32_t ch = stream->getChannelCount();
    AudioStream* in = mIn.get();

    const uint32_t v = mShared.version.load(std::memory_order_acquire);
    if (v != mSeenVersion) {
        mSeenVersion = v;
        mProcessor.setParams(loadParams());
    }

    if (!in) {
        std::memset(out, 0, sizeof(float) * numFrames * ch);
        return DataCallbackResult::Continue;
    }

    // At start-up, throw away whatever the mic has buffered so we begin
    // with the minimum possible delay.
    if (mDrainCallbacks > 0) {
        --mDrainCallbacks;
        drainInput(in);
        std::memset(out, 0, sizeof(float) * numFrames * ch);
        return DataCallbackResult::Continue;
    }

    // If the input clock runs slightly fast, frames pile up and latency
    // creeps up. Drop the excess.
    auto avail = in->getAvailableFrames();
    if (avail && avail.value() > numFrames * 2) {
        int32_t toDrop = avail.value() - numFrames;
        while (toDrop > 0) {
            auto r = in->read(mScratch.data(), std::min(toDrop, kMaxChunk), 0);
            if (!r || r.value() <= 0) break;
            toDrop -= r.value();
        }
    }

    int32_t done = 0;
    while (done < numFrames) {
        const int32_t want = std::min(numFrames - done, kMaxChunk);
        auto r = in->read(mInBuf.data(), want, 0);
        if (!r) {
            if (r.error() == Result::ErrorDisconnected) {
                std::memset(out + done * ch, 0, sizeof(float) * (numFrames - done) * ch);
                restartAsync();
                return DataCallbackResult::Stop;
            }
            break;
        }
        const int32_t got = r.value();
        for (int32_t i = 0; i < got; ++i) {
            const float y = mProcessor.process(mInBuf[i]);
            float* frame = out + (done + i) * ch;
            for (int32_t c = 0; c < ch; ++c) frame[c] = y;
        }
        done += got;
        if (got < want) break;  // mic underrun: pad with silence
    }
    if (done < numFrames) {
        std::memset(out + done * ch, 0, sizeof(float) * (numFrames - done) * ch);
    }
    return DataCallbackResult::Continue;
}
