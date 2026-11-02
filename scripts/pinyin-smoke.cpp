#include "learnime_pinyin.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

struct Choice {
    std::string text, path;
    unsigned long long token;
    int index, consumed;
};
static std::vector<Choice> search(li_pinyin * engine, const std::string & input, bool nine = false) {
    std::istringstream lines(li_pinyin_search(engine, input.c_str(), nine));
    std::string line;
    std::vector<Choice> out;
    while (std::getline(lines, line)) {
        std::istringstream fields(line);
        std::vector<std::string> parts;
        std::string field;
        while (std::getline(fields, field, '\t')) parts.push_back(field);
        assert(parts.size() == 6);
        out.push_back({parts[0], parts[5], std::stoull(parts[3]), std::stoi(parts[4]), std::stoi(parts[2])});
    }
    return out;
}
static std::string select(li_pinyin * engine, const Choice & c, bool learn) {
    return li_pinyin_select(engine, c.token, c.index, c.path.c_str(), learn);
}
static std::string read(const char * path) {
    std::ifstream file(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}
static void check_sentence_alternatives(li_pinyin * engine) {
    for (bool nine : {true, false}) {
        const std::string input = nine ? "9694264484542" : "woxianghuijia";
        std::vector<Choice> rows;
        // Match real typing: no character or syllable is selected midway.
        for (size_t length = 1; length <= input.size(); ++length)
            rows = search(engine, input.substr(0, length), nine);
        bool found = false;
        for (size_t rank = 0; rank < rows.size(); ++rank) {
            if (rows[rank].text != "我想回家") continue;
            assert(rank < 5 && rows[rank].consumed == static_cast<int>(input.size()));
            assert(select(engine, rows[rank], false) == "我想回家");
            std::cout << (nine ? "T9" : "QWERTY") << " full sentence rank=" << rank + 1
                      << "; consumed=" << rows[rank].consumed << "; no intermediate choice\n";
            found = true; break;
        }
        assert(found);
    }
    std::string choices = li_pinyin_search_advanced(engine, "9694264484542", true, "", false);
    assert(choices.find("@syllables\two:2") != std::string::npos);
    assert(choices.find("yo:2") != std::string::npos && choices.find("w:1*") != std::string::npos);
    for (const char * locks : {"w:1*", "wo:2", "wo:2,x:3*"}) {
        std::string constrained = li_pinyin_search_advanced(engine, "9694264484542", true, locks, false);
        assert(constrained.find("我想回家\two xiang hui jia\t13") != std::string::npos);
        assert(constrained.find("yo:2") == std::string::npos);
    }
    assert(std::string(li_pinyin_search_advanced(engine, "96", true, "w:1*,o:2", false)).empty());
    assert(std::string(li_pinyin_search_advanced(engine, "967", true, "wo:2,x:3*", false)).empty());
}
int main(int argc, char ** argv) {
    assert(argc == 3);
    auto * engine = li_pinyin_create_with_user(argv[1], argv[2]);
    assert(engine);
    li_pinyin_destroy(engine);
    const auto empty = read(argv[2]);
    engine = li_pinyin_create_with_user(argv[1], argv[2]);
    const auto stale = search(engine, "nihao").at(0);
    search(engine, "zhongguo");
    assert(select(engine, stale, true).empty());
    auto readonly = search(engine, "nihao").at(0);
    assert(select(engine, readonly, false) == readonly.text);
    check_sentence_alternatives(engine);
    li_pinyin_destroy(engine);
    assert(read(argv[2]) == empty);
    engine = li_pinyin_create_with_user(argv[1], argv[2]);
    auto choices = search(engine, "shijie");
    std::cout << "shijie first: " << choices.at(0).text << '\n';
    auto chosen = choices.at(0);
    for (const auto & c : choices) if (c.consumed == 6 && c.text != chosen.text) { chosen = c; break; }
    std::cout << "explicit learning: " << chosen.text << '\n';
    assert(select(engine, chosen, true) == chosen.text);
    li_pinyin_destroy(engine);
    const auto once = read(argv[2]);
    assert(once != empty);
    engine = li_pinyin_create_with_user(argv[1], argv[2]);
    auto learned = search(engine, "shijie");
    bool found = false;
    for (const auto & c : learned) if (c.text == chosen.text) {
        assert(select(engine, c, true) == chosen.text); found = true; break;
    }
    assert(found);
    li_pinyin_destroy(engine);
    assert(read(argv[2]) != once);
    engine = li_pinyin_create_with_user(argv[1], argv[2]);
    std::cout << "first after restart: " << search(engine, "shijie").at(0).text << '\n';
    auto nine = search(engine, "64426", true);
    assert(!nine.empty());
    for (size_t i = 0; i < nine.size(); ++i) {
        if (nine[i].path != nine[0].path) {
            assert(select(engine, nine[i], false) == nine[i].text);
            std::cout << "T9 path replay: " << nine[i].path << " -> " << nine[i].text << '\n';
            break;
        }
    }
    std::cerr << "checking unmatched tail\n";
    auto tail = search(engine, "nihaov");
    assert(!tail.empty() && tail.at(0).consumed < 6);
    std::cerr << "checking long input\n";
    auto long_input = search(engine, std::string(50, 'n'));
    for (const auto & c : long_input) assert(c.consumed <= 40);
    auto long_nine = search(engine, std::string(50, '6'), true);
    assert(!long_nine.empty() && long_nine.at(0).consumed < 50);
    assert(select(engine, stale, false).empty());
    std::string predicts = li_pinyin_predict(engine, "\xe4\xb8\xad\xe5\x9b\xbd");
    assert(!predicts.empty());
    std::cout << "prediction: " << predicts.substr(0, predicts.find('\n')) << '\n';
    li_pinyin_destroy(engine);
    std::cout << "PASS: full sentence alternatives, syllable/initial constraints, stale tokens, no-learning, durable learning, repeat counts, T9 replay, tail, prediction\n";
}
