// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2025-2026 Nishant Mishra (Tomato, https://github.com/nsh07/Tomato): adapted from ui/AppScreen.kt.
package app.fit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.fit.backup.ui.DataRoute
import app.fit.bodyweight.ui.BodyWeightRoute
import app.fit.content.ui.ImportRoute
import app.fit.core.AppInfo
import app.fit.core.DataResets
import app.fit.day.BlockId
import app.fit.day.ui.TodayRoute
import app.fit.exercises.ExerciseId
import app.fit.exercises.ui.ExerciseEditRoute
import app.fit.exercises.ui.ExerciseRoute
import app.fit.guided.ui.PlayerRoute
import app.fit.history.ui.HistoryRoute
import app.fit.history.ui.SummaryRoute
import app.fit.lessons.LessonId
import app.fit.lessons.ui.LessonRoute
import app.fit.programs.ProgramId
import app.fit.programs.ui.ProgramEditorRoute
import app.fit.recovery.ui.RecoverRoute
import app.fit.recovery.ui.SessionRoute
import app.fit.resources.Res
import app.fit.resources.ic_fitness_center
import app.fit.resources.ic_fitness_center_filled
import app.fit.resources.ic_more_horiz
import app.fit.resources.ic_more_horiz_filled
import app.fit.resources.ic_self_improvement
import app.fit.resources.ic_self_improvement_filled
import app.fit.resources.ic_today
import app.fit.resources.ic_today_filled
import app.fit.resources.more_title
import app.fit.resources.recover_title
import app.fit.resources.today_title
import app.fit.resources.train_title
import app.fit.routines.RoutineId
import app.fit.routines.ui.RoutineEditorRoute
import app.fit.settings.ui.SettingsRoute
import app.fit.ui.navigation.Route
import app.fit.ui.navigation.isTopLevel
import app.fit.ui.navigation.openOrReturn
import app.fit.ui.navigation.rememberBackStack
import app.fit.ui.navigation.selectTopLevel
import app.fit.ui.navigation.topLevelRoutes
import app.fit.ui.theme.FitTheme
import app.fit.workout.ui.WorkoutRoute
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private data class TopLevelDestination(
    val route: Route,
    val icon: DrawableResource,
    val selectedIcon: DrawableResource,
    val label: StringResource,
)

// One per tab, in the order of topLevelRoutes (the one list of tabs).
private val topLevelDestinations =
    topLevelRoutes.map { route ->
        when (route) {
            Route.Today -> {
                TopLevelDestination(route, Res.drawable.ic_today, Res.drawable.ic_today_filled, Res.string.today_title)
            }

            Route.Train -> {
                TopLevelDestination(route, Res.drawable.ic_fitness_center, Res.drawable.ic_fitness_center_filled, Res.string.train_title)
            }

            Route.Recover -> {
                TopLevelDestination(
                    route,
                    Res.drawable.ic_self_improvement,
                    Res.drawable.ic_self_improvement_filled,
                    Res.string.recover_title,
                )
            }

            Route.More -> {
                TopLevelDestination(route, Res.drawable.ic_more_horiz, Res.drawable.ic_more_horiz_filled, Res.string.more_title)
            }

            else -> {
                error("$route isn't a tab")
            }
        }
    }

// What the app was opened for, from a launcher shortcut or a notification; the app acknowledges it.
sealed interface Launch {
    // Start or resume today's workout.
    data object StartWorkout : Launch

    // Show the guided session that's running.
    data object OpenPlayer : Launch

    // A file was shared with the app: show the import screen on it; [file] is its inbox id.
    data class OpenImport(
        val file: String,
    ) : Launch
}

@Composable
fun App(
    modifier: Modifier = Modifier,
    launch: Launch? = null,
    onLaunchSeen: () -> Unit = {},
) {
    val startWorkout = launch == Launch.StartWorkout
    val epoch by koinInject<DataResets>().epoch.collectAsStateWithLifecycle()
    FitTheme {
        CompositionLocalProvider(LocalDataEpoch provides epoch) { AppContent(modifier, launch, onLaunchSeen, startWorkout) }
    }
}

