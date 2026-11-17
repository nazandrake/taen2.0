// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.content.ClipDescription
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

/**
 * Sends one picture into the field that has the focus, and this is the only place that calls `commitContent`. The field must say that it accepts the type
 * (`EditorInfo.contentMimeTypes`, matched like the platform does, so a wildcard type such as image-slash-star and a plain star match too); when it does not, nothing is sent and the caller shows
 * "This field does not accept images". There is no clipboard copy and no share sheet as a fallback. The URI is a token of [MediaShareProvider] that is
 * granted read-only to the receiving app by the commit; the token is revoked at the lock, at a wipe and when the field changes ([MediaTokens]).
 */
object MediaInserter {
    enum class Result { SENT, NOT_ACCEPTED, REFUSED }

    /** True when one of [accepted] (the types the field announces; null or empty means none) covers [mime]. */
    fun accepts(accepted: Array<String>?, mime: String): Boolean = accepted.orEmpty().any { ClipDescription.compareMimeTypes(mime, it) }

    /**
     * Commits the picture behind a new token. [packageName] is this app (the authority of the provider). Nothing is issued when the field does not accept the
     * type, so no token exists for a picture that was not sent. [REFUSED]: the field accepted the type but the commit failed or there is no connection; the
     * token is revoked again then.
     */
    fun insert(packageName: String, connection: InputConnection?, info: EditorInfo?, source: MediaSource, format: MediaFormat, size: Int): Result {
        if (connection == null || info == null || !accepts(info.contentMimeTypes, format.mime)) return Result.NOT_ACCEPTED
        val token = MediaTokens.issue(source, format, size)
        val content = InputContentInfo(MediaShareProvider.uriFor(packageName, token), ClipDescription("taen", arrayOf(format.mime)))
        val sent = try {
            connection.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        } catch (_: RuntimeException) {
            false
        }
        if (!sent) { MediaTokens.revoke(token); return Result.REFUSED }
        return Result.SENT
    }
}
