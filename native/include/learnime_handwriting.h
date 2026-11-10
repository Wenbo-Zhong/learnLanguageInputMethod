#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
void *li_handwriting_create(const char *path);
char *li_handwriting_recognize(void *handle, const int32_t *points, int count);
void li_handwriting_free(char *text);
void li_handwriting_destroy(void *handle);
#ifdef __cplusplus
}
#endif
