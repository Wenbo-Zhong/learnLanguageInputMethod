#include "learnime_pinyin.h"
#include <jni.h>
#include <cstring>
extern "C" JNIEXPORT void JNICALL Java_dev_learnime_android_NativePinyin_configure(JNIEnv *, jobject, jlong h, jint groups) {
    li_pinyin_configure(reinterpret_cast<li_pinyin *>(h), groups);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_userWords(JNIEnv *env, jobject, jlong h) {
    const char *s = li_pinyin_user_words(reinterpret_cast<li_pinyin *>(h));
    auto result = env->NewByteArray(strlen(s));
    env->SetByteArrayRegion(result, 0, strlen(s), reinterpret_cast<const jbyte *>(s));
    return result;
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_learnime_android_NativePinyin_userWord(JNIEnv *env, jobject, jlong h, jstring p, jstring w, jboolean remove) {
    const char *spelling = env->GetStringUTFChars(p, nullptr);
    const char *word = env->GetStringUTFChars(w, nullptr);
    bool result = li_pinyin_user_word(reinterpret_cast<li_pinyin *>(h), spelling, word, remove);
    env->ReleaseStringUTFChars(p, spelling); env->ReleaseStringUTFChars(w, word);
    return result;
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_learnime_android_NativePinyin_clearLearning(JNIEnv *, jobject, jlong h) {
    return li_pinyin_clear_learning(reinterpret_cast<li_pinyin *>(h));
}
static jbyteArray bytes(JNIEnv * env, const char * value) {
    auto result = env->NewByteArray(strlen(value));
    env->SetByteArrayRegion(result, 0, strlen(value), reinterpret_cast<const jbyte *>(value));
    return result;
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_learnime_android_NativePinyin_createWithUser(JNIEnv * env, jobject, jstring path, jstring user) {
    const char * p = env->GetStringUTFChars(path, nullptr);
    const char * u = env->GetStringUTFChars(user, nullptr);
    auto * engine = li_pinyin_create_with_user(p, u);
    env->ReleaseStringUTFChars(path, p); env->ReleaseStringUTFChars(user, u);
    return reinterpret_cast<jlong>(engine);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_select(JNIEnv * env, jobject, jlong handle, jlong token, jint index, jstring path, jboolean learn) {
    const char * value = env->GetStringUTFChars(path, nullptr);
    auto result = bytes(env, li_pinyin_select(reinterpret_cast<li_pinyin *>(handle), token, index, value, learn));
    env->ReleaseStringUTFChars(path, value);
    return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_predict(JNIEnv * env, jobject, jlong handle, jstring history) {
    const char * value = env->GetStringUTFChars(history, nullptr);
    auto result = bytes(env, li_pinyin_predict(reinterpret_cast<li_pinyin *>(handle), value));
    env->ReleaseStringUTFChars(history, value);
    return result;
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_learnime_android_NativePinyin_create(JNIEnv * env, jobject, jstring path) {
    const char * value = env->GetStringUTFChars(path, nullptr);
    auto * engine = li_pinyin_create(value); env->ReleaseStringUTFChars(path, value);
    return reinterpret_cast<jlong>(engine);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_search(JNIEnv * env, jobject, jlong handle, jstring input, jboolean nine) {
    const char * value = env->GetStringUTFChars(input, nullptr);
    const char * result = li_pinyin_search(reinterpret_cast<li_pinyin *>(handle), value, nine);
    env->ReleaseStringUTFChars(input, value);
    auto bytes = env->NewByteArray(strlen(result));
    env->SetByteArrayRegion(bytes, 0, strlen(result), reinterpret_cast<const jbyte *>(result));
    return bytes;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_searchAdvanced(JNIEnv * env, jobject, jlong handle, jstring input, jboolean nine, jstring locks, jboolean corrections) {
    const char * raw = env->GetStringUTFChars(input, nullptr);
    const char * fixed = env->GetStringUTFChars(locks, nullptr);
    auto result = bytes(env, li_pinyin_search_advanced(reinterpret_cast<li_pinyin *>(handle), raw, nine, fixed, corrections));
    env->ReleaseStringUTFChars(input, raw); env->ReleaseStringUTFChars(locks, fixed);
    return result;
}
extern "C" JNIEXPORT void JNICALL Java_dev_learnime_android_NativePinyin_destroy(JNIEnv *, jobject, jlong handle) {
    li_pinyin_destroy(reinterpret_cast<li_pinyin *>(handle));
}
