#include "learnime_pinyin.h"
#include "matrixsearch.h"
#include "spellingtrie.h"
#include "lpicache.h"
#include <algorithm>
#include <cctype>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <vector>

using namespace ime_pinyin;
namespace {
std::mutex decoder_mutex;
constexpr size_t max_keys = 40;
constexpr size_t beam_size = 64;
const char * key_letters[] = {"", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
struct Beam { std::string path, syllable; float score; };
struct Segment { std::string spelling; size_t end; };
struct Path { std::string text; std::vector<size_t> ends; };
struct Candidate {
    std::string text, spelling; size_t consumed; float score; std::string path; size_t index;
    std::vector<Segment> segments;
    std::string correction;
    bool preserve_tail = false;
    size_t sentence_rank = 0;
    size_t prefix_affinity = 0;
};
std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return char(std::tolower(c)); });
    return s;
}
std::string normalize_umlaut(std::string s) {
    for (size_t i = 0; i + 2 < s.size(); ++i)
        if ((s[i] == 'l' || s[i] == 'n') && s[i + 1] == 'v' && s[i + 2] == 'e') s[i + 1] = 'u';
    return s;
}
std::string digits(const std::string & s) {
    std::string out;
    for (char c : s) for (int k = 2; k <= 9; ++k)
        if (std::string(key_letters[k]).find(c) != std::string::npos) { out += char('0' + k); break; }
    return out;
}
size_t edit_distance(const std::string & a, const std::string & b) {
    std::vector<std::vector<size_t>> distance(a.size() + 1, std::vector<size_t>(b.size() + 1));
    for (size_t i = 0; i <= a.size(); ++i) distance[i][0] = i;
    for (size_t j = 0; j <= b.size(); ++j) distance[0][j] = j;
    for (size_t i = 1; i <= a.size(); ++i) {
        for (size_t j = 1; j <= b.size(); ++j) {
            distance[i][j] = std::min({distance[i - 1][j] + 1, distance[i][j - 1] + 1,
                                      distance[i - 1][j - 1] + size_t(a[i - 1] != b[j - 1])});
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
                distance[i][j] = std::min(distance[i][j], distance[i - 2][j - 2] + 1);
        }
    }
    return distance.back().back();
}
std::vector<char16> utf16(const char * text) {
    std::vector<char16> out;
    const auto * p = reinterpret_cast<const unsigned char *>(text);
    while (*p) {
        unsigned int c = *p++;
        if (c >= 0xc0 && c < 0xe0 && *p) { c = ((c & 31) << 6) | (*p++ & 63); }
        else if (c >= 0xe0 && c < 0xf0 && p[0] && p[1]) {
            c = ((c & 15) << 12) | ((p[0] & 63) << 6) | (p[1] & 63); p += 2;
        } else if (c >= 0x80) { return {}; }
        out.push_back(static_cast<char16>(c));
    }
    out.push_back(0);
    return out;
}
std::string utf8(const char16 * text) {
    std::string out;
    for (; *text; ++text) {
        unsigned int c = *text;
        if (c < 0x80) out += char(c);
        else if (c < 0x800) { out += char(0xc0 | c >> 6); out += char(0x80 | (c & 63)); }
        else { out += char(0xe0 | c >> 12); out += char(0x80 | ((c >> 6) & 63)); out += char(0x80 | (c & 63)); }
    }
    return out;
}
}
struct li_pinyin {
    MatrixSearch decoder;
    std::set<std::string> syllables, prefixes;
    std::vector<std::vector<Beam>> history;
    std::string previous_digits, output;
    std::string previous_locks;
    unsigned long long generation = 0;
    std::vector<Candidate> saved;
    float abbreviation_cost(const std::string & path, bool leave_last) {
        const uint16 * starts = nullptr;
        size_t count = decoder.get_spl_start(starts);
        uint16 ids[64] = {};
        if (!starts || count > 64 || !decoder.get_candidate_spelling_ids(0, ids, count)) return 0;
        float cost = 0;
        for (size_t i = 0; i < count - size_t(leave_last && count > 0); ++i) {
            auto typed = path.substr(starts[i], std::min(size_t(starts[i + 1]), path.size()) - starts[i]);
            typed.erase(std::remove(typed.begin(), typed.end(), '\''), typed.end());
            if (typed != lower(SpellingTrie::get_instance().get_spelling_str(ids[i])))
                cost += 4800.0f;
        }
        return cost;
    }
    bool evaluate(Beam & b) {
        decoder.reset_search();
        if (decoder.search(b.path.c_str(), b.path.size()) != b.path.size()) return false;
        // An unfinished last syllable is provisional. Earlier syllables that
        // were prematurely ended as initials must not beat fully spelled paths.
        b.score = decoder.best_sentence_score() + abbreviation_cost(b.path, true);
        return b.score < 1.0e19f;
    }
    std::vector<Beam> expand(const std::vector<Beam> & previous, char digit) {
        std::vector<Beam> next;
        std::set<std::string> seen;
        auto add = [&](std::string path, std::string syllable) {
            if (!seen.insert(path).second) return;
            Beam b{std::move(path), std::move(syllable), 0};
            if (evaluate(b)) next.push_back(std::move(b));
        };
        for (const auto & b : previous) {
            if (digit == '\'') {
                if (syllables.count(b.syllable)) add(b.path + "'", "");
                continue;
            }
            for (const char * c = key_letters[digit - '0']; *c; ++c) {
                std::string current = b.syllable + *c;
                if (prefixes.count(current)) add(b.path + *c, current);
                if (!b.syllable.empty() && syllables.count(b.syllable) && prefixes.count(std::string(1, *c)))
                    add(b.path + "'" + *c, std::string(1, *c));
            }
        }
        std::stable_sort(next.begin(), next.end(), [](const Beam & a, const Beam & b) { return a.score < b.score; });
        if (next.size() > beam_size) next.resize(beam_size);
        return next;
    }
    Path mapped(const std::string & path, const std::string & raw, bool nine) {
        Path p{path, {0}};
        size_t offset = 0;
        for (char c : path) {
            if (!nine) ++offset;
            else if (c == '\'') { if (offset < raw.size() && raw[offset] == '\'') ++offset; }
            else { while (offset < raw.size() && raw[offset] == '\'') ++offset; if (offset < raw.size()) ++offset; }
            p.ends.push_back(std::min(offset, raw.size()));
        }
        return p;
    }
    Path suffix(const Path & seed, const std::string & path, const std::string & raw, size_t offset, bool nine) {
        Path result = seed;
        for (size_t i = seed.text.size(); i < path.size(); ++i) {
            char c = path[i];
            if (!nine) ++offset;
            else if (c == '\'') { if (offset < raw.size() && raw[offset] == '\'') ++offset; }
            else { while (offset < raw.size() && raw[offset] == '\'') ++offset; if (offset < raw.size()) ++offset; }
            result.text += c; result.ends.push_back(std::min(offset, raw.size()));
        }
        return result;
    }
    // Full syllable segmentation is a cheap filter before invoking the decoder.
    bool segment(const std::string & text, std::vector<std::pair<size_t, size_t>> & parts) {
        std::set<std::pair<size_t, size_t>> failed;
        std::function<bool(size_t, size_t)> visit = [&](size_t pos, size_t count) {
            if (pos == text.size()) return true;
            if (text[pos] == '\'') return pos > 0 && visit(pos + 1, count);
            if (count == 9 || failed.count({pos, count})) return false;
            for (size_t n = std::min(size_t(6), text.size() - pos); n > 0; --n) {
                if (!syllables.count(text.substr(pos, n))) continue;
                parts.push_back({pos, pos + n});
                if (visit(pos + n, count + 1)) return true;
                parts.pop_back();
            }
            failed.insert({pos, count}); return false;
        };
        return visit(0, 0);
    }
    void collect(const std::string & path, const std::string & raw, bool nine, std::vector<Candidate> & output,
                 const Path * source = nullptr, const std::string & correction = "", float penalty = 0, bool preserve_tail = false,
                 bool sentence_variants = false) {
        decoder.reset_search();
        if (!decoder.search(path.c_str(), path.size())) return;
        collect_active(path, raw, nine, output, source, correction, penalty, preserve_tail, 0);
        if (sentence_variants) {
            size_t count = decoder.get_sentence_count();
            for (size_t rank = 1; rank < count; ++rank) {
                if (!decoder.activate_sentence(rank, false)) continue;
                collect_active(path, raw, nine, output, source, correction, penalty, preserve_tail, rank);
                decoder.activate_sentence(rank, false);
            }
        }
    }
    void collect_active(const std::string & path, const std::string & raw, bool nine, std::vector<Candidate> & output,
                 const Path * source, const std::string & correction, float penalty, bool preserve_tail, size_t sentence_rank) {
        const uint16 * starts = nullptr;
        size_t spellings = decoder.get_spl_start(starts);
        size_t count = sentence_rank ? 1 : std::min(decoder.get_candidate_num(), nine ? size_t(8) : size_t(96));
        for (size_t i = 0; i < count; ++i) {
            char16 text[64] = {};
            if (!decoder.get_candidate(i, text, 64)) continue;
            size_t chars = 0; while (text[chars]) ++chars;
            if (!chars || chars > spellings || !starts) continue;
            const Path ordinary = source ? Path{} : mapped(path, raw, nine);
            const Path & physical = source ? *source : ordinary;
            size_t end = std::min(size_t(starts[chars]), path.size());
            size_t consumed = physical.ends.at(end);
            if (!consumed || consumed > raw.size()) continue;
            if (!correction.empty() && consumed != raw.size()) continue;
            uint16 ids[64] = {};
            if (!decoder.get_candidate_spelling_ids(i, ids, chars)) continue;
            std::vector<Segment> parts;
            std::string hint;
            size_t last = 0;
            float abbreviations = 0;
            for (size_t j = 0; j < chars; ++j) {
                const char * spelling = SpellingTrie::get_instance().get_spelling_str(ids[j]);
                size_t boundary = physical.ends.at(std::min(size_t(starts[j + 1]), path.size()));
                if (!spelling || boundary <= last) { parts.clear(); break; }
                auto s = lower(spelling);
                if (nine) {
                    size_t begin = std::min(size_t(starts[j]), path.size());
                    auto typed = path.substr(begin, std::min(size_t(starts[j + 1]), path.size()) - begin);
                    typed.erase(std::remove(typed.begin(), typed.end(), '\''), typed.end());
                    if (typed != s) abbreviations += 4800.0f;
                }
                if (!correction.empty()) {
                    size_t begin = std::min(size_t(starts[j]), path.size());
                    auto typed = path.substr(begin, std::min(size_t(starts[j + 1]), path.size()) - begin);
                    typed.erase(std::remove(typed.begin(), typed.end(), '\''), typed.end());
                    if (typed != s) { parts.clear(); break; }
                }
                parts.push_back({s, boundary}); last = boundary;
                if (!hint.empty()) hint += " ";
                hint += s;
            }
            if (parts.empty()) continue;
            output.push_back({utf8(text), hint, consumed, decoder.get_candidate_score(i) + penalty + abbreviations,
                              path, sentence_rank ? 0x10000 + sentence_rank : i, parts, correction, preserve_tail, sentence_rank});
        }
    }
    bool exact_unit(const std::string & typed, const std::string & spelling, bool nine) {
        std::vector<Candidate> found;
        if (!nine) collect(typed, typed, false, found);
        else {
            std::vector<Beam> beams{Beam{"", "", 0}};
            for (char digit : typed) beams = expand(beams, digit);
            for (const auto & b : beams)
                if (b.path.find('\'') == std::string::npos) collect(b.path, typed, true, found);
        }
        return std::any_of(found.begin(), found.end(), [&](const Candidate & c) {
            return c.consumed == typed.size() && c.segments.size() == 1 && c.segments[0].spelling == spelling;
        });
    }
    bool locks(const std::string & specification, const std::string & raw, bool nine, bool corrections,
               Path & seed, size_t & offset, std::string & partial) {
        seed = {"", {0}}; offset = 0;
        size_t pos = 0, count = 0;
        while (pos < specification.size()) {
            size_t comma = specification.find(',', pos);
            std::string item = specification.substr(pos, comma == std::string::npos ? comma : comma - pos);
            size_t colon = item.find(':');
            if (colon == std::string::npos || ++count > 9) return false;
            std::string spelling = item.substr(0, colon), number = item.substr(colon + 1);
            bool unfinished = !number.empty() && number.back() == '*';
            if (unfinished) {
                number.pop_back();
                if (!nine || comma != std::string::npos || !prefixes.count(spelling)) return false;
                partial = spelling;
            }
            if ((!unfinished && !syllables.count(spelling)) || number.empty() || number.size() > 2 ||
                number.find_first_not_of("0123456789") != std::string::npos) return false;
            size_t end = std::stoul(number);
            if (end <= offset || end > raw.size()) return false;
            auto typed = raw.substr(offset, end - offset);
            typed.erase(std::remove(typed.begin(), typed.end(), '\''), typed.end());
            if (typed.empty() || typed.size() > 7) return false;
            if (!nine) typed = normalize_umlaut(typed);
            auto target = nine ? digits(spelling) : spelling;
            if (unfinished && typed != target) return false;
            bool prefix = !typed.empty() && target.compare(0, typed.size(), typed) == 0;
            if (!prefix && !(corrections && edit_distance(typed, target) <= 1) &&
                !exact_unit(typed, spelling, nine)) return false;
            if (!seed.text.empty()) { seed.text += "'"; seed.ends.push_back(offset); }
            for (char c : spelling) { seed.text += c; seed.ends.push_back(end); }
            offset = end;
            if (comma == std::string::npos) break;
            pos = comma + 1;
            if (pos == specification.size()) return false;
        }
        return true;
    }
    void fuzzy(const Path & base, size_t protected_size, const std::string & raw, bool nine,
               std::vector<Candidate> & result) {
        std::vector<std::pair<size_t, size_t>> parts;
        if (!segment(base.text, parts)) return;
        const std::pair<const char *, const char *> initials[] = {
            {"z", "zh"}, {"c", "ch"}, {"s", "sh"}, {"n", "l"}, {"f", "h"}};
        const std::pair<const char *, const char *> finals[] = {{"an", "ang"}, {"en", "eng"}, {"in", "ing"}};
        for (auto [begin, end] : parts) {
            if (begin < protected_size) continue;
            auto spelling = base.text.substr(begin, end - begin);
            std::set<std::string> alternatives;
            auto change = [&](const auto & pairs, bool initial) {
                for (auto pair : pairs) for (int direction = 0; direction < 2; ++direction) {
                    std::string from = direction ? pair.second : pair.first, to = direction ? pair.first : pair.second;
                    size_t at = initial ? 0 : spelling.size() >= from.size() ? spelling.size() - from.size() : spelling.size();
                    if (spelling.compare(at, from.size(), from) != 0) continue;
                    if (initial && from.size() == 1 && spelling.size() > 1 && spelling[1] == 'h') continue;
                    auto replacement = spelling.substr(0, at) + to + spelling.substr(at + from.size());
                    if (syllables.count(replacement)) alternatives.insert(replacement);
                }
            };
            change(initials, true); change(finals, false);
            for (const auto & replacement : alternatives) {
                Path next = base;
                next.text.replace(begin, end - begin, replacement);
                next.ends.erase(next.ends.begin() + begin + 1, next.ends.begin() + end + 1);
                next.ends.insert(next.ends.begin() + begin + 1, replacement.size(), base.ends[end]);
                collect(next.text, raw, nine, result, &next, "模糊", 128);
            }
        }
    }
    void typos(const Path & base, size_t protected_size, const std::string & raw,
               size_t exact_prefix, std::vector<Candidate> & result) {
        if (raw.size() < 3 || base.text.size() > 48) return;
        std::set<std::string> seen;
        size_t searched = 0, attempts = 0;
        auto add = [&](Path next, float penalty, bool tail = false) {
            if (searched >= 64 || attempts >= 256 || next.text == base.text || !seen.insert(next.text).second) return;
            std::vector<std::pair<size_t, size_t>> parts;
            if (!segment(next.text, parts)) return;
            ++attempts;
            size_t before = result.size();
            collect(next.text, raw, false, result, &next, "纠错", penalty, tail);
            if (result.size() > before) ++searched;
        };
        for (size_t i = protected_size; i + 1 < base.text.size(); ++i) {
            if (base.text[i] == '\'' || base.text[i + 1] == '\'') continue;
            auto next = base; std::swap(next.text[i], next.text[i + 1]); add(next, 96);
        }
        for (size_t i = protected_size; i < base.text.size(); ++i) {
            if (base.text[i] == '\'') continue;
            auto next = base; next.text.erase(i, 1); next.ends.erase(next.ends.begin() + i + 1);
            next.ends.back() = base.ends.back();
            bool tail = i + 1 == base.text.size() && exact_prefix + 1 == raw.size();
            add(next, i > 0 && base.text[i] == base.text[i - 1] ? 96 : 192, tail);
        }
        const std::string rows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        const float offsets[] = {0, 0.5f, 1.5f};
        for (size_t i = protected_size; i < base.text.size(); ++i) {
            if (base.text[i] == '\'') continue;
            for (int r = 0; r < 3; ++r) {
                auto at = rows[r].find(base.text[i]);
                if (at == std::string::npos) continue;
                float x = float(at) + offsets[r];
                for (int neighbor_row = std::max(0, r - 1); neighbor_row <= std::min(2, r + 1); ++neighbor_row)
                    for (size_t k = 0; k < rows[neighbor_row].size(); ++k) {
                        float dx = float(k) + offsets[neighbor_row] - x;
                        if (dx < -1.01f || dx > 1.01f || rows[neighbor_row][k] == base.text[i]) continue;
                        auto next = base; next.text[i] = rows[neighbor_row][k]; add(next, 128);
                    }
            }
        }
        for (size_t i = protected_size; i <= base.text.size(); ++i) for (char c = 'a'; c <= 'z'; ++c) {
            auto next = base; next.text.insert(i, 1, c);
            next.ends.insert(next.ends.begin() + i + 1, base.ends[i]); add(next, 192);
        }
    }
};
extern "C" li_pinyin * li_pinyin_create(const char * path) {
    return li_pinyin_create_with_user(path, "");
}
extern "C" li_pinyin * li_pinyin_create_with_user(const char * path, const char * user_path) {
    std::lock_guard<std::mutex> guard(decoder_mutex);
    try {
        auto e = std::make_unique<li_pinyin>();
        if (!e->decoder.init(path, user_path ? user_path : "")) return nullptr;
        LpiCache::get_instance().clear();
        e->decoder.set_max_lens(max_keys * 2, 32);
        auto & trie = SpellingTrie::get_instance();
        for (size_t id = kFullSplIdStart; id < kFullSplIdStart + trie.get_spelling_num(); ++id) {
            const char * spelling = trie.get_spelling_str(id);
            if (!spelling) continue;
            std::string s(spelling);
            std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return char(std::tolower(c)); });
            e->syllables.insert(s);
            for (size_t n = 1; n <= s.size(); ++n) e->prefixes.insert(s.substr(0, n));
        }
        return e.release();
    } catch (...) { return nullptr; }
}
static const char * search_internal(li_pinyin * e, const char * input, int nine,
                                    const char * lock_spec, bool corrections, bool advanced) {
    if (!e || !input) return "";
    std::lock_guard<std::mutex> guard(decoder_mutex);
    try {
        e->output.clear(); e->saved.clear(); ++e->generation;
        LpiCache::get_instance().clear();
        std::string raw(input);
        if (raw.empty()) return "";
        raw.resize(std::min(raw.size(), max_keys));
        for (char c : raw) if (c != '\'' && (nine ? c < '2' || c > '9' : c < 'a' || c > 'z')) return "";
        std::vector<Candidate> candidates;
        const std::string specification = lock_spec ? lock_spec : "";
        Path seed; size_t start = 0; std::string partial;
        if (!e->locks(specification, raw, nine, corrections, seed, start, partial)) return "";
        if (!seed.text.empty() && start < raw.size() && partial.empty()) {
            seed.text += "'"; seed.ends.push_back(start);
            while (start < raw.size() && raw[start] == '\'') ++start;
        }
        const size_t protected_size = seed.text.size();
        bool typed_full = true;
        if (!nine) {
            e->previous_digits.clear(); e->history.clear();
            // The upstream dictionary spells 略/虐 as lue/nue; accept the usual ü key.
            std::string normalized = normalize_umlaut(seed.text + raw.substr(start));
            Path base = e->suffix(seed, normalized, raw, start, false);
            e->collect(normalized, raw, false, candidates, &base, "", 0, false, true);
            std::vector<std::pair<size_t, size_t>> parts;
            typed_full = e->segment(normalized, parts);
            if (corrections) {
                size_t exact_prefix = 0;
                for (const auto & c : candidates) exact_prefix = std::max(exact_prefix, c.consumed);
                e->fuzzy(base, protected_size, raw, false, candidates);
                e->typos(base, protected_size, raw, exact_prefix, candidates);
                // A fully decodable prefix plus one unexplained trailing key is
                // deliberately retained. Corrections may be offered, but must
                // not displace that prefix and silently swallow the tail.
                if (exact_prefix + 1 == raw.size()) {
                    size_t prefix_end = 0;
                    for (size_t p = 0; p < base.ends.size(); ++p)
                        if (base.ends[p] == exact_prefix) prefix_end = p;
                    std::vector<std::pair<size_t, size_t>> prefix_parts;
                    if (e->segment(base.text.substr(0, prefix_end), prefix_parts))
                        for (auto & c : candidates) if (!c.correction.empty()) c.preserve_tail = true;
                }
            }
        } else {
            size_t common = 0;
            while (common < raw.size() && common < e->previous_digits.size() && raw[common] == e->previous_digits[common]) ++common;
            if (e->history.empty() || e->previous_locks != specification || common < start || common >= e->history.size()) {
                e->history.assign(start + 1, {});
                e->history.back().push_back(Beam{seed.text, partial, 0});
                common = start;
            }
            e->previous_locks = specification;
            e->history.resize(common + 1);
            for (size_t i = common; i < raw.size(); ++i) e->history.push_back(e->expand(e->history.back(), raw[i]));
            e->previous_digits = raw;
            size_t usable = e->history.size() - 1;
            while (usable > start && e->history[usable].empty()) --usable;
            // Retain the longest decodable prefix when the nine-syllable limit
            // or an impossible suffix prevents a full beam from surviving.
            size_t fuzzy_paths = 0;
            for (const auto & b : e->history[usable]) {
                auto base = e->suffix(seed, b.path, raw, start, true);
                e->collect(b.path, raw, true, candidates, &base, "", 0, false, true);
                if (corrections && usable == raw.size() && fuzzy_paths++ < 4)
                    e->fuzzy(base, protected_size, raw, true, candidates);
            }
        }
        bool corrected = std::any_of(candidates.begin(), candidates.end(), [](const Candidate & c) { return !c.correction.empty(); });
        auto tier = [&](const Candidate & c) {
            if (!c.correction.empty()) return c.preserve_tail ? 3 : 1;
            if (c.consumed != raw.size()) return 2;
            return !nine && raw.size() >= 4 && !typed_full && corrected ? 2 : 0;
        };
        std::stable_sort(candidates.begin(), candidates.end(), [&](const Candidate & a, const Candidate & b) {
            if (tier(a) != tier(b)) return tier(a) < tier(b);
            if (a.consumed != b.consumed) return a.consumed > b.consumed;
            return a.score < b.score;
        });
        Candidate best;
        bool sentence_group = !candidates.empty() && tier(candidates.front()) == 0;
        auto same_phonetics = [&](const Candidate & c) {
            if (c.spelling != best.spelling || c.segments.size() != best.segments.size()) return false;
            for (size_t i = 0; i < c.segments.size(); ++i)
                if (c.segments[i].end != best.segments[i].end) return false;
            return true;
        };
        if (sentence_group) {
            best = candidates.front();
            auto text = utf16(best.text.c_str());
            for (auto & c : candidates) {
                if (tier(c) != 0 || !same_phonetics(c)) continue;
                auto candidate_text = utf16(c.text.c_str());
                while (c.prefix_affinity < text.size() - 1 && c.prefix_affinity < candidate_text.size() - 1 &&
                       text[c.prefix_affinity] == candidate_text[c.prefix_affinity]) ++c.prefix_affinity;
            }
            // Once a fully spelled pronunciation is most likely, expose its
            // character alternatives together. Prefer preserving its confident
            // prefix before switching to unrelated pronunciations.
            std::stable_sort(candidates.begin(), candidates.end(), [&](const Candidate & a, const Candidate & b) {
                if (tier(a) != tier(b)) return tier(a) < tier(b);
                if (tier(a) == 0) {
                    bool ag = same_phonetics(a), bg = same_phonetics(b);
                    if (ag != bg) return ag;
                    if (ag && a.prefix_affinity != b.prefix_affinity) return a.prefix_affinity > b.prefix_affinity;
                }
                if (a.consumed != b.consumed) return a.consumed > b.consumed;
                return a.score < b.score;
            });
        }
        std::set<std::string> seen;
        size_t count = 0, full_count = 0, partial_count = 0, correction_count = 0;
        std::vector<Candidate> deferred;
        auto emit = [&](const Candidate & c) {
            if (count == 96) return;
            // Keep prefix choices available even when many T9 full phrases match.
            bool full = c.consumed == raw.size();
            if (nine && ((full && full_count >= 64) || (!full && partial_count >= 32))) return;
            if (!c.correction.empty() && correction_count >= 16) return;
            if (!seen.insert(c.text + "\t" + std::to_string(c.consumed)).second) return;
            if (!c.correction.empty()) ++correction_count;
            if (full) ++full_count; else ++partial_count;
            e->saved.push_back(c);
            e->output += c.text + "\t" + c.spelling + "\t" + std::to_string(c.consumed) +
                "\t" + std::to_string(e->generation) + "\t" + std::to_string(c.index) + "\t" + c.path;
            if (advanced) {
                e->output += "\t";
                for (size_t j = 0; j < c.segments.size(); ++j) {
                    if (j) e->output += ",";
                    e->output += c.segments[j].spelling + ":" + std::to_string(c.segments[j].end);
                }
                e->output += "\t" + (c.correction.empty() ? std::string("-") : c.correction);
            }
            e->output += "\n"; ++count;
        };
        if (sentence_group) {
            size_t front = 0;
            for (const auto & c : candidates) {
                if (tier(c) != 0 || !same_phonetics(c)) continue;
                size_t before = count; emit(c);
                if (count != before && ++front == 3) break;
            }
            std::set<size_t> prefix_ends;
            auto text = utf16(best.text.c_str());
            for (const auto & c : candidates) {
                if (!c.correction.empty() || c.consumed >= raw.size() || c.segments.size() >= best.segments.size()) continue;
                bool prefix = !c.segments.empty();
                for (size_t i = 0; i < c.segments.size(); ++i)
                    if (c.segments[i].spelling != best.segments[i].spelling ||
                        c.segments[i].end != best.segments[i].end) prefix = false;
                auto part = utf16(c.text.c_str());
                if (part.size() >= text.size() || !std::equal(part.begin(), part.end() - 1, text.begin())) prefix = false;
                if (!prefix || !prefix_ends.insert(c.consumed).second) continue;
                emit(c);
                if (prefix_ends.size() == 2) break;
            }
        }
        for (const auto & c : candidates) {
            if (!nine && advanced && corrected && tier(c) == 0 && full_count >= 8) { deferred.push_back(c); continue; }
            emit(c);
        }
        for (const auto & c : deferred) emit(c);
        if (advanced && nine) {
            // Independent spelling choices survive pruning of sentence candidates.
            size_t begin = partial.empty() ? start : start - digits(partial).size();
            while (begin < raw.size() && raw[begin] == '\'') ++begin;
            std::vector<Segment> options;
            std::set<std::string> option_seen;
            auto add = [&](const std::string & spelling, size_t end) {
                if (!partial.empty() && spelling.compare(0, partial.size(), partial) != 0) return;
                if (option_seen.insert(spelling + ":" + std::to_string(end)).second)
                    options.push_back({spelling, end});
            };
            for (const auto & c : e->saved) for (size_t i = 0; i < c.segments.size(); ++i) {
                size_t previous = i ? c.segments[i - 1].end : 0;
                while (previous < raw.size() && raw[previous] == '\'') ++previous;
                const auto & s = c.segments[i];
                if (previous == begin && digits(s.spelling) == raw.substr(begin, s.end - begin)) add(s.spelling, s.end);
            }
            for (const auto & s : e->syllables) {
                auto code = digits(s);
                if (begin + code.size() <= raw.size() && raw.compare(begin, code.size(), code) == 0)
                    add(s, begin + code.size());
            }
            e->output += "@syllables\t";
            bool separator = false;
            for (const auto & s : options) {
                if (separator) e->output += ",";
                e->output += s.spelling + ":" + std::to_string(s.end); separator = true;
            }
            if (begin < raw.size() && raw[begin] >= '2' && raw[begin] <= '9')
                for (char c : std::string(key_letters[raw[begin] - '0'])) {
                    std::string letter(1, c);
                    if (!e->prefixes.count(letter) || (!partial.empty() && letter != partial)) continue;
                    if (separator) e->output += ",";
                    e->output += letter + ":" + std::to_string(begin + 1) + "*"; separator = true;
                }
            e->output += "\n";
        }
        return e->output.c_str();
    } catch (...) { e->output.clear(); return ""; }
}
extern "C" const char * li_pinyin_search(li_pinyin * e, const char * input, int nine) {
    return search_internal(e, input, nine, "", false, false);
}
extern "C" const char * li_pinyin_search_advanced(li_pinyin * e, const char * input, int nine, const char * locks, int corrections) {
    return search_internal(e, input, nine, locks, corrections != 0, true);
}
extern "C" const char * li_pinyin_select(li_pinyin * e, unsigned long long token, int index, const char * path, int learn) {
    if (!e) return "";
    std::lock_guard<std::mutex> guard(decoder_mutex);
    e->output.clear();
    if (token != e->generation || index < 0 || !path) return "";
    auto found = std::find_if(e->saved.begin(), e->saved.end(), [&](const Candidate & c) {
        return c.index == size_t(index) && c.path == path;
    });
    if (found == e->saved.end()) return "";
    const auto c = *found;
    LpiCache::get_instance().clear();
    e->decoder.reset_search();
    e->decoder.search(c.path.c_str(), c.path.size());
    size_t selected_index = c.index;
    if (c.sentence_rank) {
        if (!e->decoder.activate_sentence(c.sentence_rank)) return "";
        selected_index = 0;
    }
    if (selected_index >= e->decoder.get_candidate_num()) return "";
    char16 text[64] = {};
    if (!e->decoder.get_candidate(selected_index, text, 64) || utf8(text) != c.text) return "";
    // Capture learning before choose mutates the candidate list; avoid upstream double updates.
    if (learn) e->decoder.learn_candidate(selected_index);
    e->decoder.choose(selected_index, false);
    if (learn) { e->decoder.flush_cache(); LpiCache::get_instance().clear(); }
    e->previous_digits.clear(); e->history.clear(); e->saved.clear(); ++e->generation;
    e->output = c.text;
    return e->output.c_str();
}
extern "C" const char * li_pinyin_predict(li_pinyin * e, const char * history) {
    if (!e || !history) return "";
    std::lock_guard<std::mutex> guard(decoder_mutex);
    e->output.clear(); e->saved.clear(); ++e->generation;
    auto fixed = utf16(history);
    if (fixed.size() <= 1) return "";
    if (fixed.size() > kMaxPredictSize + 1) fixed.erase(fixed.begin(), fixed.end() - (kMaxPredictSize + 1));
    char16 predictions[64][kMaxPredictSize + 1] = {};
    size_t n = e->decoder.get_predicts(fixed.data(), predictions, 64);
    for (size_t i = 0; i < n; ++i) e->output += utf8(predictions[i]) + "\n";
    return e->output.c_str();
}
extern "C" void li_pinyin_destroy(li_pinyin * e) {
    std::lock_guard<std::mutex> guard(decoder_mutex); delete e;
}
