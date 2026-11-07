package com.example.crunchboost

/** Thin JNI wrapper around the native Oboe engine (see app/src/main/cpp). */
object CrunchEngine {
    init {
        System.loadLibrary("crunch")
    }

    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeSetParams(
        lowCutHz: Float,
        crunchHz: Float,
        crunchDb: Float,
        airDb: Float,
        transient: Float,
        gateDb: Float,
        outputDb: Float,
        bypass: Boolean,
        paper: Boolean,
        crackle: Boolean,
    )
    external fun nativeOutputLatencyMs(): Double
    external fun nativeSampleRate(): Int
}
