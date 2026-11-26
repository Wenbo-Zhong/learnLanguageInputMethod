#include "learnime_pinyin.h"
#include "jni_utf8.h"

extern "C" JNIEXPORT void JNICALL Java_dev_learnime_android_NativePinyin_configure(JNIEnv *, jobject, jlong h, jint groups) {
    li_pinyin_configure(reinterpret_cast<li_pinyin *>(h), groups);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_userWords(JNIEnv *env, jobject, jlong h) {
    return li_jni_bytes(env, li_pinyin_user_words(reinterpret_cast<li_pinyin *>(h)));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_learningError(JNIEnv *env, jobject, jlong h) {
    return li_jni_bytes(env, li_pinyin_learning_error(reinterpret_cast<li_pinyin *>(h)));
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_learnime_android_NativePinyin_userWord(JNIEnv *env, jobject, jlong h, jbyteArray p, jbyteArray w, jboolean remove) {
    auto spelling = li_jni_utf8(env, p), word = li_jni_utf8(env, w);
    if (env->ExceptionCheck()) return false;
    return li_pinyin_user_word(reinterpret_cast<li_pinyin *>(h), spelling.c_str(), word.c_str(), remove);
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_learnime_android_NativePinyin_clearLearning(JNIEnv *, jobject, jlong h) {
    return li_pinyin_clear_learning(reinterpret_cast<li_pinyin *>(h));
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_learnime_android_NativePinyin_createWithUser(JNIEnv *env, jobject, jbyteArray path, jbyteArray user) {
    auto p = li_jni_utf8(env, path), u = li_jni_utf8(env, user);
    if (env->ExceptionCheck()) return 0;
    return reinterpret_cast<jlong>(li_pinyin_create_with_user(p.c_str(), u.c_str()));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_select(JNIEnv *env, jobject, jlong handle, jlong token, jint index, jbyteArray path, jboolean learn) {
    auto value = li_jni_utf8(env, path);
    if (env->ExceptionCheck()) return nullptr;
    return li_jni_bytes(env, li_pinyin_select(reinterpret_cast<li_pinyin *>(handle), token, index, value.c_str(), learn));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_predict(JNIEnv *env, jobject, jlong handle, jbyteArray history) {
    auto value = li_jni_utf8(env, history);
    if (env->ExceptionCheck()) return nullptr;
    return li_jni_bytes(env, li_pinyin_predict(reinterpret_cast<li_pinyin *>(handle), value.c_str()));
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_learnime_android_NativePinyin_create(JNIEnv *env, jobject, jbyteArray path) {
    auto value = li_jni_utf8(env, path);
    if (env->ExceptionCheck()) return 0;
    return reinterpret_cast<jlong>(li_pinyin_create(value.c_str()));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_search(JNIEnv *env, jobject, jlong handle, jbyteArray input, jboolean nine) {
    auto value = li_jni_utf8(env, input);
    if (env->ExceptionCheck()) return nullptr;
    return li_jni_bytes(env, li_pinyin_search(reinterpret_cast<li_pinyin *>(handle), value.c_str(), nine));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativePinyin_searchAdvanced(JNIEnv *env, jobject, jlong handle, jbyteArray input, jboolean nine, jbyteArray locks, jboolean corrections) {
    auto raw = li_jni_utf8(env, input), fixed = li_jni_utf8(env, locks);
    if (env->ExceptionCheck()) return nullptr;
    return li_jni_bytes(env, li_pinyin_search_advanced(reinterpret_cast<li_pinyin *>(handle), raw.c_str(), nine, fixed.c_str(), corrections));
}
extern "C" JNIEXPORT void JNICALL Java_dev_learnime_android_NativePinyin_destroy(JNIEnv *, jobject, jlong handle) {
    li_pinyin_destroy(reinterpret_cast<li_pinyin *>(handle));
}
