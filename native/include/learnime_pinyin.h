#pragma once
#ifdef __cplusplus
extern "C" {
#endif
typedef struct li_pinyin li_pinyin;
// Production decoder: libime. dictionary_path points to sc.dict; sc.lm and
// sc.lm.predict must be in the same directory. User files use LIUSER1 framing.
// Per-handle calls are serialized by platform adapters; engine globals are locked internally.
li_pinyin * li_pinyin_create(const char * dictionary_path);
li_pinyin * li_pinyin_create_with_user(const char * dictionary_path, const char * user_path);
// UTF-8 TSV: text, spelling, consumed keys, generation, decoder index, search path.
// At most 96 rows. Long input searches its first 40 keys without consuming the rest.
// Returned pointer is valid until the next search/destroy on this handle.
const char * li_pinyin_search(li_pinyin * engine, const char * input, int nine_key);
// Adds "spelling:end,..." and correction kind to TSV. Locks bind raw key ranges,
// including incomplete syllables. Corrections never rewrite the caller's input.
const char * li_pinyin_search_advanced(li_pinyin * engine, const char * input, int nine_key,
                                     const char * locks, int corrections);
// Configure independent fuzzy groups (bit0 an/ang, bit1 en/eng, bit2 in/ing,
// bit3 c/ch, bit4 s/sh, bit5 z/zh, bit6 l/n, bit7 f/h). Legacy APIs keep defaults
// until configured. Explicit configuration applies regardless of typo correction.
void li_pinyin_configure(li_pinyin *engine, unsigned int fuzzy_groups);
// Native user dictionary management, TSV fullPinyin/word/cost.
const char *li_pinyin_user_words(li_pinyin *engine);
int li_pinyin_user_word(li_pinyin *engine, const char *spelling, const char *word, int remove);
int li_pinyin_clear_learning(li_pinyin *engine);
// Selection identifies a saved search generation and decoder index/path pair.
// Returns empty on stale/invalid selection. Learning can be disabled per editor.
// Learning failure does not reject a valid selection. In-memory updates remain
// pending and are retried by the next save.
const char * li_pinyin_select(li_pinyin * engine, unsigned long long token, int index, const char * path, int learn);
// Last optional learning error, cleared after successful persistence.
const char * li_pinyin_learning_error(li_pinyin * engine);
// UTF-8 newline-separated predictions for an explicit committed history.
const char * li_pinyin_predict(li_pinyin * engine, const char * history);
void li_pinyin_destroy(li_pinyin * engine);
#ifdef __cplusplus
}
#endif
