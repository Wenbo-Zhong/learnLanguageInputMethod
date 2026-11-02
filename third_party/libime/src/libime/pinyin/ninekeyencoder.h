// LearnIME extension: numeric ambiguity is resolved inside libime's word graph.
// Modified 2026-10-31; SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#include <libime/pinyin/pinyinencoder.h>
namespace libime {
class NineKeyGraph : public SegmentGraph {
public:
    using SegmentGraph::SegmentGraph;
};
class NineKeyEncoder {
public:
    static NineKeyGraph parse(std::string input, PinyinFuzzyFlags flags);
    static MatchedPinyinSyllablesWithFuzzyFlags match(
        std::string_view pattern, PinyinFuzzyFlags flags, bool completion);
};
}
