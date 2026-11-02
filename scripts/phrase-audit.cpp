#include "learnime_pinyin.h"
#include <chrono>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
struct Row { std::vector<std::string> f; };
static std::vector<Row> query(li_pinyin *e, const std::string &raw, bool nine, const std::string &locks = "") {
    std::istringstream lines(li_pinyin_search_advanced(e, raw.c_str(), nine, locks.c_str(), 1));
    std::vector<Row> rows; std::string line, field;
    while (std::getline(lines, line)) {
        if (line.starts_with("@syllables")) continue;
        Row row; std::istringstream fields(line);
        while (std::getline(fields, field, '\t')) row.f.push_back(field);
        if (row.f.size() == 8) rows.push_back(std::move(row));
    }
    return rows;
}
int main(int argc, char **argv) {
    if (argc != 2) return 1;
    auto *e = li_pinyin_create(argv[1]);
    if (!e) return 2;
    std::string line;
    std::cout << "target\tpinyin\tmode\traw\trank\tlocked_rank\tfirst\tfirst_pinyin\tfirst_consumed\tpath_visible\treplay\tmilliseconds\tcandidate_count\n";
    while (std::getline(std::cin, line)) {
        auto tab = line.find('\t');
        auto target = line.substr(0, tab), spelling = line.substr(tab + 1);
        for (bool nine : {true, false}) {
            std::istringstream syllables(spelling); std::string s, raw, locks;
            const std::string letters[] = {"abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
            while (syllables >> s) {
                for (char c : s) {
                    if (!nine) raw += c;
                    else for (size_t k = 0; k < 8; ++k) if (letters[k].find(c) != std::string::npos) { raw += char('2' + k); break; }
                }
                if (!locks.empty()) locks += ',';
                locks += s + ":" + std::to_string(raw.size());
            }
            auto start = std::chrono::steady_clock::now();
            std::vector<Row> rows;
            for (size_t i = 1; i <= raw.size(); ++i) rows = query(e, raw.substr(0, i), nine);
            auto ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
            int rank = 0, lockedRank = 0; bool visible = false; std::string replay = "-";
            for (size_t i = 0; i < rows.size(); ++i) {
                if (rows[i].f[1] == spelling && std::stoul(rows[i].f[2]) == raw.size()) visible = true;
                if (rows[i].f[0] == target && std::stoul(rows[i].f[2]) == raw.size()) rank = int(i + 1);
            }
            if (rank) {
                auto &r = rows[rank - 1].f;
                replay = li_pinyin_select(e, std::stoull(r[3]), std::stoi(r[4]), r[5].c_str(), 0);
            }
            auto constrained = query(e, raw, nine, locks);
            for (size_t i = 0; i < constrained.size(); ++i)
                if (constrained[i].f[0] == target && std::stoul(constrained[i].f[2]) == raw.size()) lockedRank = int(i + 1);
            auto first = rows.empty() ? std::vector<std::string>(8) : rows.front().f;
            std::cout << target << '\t' << spelling << '\t' << (nine ? "9" : "26") << '\t' << raw << '\t'
                      << rank << '\t' << lockedRank << '\t' << first[0] << '\t' << first[1] << '\t'
                      << first[2] << '\t' << visible << '\t' << replay << '\t' << ms << '\t' << rows.size() << '\n';
        }
    }
    li_pinyin_destroy(e);
}
