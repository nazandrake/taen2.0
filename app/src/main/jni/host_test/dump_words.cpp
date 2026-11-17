// Host tool (tools/gen-glide-fixtures.sh): lists the words of a read-only .dict file with their unigram
// probability, one "word<TAB>probability" line each, most probable first. Used only to build the fixed word
// lists of the glide quality gate (app/src/test/resources/glide); it is not part of the APK.
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <utility>
#include <vector>

#include "defines.h"
#include "dictionary/interface/dictionary_structure_with_buffer_policy.h"
#include "dictionary/property/unigram_property.h"
#include "dictionary/property/word_property.h"
#include "dictionary/structure/dictionary_structure_with_buffer_policy_factory.h"

using namespace latinime;

static void appendUtf8(std::string *out, int cp) {
    if (cp < 0x80) {
        out->push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out->push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out->push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out->push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out->push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: dump_words <file.dict> <file size in bytes>\n");
        return 2;
    }
    DictionaryStructureWithBufferPolicy::StructurePolicyPtr dict =
            DictionaryStructureWithBufferPolicyFactory::newPolicyForExistingDictFile(
                    argv[1], 0, atoi(argv[2]), false /* isUpdatable */);
    if (!dict) {
        fprintf(stderr, "cannot open %s\n", argv[1]);
        return 1;
    }
    std::vector<std::pair<int, std::string>> words;
    int token = 0;
    do {
        int cps[MAX_WORD_LENGTH];
        int count = 0;
        token = dict->getNextWordAndNextToken(token, cps, &count);
        if (count <= 0) continue;
        const WordProperty property = dict->getWordProperty(CodePointArrayView(cps, count));
        const UnigramProperty &u = property.getUnigramProperty();
        if (u.isNotAWord() || u.isBlacklisted() || u.getProbability() == NOT_A_PROBABILITY) continue;
        std::string word;
        for (int i = 0; i < count; ++i) appendUtf8(&word, cps[i]);
        words.emplace_back(u.getProbability(), word);
    } while (token != 0);
    std::stable_sort(words.begin(), words.end(),
            [](const std::pair<int, std::string> &a, const std::pair<int, std::string> &b) {
                return a.first > b.first;
            });
    for (const auto &w : words) printf("%s\t%d\n", w.second.c_str(), w.first);
    return 0;
}
