// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.fit.content.MdBlock
import app.fit.content.MdSpan
import app.fit.ui.theme.LocalAppFonts

// Renders the content format's Markdown subset. Links open in the browser (the platform's
// UriHandler); [images] holds the bytes of each image path, null while missing or unreadable.
@Composable
fun MarkdownText(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    images: Map<String, ByteArray?> = emptyMap(),
) {
    val typography = MaterialTheme.typography
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    Text(
                        annotated(block.spans),
                        style = if (block.level == 2) typography.titleLarge else typography.titleMedium,
                        fontFamily = LocalAppFonts.current.title,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                }

                is MdBlock.Paragraph -> {
                    Text(annotated(block.spans), style = typography.bodyLarge)
                }

                is MdBlock.Bullets -> {
                    ListItems(block.items) { "•" }
                }

                is MdBlock.Numbered -> {
                    ListItems(block.items) { "${block.start + it}." }
                }

                is MdBlock.Image -> {
                    images[block.path]?.let { ContentImage(it, block.alt) }
                }
            }
        }
    }
}

@Composable
private fun ListItems(
    items: List<List<MdSpan>>,
    marker: (Int) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { index, spans ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    marker(index),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 16.dp),
                )
                Text(annotated(spans), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun annotated(spans: List<MdSpan>): AnnotatedString {
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return remember(spans, link) {
        buildAnnotatedString {
            spans.forEach { span ->
                val style =
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                    )
                val url = span.url
                if (url != null) {
                    withLink(LinkAnnotation.Url(url, link)) { withStyle(style) { append(span.text) } }
                } else {
                    withStyle(style) { append(span.text) }
                }
            }
        }
    }
}

@Composable
private fun ContentImage(
    bytes: ByteArray,
    alt: String,
) {
    val bitmap: ImageBitmap =
        remember(bytes) { decodeImageOrNull(bytes) } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = alt.ifBlank { null },
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                .clip(RoundedCornerShape(20.dp)),
    )
}
