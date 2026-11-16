// Host test (tools/native-test.sh): a ver4 dictionary is serialized to a byte buffer and opened from one without
// any file, so the learned words of the keyboard can be kept encrypted by the Java side (privacy invariant P7).
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "defines.h"
#include "dictionary/header/header_read_write_utils.h"
#include "dictionary/interface/dictionary_structure_with_buffer_policy.h"
#include "dictionary/property/historical_info.h"
#include "dictionary/property/ngram_context.h"
#include "dictionary/property/unigram_property.h"
#include "dictionary/property/word_property.h"
#include "dictionary/structure/dictionary_structure_with_buffer_policy_factory.h"
#include "suggest/core/dictionary/dictionary.h"
#include "utils/time_keeper.h"

using namespace latinime;

static int failures = 0;
static int checks = 0;
#define CHECK(cond) do { ++checks; if (!(cond)) { ++failures; printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); } } while (0)
#define CHECK_EQ(a, b) do { ++checks; long long va = (a), vb = (b); if (va != vb) { ++failures; \
    printf("FAIL %s:%d %s == %s (%lld vs %lld)\n", __FILE__, __LINE__, #a, #b, va, vb); } } while (0)

static std::vector<int> cps(const char *s) {
    std::vector<int> v;
    while (*s) v.push_back(static_cast<unsigned char>(*s++));
    return v;
}

typedef DictionaryStructureWithBufferPolicy::StructurePolicyPtr PolicyPtr;

static PolicyPtr newHistoryDict() {
    DictionaryHeaderStructurePolicy::AttributeMap attrs;
    std::vector<int> key, value;
    HeaderReadWriteUtils::insertCharactersIntoVector("USES_FORGETTING_CURVE", &key);
    HeaderReadWriteUtils::insertCharactersIntoVector("1", &value);
    attrs[key] = value;
    key.clear(); value.clear();
    HeaderReadWriteUtils::insertCharactersIntoVector("HAS_HISTORICAL_INFO", &key);
    HeaderReadWriteUtils::insertCharactersIntoVector("1", &value);
    attrs[key] = value;
    std::vector<int> locale = cps("en_US");
    return DictionaryStructureWithBufferPolicyFactory::newPolicyForOnMemoryDict(403, locale, &attrs);
}

static void addWord(DictionaryStructureWithBufferPolicy *dict, const char *word, int prob, int ts, int level, int count) {
    std::vector<int> w = cps(word);
    UnigramProperty p(false, false, false, false, prob, HistoricalInfo(ts, level, count));
    dict->addUnigramEntry(CodePointArrayView(w.data(), w.size()), &p);
}

static WordProperty propertyOf(const DictionaryStructureWithBufferPolicy *dict, const char *word) {
    std::vector<int> w = cps(word);
    return dict->getWordProperty(CodePointArrayView(w.data(), w.size()));
}

static void testRoundTripKeepsWordsAndHistory() {
    TimeKeeper::setCurrentTime();
    const int now = TimeKeeper::peekCurrentTime();
    PolicyPtr dict = newHistoryDict();
    CHECK(dict != nullptr);
    addWord(dict.get(), "hello", 100, now - 1000, 5, 0);
    addWord(dict.get(), "world", 80, now - 2000, 3, 0);

    std::vector<uint8_t> bytes;
    CHECK(dict->flushToMemory(false /* withGC */, &bytes));
    CHECK(bytes.size() > 8);

    PolicyPtr reopened = DictionaryStructureWithBufferPolicyFactory::newPolicyForSerializedDict(
            bytes.data(), static_cast<int>(bytes.size()));
    CHECK(reopened != nullptr);
    if (!reopened) return;
    for (const char *word : {"hello", "world"}) {
        WordProperty before = propertyOf(dict.get(), word);
        WordProperty after = propertyOf(reopened.get(), word);
        CHECK(after.getCodePoints().size() == strlen(word));
        CHECK_EQ(after.getUnigramProperty().getHistoricalInfo().getTimestamp(),
                before.getUnigramProperty().getHistoricalInfo().getTimestamp());
        CHECK_EQ(after.getUnigramProperty().getHistoricalInfo().getLevel(),
                before.getUnigramProperty().getHistoricalInfo().getLevel());
        CHECK_EQ(after.getUnigramProperty().getHistoricalInfo().getCount(),
                before.getUnigramProperty().getHistoricalInfo().getCount());
    }
    // (the engine may normalise stored timestamps, so the contract is "the reopened dictionary equals the original",
    // not "equals the input"; pass 2 below checks counts and n-grams against the learning calls)
}

// ---- realistic learning, GC, corruption ---------------------------------------------------------

// What the keyboard does after every committed word: update the word and its n-grams.
static void learn(DictionaryStructureWithBufferPolicy *dict, const std::vector<std::string> &prevWords,
        const char *word, int ts) {
    std::vector<int> w = cps(word);
    if (prevWords.empty()) { // first word of a sentence: the previous word is the beginning-of-sentence marker
        int none[1] = {0};
        NgramContext ctx(none, 0, true);
        dict->updateEntriesForWordWithNgramContext(&ctx, CodePointArrayView(w.data(), w.size()), true, HistoricalInfo(ts, 0, 1));
        return;
    }
    std::vector<int> prev = cps(prevWords.back().c_str());
    NgramContext ctx(prev.data(), static_cast<int>(prev.size()), false);
    dict->updateEntriesForWordWithNgramContext(&ctx, CodePointArrayView(w.data(), w.size()), true, HistoricalInfo(ts, 0, 1));
}

static std::string describe(const WordProperty &p) {
    char buf[256];
    const UnigramProperty &u = p.getUnigramProperty();
    std::string s;
    for (size_t i = 0; i < p.getCodePoints().size(); ++i) { snprintf(buf, sizeof(buf), "%d,", p.getCodePoints()[i]); s += buf; }
    snprintf(buf, sizeof(buf), " bos=%d notaword=%d prob=%d ts=%d lvl=%d cnt=%d;", u.representsBeginningOfSentence(), u.isNotAWord(),
            u.getProbability(), u.getHistoricalInfo().getTimestamp(), u.getHistoricalInfo().getLevel(), u.getHistoricalInfo().getCount());
    s += buf;
    for (const NgramProperty &n : p.getNgramProperties()) {
        s += " ngram[";
        for (size_t i = 1; i <= n.getNgramContext()->getPrevWordCount(); ++i) {
            CodePointArrayView c = n.getNgramContext()->getNthPrevWordCodePoints(i);
            for (size_t j = 0; j < c.size(); ++j) { snprintf(buf, sizeof(buf), "%d,", c[j]); s += buf; }
            s += n.getNgramContext()->isNthPrevWordBeginningOfSentence(i) ? "bos|" : "|";
        }
        s += "->";
        for (int c : *n.getTargetCodePoints()) { snprintf(buf, sizeof(buf), "%d,", c); s += buf; }
        snprintf(buf, sizeof(buf), " p=%d ts=%d lvl=%d cnt=%d]", n.getProbability(), n.getHistoricalInfo().getTimestamp(),
                n.getHistoricalInfo().getLevel(), n.getHistoricalInfo().getCount());
        s += buf;
    }
    return s;
}

// Every word of the dictionary with all its properties, in iteration order.
static std::vector<std::string> dumpAll(DictionaryStructureWithBufferPolicy *dict) {
    std::vector<std::string> all;
    int token = 0;
    do {
        int cp[MAX_WORD_LENGTH];
        int count = 0;
        token = dict->getNextWordAndNextToken(token, cp, &count);
        if (count == 0) break;
        all.push_back(describe(dict->getWordProperty(CodePointArrayView(cp, count))));
    } while (token != 0);
    return all;
}

static PolicyPtr reopen(const std::vector<uint8_t> &bytes) {
    return DictionaryStructureWithBufferPolicyFactory::newPolicyForSerializedDict(bytes.data(), static_cast<int>(bytes.size()));
}

static PolicyPtr learnedSample(int now) {
    PolicyPtr dict = newHistoryDict();
    const char *sentence[] = {"good", "morning", "my", "friend"};
    for (int round = 0; round < 4; ++round) {
        std::vector<std::string> prev;
        for (const char *w : sentence) {
            learn(dict.get(), prev, w, now - 100 * round - prev.size());
            prev.push_back(w);
        }
    }
    return dict;
}

static void testFullFidelityAndIdempotence() {
    TimeKeeper::setCurrentTime();
    const int now = TimeKeeper::peekCurrentTime();
    PolicyPtr dict = learnedSample(now);
    std::vector<std::string> before = dumpAll(dict.get());
    CHECK_EQ(before.size(), 5); // BoS marker, good, morning, my, friend

    std::vector<uint8_t> bytes;
    CHECK(dict->flushToMemory(false, &bytes));
    PolicyPtr reopened = reopen(bytes);
    CHECK(reopened != nullptr);
    if (!reopened) return;
    std::vector<std::string> after = dumpAll(reopened.get());
    CHECK_EQ(after.size(), before.size());
    for (size_t i = 0; i < before.size() && i < after.size(); ++i) {
        if (before[i] != after[i]) printf("  before: %s\n  after:  %s\n", before[i].c_str(), after[i].c_str());
        CHECK(before[i] == after[i]);
    }
    // learned counts are really there (4 rounds, each word once per round), so the comparison above is not empty
    WordProperty friendProp = propertyOf(reopened.get(), "friend");
    CHECK_EQ(friendProp.getUnigramProperty().getHistoricalInfo().getCount(), 4);
    CHECK_EQ(friendProp.getNgramProperties().size(), 0);
    WordProperty goodProp = propertyOf(reopened.get(), "good");
    CHECK_EQ(goodProp.getNgramProperties().size(), 1); // good -> morning
    // serializing the reopened dictionary again gives the same bytes
    std::vector<uint8_t> again;
    CHECK(reopened->flushToMemory(false, &again));
    CHECK(again == bytes);
}

static void testGcKeepsLearnedWordsAndStaysUpdatable() {
    TimeKeeper::setCurrentTime();
    const int now = TimeKeeper::peekCurrentTime();
    PolicyPtr dict = learnedSample(now);
    std::vector<std::string> before = dumpAll(dict.get());
    std::vector<uint8_t> gcBytes;
    CHECK(dict->flushToMemory(true, &gcBytes));
    PolicyPtr gced = reopen(gcBytes);
    CHECK(gced != nullptr);
    if (!gced) return;
    std::vector<std::string> after = dumpAll(gced.get());
    CHECK_EQ(after.size(), before.size());
    CHECK_EQ(propertyOf(gced.get(), "morning").getUnigramProperty().getHistoricalInfo().getCount(), 4);
    // still updatable, and the update survives another round trip
    learn(gced.get(), {"friend"}, "zebra", now);
    std::vector<uint8_t> next;
    CHECK(gced->flushToMemory(false, &next));
    PolicyPtr third = reopen(next);
    CHECK(third != nullptr);
    if (!third) return;
    CHECK_EQ(propertyOf(third.get(), "zebra").getUnigramProperty().getHistoricalInfo().getCount(), 1);
    CHECK_EQ(propertyOf(third.get(), "friend").getNgramProperties().size(), 1); // friend -> zebra
    CHECK_EQ(dumpAll(third.get()).size(), 6);
}

static void testEmptyDictionaryRoundTrip() {
    PolicyPtr dict = newHistoryDict();
    std::vector<uint8_t> bytes;
    CHECK(dict->flushToMemory(false, &bytes));
    PolicyPtr reopened = reopen(bytes);
    CHECK(reopened != nullptr);
    if (reopened) CHECK_EQ(dumpAll(reopened.get()).size(), 0);
    std::vector<uint8_t> gc;
    CHECK(dict->flushToMemory(true, &gc));
    CHECK(reopen(gc) != nullptr);
}

static void setUint32(std::vector<uint8_t> *b, size_t at, uint32_t v) {
    for (int i = 0; i < 4; ++i) (*b)[at + i] = static_cast<uint8_t>(v >> (8 * i));
}

static void testCorruptBytesAreRejected() {
    TimeKeeper::setCurrentTime();
    PolicyPtr dict = learnedSample(TimeKeeper::peekCurrentTime());
    std::vector<uint8_t> good;
    CHECK(dict->flushToMemory(false, &good));
    CHECK(reopen(good) != nullptr); // positive control: the unmodified bytes open

    CHECK(DictionaryStructureWithBufferPolicyFactory::newPolicyForSerializedDict(nullptr, 0) == nullptr);
    CHECK(DictionaryStructureWithBufferPolicyFactory::newPolicyForSerializedDict(good.data(), -5) == nullptr);
    for (size_t cut : {size_t(1), size_t(11), size_t(12), size_t(13), good.size() / 2, good.size() - 1}) {
        std::vector<uint8_t> t(good.begin(), good.begin() + cut);
        CHECK(reopen(t) == nullptr); // truncated
    }
    std::vector<uint8_t> extra = good;
    extra.push_back(0);
    CHECK(reopen(extra) == nullptr); // a trailing byte
    std::vector<uint8_t> magic = good;
    magic[0] ^= 1;
    CHECK(reopen(magic) == nullptr);
    std::vector<uint8_t> hdr = good;
    setUint32(&hdr, 4, 0);
    CHECK(reopen(hdr) == nullptr); // empty header
    std::vector<uint8_t> hdr2 = good;
    uint32_t headerSize = good[4] | (good[5] << 8) | (good[6] << 16) | (static_cast<uint32_t>(good[7]) << 24);
    setUint32(&hdr2, 4, headerSize + 1); // sizes no longer add up
    CHECK(reopen(hdr2) == nullptr);
    std::vector<uint8_t> hdrMagic = good;
    hdrMagic[12] ^= 0xff; // first byte of the header file: the dictionary magic number
    CHECK(reopen(hdrMagic) == nullptr);
    // a container whose sizes are consistent but whose body is cut: the body's own length fields must catch it
    uint32_t bodySize = good[8] | (good[9] << 8) | (good[10] << 16) | (static_cast<uint32_t>(good[11]) << 24);
    std::vector<uint8_t> cutBody(good.begin(), good.end() - 10);
    setUint32(&cutBody, 8, bodySize - 10);
    CHECK(reopen(cutBody) == nullptr);
    // random garbage of several sizes (deterministic generator)
    uint32_t seed = 12345;
    for (size_t n : {size_t(0), size_t(5), size_t(64), size_t(4096)}) {
        std::vector<uint8_t> junk(n);
        for (auto &c : junk) { seed = seed * 1664525u + 1013904223u; c = static_cast<uint8_t>(seed >> 24); }
        CHECK(reopen(junk) == nullptr);
    }
}

// ---- bulk word read for the glide word list (Dictionary::getWordsBulk) -------------------------------

// The word list the old way: one iteration step and one probability lookup per word (what BinaryDictionary.forEachWord did
// with two JNI calls per word). Words without probability and the beginning-of-sentence marker are left out.
static std::vector<std::string> perWordList(Dictionary *dict) {
    std::vector<std::string> out;
    int token = 0;
    do {
        int cp[MAX_WORD_LENGTH];
        int count = 0;
        token = dict->getNextWordAndNextToken(token, cp, &count);
        if (count <= 0 || cp[0] == CODE_POINT_BEGINNING_OF_SENTENCE) continue;
        const int probability = dict->getProbability(CodePointArrayView(cp, count));
        if (probability == NOT_A_PROBABILITY) continue;
        std::string s = std::to_string(probability) + ":";
        for (int i = 0; i < count; ++i) s += std::to_string(cp[i]) + ",";
        out.push_back(s);
    } while (token != 0);
    return out;
}

// The same list from getWordsBulk, with a buffer of [capacity] ints; [chunks] counts the native calls.
static std::vector<std::string> bulkList(Dictionary *dict, int capacity, int *chunks) {
    std::vector<std::string> out;
    std::vector<int> buffer(capacity);
    int token = 0;
    *chunks = 0;
    do {
        int used = -1;
        token = dict->getWordsBulk(token, buffer.data(), capacity, &used);
        ++*chunks;
        for (int i = 0; i < used;) {
            const int probability = buffer[i], length = buffer[i + 1];
            std::string s = std::to_string(probability) + ":";
            for (int j = 0; j < length; ++j) s += std::to_string(buffer[i + 2 + j]) + ",";
            out.push_back(s);
            i += 2 + length;
        }
        if (used <= 0 && token != 0) { ++failures; printf("FAIL no progress\n"); break; }
    } while (token != 0);
    return out;
}

static void testBulkReadEqualsPerWordRead() {
    TimeKeeper::setCurrentTime();
    const int now = TimeKeeper::peekCurrentTime();
    PolicyPtr policy = learnedSample(now); // good morning my friend, with the beginning-of-sentence marker
    for (int i = 0; i < 300; ++i) {
        std::string w = "word" + std::to_string(i) + (i % 3 == 0 ? "long" : "");
        addWord(policy.get(), w.c_str(), 10 + i % 200, now - i, 0, 1);
    }
    // entries that have no probability outside of suggestions: not a word (a shortcut source), blacklisted
    for (const char *special : {"notaword", "blocked"}) {
        std::vector<int> w = cps(special);
        const bool notAWord = special[0] == 'n';
        UnigramProperty p(false, notAWord, !notAWord, false, 50, HistoricalInfo(now, 0, 1));
        policy->addUnigramEntry(CodePointArrayView(w.data(), w.size()), &p);
    }
    Dictionary dict(nullptr, std::move(policy));
    std::vector<std::string> expected = perWordList(&dict);
    CHECK_EQ(expected.size(), 304); // the marker, the not-a-word and the blacklisted entry are left out; the list is not empty
    int dictionaryEntries = 0, token = 0;
    do {
        int cp[MAX_WORD_LENGTH];
        int count = 0;
        token = dict.getNextWordAndNextToken(token, cp, &count);
        if (count > 0) ++dictionaryEntries;
    } while (token != 0);
    CHECK_EQ(dictionaryEntries, 307); // positive control: the three left-out entries are really in the dictionary
    for (int capacity : {MAX_WORD_LENGTH + 2, 120, 1000, 100000}) {
        int chunks = 0;
        std::vector<std::string> got = bulkList(&dict, capacity, &chunks);
        CHECK(got == expected);
        if (capacity == 100000) CHECK_EQ(chunks, 1);
        if (capacity == 120) CHECK(chunks > 20); // the walk really goes over several buffers
    }
}

static void testBulkReadRefusesATooSmallBuffer() {
    TimeKeeper::setCurrentTime();
    Dictionary dict(nullptr, learnedSample(TimeKeeper::peekCurrentTime()));
    int buffer[10];
    int used = 99;
    // an error is -1 (the Java side must not take the walk for a finished, complete one), 0 is "all read"
    CHECK_EQ(dict.getWordsBulk(0, buffer, MAX_WORD_LENGTH + 1, &used), -1); // ends the walk instead of looping
    CHECK_EQ(used, 0);
    CHECK_EQ(dict.getWordsBulk(0, buffer, 0, &used), -1);
    CHECK_EQ(used, 0);
    CHECK_EQ(dict.getWordsBulk(-3, buffer, 100000, &used), -1); // an invalid token ends the walk
    CHECK_EQ(used, 0);
    std::vector<int> big(100000);
    CHECK_EQ(dict.getWordsBulk(0, big.data(), 100000, &used), 0); // positive control: the same dictionary with room is read, to its end
    CHECK(used > 0);
}

static void testBulkReadOfAnEmptyDictionary() {
    Dictionary dict(nullptr, newHistoryDict());
    std::vector<int> buffer(200);
    int used = 99;
    CHECK_EQ(dict.getWordsBulk(0, buffer.data(), 200, &used), 0);
    CHECK_EQ(used, 0);
}

// ---- the read-only main dictionaries (PatriciaTrie, format 202): the one-pass walk against the word-by-word read -----------

#include <algorithm>
#include <cstdlib>

static std::vector<std::string> sortedCopy(std::vector<std::string> v) {
    std::sort(v.begin(), v.end());
    return v;
}

// A real bundled dictionary (file, size, number of words with a probability known from an earlier measurement): the policy gives the
// list in one pass (positive control: the fast path is really used), and the list holds exactly the words of the word-by-word
// read with the same probabilities, whatever the buffer size and so the number of chunks (the order differs, so compare sorted).
static void testBulkReadOfARealMainDictionary(const char *file, int size, int expectedWords) {
    DictionaryStructureWithBufferPolicy::StructurePolicyPtr policy =
            DictionaryStructureWithBufferPolicyFactory::newPolicyForExistingDictFile(file, 0, size, false /* isUpdatable */);
    CHECK(policy != nullptr);
    if (!policy) return;
    std::vector<int> all;
    CHECK(policy->getAllWordRecords(&all));
    CHECK(all.size() > static_cast<size_t>(expectedWords) * 3);
    Dictionary dict(nullptr, std::move(policy));
    std::vector<std::string> expected = sortedCopy(perWordList(&dict));
    CHECK_EQ(expected.size(), expectedWords);
    for (int capacity : {MAX_WORD_LENGTH + 2, 4096, 65536, 1 << 24}) {
        int chunks = 0;
        std::vector<std::string> got = sortedCopy(bulkList(&dict, capacity, &chunks));
        CHECK(got == expected);
        if (capacity == (1 << 24)) CHECK_EQ(chunks, 1);
        if (capacity == 65536) CHECK(chunks > 5);
    }
    // a walk that is restarted in the middle starts over with the whole list
    std::vector<int> buffer(4096);
    int used = 0;
    const int middle = dict.getWordsBulk(0, buffer.data(), 4096, &used);
    CHECK(middle != 0 && used > 0);
    int chunks = 0;
    CHECK(sortedCopy(bulkList(&dict, 65536, &chunks)) == expected);
}


// ---- the bulk walk under concurrency, damage and pointers that share arrays or point nowhere -------------

#include <atomic>
#include <cstring>
#include <thread>
#include <unistd.h>

// Walks a dictionary to its end; returns false on an error (a -1 token) and puts the records of the words read in [out].
static bool walkOnce(Dictionary *dict, int capacity, std::vector<std::string> *out) {
    std::vector<int> buffer(capacity);
    int token = 0;
    do {
        int used = 0;
        token = dict->getWordsBulk(token, buffer.data(), capacity, &used);
        if (token < 0) return false;
        for (int i = 0; i < used;) {
            const int probability = buffer[i], length = buffer[i + 1];
            std::string w = std::to_string(probability) + ":";
            for (int j = 0; j < length; ++j) w += std::to_string(buffer[i + 2 + j]) + ",";
            out->push_back(w);
            i += 2 + length;
        }
        if (used <= 0 && token != 0) return false;
    } while (token != 0);
    return true;
}

// Several threads walk one dictionary at once (two lexicon reads of one dictionary, the main list and a restarted one): every walk
// either returns the complete list or an error, never a short list or garbage, and the list that the first walk finishes is read.
// Under ThreadSanitizer (tools/native-test.sh with TAEN_NATIVE_SANITIZE=thread) the shared transient list must not race.
static void testConcurrentWalksGiveTheCompleteListOrAnError(const char *file, int size) {
    DictionaryStructureWithBufferPolicy::StructurePolicyPtr policy =
            DictionaryStructureWithBufferPolicyFactory::newPolicyForExistingDictFile(file, 0, size, false);
    CHECK(policy != nullptr);
    if (!policy) return;
    Dictionary dict(nullptr, std::move(policy));
    std::vector<std::string> expected;
    CHECK(walkOnce(&dict, 65536, &expected)); // alone: complete
    std::sort(expected.begin(), expected.end());
    CHECK(expected.size() > 1000);
    const int threads = 4, rounds = 3;
    std::atomic<int> complete(0), errors(0), wrong(0);
    for (int round = 0; round < rounds; ++round) {
        std::vector<std::thread> pool;
        for (int t = 0; t < threads; ++t) {
            pool.emplace_back([&, t]() {
                std::vector<std::string> got;
                if (!walkOnce(&dict, t % 2 == 0 ? 65536 : 8192, &got)) { ++errors; return; }
                std::sort(got.begin(), got.end());
                if (got == expected) ++complete; else ++wrong;
            });
        }
        for (std::thread &thread : pool) thread.join();
    }
    CHECK_EQ(wrong.load(), 0); // nothing short, nothing mixed up
    CHECK(complete.load() >= rounds); // positive control: in every round the walk that finished first got the whole list
    CHECK_EQ(complete.load() + errors.load(), threads * rounds);
    std::vector<std::string> after;
    CHECK(walkOnce(&dict, 65536, &after)); // and the dictionary is fine afterwards
    std::sort(after.begin(), after.end());
    CHECK(after == expected);
}

// the time source (TimeKeeper) is no process-wide plain variable. Every ordinary entry point of a
// Dictionary sets it, so dictionaries of different objects used by different threads (the typing suggestions and the background glide
// read, the learned-word and the personal dictionary) must not race on it, and a getNextWordAndNextToken export iteration must not
// run over a word walk of the same dictionary. The values are the single-thread ones; ThreadSanitizer is what sees the data race.
static PolicyPtr concurrentSample(int now) {
    PolicyPtr policy = learnedSample(now);
    for (int i = 0; i < 200; ++i) {
        std::string w = "word" + std::to_string(i);
        addWord(policy.get(), w.c_str(), 10 + i % 200, now - i, 0, 1);
    }
    return policy;
}

static void testNormalCallsRaceNeitherWithEachOtherNorWithAWalk() {
    TimeKeeper::setCurrentTime();
    const int now = TimeKeeper::peekCurrentTime();
    Dictionary d1(nullptr, concurrentSample(now)), d2(nullptr, concurrentSample(now)), d3(nullptr, concurrentSample(now));
    const std::vector<std::string> expected = perWordList(&d1);
    CHECK(expected.size() > 200);
    const std::vector<int> probe = cps("word7");
    const int expectedProbability = d2.getProbability(CodePointArrayView(probe.data(), probe.size()));
    CHECK(expectedProbability != NOT_A_PROBABILITY); // positive control: the word is there
    std::atomic<int> wrong(0);
    std::vector<std::thread> pool;
    for (Dictionary *d : {&d2, &d3}) { // ordinary calls on two other dictionary objects (one dictionary is used by one caller at a time: the Java side holds a lock)
        pool.emplace_back([&, d]() {
            for (int i = 0; i < 300; ++i)
                if (d->getProbability(CodePointArrayView(probe.data(), probe.size())) != expectedProbability) ++wrong;
        });
    }
    // The walk and the export iteration of d1 run at the same time on one dictionary. The Java side holds a lock for a whole
    // iteration, so their lists are not compared while they overlap (both use the iteration state of the policy): every call must
    // be safe, which is what ThreadSanitizer checks, and the dictionary must be intact afterwards.
    pool.emplace_back([&]() { for (int i = 0; i < 4; ++i) { int chunks = 0; bulkList(&d1, 4096, &chunks); } });
    pool.emplace_back([&]() {
        for (int i = 0; i < 4; ++i) {
            int token = 0;
            do {
                int cp[MAX_WORD_LENGTH];
                int count = 0;
                token = d1.getNextWordAndNextToken(token, cp, &count);
            } while (token != 0);
        }
    });
    for (std::thread &thread : pool) thread.join();
    CHECK_EQ(wrong.load(), 0);
    CHECK(perWordList(&d1) == expected);
    int token = 0, entries = 0;
    do {
        int cp[MAX_WORD_LENGTH];
        int count = 0;
        token = d1.getNextWordAndNextToken(token, cp, &count);
        if (count > 0) ++entries;
    } while (token != 0);
    CHECK(entries >= static_cast<int>(expected.size())); // the export iteration alone is complete
}

// A thread that never set the time reads a real one (not 0): the value is per thread, read-through for threads of a walk.
static void testTimeIsPerThreadAndAlwaysReal() {
    TimeKeeper::setCurrentTime();
    const int mine = TimeKeeper::peekCurrentTime();
    int other = 0;
    std::thread([&]() { other = TimeKeeper::peekCurrentTime(); }).join();
    CHECK(mine > 1000000000);
    CHECK(other >= mine && other - mine < 5);
    TimeKeeper::startTestModeWithForceCurrentTime(12345);
    int forced = 0;
    std::thread([&]() { forced = TimeKeeper::peekCurrentTime(); TimeKeeper::setCurrentTime(); forced += TimeKeeper::peekCurrentTime(); }).join();
    CHECK_EQ(forced, 24690); // test mode holds for every thread, and setCurrentTime does not undo it
    TimeKeeper::stopTestMode();
}

// A dictionary cut short in the middle of its PtNode arrays (a damaged file, an unfinished copy): the one-pass walk reaches positions
// beyond the buffer; that is an error of the walk, not the end of a shorter list.
static void testDamagedMainDictionaryIsAnErrorNotAShortList(const char *file, int size) {
    DictionaryStructureWithBufferPolicy::StructurePolicyPtr whole =
            DictionaryStructureWithBufferPolicyFactory::newPolicyForExistingDictFile(file, 0, size, false);
    CHECK(whole != nullptr);
    std::vector<int> all;
    CHECK(whole && whole->getAllWordRecords(&all)); // positive control: the whole file gives its list
    DictionaryStructureWithBufferPolicy::StructurePolicyPtr cut =
            DictionaryStructureWithBufferPolicyFactory::newPolicyForExistingDictFile(file, 0, size / 2, false);
    CHECK(cut != nullptr);
    if (!cut) return;
    std::vector<int> partial;
    CHECK(!cut->getAllWordRecords(&partial));
    CHECK(cut->isCorrupted());
    Dictionary dict(nullptr, std::move(cut));
    std::vector<std::string> words;
    CHECK(!walkOnce(&dict, 65536, &words)); // the Java side sees -1 and keeps no list
}

// A v2 dictionary made by hand (header, then PtNode arrays without forward links): a file path for the policy factory.
static std::string writeDictionary(const char *name, const std::vector<uint8_t> &body) {
    const char *dir = getenv("TAEN_NATIVE_TEST_DIR");
    const std::string path = std::string(dir ? dir : ".") + "/" + name;
    std::vector<uint8_t> file = {0x9B, 0xC1, 0x3A, 0xFE, 0x00, 0xCA /* format 202 */, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C};
    file.insert(file.end(), body.begin(), body.end());
    FILE *f = fopen(path.c_str(), "wb");
    CHECK(f != nullptr);
    if (!f) return path;
    fwrite(file.data(), 1, file.size(), f);
    fclose(f);
    return path;
}

static int wordsOf(const std::string &path, std::vector<std::string> *out, bool *walked) {
    FILE *f = fopen(path.c_str(), "rb");
    fseek(f, 0, SEEK_END);
    const int size = static_cast<int>(ftell(f));
    fclose(f);
    DictionaryStructureWithBufferPolicy::StructurePolicyPtr policy =
            DictionaryStructureWithBufferPolicyFactory::newPolicyForExistingDictFile(path.c_str(), 0, size, false);
    CHECK(policy != nullptr);
    if (!policy) return -1;
    std::vector<int> records;
    const bool ok = policy->getAllWordRecords(&records);
    const bool corrupted = policy->isCorrupted();
    Dictionary dict(nullptr, std::move(policy));
    *walked = walkOnce(&dict, 4096, out);
    return ok ? 1 : (corrupted ? 0 : -2);
}

// PtNode arrays that share their children: n arrays of two PtNodes, both PtNodes of an array point to the next array, so the number
// of paths is 2^n although the dictionary has about 7n bytes (what a damaged dictionary, or one with pointers that repeat, can do).
// A tree has at most as many PtNodes as the buffer has bytes, and the walk must give up when it reads more than that.
static void testPointersThatShareArraysEndTheWalkInsteadOfExploding() {
    alarm(60); // without the bound the walk would run (and fill memory) for ever: the test dies instead of hanging
    // positive control: a real small tree ("a", "b", "bc") is read, 3 words
    {
        std::vector<uint8_t> body = {
            2,
            0x10, 'a', 100,
            0x50, 'b', 100, 1,
            1,
            0x10, 'c', 90};
        std::vector<std::string> words;
        bool walked = false;
        CHECK_EQ(wordsOf(writeDictionary("tree.dict", body), &words, &walked), 1);
        CHECK(walked);
        CHECK_EQ(words.size(), 3);
    }
    // a child pointer that points beyond the buffer: the reading helper stops with an error, which is no end of a shorter list
    {
        std::vector<uint8_t> body = {
            2,
            0x10, 'a', 100,
            0x50, 'b', 100, 200}; // the child array would be at byte 206 of an 8 byte body
        std::vector<std::string> words;
        bool walked = true;
        CHECK_EQ(wordsOf(writeDictionary("dangling.dict", body), &words, &walked), 0);
        CHECK(!walked);
        CHECK(words.empty());
    }
    for (const int levels : {3, 40}) {
        std::vector<uint8_t> body;
        for (int i = 0; i < levels; ++i) {
            body.insert(body.end(), {2, 0x40, 'a', 4, 0x40, 'b', 1});
        }
        body.insert(body.end(), {1, 0x10, 'z', 100});
        std::vector<std::string> words;
        bool walked = true;
        const int result = wordsOf(writeDictionary("shared.dict", body), &words, &walked);
        if (levels == 3) {
            // 22 PtNode reads fit in the 24 bytes: a small sharing is no damage (positive control for the bound, not only for the tree)
            CHECK_EQ(result, 1);
            CHECK(walked);
            CHECK_EQ(words.size(), 8);
        } else {
            CHECK_EQ(result, 0); // the policy fails and marks itself corrupted
            CHECK(!walked); // the bulk read is an error (-1), not a list
            CHECK(words.empty());
        }
    }
    alarm(0);
}

// TAEN_NATIVE_SKIP=concurrent,damaged,shared leaves those tests out (to see the others when one of them crashes, a red run)
static bool enabled(const char *name) {
    const char *skip = getenv("TAEN_NATIVE_SKIP");
    return !skip || !strstr(skip, name);
}

int main(int argc, char **argv) {
    // optional: <dict file> <size> <words> triples of real main dictionaries (tools/native-test.sh passes the bundled ones)
    for (int i = 1; i + 2 < argc; i += 3) {
        testBulkReadOfARealMainDictionary(argv[i], atoi(argv[i + 1]), atoi(argv[i + 2]));
        if (i == 1) { // one real dictionary is enough for these (the sanitizer builds are slow)
            if (enabled("concurrent")) testConcurrentWalksGiveTheCompleteListOrAnError(argv[i], atoi(argv[i + 1]));
            if (enabled("damaged")) testDamagedMainDictionaryIsAnErrorNotAShortList(argv[i], atoi(argv[i + 1]));
        }
    }
    if (enabled("shared")) testPointersThatShareArraysEndTheWalkInsteadOfExploding();
    testNormalCallsRaceNeitherWithEachOtherNorWithAWalk();
    testTimeIsPerThreadAndAlwaysReal();
    testBulkReadEqualsPerWordRead();
    testBulkReadRefusesATooSmallBuffer();
    testBulkReadOfAnEmptyDictionary();
    testRoundTripKeepsWordsAndHistory();
    testFullFidelityAndIdempotence();
    testGcKeepsLearnedWordsAndStaysUpdatable();
    testEmptyDictionaryRoundTrip();
    testCorruptBytesAreRejected();
    printf("%d checks, %d failures\n", checks, failures);
    printf(failures == 0 ? "NATIVE TEST PASS\n" : "NATIVE TEST FAIL\n");
    return failures == 0 ? 0 : 1;
}
