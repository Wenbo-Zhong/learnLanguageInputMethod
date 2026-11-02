#include "learnime_sentencepiece.h"

#include <sentencepiece_processor.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

struct li_sentencepiece { sentencepiece::SentencePieceProcessor value; };

extern "C" li_sentencepiece *li_sentencepiece_create(const char *path) {
    if (!path) return nullptr;
    auto *processor = new li_sentencepiece();
    if (!processor->value.Load(path).ok()) { delete processor; return nullptr; }
    return processor;
}

extern "C" void li_sentencepiece_destroy(li_sentencepiece *processor) { delete processor; }

extern "C" int li_sentencepiece_encode(li_sentencepiece *processor, const char *text,
                                           int32_t *output, size_t capacity) {
    if (!processor || !text || !output) return -1;
    std::vector<int> ids;
    if (!processor->value.Encode(text, &ids).ok()) return -1;
    const size_t count = std::min(ids.size(), capacity);
    for (size_t i = 0; i < count; ++i) output[i] = static_cast<int32_t>(ids[i]);
    return static_cast<int>(count);
}

extern "C" char *li_sentencepiece_decode(li_sentencepiece *processor, const int32_t *ids, size_t count) {
    if (!processor || (!ids && count)) return nullptr;
    std::vector<int> values;
    values.reserve(count);
    for (size_t i = 0; i < count; ++i) values.push_back(static_cast<int>(ids[i]));
    std::string decoded;
    if (!processor->value.Decode(values, &decoded).ok()) return nullptr;
    auto *result = static_cast<char *>(std::malloc(decoded.size() + 1));
    if (!result) return nullptr;
    std::memcpy(result, decoded.c_str(), decoded.size() + 1);
    return result;
}

extern "C" void li_sentencepiece_free(void *memory) { std::free(memory); }