@Composable
private fun AppContent(
    modifier: Modifier,
    launch: Launch?,
    onLaunchSeen: () -> Unit,
    startWorkout: Boolean,
) {
    val backStack = rememberBackStack()
    // Sub-screens keep their tab highlighted.
    val currentTab = backStack.lastOrNull { key -> topLevelDestinations.any { it.route == key } }
    val motionScheme = MaterialTheme.motionScheme
    // Bumped by "Use today", so Today shows today when it opens; History sets the day to show.
    var showToday by rememberSaveable { mutableIntStateOf(0) }
    var showDate by rememberSaveable { mutableStateOf<String?>(null) }
    val showDay: (LocalDate?) -> Unit = { date ->
        showDate = date?.toString()
        showToday++
        backStack.selectTopLevel(Route.Today)
    }
    val openPlayer = { if (backStack.lastOrNull() != Route.Player) backStack.add(Route.Player) }
    // The workout shortcut starts from Today, whatever was open; the player opens over what's open.
    LaunchedEffect(launch) {
        when (launch) {
            Launch.StartWorkout -> {
                backStack.selectTopLevel(Route.Today)
            }

            Launch.OpenPlayer -> {
                openPlayer()
                onLaunchSeen()
            }

            // Its own import screen, also over one that's open: that one keeps its preview.
            is Launch.OpenImport -> {
                if (backStack.none { it is Route.Import }) backStack.selectTopLevel(Route.More)
                backStack.add(Route.Import(launch.file))
                onLaunchSeen()
            }

            null -> {}
        }
    }
    // Today's reorder mode counts as a sub-screen.
    var todayReorder by remember { mutableStateOf(false) }
    // The toolbar belongs to the four top-level screens only.
    val top = backStack.lastOrNull()
    val showBar = top.isTopLevel() && !(top == Route.Today && todayReorder)
    Scaffold(
        modifier = modifier,
        bottomBar = {
            // Outside NavDisplay, so page transitions never move or resize it (as Tomato does).
            AnimatedVisibility(
                visible = showBar,
                enter = fadeIn(motionScheme.defaultEffectsSpec()),
                exit = fadeOut(motionScheme.defaultEffectsSpec()),
            ) {
                BottomToolbar(currentTab = currentTab, onSelect = backStack::selectTopLevel)
            }
        },
    ) { padding ->
        // The navigation host fills the window and never resizes; each screen pads itself for the
        // bar, so a screen that hides the bar (the guided workout) doesn't make the others jump.
        // Consumed too, so a screen's own navigationBarsPadding or imePadding doesn't add it again.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        val screen = Modifier.padding(bottom).consumeWindowInsets(bottom)
        val fade = fadeIn(motionScheme.defaultEffectsSpec()) togetherWith fadeOut(motionScheme.defaultEffectsSpec())
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            transitionSpec = { fade },
            popTransitionSpec = { fade },
            predictivePopTransitionSpec = { fade },
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider =
                entryProvider {
                    entry<Route.Today> {
                        TodayRoute(
                            modifier = screen,
                            showToday = showToday,
                            showDate = showDate?.let(LocalDate::parse),
                            onOpenSummary = { date ->
                                if (backStack.lastOrNull() == Route.Today) backStack.add(Route.Summary(date.toString()))
                            },
                            startWorkout = startWorkout,
                            onStartWorkoutSeen = onLaunchSeen,
                            onOpenPlayer = { if (backStack.lastOrNull() == Route.Today) openPlayer() },
                            onReorder = { todayReorder = it },
                            onOpenBlock = { id ->
                                if (backStack.lastOrNull() ==
                                    Route.Today
                                ) {
                                    backStack.add(Route.Workout(id.value))
                                }
                            },
                        )
                    }
                    entry<Route.Workout> { key ->
                        WorkoutRoute(
                            id = BlockId(key.blockId),
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                            onFinished = { date ->
                                if (backStack.lastOrNull() == key) {
                                    backStack.removeLastOrNull()
                                    backStack.add(Route.Summary(date.toString()))
                                }
                            },
                        )
                    }
                    entry<Route.Train> {
                        TrainRoute(
                            modifier = screen,
                            onOpenRoutine = { id -> if (backStack.lastOrNull() == Route.Train) backStack.add(Route.Routine(id.value)) },
                            onOpenExercise = { id ->
                                if (backStack.lastOrNull() ==
                                    Route.Train
                                ) {
                                    backStack.add(Route.Exercise(id.value))
                                }
                            },
                            onOpenLesson = { id -> if (backStack.lastOrNull() == Route.Train) backStack.add(Route.Lesson(id.value)) },
                            onOpenProgram = { id -> if (backStack.lastOrNull() == Route.Train) backStack.add(Route.Program(id.value)) },
                        )
                    }
                    entry<Route.Program> { key ->
                        ProgramEditorRoute(
                            id = ProgramId(key.id),
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                        )
                    }
                    entry<Route.Routine> { key ->
                        RoutineEditorRoute(
                            id = RoutineId(key.id),
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                            onUsedToday = { showDay(null) },
                        )
                    }
                    entry<Route.Exercise> { key ->
                        ExerciseRoute(
                            id = ExerciseId(key.id),
                            modifier = screen,
                            onEdit = { if (backStack.lastOrNull() == key) backStack.add(Route.ExerciseEdit(key.id)) },
                            onOpenLesson = { id -> if (backStack.lastOrNull() == key) backStack.openOrReturn(Route.Lesson(id.value)) },
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                        )
                    }
                    entry<Route.Lesson> { key ->
                        LessonRoute(
                            id = LessonId(key.id),
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                            onOpenExercise = { id ->
                                if (backStack.lastOrNull() ==
                                    key
                                ) {
                                    backStack.openOrReturn(Route.Exercise(id.value))
                                }
                            },
                            onOpenPlayer = { if (backStack.lastOrNull() == key) openPlayer() },
                        )
                    }
                    entry<Route.ExerciseEdit> { key ->
                        ExerciseEditRoute(
                            id = ExerciseId(key.id),
                            modifier = screen,
                            onDone = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                        )
                    }
                    entry<Route.Recover> {
                        RecoverRoute(
                            modifier = screen,
                            onOpenSession = { id ->
                                if (backStack.lastOrNull() == Route.Recover) backStack.add(Route.RecoverySession(id.value))
                            },
                            onOpenPlayer = { if (backStack.lastOrNull() == Route.Recover) openPlayer() },
                        )
                    }
                    entry<Route.RecoverySession> { key ->
                        SessionRoute(
                            id = key.id,
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                            onOpenPlayer = { if (backStack.lastOrNull() == key) openPlayer() },
                        )
                    }
                    entry<Route.Player> {
                        PlayerRoute(
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == Route.Player) backStack.removeLastOrNull() },
                            onContinue = { id ->
                                if (backStack.lastOrNull() == Route.Player) {
                                    backStack.removeLastOrNull()
                                    backStack.add(Route.Workout(id.value))
                                }
                            },
                        )
                    }
                    entry<Route.More> {
                        MoreScreen(
                            modifier = screen,
                            onOpenHistory = { if (backStack.lastOrNull() == Route.More) backStack.add(Route.History) },
                            onOpenBodyWeight = { if (backStack.lastOrNull() == Route.More) backStack.add(Route.BodyWeight) },
                            onOpenSettings = { if (backStack.lastOrNull() == Route.More) backStack.add(Route.Settings) },
                            onOpenImport = { if (backStack.lastOrNull() == Route.More) backStack.add(Route.Import("")) },
                            onOpenData = { if (backStack.lastOrNull() == Route.More) backStack.add(Route.Data) },
                            onOpenHelp = { if (backStack.lastOrNull() == Route.More) backStack.add(Route.Help) },
                            onOpenAbout = { if (backStack.lastOrNull() == Route.More) backStack.add(Route.About) },
                        )
                    }
                    entry<Route.Help> {
                        HelpScreen(
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == Route.Help) backStack.removeLastOrNull() },
                        )
                    }
                    entry<Route.About> {
                        AboutScreen(
                            version = koinInject<AppInfo>().version,
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == Route.About) backStack.removeLastOrNull() },
                        )
                    }
                    entry<Route.Import> { key ->
                        ImportRoute(
                            share = key.file,
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                        )
                    }
                    entry<Route.Data> {
                        DataRoute(
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == Route.Data) backStack.removeLastOrNull() },
                        )
                    }
                    entry<Route.History> {
                        HistoryRoute(
                            modifier = screen,
                            onOpenDay = { if (backStack.lastOrNull() == Route.History) showDay(it) },
                            onOpenSummary = { date ->
                                if (backStack.lastOrNull() == Route.History) backStack.add(Route.Summary(date.toString()))
                            },
                            onBack = { if (backStack.lastOrNull() == Route.History) backStack.removeLastOrNull() },
                        )
                    }
                    entry<Route.Summary> { key ->
                        SummaryRoute(
                            date = LocalDate.parse(key.date),
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                        )
                    }
                    entry<Route.BodyWeight> {
                        BodyWeightRoute(
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == Route.BodyWeight) backStack.removeLastOrNull() },
                        )
                    }
                    entry<Route.Settings> {
                        SettingsRoute(
                            modifier = screen,
                            onBack = { if (backStack.lastOrNull() == Route.Settings) backStack.removeLastOrNull() },
                        )
                    }
                },
        )
    }
}

