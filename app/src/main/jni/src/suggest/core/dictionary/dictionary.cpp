/*
 * Copyright (C) 2009, The Android Open Source Project
 * modified
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "LatinIME: dictionary.cpp"

#include "suggest/core/dictionary/dictionary.h"

#include "defines.h"
#include "dictionary/interface/dictionary_header_structure_policy.h"
#include "dictionary/property/ngram_context.h"
#include "suggest/core/dictionary/dictionary_utils.h"
#include "suggest/core/result/suggestion_results.h"
#include "suggest/core/session/dic_traverse_session.h"
#include "suggest/core/suggest.h"
#include "suggest/core/suggest_options.h"
#include "suggest/policyimpl/gesture/gesture_suggest_policy_factory.h"
#include "suggest/policyimpl/typing/typing_suggest_policy_factory.h"
#include "utils/int_array_view.h"
#include "utils/time_keeper.h"

namespace latinime {

const int Dictionary::HEADER_ATTRIBUTE_BUFFER_SIZE = 32;

Dictionary::Dictionary(JNIEnv *env, DictionaryStructureWithBufferPolicy::StructurePolicyPtr
        dictionaryStructureWithBufferPolicy)
        : mDictionaryStructureWithBufferPolicy(std::move(dictionaryStructureWithBufferPolicy)),
          mGestureSuggest(new Suggest(GestureSuggestPolicyFactory::getGestureSuggestPolicy())),
          mTypingSuggest(new Suggest(TypingSuggestPolicyFactory::getTypingSuggestPolicy())) {
}

void Dictionary::getSuggestions(ProximityInfo *proximityInfo, DicTraverseSession *traverseSession,
        int *xcoordinates, int *ycoordinates, int *times, int *pointerIds, int *inputCodePoints,
        int inputSize, const NgramContext *const ngramContext,
        const SuggestOptions *const suggestOptions, const float weightOfLangModelVsSpatialModel,
        SuggestionResults *const outSuggestionResults) const {
    TimeKeeper::setCurrentTime();
    traverseSession->init(this, ngramContext, suggestOptions);
    const auto &suggest = suggestOptions->isGesture() ? mGestureSuggest : mTypingSuggest;
    suggest->getSuggestions(proximityInfo, traverseSession, xcoordinates,
            ycoordinates, times, pointerIds, inputCodePoints, inputSize,
            weightOfLangModelVsSpatialModel, outSuggestionResults);
}

Dictionary::NgramListenerForPrediction::NgramListenerForPrediction(
        const NgramContext *const ngramContext, const WordIdArrayView prevWordIds,
        SuggestionResults *const suggestionResults,
        const DictionaryStructureWithBufferPolicy *const dictStructurePolicy)
    : mNgramContext(ngramContext), mPrevWordIds(prevWordIds),
      mSuggestionResults(suggestionResults), mDictStructurePolicy(dictStructurePolicy) {}

void Dictionary::NgramListenerForPrediction::onVisitEntry(const int ngramProbability,
        const int targetWordId) {
    if (targetWordId == NOT_A_WORD_ID) {
        return;
    }
    if (mNgramContext->isNthPrevWordBeginningOfSentence(1 /* n */)
            && ngramProbability == NOT_A_PROBABILITY) {
        return;
    }
    int targetWordCodePoints[MAX_WORD_LENGTH];
    const int codePointCount = mDictStructurePolicy->getCodePointsAndReturnCodePointCount(
            targetWordId, MAX_WORD_LENGTH, targetWordCodePoints);
    if (codePointCount <= 0) {
        return;
    }
    const WordAttributes wordAttributes = mDictStructurePolicy->getWordAttributesInContext(
            mPrevWordIds, targetWordId, nullptr /* multiBigramMap */);
    if (wordAttributes.getProbability() == NOT_A_PROBABILITY) {
        return;
    }
    mSuggestionResults->addPrediction(targetWordCodePoints, codePointCount,
            wordAttributes.getProbability());
}

