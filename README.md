# CrunchBoost (prototype)

Live "crunch enhancer": your mic goes through a crunch-focused EQ and comes out in your headphones with as little delay as the phone allows.

Inspired by Zampini & Spence's 2008 "sonic chip" experiment. When people heard their own chewing played back with the highs boosted, they rated the chips as crunchier and fresher.

## Build and run

1. Unzip, then in Android Studio choose **File → Open** and pick the `CrunchBoost` folder.
2. Let it sync. If Studio asks, install the NDK and CMake 3.22.1 (SDK Manager → SDK Tools).
3. Plug in a phone with USB debugging on, then press **Run**.
4. Plug in **wired** headphones (a USB-C or 3.5 mm headset with an inline mic is ideal), tap **Start**, and eat something crunchy.

Requirements: Android 8.0+ (minSdk 26). The project uses AGP 8.7 / Gradle 8.9 / Kotlin 2.0 and [Oboe](https://github.com/google/oboe) 1.9 (from Maven).

## How it works

```
mic ──► Oboe input stream (VoiceRecognition preset: no noise suppression or AGC)
         │  non-blocking read inside the output callback
         ▼
   4th-order high-pass ──► peak EQ "crunch" ──► high shelf "air"
         ──► transient shaper "snap" ──► [crunch layer] ──► soft gate ──► gain ──► soft limiter
         ▼
Oboe output stream (low-latency, exclusive) ──► headphones
```

| File | What it does |
|---|---|
| `app/src/main/cpp/Dsp.h` | The DSP chain. Pure C++, so you can test it on a desktop. |
| `app/src/main/cpp/CrunchEngine.cpp` | Full-duplex Oboe engine. Handles latency trimming and headphone plug/unplug restarts. |
| `app/src/main/java/.../MainActivity.kt` | UI with presets, sliders, an A/B bypass switch and a headphone-route check. |

## Tuning notes

- **Low cut**: sealed earbuds make chewing boomy (the occlusion effect). Cutting 150–300 Hz is half the trick.
- **Crunch frequency/boost**: about 3–6 kHz is where "crisp" lives. Push higher (6–9 kHz) for lettuce and fizz.
- **Snap**: exaggerates the attack of each fracture. This often matters more than EQ.
- **Quiet between bites**: a gentle gate (−20 dB floor) that stops the boosted room hiss from hissing at you.
- **Bypass**: flip it mid-bite. The effect is much easier to notice as a contrast.

## Locked settings

These were tuned by ear on a real phone and are fixed in `MainActivity.kt` (companion object): low cut 99 Hz, crunch frequency 4 kHz and air +4 dB (both apply only when Paper mode is off), snap 82%, and quiet-between-bites gate −34 dB. The adjustable sliders are Crunch boost and Volume. Presets now set only the crunch boost.

## Effects (toggles)

- **Paper mode**: replaces the crunch peak and air shelf with one broad high shelf from 2 kHz up, using the Crunch boost amount. This follows Zampini & Spence (2004), where boosting 2–20 kHz made chips seem crisper and fresher.
- **Crunch layer**: detects each bite's onset and adds a 50–140 ms burst of resonant micro-clicks that scales with the bite's loudness. This is loosely based on Endo, Ino & Fujisaki (2016), where an artificial crunchy chewing sound made soft foods seem stiffer.

Research tip: hearing the unprocessed sound gives your brain a reference point, so use the bypass switch sparingly.

## Known limitations

- **Latency.** You hear your own chewing through your jaw instantly, so any delay sounds like an echo. Pixels and recent Samsungs typically land around 10–25 ms round trip on wired headphones. Bluetooth adds 100–300 ms, and the app warns you about it.
- The status line shows *output* latency only, because Android doesn't report input latency. For a true round-trip figure, use Google's OboeTester app (Round Trip Latency test).
- The engine stops when the app goes to the background, because Android blocks background mic access without a foreground service.
- **Feedback.** Open-back headphones plus high volume can squeal. Start quiet. The app refuses to run through the phone speaker.
- Earbud-internal mics (such as the ANC mics in Pixel Buds or Galaxy Buds) aren't available to apps, so the inline or phone mic is what you get.

## Ideas for next steps

- A per-food auto-preset (detect crunch spectra and pick settings automatically)
- A loudness-matched bypass so the A/B comparison is fair
- Recording before/after clips to share
- Running a foreground service so it keeps working with the screen off
