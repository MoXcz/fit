// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

data class HistoryStats(
    // Days up to today with a done working set.
    val workouts: Int = 0,
    // Of those, the ones in the last 7 days, today included.
    val lastSevenDays: Int = 0,
    // Days in a row back from today, or from yesterday while today has nothing yet.
    val streak: Int = 0,
) {
    companion object {
        // Calendar days (LocalDate), never 24-hour steps, so a DST change can't skip or repeat one.
        fun of(
            // Days with a done working set (DayRepository.trainedDays).
            trained: Set<LocalDate>,
            today: LocalDate,
        ): HistoryStats {
            val yesterday = today.minus(1, DateTimeUnit.DAY)
            var cursor = if (today in trained) today else yesterday
            var streak = 0
            while (cursor in trained) {
                streak++
                cursor = cursor.minus(1, DateTimeUnit.DAY)
            }
            val weekStart = today.minus(6, DateTimeUnit.DAY)
            return HistoryStats(
                workouts = trained.count { it <= today },
                lastSevenDays = trained.count { it in weekStart..today },
                streak = streak,
            )
        }
    }
}
