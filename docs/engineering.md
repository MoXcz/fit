# Engineering guide

How Fit is built and why. Each section gives the rule, then the reasons. Fit is a sibling of
[Hush](https://github.com/MoXcz/hush) and uses its architecture and patterns; where Fit differs from
Hush on purpose, it says so.

## 1. Versions and the version catalog

- All versions live in `gradle/libs.versions.toml`; build files only use `libs.*` aliases. Plugins
  are declared once in the root `build.gradle.kts` with `apply false`. No `buildSrc` or convention
  plugins.
- Use stable versions released at least two weeks earlier (Dependabot's cooldown enforces the same
  for GitHub Actions). Check a version with Maven's `maven-metadata.xml` and the `.pom`'s
  `Last-Modified`; Google artifacts are at `https://dl.google.com/dl/android/maven2/`.
- Add a library to the catalog only with the change that first uses it.

Starting versions (the same as Hush 1.0.0): Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.21, KSP 2.3.12,
Compose Multiplatform 1.12.2, Material3 1.12.0-alpha03, lifecycle 2.11.0, Navigation 3 1.1.2,
coroutines 1.11.0, kotlinx-serialization 1.11.0, Room 3 3.0.3, sqlite-bundled 2.7.1, Koin BOM
4.2.2, DataStore 1.3.0, kotlinx-datetime 0.8.0, ktlint 1.8.0 (plugin 14.2.0). SDK levels:
`compileSdk = 37`, `targetSdk = 37`, `minSdk = 26`, `jvmTarget = 17`.

Why some versions are what they are:

- **Gradle 9.7.1, not 9.8:** AGP 9.4.1 prints `Configuration.setVisible()` deprecation warnings on
  Gradle 9.8.
- **Material3 alpha:** the Material 3 Expressive components (`MaterialExpressiveTheme`,
  `ButtonGroup`, shape-morphing buttons, `SegmentedListItem`, floating toolbar) aren't in a stable
  Material3. `1.12.0-alpha03` is the version CMP 1.12.2 maps; the expressive APIs are opted in once
  in `shared/build.gradle.kts`.
- **SDK 37:** Compose 1.12, lifecycle 2.11 and core 1.19 require `minCompileSdk=37`.
- **No detekt:** 1.23.x lags behind Kotlin 2.4 and 2.0 is alpha. Android Lint covers the
  Android-specific checks.

## 2. Modules and source sets

```
shared/       KMP library (com.android.kotlin.multiplatform.library): all logic and all UI
              (Compose MP), the content parser. Targets android and jvm.
androidApp/   the Android app: Application, MainActivity, SessionService, notifications, alarms,
              reminders, cues (sound, vibration), shortcuts, file import, Koin wiring
content/      built-in content as Markdown files (docs/content-format.md), bundled into the APK
```

Dependencies: `androidApp -> shared`. `shared` depends on nothing of ours.

Why:

- AGP 9's KMP plugin can't be combined with the application plugin, so the app entry point is its
  own module (as in Hush and Tomato).
- `jvm()` in `shared` gives fast host tests (`:shared:jvmTest`), runs the Room migration tests
  (`MigrationTestHelper` is instrumented-only on Android) and Koin `verify()`, and fails the build if
  Android types leak into commonMain. There is no desktop app (**differs from Hush**), but the target
  keeps one possible.
- Platform services live in `androidApp`, not `shared/androidMain`; `androidMain`/`jvmMain` only hold
  small actuals (§9).
- Compose tooling only on `androidRuntimeClasspath`. Compose resources live in
  `shared/src/commonMain/composeResources`; only `androidApp` has `res/`.

## 3. Content files are the contract

Exercises, routines, programs, recovery sessions and lessons can be written as Markdown files with a
YAML header ([content-format.md](content-format.md)). Built-in ones live in `content/` and ship in
the APK; the same files can be imported on the phone. Agents research and write these files; they
never write SQL seeds or Kotlin for content.

- One parser (`shared/.../content/`) reads every file into domain drafts and returns a sealed
  result with each problem's file, line and field. Nothing else parses content. It reads the YAML
  header as a node tree with line numbers (snakeyaml-engine-kmp) and maps it by hand, because a
  data-binding library can't report a field's line or reject unknown keys inside nested maps.
- Built-in content is copied into the APK's assets at build time and synced into the database on
  first start and whenever the bundled files change (a fingerprint in DataStore). The sync adds new
  items, updates ones the user never edited, and leaves edited ones alone.
- `content/` is checked by a jvmTest that parses every file and checks every reference (exercise
  ids, image paths, durations). A file that doesn't parse fails the build.
- `content/` is a small example set (one routine, one recovery session, one lesson and the
  exercises they use). The app-shell UI test adds a larger library from
  `shared/src/jvmTest/resources/content-fixture/`, so the screens have something to show; that
  folder never ships.
- Imported and built-in items keep their `id` (a slug), so re-importing a file updates the same item.
  Workout days and logs are snapshots, so changing content never rewrites history.
- An import (`ContentImporter`) runs the same parser and check over the picked file or pack together
  with what the app already holds, shows a preview, and writes everything in one transaction or
  nothing. It replaces an item by id even when it was changed in the app (the preview says so and the
  user confirms). Imported items count as the user's own: the built-in sync never overwrites or
  removes them, also when they took over a built-in id. A built-in routine, program, recovery
  session or lesson the user deleted stays deleted (`content_tombstone`) until it's imported again.
- The format is versioned (`format: 1`). A change that would make old files invalid is a new format
  version, read side by side with the old one.

Why: content is where agents add the most value and where mistakes are cheapest to catch before they
reach the phone. A plain text format can be reviewed in a diff, written without the app, and checked
by tests. This is the rule we never bend, like Hush's Blocker boundary.

## 4. Packages and naming

Base package and `applicationId` `app.fit` (permanent); debug builds add `.debug` and install side by
side with their own data. Packages are by feature first: `core`, `exercises`, `routines`,
`programs`, `day` (a day's workout and its sets), `workout` (the guided workout: rest and holds),
`guided` (the follow-along player shared by warm-ups, recovery and lesson practice), `recovery`,
`lessons`, `history`, `stats`, `bodyweight`, `content` (format, parser, import), `backup` (export,
restore, wipes), `cues`,
`reminders`, `settings`, `data/db`, `data/prefs`, `ui`, `di`, each with a `ui/` subpackage for its
screens.

| Thing | Convention | Example |
| --- | --- | --- |
| Screen | stateless `<Feature>Screen` + `<Feature>Route` that wires the VM | `TodayScreen`, `TodayRoute` |
| UI state / intents | `@Immutable data class <Feature>UiState`, `sealed interface <Feature>Action` | `WorkoutAction.CompleteSet` |
| ViewModel | `<Feature>ViewModel` with one public `onAction()` | `WorkoutViewModel` |
| Repository | interface `XRepository`, implementation named by how it works | `RoomRoutineRepository` |
| Fakes | `Fake` prefix | `FakeRoutineRepository` |
| Flows | nouns, no `get…Stream` | `val routines: Flow<List<Routine>>` |
| Platform actuals | `File.android.kt`, `File.jvm.kt` | |
| Room entities | `<Name>Entity`, mapped to domain models in the repository | `SetEntryEntity` |
| Tests | backtick sentences | `` `rest keeps running when you move to the next exercise` `` |

## 5. Architecture

- **Data layer** (repositories, content import): one source of truth per kind of data; `Flow` for
  observed data, `suspend` for one-shot operations; entities mapped to domain models.
- **Domain logic** in plain Kotlin in commonMain (`WorkoutController`, `RestEngine`,
  `GuidedEngine`, `Progression`, `WarmupPlan`, `Plates`), no Compose, Android or Koin imports, no
  use-case classes.
- **UI layer:** one ViewModel per screen with one `uiState: StateFlow<XUiState>` and one
  `onAction(XAction)`. No `Context` in ViewModels. No one-off event channels: things the UI must
  react to are state it acknowledges (e.g. an undo offer the UI dismisses).

Why: the guided workout is driven by the UI, a foreground service and an alarm at once; one owner of
its state (`WorkoutController`, a Koin single) keeps them from drifting. Rest belongs to the running
session, not to the screen, so moving between exercises never cancels it (a bug in the old app).

## 6. Timers and time

- Store timestamps and derive the rest: `remaining = endsAt - now`. The once-a-second loop only
  republishes. Pause stores the remaining duration; resume sets a new end time.
- Inject `kotlin.time.Clock`; never call `Clock.System` in logic. Persist instants as epoch ms.
  Calendar logic (day keys, program weeks, streaks) uses `LocalDate` in the system time zone, never
  millisecond arithmetic (the old app was off by one after DST).
- The live state (current block and set, rest, hold, guided step) is a snapshot saved on every change
  through a `…StateStore` (DataStore keys) and restored at start, so a killed process resumes where it
  was. A hold that ended while the app was away is logged with the time it ran.
- Rest end, hold end and guided step end are one idempotent `expireIfDue()` under a `Mutex`, because
  the ticker, the alarm and app resume can all call it.
- A rest of 0 s means no rest. A timed set logs the time actually held, not the planned time.
- A workout and a guided session never time at once (`SessionTimers`): whichever starts or resumes a
  timer last wins, and the other gives way (a rest ends, a hold or a step pauses). Asking first would
  be a confirmation for something Undo-free and cheap to redo; a paused step loses nothing.
- A guided session left still (paused, or waiting for Done or Next) for 30 minutes ends itself as
  stopped, at the moment it was last touched; its deadline is a wakeup that only ends it, scheduled
  even with the alarm off. A warm-up belongs to today's unfinished workout and ends with it.

## 7. Kotlin practices

- No `GlobalScope` or ad hoc scopes: long-lived work runs in the injected `ApplicationScope`
  (`SupervisorJob() + dispatchers.default`, the only `CoroutineScope` in the graph); UI work in
  `viewModelScope`; the service in its own scope cancelled in `onDestroy`.
- Inject `AppDispatchers(io, default)`. Classes that block switch to `io` themselves (main-safe).
- Never swallow `CancellationException`: no `runCatching` around suspend calls; catch specific
  exceptions.
- Expected failures are sealed result types handled with exhaustive `when` (`SaveResult.NameTaken`,
  `ContentResult.Invalid`); no `kotlin.Result` in our APIs. Exceptions are for bugs.
- `val` and read-only collections in state; `kotlin.time.Duration` everywhere except storage;
  `@JvmInline value class` ids. `private` by default, no `explicitApi()`.
- Official code style, ktlint on every module (`build/` excluded), `.editorconfig` allows
  `@Composable` function names.

## 8. Compose practices

- **Hoist state:** `XRoute(viewModel = koinViewModel())` collects with
  `collectAsStateWithLifecycle()` and passes plain values to `XScreen(state, onAction, modifier)`.
  Every emitting composable takes `modifier: Modifier = Modifier`. Previews only use `XScreen`s.
- **Effects:** `LaunchedEffect` for keyed suspend work, `DisposableEffect` for anything to undo
  (keep-screen-on during a workout or guided session), `LifecycleResumeEffect` to re-check
  permissions on resume.
- **Navigation 3:** `@Serializable sealed interface Route : NavKey`, `rememberNavBackStack`,
  `NavDisplay` with the saveable-state and ViewModel-store entry decorators. Four top-level tabs:
  **Today, Train, Recover, More**; the floating toolbar shows on those four only (not in a mode such
  as Today's reorder), so every other destination is a sub-screen with Back. Android Back leaves a
  sheet or a mode before its screen, and the guided screens before the overview.
- **One top bar:** back (off the tabs), a title, and at most one action: an icon (Edit, Save) or ⋮
  with a menu. Actions a screen is for go in its body (a hero card's button, a connected button
  group, a FAB), not in the bar.
- **Look:** Hush's Material 3 Expressive components, motion, Manrope and floating toolbar, with
  Fit's own identity: one muted sage-green accent on near-neutral greys, all roles generated into
  `ui/theme/Color.kt` from one seed with Material Color Utilities (regenerate, don't hand-edit; the
  header records the seed and WCAG AA ratios in both themes). The accent only on the current primary
  action, progress and the selected tab. Light and dark follow the system; no dynamic colour.
- **Workout screens are spacious, editing screens are compact.** During a workout the dial (Hush's
  progress ring) holds the current set's load and reps, or the rest or hold countdown, with one
  primary action (**Complete set**, **Start timer**, **Skip rest**, **Next exercise**, **Finish
  workout**) in a connected group whose position never changes; everything else is in a sheet. Plans
  and editors use dense rows; menus and settings use segmented rows. Times read as m:ss. Tabular
  figures for timers and loads.
- **Motion lives in components:** page changes cross-fade with `defaultEffectsSpec()`; the floating
  toolbar sits outside `NavDisplay` and never moves on a page change; the navigation host is never
  padded (each route gets the bar padding, with that inset consumed, so a screen adds no
  `navigationBarsPadding` of its own). Use the theme's `motionScheme` specs; never hard-code
  durations.
- **Copy:** labels, units, short empty states, errors and the consequence of destructive actions.
  Explanations go to the user guide, not the screen. Act first and offer Undo instead of a
  confirmation, except for what can't be undone: replacing a populated day, importing over
  existing content, restoring a backup and the wipes. The Undo bar (`UndoSnackbar`) can be swiped
  away, which lets the action stand; a list it can sit over ends with `UndoBarSpace`, so its last row
  scrolls clear of the bar. The bar never sits over a control: it is stacked above a screen's bottom
  button, and in the focus view it is under the top bar while the sheet is closed.
- **Accessibility:** 48 dp touch targets, content descriptions on icon buttons, large text and
  reduced motion respected, readable muted text in both themes.

## 9. Expect/actual vs interfaces

Platform services (session service, wakeups, cues, reminders, shortcuts, the file picker and save
dialog `Documents`, zip files `Archives`) are interfaces in commonMain, implemented in `androidApp`
and bound in Koin. `expect`/`actual` only for
small `@Composable` effects (keep screen on), platform builder paths (Room, DataStore), date
formatting and constants; never `expect class`. Why: interfaces can have real and fake
implementations at once.

## 10. Android specifics

**Session service.** A `specialUse` foreground service runs only while a workout or guided session is
running and shows one notification: a chronometer counting down the rest, hold or step, or counting
up the workout when nothing is timed, with "Next: …" naming what comes next. Two channels: the
ongoing session (low importance) and timer end (high, but silent: the app beeps and vibrates
itself, so the alarm setting covers both and the system never adds a second sound).
`POST_NOTIFICATIONS` is asked when the first rest or hold starts.

**Wakeups.** One `AlarmManager` scheduler behind `WakeupScheduler`, one `PendingIntent` per purpose:
rest, hold and step end are exact (`setExactAndAllowWhileIdle`, falling back to inexact). The alarm
only wakes the app; the decision is `expireIfDue()`. Not WorkManager.

**Cues.** Behind commonMain ports. `WorkoutAlerts` and `GuidedAlerts`: the old app's beep, two
880 Hz pips generated into an `AudioTrack` on the alarm stream (`ToneGenerator` only plays fixed
telephony tones), and a [180, 90, 180] ms vibration. The alarm setting turns sound and vibration off
everywhere, and with it off no end is scheduled.

**Reminders.** A recovery session can have a daily reminder at a local time (`reminder` table). What
is due, what shows and the next alarm are decided in commonMain (`Reminders.check()`, from the table
and the clock) at every start of the app, at every change of the table, and when the alarm fires.
One inexact `setAndAllowWhileIdle` alarm for the next reminder due, behind `ReminderAlarms`: nobody
waits for a reminder to the second, and exact alarms are kept for the timers. A day gets a reminder
once; none when the session was already done that day, is running, or the time passed over an hour
ago (the phone was off). The notification has its own channel, no actions and no badge; a tap starts
the session as its pinned shortcut would. Reminders are notifications the user asked for, so they
don't follow the alarm setting. `POST_NOTIFICATIONS` is asked when a reminder is first switched on.
A receiver for `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIMEZONE_CHANGED` and `TIME_SET`, enabled
only while a reminder is on, checks again (`RECEIVE_BOOT_COMPLETED`).

**Shortcuts.** A static launcher shortcut "Start workout" and dynamic shortcuts for pinned recovery
sessions (`ShortcutManagerCompat`), each an intent the app routes to the right destination.

**Import and backup.** The system file picker (`ACTION_OPEN_DOCUMENT`, any type, since providers
report `.md` files differently) and a share target (`ACTION_SEND` and `ACTION_VIEW` for Markdown, zip
and untyped files; not plain text, or Fit would be offered for every shared text) accept `.md` files
and `.zip` packs (Markdown plus images). A backup is saved through the system's save dialog
(`ACTION_CREATE_DOCUMENT`). No storage permission. The Android side only hands bytes over
(`AndroidDocuments`, `ZipArchives`, which counts sizes as it unpacks and wants the zip's end
record, and `BitmapImageProbe`, which says whether an image can be shown); every rule about paths,
layout, sizes and text is in commonMain (`ContentPacks`). Each activity registers the picker and
save launchers when it's created, so a result reaches the activity recreated while the dialog was
open; a shared file waits in `ImportInbox` under its own id for the import screen opened for it, so
a second activity (a share starts one in the sharing app's task) or another screen can't take it. Imported images are stored in the database
(`imported_image`), not in the files directory as first planned: an import, a restore and a wipe
then change the images in the same transaction as the items that use them, and a failed one leaves
none behind. An imported image is shown before a bundled one with the same name.

**Manifest.** No `INTERNET` permission: nothing goes to the network. Video and reference links open
in the browser.

## 11. Persistence

- **Room 3** in commonMain with `BundledSQLiteDriver` and `setQueryCoroutineContext(dispatchers.io)`;
  only the file path is platform code. Schemas are exported to `shared/schemas` and committed. Every
  version bump gets a migration and a jvmTest (`MigrationTestHelper`); never
  `fallbackToDestructiveMigration`. Migrations keep producing their own version's rows.
- **A workout day is a copy:** applying a routine copies its items and targets into the day's blocks
  and sets; editing a routine never rewrites history. Targets and what was done are separate
  columns (the old app overwrote targets with the reps done).
- **Content ids:** rows created from content keep the file's `id` (unique), so import updates them;
  rows created in the app have none until exported.
- **DataStore Preferences** for settings and the live session snapshot. Defaults live in `Settings()`.
- **Backups:** `allowBackup="false"`; data moves with the app's own export and restore
  (`Backups`). **Differs from Hush**, which has sync instead. A backup is one `.zip`: `backup.json`
  (format version, the schema version it was written from, the settings, the built-in content
  fingerprint, the list of images with their sizes, and every table as a list of rows keyed by
  column) and the imported images as `images/<name>`. The tables come from the database itself
  (`sqlite_master`), so a new table is covered without a change; a test fills every table and fails
  for one it doesn't fill.
  - **Reading** uses `kotlinx-serialization-json` (same version as `-core`), and is stricter than
    the library: `backup.json` must read back to exactly its own text, which refuses a key given
    twice (the library merges them), anything after the end and anything rearranged by hand; numbers
    must be finite. The images in the zip must be exactly the ones it lists, so a zip cut short is
    refused.
  - **Restore** checks every row against its table's columns, storage types and null-ness, and the
    text the app parses rather than falls back on (`BackupFormat.DATE_COLUMNS`; a test puts garbage
    in every text column and reads every screen's data, so a new such column fails it). It writes
    everything in one transaction with the foreign keys checked at its end, and is tried and rolled
    back once before the user is asked, so a file that's damaged, from a newer version or
    inconsistent changes nothing. The settings and a forgotten content fingerprint are written
    before the rows (and put back if the write fails); wipe everything clears the settings file
    before the rows. The process dying in between then leaves the backup's settings with its rows,
    and built-in content that syncs at the next start.
  - **Limits** are what a phone holds in memory: a picked file 32 MB, `backup.json` 8 MB, an image
    8 MB, 48 MB unpacked. Over them a file is "too large", not a crash; an export that restore
    wouldn't take says so when it's made.
  - Timer state is never in a backup; a running workout or session is ended before a restore or a
    wipe (`LiveSessions`), and Undo offers from before one are dropped (`DataResets`,
    `UndoSnackbar`): their rows and ids belong to data that's gone.
  - Format 1 accepts the schema it was written from (10, the first) up to the current one and
    refuses newer ones. **A later schema change must say in `BackupFormat` how rows of the earlier
    schemas are read** (a default for a new column, a rename); the committed schema-10 backup
    (`shared/src/jvmTest/resources/backups/schema-10/`) has a test that fails until it does.
    Schema 11 added the `reminder` table: a schema-10 backup restores with no reminders
    (`ADDED_TABLES`).

## 12. Dependency injection: Koin

- Classic DSL with constructor references (`singleOf`, `viewModelOf`); one module per feature in
  `shared/.../di/`, plus `androidModule` in `androidApp`.
- Constructor injection everywhere; `by inject()` only in Android framework classes Koin can't
  construct (service, receivers). `koinViewModel()` only in `XRoute`.
- `Module.verify()` tests (shared and Android) fail the build on a missing binding.

## 13. Testing

- Logic tests in `shared/src/commonTest` (run on the JVM and the Android host); Room, Koin and
  content tests in `jvmTest`; Android helpers in `androidApp/src/test`.
- **UI tests** in `shared/src/jvmTest/.../ui/test/`: Compose Multiplatform's `ui-test` runs the real
  routes with their real ViewModels over the fakes, headless on the JVM (here and on CI), at a phone's
  size (411 × 891 dp at 2.625) in the dark theme. Timers run on `TestClock`, so a 90 s rest takes no
  time. `screenshot("name")` saves the whole window, sheets included, to
  `shared/build/ui-screenshots/<name>.png` (under `build/`, never committed): one per screen and
  state worth looking at. They replace looking at the phone; only Android-only behaviour (service,
  notification, alarms, screen off, shortcuts, keep screen on) is left for the user to check on the
  device. Why on the JVM: no emulator, and driving the phone and waiting for real timers took most
  of each version's time.
- Fakes, not mocks (`FakeRoutineRepository`, `FakeWakeupScheduler`, `FakeCues` …), all in-memory.
- `runTest` with a `TestClock` tied to the test scheduler, so `advanceTimeBy` moves both delays and
  "now"; ViewModel tests set `Dispatchers.Main` to a test dispatcher.
- Every product rule gets a test named after it (progression, warm-up percentages, plates, stale
  finish, rest over-count, sides doubling a guided step, …). Every bug listed as "do not copy" from
  the old app gets a test that it doesn't happen.

## 14. Licensing

Fit reuses code from Hush, which derives from Tomato (GPL-3.0), so **the project is
GPL-3.0-or-later**, with the `LICENSE` file and an SPDX header
(`// SPDX-License-Identifier: GPL-3.0-or-later`) in each source file. Files adapted from Tomato (via
Hush) keep Nishant Mishra's copyright line. When you copy or adapt a file, keep the original
copyright line next to ours and name the source in the commit message. Manrope is OFL-1.1; Material
Symbols icons are Apache-2.0. Content written for Fit (exercises, sessions, lessons) is part of the
repo under the same licence; quote sources by link, don't copy their text or images. This is an
engineering note, not legal advice.
