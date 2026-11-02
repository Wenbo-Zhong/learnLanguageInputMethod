#include <jni.h>
#include <sentencepiece_processor.h>
#include <string>
#include <vector>

extern "C" JNIEXPORT jlong JNICALL
Java_dev_learnime_android_NativeSentencePiece_create(JNIEnv *env, jobject, jstring path) {
    const char *value = env->GetStringUTFChars(path, nullptr);
    auto *processor = new sentencepiece::SentencePieceProcessor();
    const auto status = processor->Load(value);
    env->ReleaseStringUTFChars(path, value);
    if (!status.ok()) { delete processor; return 0; }
    return reinterpret_cast<jlong>(processor);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_learnime_android_NativeSentencePiece_encode(JNIEnv *env, jobject, jlong handle, jstring text) {
    const char *value = env->GetStringUTFChars(text, nullptr);
    std::vector<int> ids;
    auto *processor = reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
    const auto status = processor->Encode(value, &ids);
    env->ReleaseStringUTFChars(text, value);
    if (!status.ok()) return env->NewIntArray(0);
    auto output = env->NewIntArray(static_cast<jsize>(ids.size()));
    env->SetIntArrayRegion(output, 0, static_cast<jsize>(ids.size()), ids.data());
    return output;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_learnime_android_NativeSentencePiece_decode(JNIEnv *env, jobject, jlong handle, jintArray input) {
    auto array = env->GetIntArrayElements(input, nullptr);
    const auto count = env->GetArrayLength(input);
    std::vector<int> ids(array, array + count);
    env->ReleaseIntArrayElements(input, array, JNI_ABORT);
    std::string output;
    auto *processor = reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
    const auto status = processor->Decode(ids, &output);
    return status.ok() ? env->NewStringUTF(output.c_str()) : env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_learnime_android_NativeSentencePiece_destroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
}

// M2M100's HF vocabulary differs from the SentencePiece vocabulary. Decode the
// actual pieces, including entries absent from SPM, rather than re-mapping to IDs.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_learnime_android_NativeSentencePiece_decodePieces(JNIEnv *env, jobject, jlong handle, jobjectArray input) {
    std::vector<std::string> pieces;
    const jsize count = env->GetArrayLength(input);
    pieces.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(input, i));
        const jsize length = env->GetArrayLength(bytes);
        std::string piece(length, '\0');
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(piece.data()));
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) return nullptr;
        pieces.push_back(std::move(piece));
    }
    std::string output;
    auto *processor = reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
    if (!processor->Decode(pieces, &output).ok()) return nullptr;
    auto bytes = env->NewByteArray(static_cast<jsize>(output.size()));
    if (bytes != nullptr) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(output.size()),
                                                 reinterpret_cast<const jbyte *>(output.data()));
    return bytes;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_learnime_android_NativeSentencePiece_encodeUtf8(JNIEnv *env, jobject, jlong handle, jbyteArray input) {
    const jsize length = env->GetArrayLength(input);
    std::string text(length, '\0');
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(text.data()));
    if (env->ExceptionCheck()) return nullptr;
    std::vector<int> ids;
    auto *processor = reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
    if (!processor->Encode(text, &ids).ok()) return env->NewIntArray(0);
    auto output = env->NewIntArray(static_cast<jsize>(ids.size()));
    if (output != nullptr) env->SetIntArrayRegion(output, 0, static_cast<jsize>(ids.size()), ids.data());
    return output;
}
