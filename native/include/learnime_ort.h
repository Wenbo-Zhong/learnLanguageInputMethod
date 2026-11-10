#pragma once
#include <stddef.h>
#include <stdint.h>
#pragma GCC visibility push(default)
#ifdef __cplusplus
extern "C" {
#endif
typedef struct li_ort li_ort;
typedef struct li_ort_value li_ort_value;
typedef struct li_ort_result li_ort_result;
const char *li_ort_error(void);
li_ort *li_ort_create(const char *encoder, const char *decoder);
void li_ort_destroy(li_ort *);
void li_ort_cancel(li_ort *);
void li_ort_begin(li_ort *);
li_ort_value *li_ort_tensor(const void *data, size_t bytes, const int64_t *shape, size_t rank, int type);
void li_ort_value_destroy(li_ort_value *);
li_ort_result *li_ort_run(li_ort *, int decoder, const char *const *names, li_ort_value *const *values, size_t count);
li_ort_value *li_ort_output(li_ort_result *, const char *name);
void li_ort_result_destroy(li_ort_result *);
int li_ort_top_tokens(li_ort_value *, int vocabulary, int32_t *tokens, double *scores, size_t count);
uint64_t li_ort_footprint(void);
#pragma GCC visibility pop
#ifdef __cplusplus
}
#endif
