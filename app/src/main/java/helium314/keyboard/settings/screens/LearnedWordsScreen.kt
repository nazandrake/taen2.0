// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.dictionary.ListedWord
import helium314.keyboard.latin.personalization.LearnedWords
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** What the screen shows for one language: null lists while the phone is locked or the words cannot be read now. */
private class LanguageWords(val learned: List<ListedWord>?, val removed: List<String>?)

/**
 * Settings > Privacy > Learned words: the words Taen learned for a language, each removable, and the words removed from the suggestions, each
 * restorable. Like every settings page it is shown only while the app lock is open (SettingsActivity) and kept off screenshots (SecureWindow);
 * while the phone is locked nothing is read or shown. The words are held by the screen only while it is open (no saved state).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnedWordsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var languages by remember { mutableStateOf<List<String>?>(emptyList()) }
    var loadedLanguages by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) } // a language tag: no typed text in the saved state
    var words by remember { mutableStateOf<LanguageWords?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    val removedText = stringResource(R.string.learned_word_removed)
    val removeFailed = stringResource(R.string.learned_word_remove_failed)
    val restoreFailed = stringResource(R.string.removed_word_restore_failed)

    LaunchedEffect(version) {
        val tags = withContext(Dispatchers.IO) { LearnedWords.languages(ctx) }
        languages = tags
        loadedLanguages = true
        val tag = selected?.takeIf { tags != null && it in tags } ?: tags?.firstOrNull()
        selected = tag
        words = if (tag == null) null else withContext(Dispatchers.IO) { LanguageWords(LearnedWords.learned(ctx, tag), LearnedWords.removed(ctx, tag)) }
    }

    fun change(action: () -> Boolean, failed: String, done: String?) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { action() }
            if (!ok) Toast.makeText(ctx, failed, Toast.LENGTH_LONG).show()
            else if (done != null) Toast.makeText(ctx, done, Toast.LENGTH_SHORT).show()
            version++
        }
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TopAppBar(
                title = { Text(stringResource(R.string.learned_words_title)) },
                windowInsets = WindowInsets(0),
                navigationIcon = { BackButton { onClickBack() } },
            )
            val tags = languages
            when {
                !loadedLanguages -> Line(stringResource(R.string.learned_words_loading))
                tags == null -> Line(stringResource(R.string.learned_words_locked))
                tags.isEmpty() -> Line(stringResource(R.string.learned_words_none))
                else -> LazyColumn {
                    item { Line(stringResource(R.string.learned_words_info)) }
                    val tag = selected
                    if (tags.size > 1 && tag != null) item {
                        Preference(name = stringResource(R.string.learned_words_language), description = languageName(tag), onClick = { showPicker = true })
                    }
                    val current = words
                    val learned = current?.learned
                    val removed = current?.removed
                    item { PreferenceCategory(stringResource(R.string.learned_words_section, learned?.size ?: 0)) }
                    when {
                        current == null -> item { Line(stringResource(R.string.learned_words_loading)) }
                        learned == null -> item { Line(stringResource(R.string.learned_words_locked)) }
                        learned.isEmpty() -> item { Line(stringResource(R.string.learned_words_empty)) }
                        else -> items(learned, key = { "l:" + it.word }) { w ->
                            WordRow(w.word, stringResource(R.string.learned_word_remove)) {
                                if (tag != null) change({ LearnedWords.remove(ctx, tag, w.word) }, removeFailed, removedText)
                            }
                        }
                    }
                    item { PreferenceCategory(stringResource(R.string.removed_words_section, removed?.size ?: 0)) }
                    when {
                        current == null -> {}
                        removed == null -> item { Line(stringResource(R.string.learned_words_locked)) }
                        removed.isEmpty() -> item { Line(stringResource(R.string.removed_words_none)) }
                        else -> items(removed, key = { "r:$it" }) { w ->
                            WordRow(w, stringResource(R.string.removed_word_restore)) {
                                if (tag != null) change({ LearnedWords.restore(ctx, tag, w) }, restoreFailed, null)
                            }
                        }
                    }
                }
            }
        }
    }
    val tags = languages
    if (showPicker && tags != null) {
        ListPickerDialog(
            onDismissRequest = { showPicker = false },
            items = tags,
            selectedItem = selected,
            title = { Text(stringResource(R.string.learned_words_language)) },
            getItemName = { languageName(it) },
            onItemSelected = { selected = it; words = null; version++ },
        )
    }
}

private fun languageName(tag: String): String = Locale.forLanguageTag(tag).let { it.getDisplayName(it).ifBlank { tag } }

@Composable
private fun WordRow(word: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(word, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}
