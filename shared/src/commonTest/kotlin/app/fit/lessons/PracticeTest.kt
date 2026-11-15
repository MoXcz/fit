// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.lessons

import app.fit.exercises.Sides
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedStep
import app.fit.guided.practicePlan
import app.fit.workout.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PracticeTest {
    private fun log(
        plan: Long?,
        kind: GuidedKind = GuidedKind.Practice,
        outcome: GuidedOutcome = GuidedOutcome.Completed,
        steps: Int = 3,
        at: Int = 0,
    ): GuidedLog {
        val start = TestClock.START + at.minutes
        return GuidedLog(plan?.let(::GuidedPlanId), kind, "Push-up", TestClock.TODAY, start, start + 5.minutes, outcome, steps)
    }

    @Test
    fun `every practice run with a step done counts, completed or stopped`() {
        val counts =
            Practice.counts(
                listOf(
                    log(10),
                    log(10, outcome = GuidedOutcome.Stopped, steps = 1, at = 1),
                    log(11, at = 2),
                ),
            )
        assertEquals(mapOf(GuidedPlanId(10) to 2, GuidedPlanId(11) to 1), counts)
    }

    @Test
    fun `recovery runs, runs with no step done and runs whose plan went never count`() {
        val counts =
            Practice.counts(
                listOf(
                    log(10, kind = GuidedKind.Recovery),
                    log(10, steps = 0, at = 1),
                    log(null, at = 2),
                ),
            )
        assertEquals(emptyMap(), counts)
    }

    @Test
    fun `the practice estimate follows the recovery rules - pauses before each step and round, 3 s a rep`() {
        // 20 + 40, then two rounds of 20 + 18: 136 s.
        assertEquals(136.seconds, Practice.estimate(practicePlan()))
        assertEquals(2, Practice.minutes(practicePlan()))
        // A step done on each side runs twice, with a 5 s switch between.
        val sides = practicePlan().copy(steps = listOf(GuidedStep("Side plank", duration = 30.seconds, sides = Sides.Each)))
        assertEquals(85.seconds, Practice.estimate(sides))
    }

    @Test
    fun `a short drill reads as at least a minute`() {
        val short = practicePlan().copy(transition = 0.seconds, steps = listOf(GuidedStep("Hold", duration = 10.seconds)))
        assertEquals(1, Practice.minutes(short))
    }
}
