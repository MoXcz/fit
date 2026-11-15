// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.core

import kotlinx.datetime.TimeZone

/** The phone's time zone now, asked each time: it changes while the app runs (travel, a setting). */
fun interface ZoneSource {
    fun current(): TimeZone
}