void Dictionary::getPredictions(const NgramContext *const ngramContext,
        SuggestionResults *const outSuggestionResults) const {
    TimeKeeper::setCurrentTime();
    WordIdArray<MAX_PREV_WORD_COUNT_FOR_N_GRAM> prevWordIdArray;
    const WordIdArrayView prevWordIds = ngramContext->getPrevWordIds(
            mDictionaryStructureWithBufferPolicy.get(), &prevWordIdArray,
            true /* tryLowerCaseSearch */);
    NgramListenerForPrediction listener(ngramContext, prevWordIds, outSuggestionResults,
            mDictionaryStructureWithBufferPolicy.get());
    mDictionaryStructureWithBufferPolicy->iterateNgramEntries(prevWordIds, &listener);
}

int Dictionary::getProbability(const CodePointArrayView codePoints) const {
    return getNgramProbability(nullptr /* ngramContext */, codePoints);
}

int Dictionary::getMaxProbabilityOfExactMatches(const CodePointArrayView codePoints) const {
    TimeKeeper::setCurrentTime();
    return DictionaryUtils::getMaxProbabilityOfExactMatches(
            mDictionaryStructureWithBufferPolicy.get(), codePoints);
}

int Dictionary::getNgramProbability(const NgramContext *const ngramContext,
        const CodePointArrayView codePoints) const {
    TimeKeeper::setCurrentTime();
    const int wordId = mDictionaryStructureWithBufferPolicy->getWordId(codePoints,
            false /* forceLowerCaseSearch */);
    if (wordId == NOT_A_WORD_ID) return NOT_A_PROBABILITY;
    if (!ngramContext) {
        return getDictionaryStructurePolicy()->getProbabilityOfWord(WordIdArrayView(), wordId);
    }
    WordIdArray<MAX_PREV_WORD_COUNT_FOR_N_GRAM> prevWordIdArray;
    const WordIdArrayView prevWordIds = ngramContext->getPrevWordIds(
            mDictionaryStructureWithBufferPolicy.get(), &prevWordIdArray,
            true /* tryLowerCaseSearch */);
    return getDictionaryStructurePolicy()->getProbabilityOfWord(prevWordIds, wordId);
}

bool Dictionary::addUnigramEntry(const CodePointArrayView codePoints,
        const UnigramProperty *const unigramProperty) {
    if (unigramProperty->representsBeginningOfSentence()
            && !mDictionaryStructureWithBufferPolicy->getHeaderStructurePolicy()
                    ->supportsBeginningOfSentence()) {
        AKLOGE("The dictionary doesn't support Beginning-of-Sentence.");
        return false;
    }
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->addUnigramEntry(codePoints, unigramProperty);
}

bool Dictionary::removeUnigramEntry(const CodePointArrayView codePoints) {
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->removeUnigramEntry(codePoints);
}

bool Dictionary::addNgramEntry(const NgramProperty *const ngramProperty) {
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->addNgramEntry(ngramProperty);
}

bool Dictionary::removeNgramEntry(const NgramContext *const ngramContext,
        const CodePointArrayView codePoints) {
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->removeNgramEntry(ngramContext, codePoints);
}

bool Dictionary::updateEntriesForWordWithNgramContext(const NgramContext *const ngramContext,
        const CodePointArrayView codePoints, const bool isValidWord,
        const HistoricalInfo historicalInfo) {
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->updateEntriesForWordWithNgramContext(ngramContext,
            codePoints, isValidWord, historicalInfo);
}

bool Dictionary::flush(const char *const filePath) {
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->flush(filePath);
}

bool Dictionary::flushWithGC(const char *const filePath) {
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->flushWithGC(filePath);
}

bool Dictionary::flushToMemory(const bool withGC, std::vector<uint8_t> *const outBytes) {
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->flushToMemory(withGC, outBytes);
}

bool Dictionary::needsToRunGC(const bool mindsBlockByGC) {
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->needsToRunGC(mindsBlockByGC);
}

void Dictionary::getProperty(const char *const query, const int queryLength, char *const outResult,
        const int maxResultLength) {
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->getProperty(query, queryLength, outResult,
            maxResultLength);
}

const WordProperty Dictionary::getWordProperty(const CodePointArrayView codePoints) {
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->getWordProperty(codePoints);
}

