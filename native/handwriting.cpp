#include "learnime_handwriting.h"
#include <zinnia.h>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
extern "C" void *li_handwriting_create(const char *path) {
    try {
        std::unique_ptr<zinnia::Recognizer> r(zinnia::Recognizer::create());
        return r->open(path) ? r.release() : nullptr;
    } catch (...) { return nullptr; }
}
extern "C" char *li_handwriting_recognize(void *handle, const int32_t *points, int count) {
    if (!handle || !points || count <= 0 || count > 30000 || count % 3) return nullptr;
    try {
        std::unique_ptr<zinnia::Character> c(zinnia::Character::create());
        c->set_width(1000); c->set_height(1000);
        for (int i = 0; i < count; i += 3) c->add(points[i], points[i+1], points[i+2]);
        std::unique_ptr<zinnia::Result> r(static_cast<zinnia::Recognizer *>(handle)->classify(*c, 12));
        std::string text;
        if (r) for (size_t i = 0; i < r->size(); ++i) text += std::string(r->value(i)) + "\n";
        auto *out = static_cast<char *>(std::malloc(text.size()+1));
        if (out) std::memcpy(out, text.c_str(), text.size()+1);
        return out;
    } catch (...) { return nullptr; }
}
extern "C" void li_handwriting_free(char *text) { std::free(text); }
extern "C" void li_handwriting_destroy(void *handle) { delete static_cast<zinnia::Recognizer *>(handle); }