@Composable
private fun BottomToolbar(
    currentTab: NavKey?,
    onSelect: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Mirrors Tomato's toolbar (AppScreen.kt): the selected button grows its label with the theme's
    // default spatial spring; nothing else animates.
    val colors = MaterialTheme.colorScheme
    val motionScheme = MaterialTheme.motionScheme
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors =
                FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                    toolbarContainerColor = colors.primaryContainer,
                    toolbarContentColor = colors.onPrimaryContainer,
                ),
            modifier =
                Modifier
                    .navigationBarsPadding()
                    .padding(vertical = FloatingToolbarDefaults.ScreenOffset)
                    .zIndex(1f),
        ) {
            topLevelDestinations.forEach { destination ->
                val selected = currentTab == destination.route
                val label = stringResource(destination.label)
                ToggleButton(
                    checked = selected,
                    onCheckedChange = { if (!selected) onSelect(destination.route) },
                    colors =
                        ToggleButtonDefaults.toggleButtonColors(
                            containerColor = colors.primaryContainer,
                            contentColor = colors.onPrimaryContainer,
                            checkedContainerColor = colors.primary,
                            checkedContentColor = colors.onPrimary,
                        ),
                    shapes = ToggleButtonShapes(CircleShape, CircleShape, CircleShape),
                    modifier = Modifier.height(56.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(if (selected) destination.selectedIcon else destination.icon),
                            contentDescription = label,
                        )
                        AnimatedVisibility(
                            visible = selected,
                            enter = expandHorizontally(motionScheme.defaultSpatialSpec()),
                            exit = shrinkHorizontally(motionScheme.defaultSpatialSpec()),
                        ) {
                            Text(
                                text = label,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                            )
                        }
                    }
                }
            }
        }
    }
}
