#include <jni.h>

#include "CrunchEngine.h"

static CrunchEngine& engine() {
    static CrunchEngine e;
    return e;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_crunchboost_CrunchEngine_nativeStart(JNIEnv*, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_crunchboost_CrunchEngine_nativeStop(JNIEnv*, jobject) {
    engine().stop();
}

JNIEXPORT void JNICALL
Java_com_example_crunchboost_CrunchEngine_nativeSetParams(JNIEnv*, jobject, jfloat lowCutHz,
                                                          jfloat crunchHz, jfloat crunchDb,
                                                          jfloat airDb, jfloat transient,
                                                          jfloat gateDb, jfloat outputDb,
                                                          jboolean bypass) {
    crunch::Params p;
    p.lowCutHz = lowCutHz;
    p.crunchHz = crunchHz;
    p.crunchDb = crunchDb;
    p.airDb = airDb;
    p.transient = transient;
    p.gateDb = gateDb;
    p.outputDb = outputDb;
    p.bypass = bypass == JNI_TRUE;
    engine().setParams(p);
}

JNIEXPORT jdouble JNICALL
Java_com_example_crunchboost_CrunchEngine_nativeOutputLatencyMs(JNIEnv*, jobject) {
    return engine().outputLatencyMs();
}

JNIEXPORT jint JNICALL
Java_com_example_crunchboost_CrunchEngine_nativeSampleRate(JNIEnv*, jobject) {
    return engine().sampleRate();
}

}  // extern "C"
