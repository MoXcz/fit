// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fit.exercises.ExerciseId
import app.fit.exercises.ui.ExercisesRoute
import app.fit.lessons.LessonId
import app.fit.lessons.ui.LessonsRoute
import app.fit.programs.ProgramId
import app.fit.programs.ui.ProgramsRoute
import app.fit.resources.Res
import app.fit.resources.exercises_title
import app.fit.resources.lessons_title
import app.fit.resources.programs_title
import app.fit.resources.routines_title
import app.fit.resources.train_title
import app.fit.routines.RoutineId
import app.fit.routines.ui.RoutinesRoute
import org.jetbrains.compose.resources.stringResource

enum class TrainTab { Routines, Exercises, Lessons, Programs }

// Opens on Routines; the switch keeps its place when a routine, exercise, lesson or program is opened
// and closed.
@Composable
fun TrainRoute(
    onOpenRoutine: (RoutineId) -> Unit,
    onOpenExercise: (ExerciseId) -> Unit,
    onOpenLesson: (LessonId) -> Unit,
    onOpenProgram: (ProgramId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(TrainTab.Routines) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        ScreenTopBar(title = { ScreenTitle(stringResource(Res.string.train_title)) })
        ConnectedChoice(
            options = TrainTab.entries,
            selected = tab,
            optionLabel = {
                stringResource(
                    when (it) {
                        TrainTab.Routines -> Res.string.routines_title
                        TrainTab.Exercises -> Res.string.exercises_title
                        TrainTab.Lessons -> Res.string.lessons_title
                        TrainTab.Programs -> Res.string.programs_title
                    },
                )
            },
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        when (tab) {
            TrainTab.Routines -> RoutinesRoute(onOpen = onOpenRoutine)
            TrainTab.Exercises -> ExercisesRoute(onOpen = onOpenExercise)
            TrainTab.Lessons -> LessonsRoute(onOpen = onOpenLesson)
            TrainTab.Programs -> ProgramsRoute(onOpen = onOpenProgram)
        }
    }
}
