#include "learnime_pinyin.h"
#include <cassert>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
struct Row { std::vector<std::string> f; };
static std::vector<Row> query(li_pinyin *e, const char *raw, bool nine, const char *locks = "", bool correct = true) {
    std::istringstream lines(li_pinyin_search_advanced(e, raw, nine, locks, correct));
    std::vector<Row> rows; std::string line, field;
    while (std::getline(lines, line)) {
        if (line.starts_with("@syllables")) continue;
        Row row; std::istringstream fields(line);
        while (std::getline(fields, field, '\t')) row.f.push_back(field);
        assert(row.f.size() == 8); rows.push_back(row);
    }
    return rows;
}
static bool has(const std::vector<Row> &rows, const std::string &text, bool corrected = false) {
    for (const auto &r : rows) if (r.f[0] == text && (!corrected || r.f[7] != "-")) return true;
    return false;
}
static std::string choose(li_pinyin *e, const Row &r) {
    return li_pinyin_select(e, std::stoull(r.f[3]), std::stoi(r.f[4]), r.f[5].c_str(), false);
}
int main(int argc, char **argv) {
    assert(argc >= 2);
    auto *e = li_pinyin_create(argv[1]); assert(e);
    if (argc > 2) {
        auto rows = query(e, argv[2], argc > 3 && std::string(argv[3]) == "nine", argc > 4 ? argv[4] : "");
        for (const auto &r : rows) {
            for (auto &f : r.f) std::cout << f << '\t';
            std::cout << '\n';
        }
        li_pinyin_destroy(e); return 0;
    }
    for (auto raw : {"nihoa", "nihso", "njhao"}) {
        auto rows = query(e, raw, false);
        assert(rows.at(0).f[0] == "你好" && rows[0].f[7] == "纠错");
        assert(rows[0].f[6] == "ni:2,hao:5");
        assert(choose(e, rows[0]) == "你好");
    }
    assert(has(query(e, "zongguo", false), "中国", true));
    auto exact = query(e, "nihao", false); assert(exact[0].f[0] == "你好" && exact[0].f[7] == "-");
    auto tail = query(e, "nihaov", false);
    assert(tail[0].f[0] == "你好" && tail[0].f[2] == "5" && tail[0].f[7] == "-");
    for (auto &r : query(e, "nihoa", false, "", false)) assert(r.f[7] == "-");
    auto nine = query(e, "64426", true); assert(nine[0].f[0] == "你好" && nine[0].f[6] == "ni:2,hao:5");
    for (auto &r : query(e, "64426", true, "ni:2")) assert(r.f[6].starts_with("ni:2"));
    auto second = query(e, "64426", true, "ni:2,gao:5");
    assert(has(second, "你高"));
    for (auto &r : second) if (r.f[0] == "你高") {
        assert(r.f[6] == "ni:2,gao:5" && choose(e, r) == "你高"); break;
    }
    for (auto &r : query(e, "644266", true, "ni:2,hao:5"))
        if (std::stoi(r.f[2]) > 2) assert(r.f[6].starts_with("ni:2,hao:5"));
    assert(query(e, "nihoa", false, "ni:2,hao:5")[0].f[0] == "你好");
    assert(!query(e, "lve", false, "lue:3", false).empty());
    assert(query(e, "64426", true, "xuan:4").empty());
    assert(query(e, "64426", true, "ni:80").empty());
    assert(has(query(e, "6", true), "你"));
    assert(query(e, "6", true, "ni:1")[0].f[0] == "你");
    for (auto &r : query(e, "4266", true, "hao:3")) assert(r.f[6].starts_with("hao:3"));
    assert(!query(e, std::string(50, '6').c_str(), true).empty());
    for (auto &r : query(e, std::string(50, 'n').c_str(), false)) assert(std::stoi(r.f[2]) <= 40);
    assert(!query(e, "nihaonihaonihaonihaonihaonihaonihaonihaonihaonihao", false).empty());
    li_pinyin_configure(e, 0);
    assert(!has(query(e, "zongguo", false, "", false), "中国", true));
    li_pinyin_configure(e, 1 << 5);
    assert(has(query(e, "zongguo", false, "", false), "中国", true));
    assert(li_pinyin_user_word(e, "kai'yuan'ci'ku", "开源词库", false));
    assert(std::string(li_pinyin_user_words(e)).find("开源词库 kai'yuan'ci'ku") != std::string::npos);
    assert(has(query(e, "kaiyuanciku", false), "开源词库"));
    assert(!li_pinyin_user_word(e, "kai'yuan", "开源词库", false));
    assert(!li_pinyin_user_word(e, "invalid", "词", false));
    assert(li_pinyin_user_word(e, "kai'yuan'ci'ku", "开源词库", true));
    assert(std::string(li_pinyin_user_words(e)).find("开源词库") == std::string::npos);
    assert(li_pinyin_user_word(e, "kai'yuan'ci'ku", "开源词库", false));
    assert(li_pinyin_clear_learning(e));
    assert(std::string(li_pinyin_user_words(e)).empty());
    li_pinyin_destroy(e);
    std::cout << "PASS: correction, selection, independent fuzzy, user dictionary add/remove/reset, unmatched tail, disabled correction, sequential and partial locks, long input\n";
}
