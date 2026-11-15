// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

// A run of text with one style; [url] makes it a link.
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val url: String? = null,
)

sealed interface MdBlock {
    // Level 2 or 3: the item's title is the only level 1.
    data class Heading(
        val level: Int,
        val spans: List<MdSpan>,
    ) : MdBlock

    data class Paragraph(
        val spans: List<MdSpan>,
    ) : MdBlock

    data class Bullets(
        val items: List<List<MdSpan>>,
    ) : MdBlock

    data class Numbered(
        val start: Int,
        val items: List<List<MdSpan>>,
    ) : MdBlock

    data class Image(
        val alt: String,
        val path: String,
        val line: Int,
    ) : MdBlock
}

// Something outside the subset, at its line in the file.
data class MarkdownIssue(
    val line: Int,
    val detail: String,
)

// The blocks are a best effort even with issues: the content check rejects those, the app shows these.
data class MarkdownDoc(
    val blocks: List<MdBlock>,
    val issues: List<MarkdownIssue> = emptyList(),
) {
    val images: List<MdBlock.Image> get() = blocks.filterIsInstance<MdBlock.Image>()
}

// The Markdown subset of docs/content-format.md; anything else is an issue.
object Markdown {
    fun parse(
        text: String,
        firstLine: Int = 1,
    ): MarkdownDoc = BlockParser(text.replace("\r\n", "\n").split('\n'), firstLine).parse()
}

private val HEADING = Regex("""(#+)(?:\s+(.*))?""")
private val BULLET = Regex("""[-*+]\s+(.*)""")
private val NUMBERED = Regex("""(\d{1,9})\.\s+(.*)""")
private val IMAGE_LINE = Regex("""!\[([^\]]*)]\(([^)\s]*)\)""")
private val RULE = Regex("""([-*_])(\s*\1){2,}""")
private val SETEXT = Regex("""=+""")
private val FENCE = Regex("""(```|~~~).*""")

// The row of dashes under a table's header, with or without the outer pipes.
private val TABLE_RULE = Regex("""\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?""")

private enum class ListKind { Bullets, Numbered }

