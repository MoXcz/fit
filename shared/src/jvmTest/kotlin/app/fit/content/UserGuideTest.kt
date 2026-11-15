// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.ui.USER_GUIDE_FILE
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserGuideTest {
    // The Help screen renders the guide with the content renderer, so it has to stay in its subset.
    @Test
    fun `the user guide is Markdown the app can render`() {
        val file = File("src/commonMain/composeResources/$USER_GUIDE_FILE")
        val doc = Markdown.parse(file.readText())
        assertEquals(emptyList(), doc.issues)
        assertTrue(doc.blocks.size > 20)
    }
}
