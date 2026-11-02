#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct li_sentencepiece li_sentencepiece;
li_sentencepiece *li_sentencepiece_create(const char *model_path);
void li_sentencepiece_destroy(li_sentencepiece *processor);
int li_sentencepiece_encode(li_sentencepiece *processor, const char *text, int32_t *output, size_t capacity);
char *li_sentencepiece_decode(li_sentencepiece *processor, const int32_t *ids, size_t count);
void li_sentencepiece_free(void *memory);
#ifdef __cplusplus
}
#endif