private class BlockParser(
    private val lines: List<String>,
    private val firstLine: Int,
) {
    private val blocks = mutableListOf<MdBlock>()
    private val issues = mutableListOf<MarkdownIssue>()
    private var i = 0

    fun parse(): MarkdownDoc {
        while (i < lines.size) {
            if (lines[i].isBlank()) {
                i++
                continue
            }
            block()
        }
        return MarkdownDoc(blocks, issues.distinct().sortedBy { it.line })
    }

    private fun lineNo(index: Int) = firstLine + index

    private fun issue(
        index: Int,
        detail: String,
    ) {
        issues += MarkdownIssue(lineNo(index), detail)
    }

    private fun block() {
        val raw = lines[i]
        val text = raw.trim()
        if (indentOf(raw) >= 4) issue(i, "a line can't start indented by 4 or more spaces (that's code)")
        HEADING.matchEntire(text)?.let { match ->
            val level = match.groupValues[1].length
            val title = match.groupValues[2]
            if (level !in 2..3) issue(i, "a heading must be ## or ###")
            if (title.isBlank()) issue(i, "a heading needs text") else blocks += MdBlock.Heading(level.coerceIn(2, 3), inline(title, i))
            i++
            return
        }
        if (FENCE.matches(text)) {
            issue(i, "code blocks aren't supported")
            i++
            return
        }
        if (RULE.matches(text)) {
            issue(i, "horizontal rules aren't supported")
            i++
            return
        }
        if (SETEXT.matches(text)) {
            issue(i, "underlined headings aren't supported; use ## or ###")
            i++
            return
        }
        IMAGE_LINE.matchEntire(text)?.let { match ->
            val path = match.groupValues[2]
            if (!isImagePath(path)) issue(i, "an image must be a path such as images/name.webp")
            blocks += MdBlock.Image(match.groupValues[1].trim(), path, lineNo(i))
            i++
            return
        }
        listKind(text)?.let { kind ->
            list(kind)
            return
        }
        paragraph()
    }

    // A paragraph runs until a blank line or the start of another block.
    private fun paragraph() {
        val start = i
        val parts = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() && (i == start || !startsBlock(lines[i]))) {
            val text = lines[i].trim()
            unsupportedLine(i, text)
            if (i > start && TABLE_RULE.matches(text)) {
                issue(i - 1, TABLE)
                issue(i, TABLE)
            }
            parts += text
            i++
        }
        blocks += MdBlock.Paragraph(inline(parts.joinToString("\n"), start))
    }

    private fun unsupportedLine(
        index: Int,
        text: String,
    ) {
        when {
            text.startsWith(">") -> issue(index, "quotes aren't supported")
            text.startsWith("|") -> issue(index, TABLE)
        }
    }

    // Items of one kind; a line that isn't an item continues the one before. A blank line between
    // items keeps the list going. Indents count from the first item's, so a whole list may be indented.
    private fun list(kind: ListKind) {
        val nested = indentOf(lines[i]) + 2
        val items = mutableListOf<Pair<Int, MutableList<String>>>()
        var start = 1
        while (i < lines.size) {
            val raw = lines[i]
            val text = raw.trim()
            if (raw.isBlank()) {
                val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                val nextKind = listKind(lines[next].trim())
                val deeper = indentOf(lines[next]) >= nested
                when {
                    deeper && nextKind != null -> {
                        issue(next, NESTED)
                    }

                    deeper -> {
                        issue(next, "a list item has one paragraph; join its lines or leave out the indent")
                    }

                    nextKind == kind -> {
                        i = next
                        continue
                    }
                }
                break
            }
            val marker = listKind(text)
            when {
                marker != null && indentOf(raw) >= nested && items.isNotEmpty() -> {
                    issue(i, NESTED)
                    items.last().second += itemText(text)
                }

                marker == kind -> {
                    if (items.isEmpty() && kind == ListKind.Numbered) {
                        start = NUMBERED.matchEntire(text)!!.groupValues[1].toInt()
                    }
                    unsupportedLine(i, itemText(text))
                    items += i to mutableListOf(itemText(text))
                }

                marker != null || startsBlock(raw) -> {
                    break
                }

                else -> {
                    unsupportedLine(i, text)
                    items.last().second += text
                }
            }
            i++
        }
        val spans = items.map { (line, parts) -> inline(parts.joinToString("\n"), line) }
        blocks +=
            when (kind) {
                ListKind.Bullets -> MdBlock.Bullets(spans)
                ListKind.Numbered -> MdBlock.Numbered(start, spans)
            }
    }

    private fun startsBlock(raw: String): Boolean {
        val text = raw.trim()
        return HEADING.matches(text) ||
            FENCE.matches(text) ||
            RULE.matches(text) ||
            SETEXT.matches(text) ||
            IMAGE_LINE.matches(text) ||
            listKind(text) != null
    }

    private fun listKind(text: String): ListKind? =
        when {
            BULLET.matches(text) -> ListKind.Bullets
            NUMBERED.matches(text) -> ListKind.Numbered
            else -> null
        }

    private fun itemText(text: String): String = (BULLET.matchEntire(text) ?: NUMBERED.matchEntire(text))!!.groupValues.last()

    private fun inline(
        text: String,
        index: Int,
    ): List<MdSpan> = InlineParser(text, lineNo(index), issues).parse()

    private companion object {
        const val TABLE = "tables aren't supported"
        const val NESTED = "lists can't be nested"
    }
}

private fun indentOf(raw: String): Int {
    var width = 0
    for (c in raw) {
        when (c) {
            ' ' -> width++
            '\t' -> width += 4
            else -> return width
        }
    }
    return width
}

