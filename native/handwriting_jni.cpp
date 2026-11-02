#include <jni.h>
#include <zinnia.h>
#include <memory>
#include <string>

extern "C" JNIEXPORT jlong JNICALL Java_dev_learnime_android_NativeHandwriting_create(JNIEnv *env, jobject, jstring path) {
    std::unique_ptr<zinnia::Recognizer> recognizer(zinnia::Recognizer::create());
    const char *file = env->GetStringUTFChars(path, nullptr);
    bool ok = recognizer->open(file);
    env->ReleaseStringUTFChars(path, file);
    return ok ? reinterpret_cast<jlong>(recognizer.release()) : 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_learnime_android_NativeHandwriting_recognize(JNIEnv *env, jobject, jlong handle, jintArray input) {
    auto *recognizer = reinterpret_cast<zinnia::Recognizer *>(handle);
    std::string output;
    if (recognizer) {
        jsize size = env->GetArrayLength(input);
        if (size > 0 && size <= 30000 && size % 3 == 0) {
            jint *points = env->GetIntArrayElements(input, nullptr);
            std::unique_ptr<zinnia::Character> character(zinnia::Character::create());
            character->set_width(1000); character->set_height(1000);
            for (int i = 0; i + 2 < size; i += 3)
                character->add(points[i], points[i + 1], points[i + 2]);
            env->ReleaseIntArrayElements(input, points, JNI_ABORT);
            std::unique_ptr<zinnia::Result> result(recognizer->classify(*character, 12));
            if (result) for (size_t i = 0; i < result->size(); ++i) output += std::string(result->value(i)) + "\n";
        }
    }
    auto bytes = env->NewByteArray(output.size());
    env->SetByteArrayRegion(bytes, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
    return bytes;
}
extern "C" JNIEXPORT void JNICALL Java_dev_learnime_android_NativeHandwriting_destroy(JNIEnv *, jobject, jlong h) {
    delete reinterpret_cast<zinnia::Recognizer *>(h);
}
