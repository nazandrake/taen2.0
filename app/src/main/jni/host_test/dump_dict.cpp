// Host tool (tools/dict_eval/run.sh): lists a read-only .dict file as text for the dictionary evaluation. One line per word,
// "W<TAB>word<TAB>probability", and one line per word pair, "P<TAB>previous<TAB>next<TAB>probability" (the pairs the file stores for
// the previous word). Words that are not words, blacklisted words and words without a probability are left out, as in dump_words.
// Not part of the APK.
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "defines.h"
#include "dictionary/interface/dictionary_structure_with_buffer_policy.h"
#include "dictionary/property/ngram_property.h"
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

static std::string utf8(const int *cps, int count) {
    std::string s;
    for (int i = 0; i < count; ++i) appendUtf8(&s, cps[i]);
    return s;
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: dump_dict <file.dict> <file size in bytes>\n");
        return 2;
    }
    DictionaryStructureWithBufferPolicy::StructurePolicyPtr dict =
            DictionaryStructureWithBufferPolicyFactory::newPolicyForExistingDictFile(
                    argv[1], 0, atoi(argv[2]), false /* isUpdatable */);
    if (!dict) {
        fprintf(stderr, "cannot open %s\n", argv[1]);
        return 1;
    }
    int token = 0;
    do {
        int cps[MAX_WORD_LENGTH];
        int count = 0;
        token = dict->getNextWordAndNextToken(token, cps, &count);
        if (count <= 0) continue;
        const WordProperty property = dict->getWordProperty(CodePointArrayView(cps, count));
        const UnigramProperty &u = property.getUnigramProperty();
        if (u.isNotAWord() || u.isBlacklisted() || u.getProbability() == NOT_A_PROBABILITY) continue;
        const std::string word = utf8(cps, count);
        printf("W\t%s\t%d\n", word.c_str(), u.getProbability());
        for (const NgramProperty &n : property.getNgramProperties()) {
            const std::vector<int> *target = n.getTargetCodePoints();
            printf("P\t%s\t%s\t%d\n", word.c_str(), utf8(target->data(), static_cast<int>(target->size())).c_str(), n.getProbability());
        }
    } while (token != 0);
    return 0;
}
