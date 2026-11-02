#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <climits>
#include <new>

namespace {
struct ModelMapping {
    void* base;
    size_t mapped_length;
    size_t delta;
    jlong length;
};

void fail(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_learnime_android_MappedModelAsset_map(JNIEnv* env, jobject, jint fd, jlong offset, jlong length) {
    struct stat info {};
    const long page_size = sysconf(_SC_PAGESIZE);
    if (fd < 0 || offset < 0 || length <= 0 || length > INT_MAX || page_size <= 0 ||
        fstat(fd, &info) != 0 || offset > info.st_size || length > info.st_size - offset) {
        fail(env, "Invalid APK model asset range");
        return 0;
    }
    const size_t delta = static_cast<size_t>(offset % page_size);
    const size_t mapped_length = static_cast<size_t>(length) + delta;
    void* base = mmap(nullptr, mapped_length, PROT_READ, MAP_PRIVATE, fd, offset - delta);
    if (base == MAP_FAILED) {
        fail(env, "Could not map APK model asset");
        return 0;
    }
    auto* mapping = new (std::nothrow) ModelMapping {base, mapped_length, delta, length};
    if (mapping == nullptr) {
        munmap(base, mapped_length);
        fail(env, "Could not allocate model mapping metadata");
        return 0;
    }
    return reinterpret_cast<jlong>(mapping);
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_learnime_android_MappedModelAsset_buffer(JNIEnv* env, jobject, jlong handle) {
    auto* mapping = reinterpret_cast<ModelMapping*>(handle);
    return env->NewDirectByteBuffer(static_cast<char*>(mapping->base) + mapping->delta, mapping->length);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_learnime_android_MappedModelAsset_unmap(JNIEnv*, jobject, jlong handle) {
    auto* mapping = reinterpret_cast<ModelMapping*>(handle);
    if (mapping == nullptr) return;
    munmap(mapping->base, mapping->mapped_length);
    delete mapping;
}