// Emphasis toggles on and off, so bold and italics nest once inside each other and never inside
// themselves; links hold text and emphasis, never another link.
private class InlineParser(
    private val text: String,
    private val firstLine: Int,
    private val issues: MutableList<MarkdownIssue>,
) {
    private val spans = mutableListOf<MdSpan>()
    private val buffer = StringBuilder()
    private var bold = false
    private var italic = false
    private var url: String? = null
    private var boldFrom = 0
    private var italicFrom = 0
    private var boldBy = '*'
    private var italicBy = '*'

    fun parse(): List<MdSpan> {
        run(0, text.length, inLink = false)
        flush()
        if (bold) issue(boldFrom, "** isn't closed")
        if (italic) issue(italicFrom, "* or _ isn't closed")
        return spans
    }

    private fun lineAt(offset: Int): Int = firstLine + text.substring(0, offset).count { it == '\n' }

    private fun issue(
        offset: Int,
        detail: String,
    ) {
        issues += MarkdownIssue(lineAt(offset), detail)
    }

    private fun run(
        from: Int,
        to: Int,
        inLink: Boolean,
    ) {
        var i = from
        while (i < to) {
            val c = text[i]
            val next = text.getOrNull(i + 1)?.takeIf { i + 1 < to }
            when {
                c == '\\' && next != null && next in ESCAPABLE -> {
                    buffer.append(next)
                    i += 2
                }

                c == '\\' && (next == null || next == '\n') -> {
                    issue(i, "a line can't end with a backslash (line breaks aren't supported)")
                    i++
                }

                c == '*' || c == '_' -> {
                    i = emphasis(i, to)
                }

                c == '!' && next == '[' -> {
                    val end = link(i + 1, to, image = true)
                    if (end != null) issue(i, "an image goes on a line of its own")
                    i = end ?: run {
                        buffer.append("![")
                        i + 2
                    }
                }

                c == '[' && !inLink -> {
                    i = link(i, to, image = false) ?: run {
                        buffer.append(c)
                        i + 1
                    }
                }

                else -> {
                    when {
                        c == '`' -> issue(i, "code isn't supported")
                        c == '~' && next == '~' -> issue(i, "strikethrough isn't supported")
                        c == '<' && next != null && (next.isLetter() || next == '/' || next == '!') -> issue(i, "HTML isn't supported")
                    }
                    buffer.append(if (c == '\n') ' ' else c)
                    i++
                }
            }
        }
    }

    // A run of * (or _): opens when followed by text, closes when it follows text. _ inside a word
    // is a plain underscore (snake_case, addresses).
    private fun emphasis(
        at: Int,
        to: Int,
    ): Int {
        val c = text[at]
        var end = at
        while (end < to && text[end] == c) end++
        val count = end - at
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(end)?.takeIf { end < to }
        val canOpen = after != null && !after.isWhitespace()
        val canClose = before != null && !before.isWhitespace() && at > 0
        val inWord = c == '_' && before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true
        if (inWord || count > 3 || (!canOpen && !canClose)) {
            buffer.append(text, at, end)
            return end
        }
        flush()
        if (count >= 2) {
            when {
                bold && canClose -> {
                    if (c != boldBy) issue(at, MISMATCHED)
                    bold = false
                }

                bold -> {
                    issue(at, "bold can't be inside bold")
                }

                canOpen -> {
                    bold = true
                    boldFrom = at
                    boldBy = c
                }

                else -> {
                    buffer.append(c).append(c)
                }
            }
        }
        if (count != 2) {
            when {
                italic && canClose -> {
                    if (c != italicBy) issue(at, MISMATCHED)
                    italic = false
                }

                italic -> {
                    issue(at, "italics can't be inside italics")
                }

                canOpen -> {
                    italic = true
                    italicFrom = at
                    italicBy = c
                }

                else -> {
                    buffer.append(c)
                }
            }
        }
        return end
    }

    // [text](url): returns where it ends, or null when it isn't one (the bracket is plain text). With
    // ]( after the text it was meant as one, so a target that can't be read is an issue. An image in
    // running text keeps only its alt text.
    private fun link(
        at: Int,
        to: Int,
        image: Boolean,
    ): Int? {
        val close = closingBracket(at + 1, to) ?: return null
        if (close + 1 >= to || text[close + 1] != '(') return null
        val end = closingParen(close + 2, to)
        val target = end?.let { text.substring(close + 2, it) }
        if (end == null || target == null || target.any { it.isWhitespace() }) {
            issue(at, if (image) IMAGE_FORM else LINK_FORM)
            return null
        }
        if (!image && !isWebUrl(target)) issue(at, "a link must be a web address (https://…)")
        if (!image && text.substring(at + 1, close).isBlank()) issue(at, "a link needs text")
        flush()
        url = target.takeIf { !image }
        run(at + 1, close, inLink = true)
        flush()
        url = null
        return end + 1
    }

    // The first ] that isn't escaped.
    private fun closingBracket(
        from: Int,
        to: Int,
    ): Int? {
        var i = from
        while (i < to) {
            when (text[i]) {
                '\\' -> i++
                ']' -> return i
            }
            i++
        }
        return null
    }

    // The ) that closes the target: an address may hold balanced parentheses.
    private fun closingParen(
        from: Int,
        to: Int,
    ): Int? {
        var depth = 0
        for (i in from until to) {
            when (text[i]) {
                '(' -> {
                    depth++
                }

                ')' -> {
                    if (depth == 0) return i
                    depth--
                }
            }
        }
        return null
    }

    private fun flush() {
        if (buffer.isEmpty()) return
        val span = MdSpan(buffer.toString(), bold, italic, url)
        val last = spans.lastOrNull()
        if (last != null && last.bold == span.bold && last.italic == span.italic && last.url == span.url) {
            spans[spans.lastIndex] = last.copy(text = last.text + span.text)
        } else {
            spans += span
        }
        buffer.clear()
    }

    private companion object {
        const val ESCAPABLE = "\\`*_{}[]()#+-.!<>|~"
        const val MISMATCHED = "bold and italics close with the marker that opened them"
        const val LINK_FORM = "a link is written [text](https://…), with no spaces or title in the address"
        const val IMAGE_FORM = "an image is written ![what it shows](images/name.webp)"
    }
}
