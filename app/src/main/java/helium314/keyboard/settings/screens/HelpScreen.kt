// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.settings.manual.Block
import helium314.keyboard.settings.manual.ManualMarkdown
import helium314.keyboard.settings.manual.ManualPages
import helium314.keyboard.settings.manual.ManualTitles
import helium314.keyboard.settings.manual.Span
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.latin.utils.NextScreenIcon

/** The Help index (Settings > Help): one entry for each page of the manual. Navigation only, nothing is stored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpIndexScreen(onClickBack: () -> Unit, onOpenPage: (String) -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TopAppBar(
                title = { Text(stringResource(R.string.help_title)) },
                windowInsets = WindowInsets(0),
                navigationIcon = { BackButton { onClickBack() } },
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.help_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                for (page in ManualPages.all)
                    Preference(
                        name = stringResource(page.title),
                        description = stringResource(page.summary),
                        onClick = { onOpenPage(page.id) },
                    ) { NextScreenIcon() }
            }
        }
    }
}

/** One page of the manual, drawn from the Markdown of its asset by [ManualMarkdown]; links open other pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpPageScreen(pageId: String, onClickBack: () -> Unit, onOpenPage: (String) -> Unit) {
    val ctx = LocalContext.current
    val phoneLanguage = LocalConfiguration.current.locales[0].language
    val blocks = remember(pageId, phoneLanguage) {
        val language = ManualPages.languageFor(phoneLanguage, ManualPages.languages(ctx.assets))
        val text = ManualPages.read(ctx.assets, language, pageId) ?: ManualPages.read(ctx.assets, ManualPages.FALLBACK_LANGUAGE, pageId)
        text?.let { ManualMarkdown.parse(it) }
    }
    val page = ManualPages.byId(pageId)
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TopAppBar(
                title = { Text(if (page != null) stringResource(page.title) else stringResource(R.string.help_title)) },
                windowInsets = WindowInsets(0),
                navigationIcon = { BackButton { onClickBack() } },
            )
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (blocks == null) Text(stringResource(R.string.help_page_missing))
                else for (block in blocks) ManualBlock(block, onOpenPage)
            }
        }
    }
}

@Composable
private fun ManualBlock(block: Block, onOpenPage: (String) -> Unit) {
    when (block) {
        is Block.Heading -> {
            val title = block.key?.let { ManualTitles[it] }
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            }
            Column(Modifier.padding(top = if (block.level == 3) 8.dp else 12.dp)) {
                if (title != null) {
                    Text(stringResource(title.title), style = style, fontWeight = FontWeight.Bold)
                    title.subtitle?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                } else {
                    // an entry whose key has no title shows the key, a heading shows its words: nothing is dropped
                    Text(inlineText(if (block.spans.isEmpty() && block.key != null) listOf(Span.Text(block.key)) else block.spans, onOpenPage), style = style, fontWeight = FontWeight.Bold)
                }
            }
        }
        is Block.Paragraph -> Text(inlineText(block.spans, onOpenPage), style = MaterialTheme.typography.bodyMedium)
        is Block.Bullets -> block.items.forEach { ListItem("•", it, onOpenPage) }
        is Block.Numbered -> block.items.forEachIndexed { index, item -> ListItem("${index + 1}.", item, onOpenPage) }
        is Block.Code -> CodeBox(AnnotatedString(block.text))
        is Block.Example -> ExampleBox(block)
    }
}

@Composable
private fun ListItem(marker: String, spans: List<Span>, onOpenPage: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(marker, style = MaterialTheme.typography.bodyMedium)
        Text(inlineText(spans, onOpenPage), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CodeBox(text: AnnotatedString) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()).padding(8.dp),
    )
}

/** An example: the text before, the keys and the text after, each in its own box; the cursor mark `|` stands out. */
@Composable
private fun ExampleBox(e: Block.Example) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.help_example_before), style = MaterialTheme.typography.labelMedium)
        CodeBox(markedText(e.before))
        Text(stringResource(R.string.help_example_keys), style = MaterialTheme.typography.labelMedium)
        CodeBox(AnnotatedString(e.keys))
        Text(stringResource(R.string.help_example_after), style = MaterialTheme.typography.labelMedium)
        CodeBox(markedText(e.after))
    }
}

@Composable
private fun markedText(text: String): AnnotatedString {
    val mark = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    return buildAnnotatedString {
        for (c in text) if (c == '|') withStyle(mark) { append(c) } else append(c)
    }
}

@Composable
private fun inlineText(spans: List<Span>, onOpenPage: (String) -> Unit): AnnotatedString {
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceVariant)
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    fun AnnotatedString.Builder.add(span: Span) {
        when (span) {
            is Span.Text -> append(span.text)
            is Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { span.children.forEach { add(it) } }
            is Span.Code -> withStyle(code) { append(span.text) }
            is Span.Link -> withLink(LinkAnnotation.Clickable(span.page, link) { onOpenPage(span.page) }) { append(span.text) }
        }
    }
    return buildAnnotatedString { spans.forEach { add(it) } }
}