int Dictionary::getNextWordAndNextToken(const int token, int *const outCodePoints,
        int *const outCodePointCount) {
    // the export iteration (BinaryDictionary.getNextWordNative) runs one at a time with the word walks of the same dictionary;
    // getWordsBulk calls the policy directly, so this lock is never taken twice
    std::lock_guard<std::mutex> lock(mBulkMutex);
    TimeKeeper::setCurrentTime();
    return mDictionaryStructureWithBufferPolicy->getNextWordAndNextToken(
            token, outCodePoints, outCodePointCount);
}

int Dictionary::getWordsBulk(const int token, int *const outRecords, const int outCapacity,
        int *const outUsed) {
    *outUsed = 0;
    if (token < 0 || outCapacity < MAX_WORD_LENGTH + 2) {
        return -1;
    }
    std::lock_guard<std::mutex> lock(mBulkMutex);
    TimeKeeper::setCurrentTime(); // the time is per thread: chunks of one walk may arrive on different threads, each sets its own
    if (token == 0) {
        mBulkRecords.clear();
        mBulkRecordsReady = mDictionaryStructureWithBufferPolicy->getAllWordRecords(&mBulkRecords);
        mBulkIsList = mBulkRecordsReady;
        if (!mBulkRecordsReady) {
            std::vector<int>().swap(mBulkRecords);
            if (mDictionaryStructureWithBufferPolicy->isCorrupted()) {
                return -1; // a format with a word list whose walk failed: no list, no word-by-word guess over a damaged dictionary
            }
        }
    } else if (mBulkIsList && !mBulkRecordsReady) {
        return -1; // the list of this walk was released by another walk that finished
    }
    if (mBulkRecordsReady) {
        // the records from the offset [token] on, as many whole records as fit; an offset that is not the start of a
        // record (a token of another walk) ends the walk
        size_t at = static_cast<size_t>(token);
        int used = 0;
        if (token > 0 && at + 2 > mBulkRecords.size()) {
            return -1; // not the start of a record of this list
        }
        while (at + 2 <= mBulkRecords.size()) {
            const int length = mBulkRecords[at + 1];
            const size_t recordSize = 2 + static_cast<size_t>(length);
            if (length <= 0 || length > MAX_WORD_LENGTH || at + recordSize > mBulkRecords.size()) {
                // damaged list or an offset that is not the start of a record (a token of another walk): an error
                std::vector<int>().swap(mBulkRecords);
                mBulkRecordsReady = false;
                *outUsed = 0;
                return -1;
            }
            if (used + static_cast<int>(recordSize) > outCapacity) {
                break;
            }
            for (size_t i = 0; i < recordSize; ++i) {
                outRecords[used++] = mBulkRecords[at + i];
            }
            at += recordSize;
        }
        *outUsed = used;
        if (at >= mBulkRecords.size()) {
            std::vector<int>().swap(mBulkRecords);
            mBulkRecordsReady = false;
            return 0;
        }
        return static_cast<int>(at);
    }
    int used = 0;
    int nextToken = token;
    do {
        int wordCodePoints[MAX_WORD_LENGTH];
        int wordCodePointCount = 0;
        const int thisToken = nextToken;
        nextToken = mDictionaryStructureWithBufferPolicy->getNextWordAndNextToken(
                thisToken, wordCodePoints, &wordCodePointCount);
        if (wordCodePointCount <= 0 || wordCodePoints[0] == CODE_POINT_BEGINNING_OF_SENTENCE) {
            continue;
        }
        const int probability = getProbability(CodePointArrayView(wordCodePoints, wordCodePointCount));
        if (probability == NOT_A_PROBABILITY) {
            continue;
        }
        outRecords[used++] = probability;
        outRecords[used++] = wordCodePointCount;
        for (int i = 0; i < wordCodePointCount; ++i) {
            outRecords[used++] = wordCodePoints[i];
        }
    } while (nextToken != 0 && used + MAX_WORD_LENGTH + 2 <= outCapacity);
    *outUsed = used;
    return nextToken;
}

} // namespace latinime
