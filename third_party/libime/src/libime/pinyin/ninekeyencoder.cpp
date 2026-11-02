// LearnIME extension. No complete spelling paths are pruned before LM decoding.
// Modified 2026-10-31; SPDX-License-Identifier: LGPL-2.1-or-later
#include "ninekeyencoder.h"
#include "pinyindata.h"
#include <algorithm>
#include <bit>
#include <map>
#include <unordered_map>

namespace libime {
namespace {
char keyFor(char c) {
    constexpr std::string_view groups[] = {"abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
    for (size_t i = 0; i < 8; ++i)
        if (groups[i].find(c) != std::string_view::npos) return char('2' + i);
    return 0;
}
bool matches(std::string_view typed, std::string_view spelling) {
    if (typed.size() > spelling.size()) return false;
    for (size_t i = 0; i < typed.size(); ++i)
        if ((typed[i] >= '2' && typed[i] <= '9') ? keyFor(spelling[i]) != typed[i] : spelling[i] != typed[i])
            return false;
    return true;
}
}
MatchedPinyinSyllablesWithFuzzyFlags NineKeyEncoder::match(
    std::string_view pattern, PinyinFuzzyFlags flags, bool completion) {
    // Literal syllables are already constrained by the user's locks.
    const bool numeric = std::any_of(pattern.begin(), pattern.end(), [](char c) { return c >= '2' && c <= '9'; });
    if (!numeric) flags = PinyinFuzzyFlag::VE_UE;
    const std::string cacheKey = std::string(pattern) + ":" + std::to_string(flags.toInteger()) +
                                 (completion ? "+" : "=");
    static thread_local std::unordered_map<std::string, MatchedPinyinSyllablesWithFuzzyFlags> cache;
    if (auto found = cache.find(cacheKey); found != cache.end()) return found->second;
    using Pair = std::pair<PinyinInitial, PinyinFinal>;
    std::map<Pair, PinyinFuzzyFlags> pairs;
    auto add = [&](const auto &syls, PinyinFuzzyFlags extra) {
        for (const auto &[initial, finals] : syls) for (const auto &[final, fuzzy] : finals) {
            if (initial == PinyinInitial::Invalid || final == PinyinFinal::Invalid) continue;
            const auto combined = fuzzy | extra;
            auto [iter, inserted] = pairs.emplace(Pair{initial, final}, combined);
            if (!inserted && std::popcount(unsigned(combined.toInteger())) <
                             std::popcount(unsigned(iter->second.toInteger()))) iter->second = combined;
        }
    };
    for (const auto &entry : getPinyinMapV2()) {
        if (!flags.test(entry.flags()) || !matches(pattern, entry.pinyinView()) ||
            pattern.size() != entry.pinyinView().size()) continue;
        add(PinyinEncoder::stringToSyllablesWithFuzzyFlags(entry.pinyinView(), nullptr, flags),
            entry.flags());
    }
    const bool literalExact = !pairs.empty();
    if (completion && (numeric || !literalExact)) {
        for (const auto &entry : getPinyinMapV2()) {
            if (entry.flags() != PinyinFuzzyFlag::None || pattern.size() >= entry.pinyinView().size() ||
                !matches(pattern, entry.pinyinView())) continue;
            add(PinyinEncoder::stringToSyllablesWithFuzzyFlags(entry.pinyinView(), nullptr, flags),
                PinyinFuzzyFlag::PartialFinal);
        }
    }
    MatchedPinyinSyllablesWithFuzzyFlags result;
    for (const auto &[pair, fuzzy] : pairs) {
        if (result.empty() || result.back().first != pair.first) result.push_back({pair.first, {}});
        result.back().second.emplace_back(pair.second, fuzzy);
    }
    if (cache.size() >= 4096) cache.clear();
    cache.emplace(cacheKey, result);
    return result;
}
NineKeyGraph NineKeyEncoder::parse(std::string input, PinyinFuzzyFlags flags) {
    const size_t size = input.size();
    NineKeyGraph graph(std::move(input));
    std::vector<std::vector<size_t>> edges(size + 1);
    for (size_t from = 0; from < size; ++from) {
        if (graph.data()[from] == '\'') { edges[from].push_back(from + 1); continue; }
        for (size_t to = from + 1; to <= size && to - from <= 6; ++to) {
            if (graph.data()[to - 1] == '\'') break;
            if (!match(graph.segment(from, to), flags, to == size).empty()) edges[from].push_back(to);
        }
    }
    // Decoder traversal requires every reachable node to have a route to EOS.
    std::vector<bool> finishes(size + 1), reached(size + 1);
    finishes[size] = true; reached[0] = true;
    for (size_t i = size; i-- > 0;)
        for (size_t end : edges[i]) if (finishes[end]) finishes[i] = true;
    for (size_t i = 0; i < size; ++i) {
        if (!reached[i]) continue;
        for (size_t end : edges[i]) if (finishes[end]) {
            graph.addNext(i, end); reached[end] = true;
        }
    }
    return graph;
}
}
