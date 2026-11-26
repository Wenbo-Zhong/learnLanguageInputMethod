#pragma once
#include <jni.h>
#include <string>

// Java callers explicitly encode/decode standard UTF-8. JNI's string helpers
// use modified UTF-8 and cannot carry supplementary code points unchanged.
inline std::string li_jni_utf8(JNIEnv *env, jbyteArray input) {
    const jsize size = env->GetArrayLength(input);
    std::string value(size, '\0');
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(value.data()));
    return value;
}

inline jbyteArray li_jni_bytes(JNIEnv *env, const std::string &value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()),
                                       reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
