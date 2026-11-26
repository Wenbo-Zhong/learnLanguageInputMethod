#include "learnime_pinyin.h"
#include <libime/pinyin/pinyinime.h>
#include <libime/pinyin/pinyincontext.h>
#include <libime/pinyin/pinyindictionary.h>
#include <libime/pinyin/pinyindecoder.h>
#include <libime/pinyin/pinyinmatchstate.h>
#include <libime/pinyin/ninekeyencoder.h>
#include <libime/pinyin/pinyindata.h>
#include <libime/core/userlanguagemodel.h>
#include <libime/core/prediction.h>
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <limits>
#include <memory>
#include <set>
#include <sstream>
#include <mutex>
#include <unordered_map>
#include <cmath>

using namespace libime;
namespace {
constexpr size_t maxKeys = 40;
const std::string groups[] = {"abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
std::mutex engineMutex;
std::string digits(std::string_view s) {
    std::string out;
    for (char c : s) for (size_t i = 0; i < 8; ++i)
        if (groups[i].find(c) != std::string::npos) { out += char('2' + i); break; }
    return out;
}
std::string normalize(std::string s) {
    for (size_t i = 0; i + 2 < s.size(); ++i)
        if ((s[i] == 'l' || s[i] == 'n') && s[i + 1] == 'v' && s[i + 2] == 'e') s[i + 1] = 'u';
    return s;
}
size_t distance(const std::string &a, const std::string &b) {
    std::vector<std::vector<size_t>> d(a.size() + 1, std::vector<size_t>(b.size() + 1));
    for (size_t i = 0; i <= a.size(); ++i) d[i][0] = i;
    for (size_t j = 0; j <= b.size(); ++j) d[0][j] = j;
    for (size_t i = 1; i <= a.size(); ++i) for (size_t j = 1; j <= b.size(); ++j) {
        d[i][j] = std::min({d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + size_t(a[i - 1] != b[j - 1])});
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
            d[i][j] = std::min(d[i][j], d[i - 2][j - 2] + 1);
    }
    return d.back().back();
}
struct Lock { std::string spelling; size_t begin, end, from, to; bool partial; };
struct Segment { std::string spelling; size_t end; };
struct Candidate {
    SentenceResult sentence;
    std::string text, spelling, path, correction;
    size_t consumed = 0;
    std::vector<Segment> segments;
    bool decodedSentence = false;
    float inputCost = 0;
};
struct Query {
    std::string text;
    std::vector<size_t> rawEnds{0};
    std::vector<Lock> locks;
    size_t optionBegin = 0;
    std::string partial;
};
PinyinFuzzyFlags flags(bool corrections, bool nine, int configured) {
    auto f = PinyinFuzzyFlags(PinyinFuzzyFlag::VE_UE);
    if (corrections) {
        f |= PinyinFuzzyFlags(PinyinFuzzyFlag::CommonTypo);
        if (!nine) f |= PinyinFuzzyFlags(PinyinFuzzyFlag::AdvancedTypo) | PinyinFuzzyFlag::Correction;
    }
    unsigned int groups = configured < 0 ? (corrections ? 255 : 0) : configured;
    const PinyinFuzzyFlag values[] = {PinyinFuzzyFlag::AN_ANG, PinyinFuzzyFlag::EN_ENG,
        PinyinFuzzyFlag::IN_ING, PinyinFuzzyFlag::C_CH, PinyinFuzzyFlag::S_SH,
        PinyinFuzzyFlag::Z_ZH, PinyinFuzzyFlag::L_N, PinyinFuzzyFlag::F_H};
    for (int i = 0; i < 8; ++i) if (groups & (1u << i)) f |= values[i];
    return f;
}
std::shared_ptr<PinyinCorrectionProfile> qwertyProfile() {
    // Use the actual staggered layout, including keys on adjacent rows.
    const std::string rows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    const float offsets[] = {0, 0.5f, 1.5f};
    std::unordered_map<char, std::vector<char>> mapping;
    for (int row = 0; row < 3; ++row) for (size_t i = 0; i < rows[row].size(); ++i) {
        for (int other = std::max(0, row - 1); other <= std::min(2, row + 1); ++other)
            for (size_t j = 0; j < rows[other].size(); ++j)
                if (rows[row][i] != rows[other][j] &&
                    std::abs(float(i) + offsets[row] - float(j) - offsets[other]) <= 1.01f)
                    mapping[rows[row][i]].push_back(rows[other][j]);
    }
    return std::make_shared<PinyinCorrectionProfile>(mapping);
}
bool fullySpelled(std::string_view text) {
    std::vector<bool> reached(text.size() + 1);
    reached[0] = true;
    for (size_t i = 0; i < text.size(); ++i) if (reached[i]) {
        if (text[i] == '\'') { reached[i + 1] = true; continue; }
        for (const auto &entry : getPinyinMapV2())
            if (entry.flags() == PinyinFuzzyFlag::None && text.substr(i).starts_with(entry.pinyinView()))
                reached[i + entry.pinyinView().size()] = true;
    }
    return reached.back();
}
bool prepare(Query &q, const std::string &raw, bool nine, const char *spec, bool corrections) {
    size_t offset = 0;
    std::string specification = spec ? spec : "";
    std::istringstream items(specification);
    std::string item;
    if (!specification.empty() && specification.back() == ',') return false;
    while (std::getline(items, item, ',')) {
        size_t colon = item.find(':');
        if (colon == std::string::npos || q.locks.size() >= 40) return false;
        auto spelling = normalize(item.substr(0, colon));
        auto number = item.substr(colon + 1);
        bool partial = !number.empty() && number.back() == '*';
        if (partial) number.pop_back();
        if (number.empty() || number.size() > 2 || number.find_first_not_of("0123456789") != std::string::npos) return false;
        size_t end = std::stoul(number);
        if (end <= offset || end > raw.size() || spelling.empty()) return false;
        if (partial && (!nine || items.peek() != EOF)) return false;
        bool valid = false;
        for (const auto &entry : getPinyinMapV2()) {
            auto canonical = normalize(std::string(entry.pinyinView()));
            if (PinyinFuzzyFlags(PinyinFuzzyFlag::VE_UE).test(entry.flags()) &&
                (partial ? canonical.starts_with(spelling) : canonical == spelling)) { valid = true; break; }
        }
        if (!valid) return false;
        auto typed = raw.substr(offset, end - offset);
        typed.erase(std::remove(typed.begin(), typed.end(), '\''), typed.end());
        typed = normalize(typed);
        auto target = nine ? digits(spelling) : spelling;
        bool matches = !typed.empty() && target.starts_with(typed);
        if (partial ? typed != target : (!matches && !(corrections && distance(typed, target) <= 1))) return false;
        if (!q.text.empty()) { q.text += '\''; q.rawEnds.push_back(offset); }
        size_t from = q.text.size();
        for (char c : spelling) { q.text += c; q.rawEnds.push_back(end); }
        q.locks.push_back({spelling, offset, end, from, q.text.size(), partial});
        q.optionBegin = partial ? offset : end;
        q.partial = partial ? spelling : "";
        offset = end;
    }
    if (!q.text.empty() && !q.locks.back().partial && offset < raw.size() && raw[offset] != '\'') {
        q.text += '\''; q.rawEnds.push_back(offset);
    }
    for (size_t i = offset; i < raw.size(); ++i) { q.text += raw[i]; q.rawEnds.push_back(i + 1); }
    q.text = normalize(q.text);
    return true;
}
bool allowed(const Query &q, size_t from, size_t to) {
    for (const auto &lock : q.locks) {
        if (lock.partial) {
            if (from > lock.from && from < lock.to) return false;
            if (from == lock.from && to < lock.to) return false;
        } else if ((from < lock.to && to > lock.from) && (from != lock.from || to != lock.to)) return false;
    }
    return true;
}
// Rebuild after restricting edges so that every reachable node still reaches EOS.
std::unique_ptr<SegmentGraph> graphFor(const Query &q, size_t length, bool nine, PinyinFuzzyFlags f,
                                     const PinyinCorrectionProfile *profile) {
    std::string text = q.text.substr(0, length);
    std::unique_ptr<SegmentGraph> parsed;
    if (nine) parsed = std::make_unique<NineKeyGraph>(NineKeyEncoder::parse(text, f));
    else parsed = std::make_unique<SegmentGraph>(PinyinEncoder::parseUserPinyin(text, profile, f));
    std::vector<std::vector<size_t>> edges(length + 1);
    for (size_t i = 0; i < length; ++i)
        for (const auto &node : parsed->nodes(i)) for (const auto &next : node.nexts())
            if (allowed(q, i, next.index())) edges[i].push_back(next.index());
    if (!nine) {
        // The upstream segmenter favors initial abbreviations. Add complete
        // corrected syllables from its own correction map before decoding.
        for (size_t from = 0; from < length; ++from) {
            if (text[from] == '\'') continue;
            for (size_t to = from + 1; to <= length && to - from <= 7; ++to) {
                if (text[to - 1] == '\'') break;
                if (!allowed(q, from, to)) continue;
                auto matches = PinyinEncoder::stringToSyllablesWithFuzzyFlags(
                    std::string_view(text).substr(from, to - from), profile, f);
                bool complete = false;
                for (const auto &[initial, finals] : matches) for (const auto &[final, fuzzy] : finals)
                    if (initial != PinyinInitial::Invalid && final != PinyinFinal::Invalid) complete = true;
                if (complete && std::find(edges[from].begin(), edges[from].end(), to) == edges[from].end())
                    edges[from].push_back(to);
            }
        }
    }
    std::vector<bool> finish(length + 1), reachable(length + 1);
    finish[length] = true; reachable[0] = true;
    for (size_t i = length; i-- > 0;) for (auto end : edges[i]) if (finish[end]) finish[i] = true;
    if (!finish[0]) return nullptr;
    std::unique_ptr<SegmentGraph> result;
    if (nine) result = std::make_unique<NineKeyGraph>(text);
    else result = std::make_unique<SegmentGraph>(text);
    for (size_t i = 0; i < length; ++i) if (reachable[i]) for (auto end : edges[i]) if (finish[end]) {
        result->addNext(i, end); reachable[end] = true;
    }
    return result;
}
}
struct li_pinyin {
    std::unique_ptr<PinyinIME> ime;
    std::unique_ptr<PinyinContext> context;
    std::unique_ptr<PinyinMatchState> match;
    std::unique_ptr<SegmentGraph> graph;
    Lattice lattice;
    Prediction prediction;
    Query query;
    std::string userPath, output, learningError;
    bool learningPending = false;
    unsigned long long generation = 0;
    int fuzzyGroups = -1;
    std::vector<Candidate> saved;
    void clear() {
        saved.clear(); lattice.clear(); match->clear(); graph.reset(); output.clear(); ++generation;
    }
    void save() {
        if (userPath.empty()) return;
        learningPending = true;
        auto tmp = userPath + ".tmp";
        {
            std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
            if (!out) throw std::runtime_error("Cannot write user data");
            // Each upstream compressed reader buffers beyond its frame. Keep
            // the model and dictionary in independently bounded streams.
            std::ostringstream history(std::ios::binary), dictionary(std::ios::binary);
            ime->model()->save(history);
            ime->dict()->save(PinyinDictionary::UserDict, dictionary, PinyinDictFormat::Binary);
            auto h = history.str(), d = dictionary.str();
            out << "LIUSER1\n" << h.size() << '\n' << d.size() << '\n';
            out.write(h.data(), h.size()); out.write(d.data(), d.size());
            out.flush();
            if (!out) throw std::runtime_error("Cannot flush user data");
        }
        std::filesystem::rename(tmp, userPath);
        learningPending = false;
        learningError.clear();
    }
    bool candidate(const SentenceResult &sentence, Candidate &c) {
        c.sentence = sentence; c.text = sentence.toString();
        if (c.text.empty()) return false;
        bool corrected = false;
        for (auto *node : sentence.sentence()) {
            const auto &pn = node->as<PinyinLatticeNode>();
            const auto &encoded = pn.encodedPinyin();
            if (encoded.empty() || encoded.size() % 2) {
                // Separator nodes carry no word or phonetic code.
                if (!node->word().empty()) return false;
                continue;
            }
            size_t syl = 0;
            for (size_t i = 1; i < node->path().size(); ++i) {
                size_t from = node->path()[i - 1]->index(), to = node->path()[i]->index();
                if (graph->data()[from] == '\'') continue;
                if (syl * 2 + 2 > encoded.size()) return false;
                std::string spelling = normalize(PinyinEncoder::decodeFullPinyin(std::string_view(encoded).substr(syl++ * 2, 2)));
                if (!c.spelling.empty()) { c.spelling += ' '; c.path += '\''; }
                c.spelling += spelling; c.path += spelling;
                c.segments.push_back({spelling, query.rawEnds.at(to)});
                c.consumed = query.rawEnds.at(to);
                for (const auto &lock : query.locks) if (from == lock.from &&
                    (lock.partial ? !spelling.starts_with(lock.spelling) : spelling != lock.spelling)) return false;
                auto typed = graph->data().substr(from, to - from);
                bool numeric = typed.find_first_of("23456789") != std::string::npos;
                if (numeric) {
                    auto code = digits(spelling);
                    bool matches = typed.size() <= code.size();
                    for (size_t k = 0; matches && k < typed.size(); ++k)
                        if (typed[k] >= '2' && typed[k] <= '9') matches = typed[k] == code[k];
                    if (!matches) corrected = true;
                }
                if (!numeric && spelling != typed && !spelling.starts_with(typed) && normalize(spelling) != normalize(typed))
                    corrected = true;
                if (!numeric) {
                    if (to < graph->size() && spelling.starts_with(typed) && spelling != typed)
                        c.inputCost += 5.0f * float(spelling.size() - typed.size());
                    else if (spelling != typed && !spelling.starts_with(typed))
                        c.inputCost += 2.0f * float(distance(typed, spelling));
                }
            }
            if (syl * 2 != encoded.size()) return false;
            corrected |= pn.isCorrection();
        }
        c.correction = corrected ? "纠错" : "-";
        return c.consumed > 0 && !c.segments.empty();
    }
};
extern "C" li_pinyin *li_pinyin_create_with_user(const char *path, const char *user) {
    std::lock_guard guard(engineMutex);
    try {
        if (!path) return nullptr;
        auto e = std::make_unique<li_pinyin>();
        auto dict = std::make_unique<PinyinDictionary>();
        dict->load(PinyinDictionary::SystemDict, path, PinyinDictFormat::Binary);
        auto lmPath = (std::filesystem::path(path).parent_path() / "sc.lm").string();
        auto model = std::make_unique<UserLanguageModel>(lmPath.c_str());
        model->setCodeExtractor([](const WordNode *node) {
            const auto *pn = dynamic_cast<const PinyinLatticeNode *>(node);
            return pn ? pn->encodedPinyin() : std::string();
        });
        e->userPath = user ? user : "";
        if (!e->userPath.empty()) {
            std::ifstream in(e->userPath, std::ios::binary);
            if (in.peek() != EOF) {
                std::string magic; std::getline(in, magic);
                if (magic != "LIUSER1") throw std::runtime_error("Invalid user data version");
                size_t hSize = 0, dSize = 0;
                in >> hSize >> dSize;
                if (in.get() != '\n' || hSize > 64 * 1024 * 1024 || dSize > 64 * 1024 * 1024)
                    throw std::runtime_error("Invalid user data size");
                std::string h(hSize, '\0'), d(dSize, '\0');
                in.read(h.data(), h.size()); in.read(d.data(), d.size());
                if (!in) throw std::runtime_error("Incomplete user data");
                std::istringstream history(h, std::ios::binary), dictionary(d, std::ios::binary);
                model->load(history);
                dict->load(PinyinDictionary::UserDict, dictionary, PinyinDictFormat::Binary);
            }
        }
        e->ime = std::make_unique<PinyinIME>(std::move(dict), std::move(model));
        e->ime->setPartialLongWordLimit(0);
        e->ime->setCorrectionProfile(qwertyProfile());
        e->context = std::make_unique<PinyinContext>(e->ime.get());
        e->match = std::make_unique<PinyinMatchState>(e->context.get());
        e->prediction.setUserLanguageModel(e->ime->model());
        return e.release();
    } catch (...) { return nullptr; }
}
extern "C" li_pinyin *li_pinyin_create(const char *path) { return li_pinyin_create_with_user(path, ""); }
static const char *search(li_pinyin *e, const char *input, bool nine, const char *locks, bool corrections, bool advanced) {
    if (!e || !input) return "";
    std::lock_guard guard(engineMutex);
    try {
        e->clear();
        std::string raw(input);
        raw.resize(std::min(raw.size(), maxKeys));
        if (raw.empty()) return "";
        for (char c : raw) if (c != '\'' && (nine ? c < '2' || c > '9' : c < 'a' || c > 'z')) return "";
        e->query = Query();
        if (!prepare(e->query, raw, nine, locks, corrections)) return "";
        auto f = flags(corrections, nine, e->fuzzyGroups);
        e->ime->setFuzzyFlags(f);
        size_t minLength = e->query.locks.empty() ? 1 : e->query.locks.back().to;
        size_t searchLength = e->query.text.size();
        // Keep an unexplained trailing key editable after a fully spelled
        // phrase. A neighboring-key correction must not silently consume it.
        if (!nine && searchLength > minLength && !fullySpelled(e->query.text) &&
            fullySpelled(std::string_view(e->query.text).substr(0, searchLength - 1)))
            --searchLength;
        for (size_t length = searchLength; length >= minLength; --length) {
            e->graph = graphFor(e->query, length, nine, f, e->ime->correctionProfile().get());
            if (!e->graph) continue;
            e->ime->decoder()->decode(e->lattice, *e->graph, 96, e->ime->model()->beginState(),
                std::numeric_limits<float>::max(), -std::numeric_limits<float>::max(), 96, 192, e->match.get());
            if (e->lattice.sentenceSize()) break;
            e->lattice.clear(); e->match->clear(); e->graph.reset();
        }
        if (!e->graph) return "";
        std::vector<Candidate> full, prefix;
        auto add = [&](const SentenceResult &sentence, bool decodedSentence = false) {
            Candidate c;
            if (!e->candidate(sentence, c)) return;
            c.decodedSentence = decodedSentence;
            (c.consumed == raw.size() ? full : prefix).push_back(std::move(c));
        };
        for (size_t i = 0; i < e->lattice.sentenceSize(); ++i) add(e->lattice.sentence(i), true);
        for (size_t i = e->graph->size(); i > 0; --i)
            for (const auto &gn : e->graph->nodes(i)) for (const auto &node : e->lattice.nodes(&gn)) {
                if (node.from() == &e->graph->start() || i < e->graph->size()) add(node.toSentenceResult());
            }
        auto order = [](const Candidate &a, const Candidate &b) {
            if (a.consumed != b.consumed) return a.consumed > b.consumed;
            // Match upstream: N-best sentences already include EOS scores.
            // Dictionary node scores must not displace those sentences.
            if (a.decodedSentence != b.decodedSentence) return a.decodedSentence;
            return a.sentence.score() - a.inputCost > b.sentence.score() - b.inputCost;
        };
        std::stable_sort(full.begin(), full.end(), order);
        std::stable_sort(prefix.begin(), prefix.end(), order);
        std::set<std::string> seen;
        auto emit = [&](const std::vector<Candidate> &rows, size_t limit) {
            size_t count = 0;
            for (const auto &c : rows) {
                if (count == limit || e->saved.size() == 96) break;
                if (!seen.insert(c.text + ":" + std::to_string(c.consumed)).second) continue;
                size_t index = e->saved.size(); e->saved.push_back(c); ++count;
                e->output += c.text + "\t" + c.spelling + "\t" + std::to_string(c.consumed) + "\t" +
                    std::to_string(e->generation) + "\t" + std::to_string(index) + "\t" + c.path;
                if (advanced) {
                    e->output += '\t';
                    for (size_t i = 0; i < c.segments.size(); ++i) {
                        if (i) e->output += ',';
                        e->output += c.segments[i].spelling + ":" + std::to_string(c.segments[i].end);
                    }
                    e->output += "\t" + c.correction;
                }
                e->output += '\n';
            }
        };
        emit(full, prefix.empty() ? 96 : 64); emit(prefix, 96 - e->saved.size());
        if (advanced && nine) {
            size_t begin = e->query.optionBegin;
            while (begin < raw.size() && raw[begin] == '\'') ++begin;
            std::vector<std::string> options;
            std::set<std::string> seenOptions;
            auto option = [&](const std::string &spelling, size_t end) {
                if (!spelling.starts_with(e->query.partial)) return;
                auto s = spelling + ":" + std::to_string(end);
                if (seenOptions.insert(s).second) options.push_back(s);
            };
            for (const auto &c : e->saved) {
                size_t previous = 0;
                for (const auto &s : c.segments) {
                    while (previous < raw.size() && raw[previous] == '\'') ++previous;
                    if (previous == begin && digits(s.spelling) == raw.substr(begin, s.end - begin)) option(s.spelling, s.end);
                    previous = s.end;
                }
            }
            for (const auto &entry : getPinyinMapV2()) if (entry.flags() == PinyinFuzzyFlag::None) {
                std::string spelling(entry.pinyinView()), code = digits(spelling);
                if (raw.substr(begin, code.size()) == code) option(spelling, begin + code.size());
            }
            if (begin < raw.size() && raw[begin] >= '2' && raw[begin] <= '9')
                for (char c : groups[raw[begin] - '2']) {
                    std::string letter(1, c);
                    if (!e->query.partial.empty() && letter != e->query.partial) continue;
                    bool valid = false;
                    for (const auto &entry : getPinyinMapV2()) if (entry.pinyinView().starts_with(letter)) { valid = true; break; }
                    if (valid) options.push_back(letter + ":" + std::to_string(begin + 1) + "*");
                }
            e->output += "@syllables\t";
            for (size_t i = 0; i < options.size(); ++i) { if (i) e->output += ','; e->output += options[i]; }
            e->output += '\n';
        }
        return e->output.c_str();
    } catch (...) { e->clear(); return ""; }
}
extern "C" const char *li_pinyin_search(li_pinyin *e, const char *input, int nine) {
    return search(e, input, nine, "", false, false);
}
extern "C" const char *li_pinyin_search_advanced(li_pinyin *e, const char *input, int nine, const char *locks, int corrections) {
    return search(e, input, nine, locks, corrections, true);
}
extern "C" const char *li_pinyin_select(li_pinyin *e, unsigned long long token, int index, const char *path, int learn) {
    if (!e) return "";
    std::lock_guard guard(engineMutex);
    e->output.clear();
    if (token != e->generation || index < 0 || size_t(index) >= e->saved.size() || !path) return "";
    try {
        auto c = e->saved[index];
        Candidate validated;
        if (c.path != path || !e->candidate(c.sentence, validated) ||
            validated.text != c.text || validated.consumed != c.consumed) return "";
        if (learn) {
            try {
                // Keep in-memory learning on failure; the next successful save
                // persists it together with subsequent updates.
                e->learningPending = !e->userPath.empty();
                std::vector<HistoryBigram::WordWithCode> words;
                std::string code;
                for (auto *node : c.sentence.sentence()) {
                    auto encoded = node->as<PinyinLatticeNode>().encodedPinyin();
                    if (!encoded.empty()) { words.emplace_back(node->word(), encoded); code += encoded; }
                }
                if (words.size() > 1 && code.size() <= 8) {
                    auto spelling = PinyinEncoder::decodeFullPinyin(code);
                    e->ime->dict()->addWord(PinyinDictionary::UserDict, spelling, c.text);
                    words = {{c.text, code}};
                }
                e->ime->model()->history().addWithCode(words);
                e->save();
            } catch (const std::exception &error) {
                e->learningError = error.what();
            } catch (...) {
                e->learningError = "Unknown learning error";
            }
        }
        e->clear(); e->output = c.text;
        return e->output.c_str();
    } catch (...) { e->clear(); return ""; }
}
extern "C" const char *li_pinyin_learning_error(li_pinyin *e) {
    if (!e) return "";
    std::lock_guard guard(engineMutex);
    return e->learningPending ? e->learningError.c_str() : "";
}
extern "C" const char *li_pinyin_predict(li_pinyin *e, const char *history) {
    if (!e || !history) return "";
    std::lock_guard guard(engineMutex);
    try {
        e->clear();
        if (!*history) return "";
        for (const auto &word : e->prediction.predict(std::vector<std::string>{history}, 64))
            e->output += word + "\n";
        return e->output.c_str();
    } catch (...) { e->clear(); return ""; }
}
extern "C" void li_pinyin_destroy(li_pinyin *e) { std::lock_guard guard(engineMutex); delete e; }
extern "C" void li_pinyin_configure(li_pinyin *e, unsigned int groups) {
    if (!e) return;
    std::lock_guard guard(engineMutex);
    e->clear(); e->fuzzyGroups = groups & 255;
}
extern "C" const char *li_pinyin_user_words(li_pinyin *e) {
    if (!e) return "";
    std::lock_guard guard(engineMutex);
    try {
        e->clear();
        std::ostringstream out;
        e->ime->dict()->save(PinyinDictionary::UserDict, out, PinyinDictFormat::Text);
        e->output = out.str(); return e->output.c_str();
    } catch (...) { return ""; }
}
extern "C" int li_pinyin_user_word(li_pinyin *e, const char *spelling, const char *word, int remove) {
    if (!e || !spelling || !word) return 0;
    std::lock_guard guard(engineMutex);
    try {
        std::string p(spelling), w(word);
        if (p.empty() || p.size() > 240 || w.empty() || w.size() > 512 ||
            w.find_first_of("\t\r\n") != std::string::npos) return 0;
        auto encoded = PinyinEncoder::encodeFullPinyin(p);
        if (encoded.empty()) return 0;
        size_t characters = std::count_if(w.begin(), w.end(), [](unsigned char c) { return (c & 0xc0) != 0x80; });
        if (!remove && encoded.size() != characters * 2) return 0;
        e->clear();
        if (remove) {
            e->ime->dict()->removeWord(PinyinDictionary::UserDict, p, w);
            e->ime->model()->history().forget(w, std::string_view(encoded.data(), encoded.size()));
        } else e->ime->dict()->addWord(PinyinDictionary::UserDict, p, w);
        e->save(); return 1;
    } catch (...) { return 0; }
}
extern "C" int li_pinyin_clear_learning(li_pinyin *e) {
    if (!e) return 0;
    std::lock_guard guard(engineMutex);
    try {
        e->clear();
        e->ime->model()->history().clear();
        std::istringstream empty;
        e->ime->dict()->load(PinyinDictionary::UserDict, empty, PinyinDictFormat::Text);
        e->save(); return 1;
    } catch (...) { return 0; }
}
