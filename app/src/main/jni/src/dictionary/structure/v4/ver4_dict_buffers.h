/*
 * Copyright (C) 2013, The Android Open Source Project
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

#ifndef LATINIME_VER4_DICT_BUFFER_H
#define LATINIME_VER4_DICT_BUFFER_H

#include <cstdint>
#include <cstdio>
#include <memory>
#include <vector>

#include "defines.h"
#include "dictionary/header/header_policy.h"
#include "dictionary/structure/v4/content/language_model_dict_content.h"
#include "dictionary/structure/v4/content/shortcut_dict_content.h"
#include "dictionary/structure/v4/content/terminal_position_lookup_table.h"
#include "dictionary/structure/v4/ver4_dict_constants.h"
#include "dictionary/utils/buffer_with_extendable_buffer.h"
#include "dictionary/utils/mmapped_buffer.h"

namespace latinime {

class Ver4DictBuffers {
 public:
    typedef std::unique_ptr<Ver4DictBuffers> Ver4DictBuffersPtr;

    static Ver4DictBuffersPtr openVer4DictBuffers(const char *const dictDirPath,
            MmappedBuffer::MmappedBufferPtr &&headerBuffer,
            const FormatUtils::FORMAT_VERSION formatVersion);

    // Opens the dictionary from two buffers in memory (see flushHeaderAndDictBuffersToMemory).
    static Ver4DictBuffersPtr openVer4DictBuffers(MmappedBuffer::MmappedBufferPtr &&headerBuffer,
            MmappedBuffer::MmappedBufferPtr &&bodyBuffer,
            const FormatUtils::FORMAT_VERSION formatVersion);

    // Splits the output of flushHeaderAndDictBuffersToMemory into header and body; false if it is malformed.
    static bool splitSerialized(const uint8_t *const data, const size_t size,
            const uint8_t **const outHeader, size_t *const outHeaderSize,
            const uint8_t **const outBody, size_t *const outBodySize);

    static AK_FORCE_INLINE Ver4DictBuffersPtr createVer4DictBuffers(
            const HeaderPolicy *const headerPolicy, const int maxTrieSize) {
        return Ver4DictBuffersPtr(new Ver4DictBuffers(headerPolicy, maxTrieSize));
    }

    AK_FORCE_INLINE bool isValid() const {
        return mHeaderBuffer && mDictBuffer && mHeaderPolicy.isValid();
    }

    AK_FORCE_INLINE bool isNearSizeLimit() const {
        return mExpandableTrieBuffer.isNearSizeLimit()
                || mTerminalPositionLookupTable.isNearSizeLimit()
                || mLanguageModelDictContent.isNearSizeLimit()
                || mShortcutDictContent.isNearSizeLimit();
    }

    AK_FORCE_INLINE const HeaderPolicy *getHeaderPolicy() const {
        return &mHeaderPolicy;
    }

    AK_FORCE_INLINE BufferWithExtendableBuffer *getWritableHeaderBuffer() {
        return &mExpandableHeaderBuffer;
    }

    AK_FORCE_INLINE BufferWithExtendableBuffer *getWritableTrieBuffer() {
        return &mExpandableTrieBuffer;
    }

    AK_FORCE_INLINE const BufferWithExtendableBuffer *getTrieBuffer() const {
        return &mExpandableTrieBuffer;
    }

    AK_FORCE_INLINE TerminalPositionLookupTable *getMutableTerminalPositionLookupTable() {
        return &mTerminalPositionLookupTable;
    }

    AK_FORCE_INLINE const TerminalPositionLookupTable *getTerminalPositionLookupTable() const {
        return &mTerminalPositionLookupTable;
    }

    AK_FORCE_INLINE LanguageModelDictContent *getMutableLanguageModelDictContent() {
        return &mLanguageModelDictContent;
    }

    AK_FORCE_INLINE const LanguageModelDictContent *getLanguageModelDictContent() const {
        return &mLanguageModelDictContent;
    }

    AK_FORCE_INLINE ShortcutDictContent *getMutableShortcutDictContent() {
        return &mShortcutDictContent;
    }

    AK_FORCE_INLINE const ShortcutDictContent *getShortcutDictContent() const {
        return &mShortcutDictContent;
    }

    AK_FORCE_INLINE bool isUpdatable() const {
        return mIsUpdatable;
    }

    bool flush(const char *const dictDirPath) const {
        return flushHeaderAndDictBuffers(dictDirPath, &mExpandableHeaderBuffer);
    }

    bool flushHeaderAndDictBuffers(const char *const dictDirPath,
            const BufferWithExtendableBuffer *const headerBuffer) const;

    // Writes "TNV4", the header size and body size (uint32 little endian each), the header file content and the body
    // file content into outBytes, the same bytes a directory dictionary holds in its two files. No file is touched.
    bool flushHeaderAndDictBuffersToMemory(const BufferWithExtendableBuffer *const headerBuffer,
            std::vector<uint8_t> *const outBytes) const;

 private:
    DISALLOW_COPY_AND_ASSIGN(Ver4DictBuffers);

    static const uint8_t SERIALIZED_MAGIC[4];

    Ver4DictBuffers(MmappedBuffer::MmappedBufferPtr &&headerBuffer,
            MmappedBuffer::MmappedBufferPtr &&bodyBuffer,
            const FormatUtils::FORMAT_VERSION formatVersion,
            const std::vector<ReadWriteByteArrayView> &contentBuffers);

    Ver4DictBuffers(const HeaderPolicy *const headerPolicy, const int maxTrieSize);

    bool flushDictBuffers(FILE *const file) const;

    const MmappedBuffer::MmappedBufferPtr mHeaderBuffer;
    const MmappedBuffer::MmappedBufferPtr mDictBuffer;
    const HeaderPolicy mHeaderPolicy;
    BufferWithExtendableBuffer mExpandableHeaderBuffer;
    BufferWithExtendableBuffer mExpandableTrieBuffer;
    TerminalPositionLookupTable mTerminalPositionLookupTable;
    LanguageModelDictContent mLanguageModelDictContent;
    ShortcutDictContent mShortcutDictContent;
    const int mIsUpdatable;
};
} // namespace latinime
#endif /* LATINIME_VER4_DICT_BUFFER_H */
