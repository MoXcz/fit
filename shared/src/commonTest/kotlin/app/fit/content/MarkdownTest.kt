// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    private fun parse(text: String) = Markdown.parse(text.trimIndent())

    private fun issues(text: String) = parse(text).issues

    private fun plain(text: String) = listOf(MdSpan(text))

    @Test
    fun `headings are ## and ###`() {
        val doc = parse("## Setup\n### Feet")
        assertEquals(listOf(MdBlock.Heading(2, plain("Setup")), MdBlock.Heading(3, plain("Feet"))), doc.blocks)
        assertEquals(emptyList(), doc.issues)
    }

    @Test
    fun `a paragraph joins its lines, and a blank line starts the next`() {
        val doc = parse("For stiff wrists after typing, or as a warm-up before\npushing and pressing.\n\nStop if it hurts.")
        assertEquals(
            listOf(
                MdBlock.Paragraph(plain("For stiff wrists after typing, or as a warm-up before pushing and pressing.")),
                MdBlock.Paragraph(plain("Stop if it hurts.")),
            ),
            doc.blocks,
        )
    }

    @Test
    fun `bullet items take indented and unindented continuation lines`() {
        val doc = parse("Stop if you have:\n\n- sharp or severe pain;\n- numbness or\n  tingling;\n* swelling\nthat stays.")
        assertEquals(
            listOf(
                MdBlock.Paragraph(plain("Stop if you have:")),
                MdBlock.Bullets(listOf(plain("sharp or severe pain;"), plain("numbness or tingling;"), plain("swelling that stays."))),
            ),
            doc.blocks,
        )
        assertEquals(emptyList(), doc.issues)
    }

    @Test
    fun `numbered lists keep their first number, and a blank line between items keeps the list`() {
        val doc = parse("3. Wall\n\n4. Incline,\n   then a bench.\n\nThen floor.")
        assertEquals(
            listOf(
                MdBlock.Numbered(3, listOf(plain("Wall"), plain("Incline, then a bench."))),
                MdBlock.Paragraph(plain("Then floor.")),
            ),
            doc.blocks,
        )
    }

    @Test
    fun `a list right after a paragraph starts a new block`() {
        val doc = parse("Cues:\n- Push the floor away.")
        assertEquals(listOf(MdBlock.Paragraph(plain("Cues:")), MdBlock.Bullets(listOf(plain("Push the floor away.")))), doc.blocks)
    }

    @Test
    fun `bold, italics and links become styled runs`() {
        val doc = parse("**Stop if** it *hurts*, _really_. See [the NHS](https://www.nhs.uk/).")
        assertEquals(
            listOf(
                MdBlock.Paragraph(
                    listOf(
                        MdSpan("Stop if", bold = true),
                        MdSpan(" it "),
                        MdSpan("hurts", italic = true),
                        MdSpan(", "),
                        MdSpan("really", italic = true),
                        MdSpan(". See "),
                        MdSpan("the NHS", url = "https://www.nhs.uk/"),
                        MdSpan("."),
                    ),
                ),
            ),
            doc.blocks,
        )
        assertEquals(emptyList(), doc.issues)
    }

    @Test
    fun `emphasis nests once inside the other kind, and inside a link`() {
        val doc = parse("**Lower *slowly* now** and ***both***. [**Bold link**](https://e3rehab.com/)")
        assertEquals(
            listOf(
                MdBlock.Paragraph(
                    listOf(
                        MdSpan("Lower ", bold = true),
                        MdSpan("slowly", bold = true, italic = true),
                        MdSpan(" now", bold = true),
                        MdSpan(" and "),
                        MdSpan("both", bold = true, italic = true),
                        MdSpan(". "),
                        MdSpan("Bold link", bold = true, url = "https://e3rehab.com/"),
                    ),
                ),
            ),
            doc.blocks,
        )
        assertEquals(emptyList(), doc.issues)
    }

    @Test
    fun `bold can't nest in bold, nor italics in italics`() {
        assertEquals(listOf(MarkdownIssue(1, "bold can't be inside bold")), issues("**a **b** c**"))
        assertEquals(listOf(MarkdownIssue(1, "italics can't be inside italics")), issues("*a *b* c*"))
    }

    @Test
    fun `lists can't be nested`() {
        assertEquals(listOf(MarkdownIssue(2, "lists can't be nested")), issues("- Wall\n  - Counter"))
    }

    @Test
    fun `stars and underscores that open or close nothing are plain text`() {
        val doc = parse("3 * 5 reps, snake_case_name and https://example.com/a_b")
        assertEquals(listOf(MdBlock.Paragraph(plain("3 * 5 reps, snake_case_name and https://example.com/a_b"))), doc.blocks)
        assertEquals(emptyList(), doc.issues)
    }

    @Test
    fun `a backslash escapes a marker`() {
        assertEquals(listOf(MdBlock.Paragraph(plain("*not italic* [x]"))), parse("""\*not italic\* \[x\]""").blocks)
    }

    @Test
    fun `an unclosed marker is an issue at the line it opens on`() {
        assertEquals(listOf(MarkdownIssue(2, "** isn't closed")), issues("Fine.\n**Stop if\nyou feel pain."))
        assertEquals(listOf(MarkdownIssue(1, "* or _ isn't closed")), issues("*lower slowly"))
    }

    @Test
    fun `an image on a line of its own is a block with its line`() {
        val doc = Markdown.parse("Bottom:\n\n![Bottom position](images/pistol-bottom.webp)\n- Next", firstLine = 30)
        assertEquals(
            listOf(
                MdBlock.Paragraph(plain("Bottom:")),
                MdBlock.Image("Bottom position", "images/pistol-bottom.webp", 32),
                MdBlock.Bullets(listOf(plain("Next"))),
            ),
            doc.blocks,
        )
        assertEquals(listOf(doc.blocks[1]), doc.images)
    }

    @Test
    fun `an image must come from images, and goes on a line of its own`() {
        assertEquals(listOf(MarkdownIssue(1, "an image must be a path such as images/name.webp")), issues("![x](https://e.com/x.png)"))
        assertEquals(listOf(MarkdownIssue(1, "an image goes on a line of its own")), issues("See ![x](images/x.webp) here."))
    }

    @Test
    fun `a link must be a web address`() {
        assertEquals(listOf(MarkdownIssue(1, "a link must be a web address (https://…)")), issues("[the plank](exercises/plank.md)"))
    }

    @Test
    fun `brackets that aren't a link stay text`() {
        assertEquals(listOf(MdBlock.Paragraph(plain("Knee push-up [optional] (easier)"))), parse("Knee push-up [optional] (easier)").blocks)
    }

    @Test
    fun `everything outside the subset is an issue at its line`() {
        val text =
            """
            # Title
            #### Deep
            > a quote
            ```
            ---
            Underlined
            ===
            | a | b |

                indented code

            Use `code`, ~~struck~~ and <br> here.
            """
        assertEquals(
            listOf(
                MarkdownIssue(1, "a heading must be ## or ###"),
                MarkdownIssue(2, "a heading must be ## or ###"),
                MarkdownIssue(3, "quotes aren't supported"),
                MarkdownIssue(4, "code blocks aren't supported"),
                MarkdownIssue(5, "horizontal rules aren't supported"),
                MarkdownIssue(7, "underlined headings aren't supported; use ## or ###"),
                MarkdownIssue(8, "tables aren't supported"),
                MarkdownIssue(10, "a line can't start indented by 4 or more spaces (that's code)"),
                MarkdownIssue(12, "code isn't supported"),
                MarkdownIssue(12, "strikethrough isn't supported"),
                MarkdownIssue(12, "HTML isn't supported"),
            ),
            issues(text),
        )
    }

    @Test
    fun `a second paragraph in a list item is an issue`() {
        assertEquals(
            listOf(MarkdownIssue(3, "a list item has one paragraph; join its lines or leave out the indent")),
            issues("- Wall\n\n  More about the wall."),
        )
    }

    @Test
    fun `a link's address may hold balanced parentheses`() {
        val doc = parse("See [Push-up](https://en.wikipedia.org/wiki/Push-up_(exercise)) now.")
        assertEquals(
            listOf(
                MdBlock.Paragraph(
                    listOf(MdSpan("See "), MdSpan("Push-up", url = "https://en.wikipedia.org/wiki/Push-up_(exercise)"), MdSpan(" now.")),
                ),
            ),
            doc.blocks,
        )
        assertEquals(emptyList(), doc.issues)
    }

    @Test
    fun `text followed by an address that can't be read is an issue, not plain text`() {
        val form = "a link is written [text](https://…), with no spaces or title in the address"
        assertEquals(listOf(MarkdownIssue(1, form)), issues("""[the guide](https://e.com/a "Title")"""))
        assertEquals(listOf(MarkdownIssue(2, form)), issues("Read\n[the guide](https://e.com/a(b)"))
        assertEquals(
            listOf(MarkdownIssue(1, "an image is written ![what it shows](images/name.webp)")),
            issues("![Top position](images/top position.webp)"),
        )
    }

    @Test
    fun `an escaped bracket doesn't end a link's text`() {
        val doc = parse("""[a \] b](https://e.com/)""")
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdSpan("a ] b", url = "https://e.com/")))), doc.blocks)
        assertEquals(emptyList(), doc.issues)
    }

    @Test
    fun `a link needs text`() {
        assertEquals(listOf(MarkdownIssue(1, "a link needs text")), issues("See [](https://e.com/) and [ ](https://e.com/)."))
    }

    @Test
    fun `a nested list after a blank line is an issue too`() {
        assertEquals(listOf(MarkdownIssue(3, "lists can't be nested")), issues("- a\n\n  - nested"))
    }

    @Test
    fun `a list indented as a whole is a list, and nesting counts from its first item`() {
        val doc = Markdown.parse("  - a\n  - b\n\n  - c")
        assertEquals(listOf(MdBlock.Bullets(listOf(plain("a"), plain("b"), plain("c")))), doc.blocks)
        assertEquals(emptyList(), doc.issues)
        assertEquals(listOf(MarkdownIssue(2, "lists can't be nested")), Markdown.parse("  1. a\n     - b").issues)
    }

    @Test
    fun `a table without outer pipes is an issue at its header and rule`() {
        assertEquals(
            listOf(MarkdownIssue(2, "tables aren't supported"), MarkdownIssue(3, "tables aren't supported")),
            issues("Loads:\na | b\n--- | ---\n1 | 2"),
        )
    }

    @Test
    fun `a quote inside a list item is an issue`() {
        assertEquals(listOf(MarkdownIssue(1, "quotes aren't supported")), issues("- > quoted"))
        assertEquals(listOf(MarkdownIssue(2, "quotes aren't supported")), issues("1. Wall\n   > quoted"))
    }

    @Test
    fun `a backslash at the end of a line is an issue`() {
        val detail = "a line can't end with a backslash (line breaks aren't supported)"
        assertEquals(listOf(MarkdownIssue(1, detail)), issues("Hold here\\\nthen lower."))
        assertEquals(listOf(MarkdownIssue(2, detail)), issues("Hold here\nthen lower.\\"))
    }

    @Test
    fun `bold and italics close with the marker that opened them`() {
        val detail = "bold and italics close with the marker that opened them"
        assertEquals(listOf(MarkdownIssue(1, detail)), issues("*lower slowly_"))
        assertEquals(listOf(MarkdownIssue(1, detail)), issues("__Stop if** it hurts"))
        assertEquals(emptyList(), issues("__Stop if__ it _really_ hurts"))
    }

    @Test
    fun `a heading needs text`() {
        val doc = parse("##\n\nText.")
        assertEquals(listOf(MarkdownIssue(1, "a heading needs text")), doc.issues)
        assertEquals(listOf(MdBlock.Paragraph(plain("Text."))), doc.blocks)
    }

    @Test
    fun `an exclamation mark before brackets that aren't an image is plain text`() {
        val doc = parse("wow![1] ok")
        assertEquals(listOf(MdBlock.Paragraph(plain("wow![1] ok"))), doc.blocks)
        assertEquals(emptyList(), doc.issues)
    }
}
