// Dsp.h — the "crunch" signal chain. Pure C++, no Android deps, so it can be
// unit-tested on a desktop.
//
// Chain:  mic -> 4th-order high-pass (kills jaw/occlusion boom)
//             -> peaking boost at the "crunch" frequency (default 4 kHz)
//             -> high shelf "air" (9 kHz)
//             -> transient shaper ("snap": exaggerates the attack of each crunch)
//             -> soft noise gate (keeps boosted room hiss down between bites)
//             -> output gain -> soft limiter (protects your ears)
#pragma once

#include <algorithm>
#include <cmath>

namespace crunch {

constexpr float kPi = 3.14159265358979f;

inline float dbToGain(float db) { return std::pow(10.0f, db / 20.0f); }

struct Params {
    float lowCutHz  = 180.0f;   // high-pass corner
    float crunchHz  = 4000.0f;  // centre of the crunch boost
    float crunchDb  = 8.0f;     // amount of crunch boost
    float airDb     = 4.0f;     // high-shelf boost above ~9 kHz
    float transient = 0.5f;     // 0..1 "snap"
    float gateDb    = -60.0f;   // gate threshold; <= -80 disables
    float outputDb  = 6.0f;     // output volume
    bool  bypass    = false;    // raw mic (for A/B comparison)
};

// RBJ-cookbook biquad, transposed direct form II.
class Biquad {
public:
    float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void reset() { z1 = z2 = 0.0f; }

    void setHighPass(float fs, float f, float q) {
        const float w = 2.0f * kPi * f / fs, c = std::cos(w), alpha = std::sin(w) / (2.0f * q);
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha);
    }
    void setPeak(float fs, float f, float q, float db) {
        const float A = std::pow(10.0f, db / 40.0f);
        const float w = 2.0f * kPi * f / fs, c = std::cos(w), alpha = std::sin(w) / (2.0f * q);
        set(1 + alpha * A, -2 * c, 1 - alpha * A, 1 + alpha / A, -2 * c, 1 - alpha / A);
    }
    void setHighShelf(float fs, float f, float db) {
        const float A = std::pow(10.0f, db / 40.0f);
        const float w = 2.0f * kPi * f / fs, c = std::cos(w);
        const float alpha = std::sin(w) / 2.0f * std::sqrt(2.0f);  // shelf slope S = 1
        const float sA = 2.0f * std::sqrt(A) * alpha;
        set(A * ((A + 1) + (A - 1) * c + sA),
            -2 * A * ((A - 1) + (A + 1) * c),
            A * ((A + 1) + (A - 1) * c - sA),
            (A + 1) - (A - 1) * c + sA,
            2 * ((A - 1) - (A + 1) * c),
            (A + 1) - (A - 1) * c - sA);
    }

private:
    void set(float nb0, float nb1, float nb2, float na0, float na1, float na2) {
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0;
        a1 = na1 / na0; a2 = na2 / na0;
    }
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1 = 0, z2 = 0;
};

class EnvelopeFollower {
public:
    void setup(float fs, float attackSec, float releaseSec) {
        att = std::exp(-1.0f / (attackSec * fs));
        rel = std::exp(-1.0f / (releaseSec * fs));
    }
    float process(float rectified) {
        const float k = rectified > env ? att : rel;
        env = k * env + (1.0f - k) * rectified;
        return env;
    }
    void reset() { env = 0.0f; }

private:
    float env = 0.0f, att = 0.0f, rel = 0.0f;
};

class CrunchProcessor {
public:
    void prepare(float sampleRate) {
        fs = sampleRate;
        hp1.reset(); hp2.reset(); peak.reset(); shelf.reset();
        fastEnv.setup(fs, 0.0005f, 0.020f);  // follows each crunch
        slowEnv.setup(fs, 0.020f, 0.200f);   // follows the overall level
        fastEnv.reset(); slowEnv.reset();
        gateOpen  = 1.0f - std::exp(-1.0f / (0.001f * fs));
        gateClose = 1.0f - std::exp(-1.0f / (0.120f * fs));
        gateGain = 1.0f;
        setParams(p);
    }

    void setParams(const Params& np) {
        p = np;
        const float nyq = 0.45f * fs;
        const float lc = std::clamp(p.lowCutHz, 20.0f, nyq);
        // Two stages with these Qs = 4th-order Butterworth (24 dB/oct).
        hp1.setHighPass(fs, lc, 0.5412f);
        hp2.setHighPass(fs, lc, 1.3066f);
        peak.setPeak(fs, std::clamp(p.crunchHz, 200.0f, nyq), 0.9f, p.crunchDb);
        shelf.setHighShelf(fs, std::min(9000.0f, nyq), p.airDb);
        transientAmt = 3.0f * std::clamp(p.transient, 0.0f, 1.0f);
        gateEnabled = p.gateDb > -79.5f;
        gateThresh = dbToGain(p.gateDb);
        outGain = dbToGain(p.outputDb);
    }

    float process(float x) {
        if (p.bypass) return softClip(x * outGain);

        float y = hp2.process(hp1.process(x));
        y = shelf.process(peak.process(y));

        const float r = std::fabs(y);
        const float fast = fastEnv.process(r);
        const float slow = slowEnv.process(r);

        if (transientAmt > 0.0f) {
            // When the fast envelope jumps above the slow one we're at the
            // start of a crunch: push it up (max +12 dB).
            const float ratio = (fast + 1e-5f) / (slow + 1e-5f);
            y *= std::min(1.0f + transientAmt * std::max(0.0f, ratio - 1.0f), 4.0f);
        }

        if (gateEnabled) {
            const float target = fast > gateThresh ? 1.0f : 0.1f;  // -20 dB floor, not silence
            gateGain += (target - gateGain) * (target > gateGain ? gateOpen : gateClose);
            y *= gateGain;
        }

        return softClip(y * outGain);
    }

private:
    static float softClip(float x) { return 0.8f * std::tanh(x * 1.25f); }  // never exceeds 0.8 FS

    float fs = 48000.0f;
    Params p;
    Biquad hp1, hp2, peak, shelf;
    EnvelopeFollower fastEnv, slowEnv;
    float transientAmt = 0.0f;
    bool gateEnabled = true;
    float gateThresh = 0.001f, gateGain = 1.0f, gateOpen = 0.0f, gateClose = 0.0f;
    float outGain = 1.0f;
};

}  // namespace crunch
