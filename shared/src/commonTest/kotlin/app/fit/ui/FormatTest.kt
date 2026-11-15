// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FormatTest {
    @Test
    fun `times read as m_ss everywhere, ranges too`() {
        assertEquals("0:45", formatClock(45.seconds))
        assertEquals("0:00", formatClock(0.seconds))
        assertEquals("2:00", formatClock(2.minutes))
        assertEquals("1:30", formatClock(90.seconds))
        assertEquals("30:00", formatClock(30.minutes))
        assertEquals("1:30", formatDurationRange(90.seconds, null))
        assertEquals("0:30–0:45", formatDurationRange(30.seconds, 45.seconds))
        assertEquals("1:30–2:00", formatDurationRange(90.seconds, 2.minutes))
    }

    @Test
    fun `estimates read in whole minutes`() {
        assertEquals("49 min", formatEstimate(49.minutes))
        assertEquals("65 min", formatEstimate(65.minutes + 30.seconds))
    }

    @Test
    fun `a time field takes m_ss, h_mm_ss or plain seconds`() {
        assertEquals(30.minutes, parseClock("30:00"))
        assertEquals(90.seconds, parseClock("1:30"))
        assertEquals(90.seconds, parseClock(" 90 "))
        assertEquals(30.minutes, parseClock("1800"))
        assertEquals(3725.seconds, parseClock("1:02:05"))
        assertEquals(null, parseClock(""))
        assertEquals(null, parseClock("1:5"))
        assertEquals(null, parseClock("1:75"))
        assertEquals(null, parseClock("a:00"))
    }

    @Test
    fun `numbers drop trailing zeros`() {
        assertEquals("20", formatNumber(20.0))
        assertEquals("22.5", formatNumber(22.5))
        assertEquals("1.25", formatNumber(1.25))
        assertEquals("0", formatNumber(0.0))
    }

    @Test
    fun `rep ranges use an en dash`() {
        assertEquals("8–12", formatRange(8, 12))
        assertEquals("10", formatRange(10, null))
    }

    @Test
    fun `the clock reads minutes and seconds, and hours once there are any`() {
        assertEquals("0:05", formatClock(5.seconds))
        assertEquals("12:30", formatClock(750.seconds))
        assertEquals("1:02:05", formatClock(3725.seconds))
    }

    @Test
    fun `weights and distances read back as they were typed, with a point or a comma`() {
        assertEquals("62.5", formatNumber(62.5))
        assertEquals("60", formatNumber(60.0))
        assertEquals("102.5", formatNumber(102.5))
        assertEquals("142.5", formatNumber(142.5))
        assertEquals("317.5", formatNumber(317.5))
        assertEquals("10.25", formatNumber(10.25))
        assertEquals(62.5, parseDecimal("62.5"))
        assertEquals(62.5, parseDecimal(" 62,5 "))
        assertEquals(null, parseDecimal(""))
        assertEquals(null, parseDecimal("6x"))
    }

    @Test
    fun `a weight or distance field takes a comma or a point, and nothing that isn't a finite number`() {
        assertEquals(62.5, parseDecimal(" 62,5 "))
        assertEquals(62.5, parseDecimal("62.5"))
        listOf("", "abc", "NaN", "Infinity", "-Infinity", "1e999").forEach { assertNull(parseDecimal(it), it) }
    }
}
