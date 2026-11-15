// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.backup

import app.fit.core.WeightUnit
import app.fit.settings.Settings
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// The backup reader is stricter than the JSON library it uses.
class BackupFormatTest {
    private val text =
        "{\"app\":\"fit\",\"format\":1,\"note\":\"Line\\none \\\"quoted\\\" é 日本\"," +
            "\"weight\":81.5,\"rows\":[{\"id\":1,\"x\":null}]}"

    @Test
    fun `backup json reads when it is exactly what the app writes`() {
        val root = assertNotNull(BackupFormat.read(text))
        assertEquals(JsonPrimitive(1), root["format"])
        assertEquals(JsonPrimitive("Line\none \"quoted\" é 日本"), root["note"])
    }

    @Test
    fun `a key given twice, text after the end, a number that isn't finite and anything rearranged are refused`() {
        listOf(
            text.replace("\"format\":1,", "\"format\":1,\"format\":2,"),
            "$text x",
            "$text$text",
            text.replace("81.5", "NaN"),
            text.replace("81.5", "Infinity"),
            text.replace("{\"id\":1", "{ \"id\":1"),
            text.dropLast(1),
            text.replace("\"app\"", "app"),
            "[1,2]",
            "",
        ).forEach { assertNull(BackupFormat.read(it), it) }
    }

    @Test
    fun `settings read back as written, and not with a key missing, unknown or of the wrong type`() {
        val settings = Settings(WeightUnit.Lb, barKg = 15.0, barLb = 35.0, alarm = false, keepScreenOn = false, autoAdvance = false)
        val json = BackupFormat.settings(settings)
        assertEquals(settings, BackupFormat.settings(json))

        fun changed(change: (String) -> String) = BackupFormat.settings(BackupFormat.read(change(json.toString())))
        assertEquals(settings, changed { it })
        assertNull(changed { it.replace("\"alarm\":false,", "") })
        assertNull(changed { it.replace("\"alarm\":false", "\"alarm\":false,\"voice\":true") })
        assertNull(changed { it.replace("\"alarm\":false", "\"alarm\":\"false\"") })
        assertNull(changed { it.replace("\"barKg\":15.0", "\"barKg\":1e999") })
        assertNull(changed { it.replace("\"weightUnit\":\"Lb\"", "\"weightUnit\":\"Stone\"") })
    }
}
