#include "learnime_ort.h"
#include <onnxruntime_cxx_api.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <mach/mach.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
thread_local std::string error;
Ort::Env &environment() { static Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "LearnIME-M2M"); return env; }
struct Mapping {
    void *data = MAP_FAILED;
    size_t size = 0;
    explicit Mapping(const char *path) {
        int fd = open(path, O_RDONLY);
        if (fd < 0) throw std::runtime_error("M2M100 模型文件无法打开");
        struct stat info {};
        if (fstat(fd, &info) || info.st_size <= 0) { close(fd); throw std::runtime_error("M2M100 模型文件无效"); }
        size = info.st_size;
        data = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (data == MAP_FAILED) throw std::runtime_error("M2M100 模型映射失败");
    }
    ~Mapping() { if (data != MAP_FAILED) munmap(data, size); }
};
Ort::SessionOptions options() {
    Ort::SessionOptions value;
    value.SetIntraOpNumThreads(2);
    value.SetInterOpNumThreads(1);
    value.DisableCpuMemArena();
    value.DisableMemPattern();
    value.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_DISABLE_ALL);
    value.AddConfigEntry("session.disable_prepacking", "1");
    value.AddConfigEntry("session.use_ort_model_bytes_directly", "1");
    value.AddConfigEntry("session.use_ort_model_bytes_for_initializers", "1");
    return value;
}
}
struct li_ort_value {
    Ort::Value owned{nullptr};
    const OrtValue *value = nullptr;
    std::vector<uint8_t> bytes;
};
struct li_ort_result {
    std::vector<std::string> names;
    std::vector<Ort::Value> values;
    std::vector<li_ort_value> references;
};
struct li_ort {
    // Sessions are destroyed before their borrowed mappings.
    Mapping encoderBytes, decoderBytes;
    Ort::SessionOptions config;
    Ort::Session encoder, decoder;
    Ort::RunOptions run;
    std::vector<std::string> encoderOutputs, decoderOutputs;
    li_ort(const char *enc, const char *dec)
        : encoderBytes(enc), decoderBytes(dec), config(options()),
          encoder(environment(), encoderBytes.data, encoderBytes.size, config),
          decoder(environment(), decoderBytes.data, decoderBytes.size, config) {
        Ort::AllocatorWithDefaultOptions allocator;
        for (size_t i = 0; i < encoder.GetOutputCount(); ++i)
            encoderOutputs.emplace_back(encoder.GetOutputNameAllocated(i, allocator).get());
        for (size_t i = 0; i < decoder.GetOutputCount(); ++i)
            decoderOutputs.emplace_back(decoder.GetOutputNameAllocated(i, allocator).get());
    }
};
extern "C" const char *li_ort_error() { return error.c_str(); }
extern "C" li_ort *li_ort_create(const char *encoder, const char *decoder) {
    try { return new li_ort(encoder, decoder); } catch (const std::exception &e) { error = e.what(); return nullptr; }
}
extern "C" void li_ort_destroy(li_ort *value) { delete value; }
extern "C" void li_ort_cancel(li_ort *value) { if (value) try { value->run.SetTerminate(); } catch (...) {} }
extern "C" void li_ort_begin(li_ort *value) { if (value) try { value->run.UnsetTerminate(); } catch (...) {} }
extern "C" li_ort_value *li_ort_tensor(const void *data, size_t bytes, const int64_t *shape, size_t rank, int type) {
    try {
        auto result = std::make_unique<li_ort_value>();
        result->bytes.resize(std::max(bytes, size_t(1)));
        if (bytes) std::memcpy(result->bytes.data(), data, bytes);
        auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        result->owned = Ort::Value::CreateTensor(memory, result->bytes.data(), bytes, shape, rank,
                                               static_cast<ONNXTensorElementDataType>(type));
        result->value = result->owned;
        return result.release();
    } catch (const std::exception &e) { error = e.what(); return nullptr; }
}
extern "C" void li_ort_value_destroy(li_ort_value *value) { delete value; }
extern "C" li_ort_result *li_ort_run(li_ort *runtime, int decoder, const char *const *names,
                                    li_ort_value *const *values, size_t count) {
    try {
        auto result = std::make_unique<li_ort_result>();
        result->names = decoder ? runtime->decoderOutputs : runtime->encoderOutputs;
        std::vector<const char *> outputs;
        for (auto &name : result->names) outputs.push_back(name.c_str());
        std::vector<const OrtValue *> inputs;
        for (size_t i = 0; i < count; ++i) inputs.push_back(values[i]->value);
        std::vector<OrtValue *> raw(outputs.size(), nullptr);
        auto *status = Ort::GetApi().Run(decoder ? runtime->decoder : runtime->encoder, runtime->run,
                                        names, inputs.data(), count, outputs.data(), outputs.size(), raw.data());
        for (auto *value : raw) result->values.emplace_back(value);
        Ort::ThrowOnError(status);
        result->references.resize(raw.size());
        for (size_t i = 0; i < raw.size(); ++i) result->references[i].value = result->values[i];
        return result.release();
    } catch (const std::exception &e) { error = e.what(); return nullptr; }
}
extern "C" li_ort_value *li_ort_output(li_ort_result *result, const char *name) {
    for (size_t i = 0; i < result->names.size(); ++i) if (result->names[i] == name) return &result->references[i];
    error = std::string("Missing model output: ") + name; return nullptr;
}
extern "C" void li_ort_result_destroy(li_ort_result *result) { delete result; }
extern "C" int li_ort_top_tokens(li_ort_value *value, int vocabulary, int32_t *tokens, double *scores, size_t count) {
    try {
        Ort::ConstValue tensor(value->value);
        auto info = tensor.GetTensorTypeAndShapeInfo();
        size_t size = info.GetElementCount();
        if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || size < size_t(vocabulary))
            throw std::runtime_error("M2M100 logits shape/type invalid");
        auto *data = tensor.GetTensorData<float>() + size - vocabulary;
        double maximum = -INFINITY;
        for (int i = 0; i < vocabulary; ++i) {
            if (!std::isfinite(data[i])) throw std::runtime_error("M2M100 模型输出无效");
            maximum = std::max(maximum, double(data[i]));
        }
        double sum = 0;
        for (int i = 0; i < vocabulary; ++i) sum += std::exp(double(data[i]) - maximum);
        double normalizer = maximum + std::log(sum);
        std::vector<int32_t> order(vocabulary);
        std::iota(order.begin(), order.end(), 0);
        count = std::min(count, order.size());
        std::partial_sort(order.begin(), order.begin() + count, order.end(),
            [&](int a, int b) { return data[a] == data[b] ? a < b : data[a] > data[b]; });
        for (size_t i = 0; i < count; ++i) { tokens[i] = order[i]; scores[i] = data[order[i]] - normalizer; }
        return int(count);
    } catch (const std::exception &e) { error = e.what(); return -1; }
}
extern "C" uint64_t li_ort_footprint() {
    task_vm_info_data_t info {};
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, reinterpret_cast<task_info_t>(&info), &count) != KERN_SUCCESS) return 0;
    return info.phys_footprint;
}
