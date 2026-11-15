# AGENTS.md

Read this file before you touch code. It holds the rules; the details live in [`docs/`](docs/README.md).

## Project

**Fit** (package `app.fit`) is a minimalist workout app for Android: routines and a library of
exercises, a day's workout with sets, reps, weight, rest and timed holds, a guided workout one set at
a time, programs (weekday → routine), history, calendar and progress, plus **recovery sessions**
(5–15 minute follow-along stretches by body area) and **lessons** (notes for teaching yourself an
exercise, with a practice drill). Routines, recovery sessions and lessons can be written as Markdown
files ([content format](docs/content-format.md)), bundled from `content/` or imported on the phone.

Principles: one screen does one thing, few settings, no shame (no streak guilt, a skipped day is
fine), act first and offer Undo, workout screens spacious and editing screens compact. When scope is
unclear, ask the user.

Reference code (read-only, cloned beside this repo):

- **Hush** (`../hush`): the architecture, patterns, look and build Fit copies (KMP + Compose MP +
  Room 3 + Koin + Navigation 3). Upstream: https://github.com/MoXcz/hush
- **Tomato** (via Hush): the source of Hush's timer design and Expressive look.
  Upstream: https://github.com/nsh07/Tomato

## Rules for agents

1. **Stay minimal.** Don't add features, screens, settings, permissions or dependencies the user
   didn't ask for. If you think something is missing, tell the user. Don't build it.
2. **Work on the current version only.** Don't scaffold future features.
3. **Follow the [engineering guide](docs/engineering.md).** If a rule doesn't fit, don't quietly
   break it: change the rule there (with the reason) in the same change and say so.
4. **Test commonMain logic.** Every rule in the domain packages and the ViewModels gets unit tests in
   `commonTest`. Use fakes, not mocks. A change to logic without a test is not done.
5. **Content files are the contract** ([guide §3](docs/engineering.md#3-content-files-are-the-contract)).
   Built-in exercises, routines, recovery sessions and lessons are files in `content/` in the
   [content format](docs/content-format.md), never SQL seeds or Kotlin. The content check must pass.
6. **Comments:** keep them short, and only for a non-obvious *why*. Don't narrate code. KDoc goes on
   public interfaces (repositories, controllers, ports) and nowhere else by default.
7. **Verify before you finish:** run the [commands](docs/commands.md) that cover what you touched.
   They must pass. Say what you ran when you report back. UI changes are checked with JVM Compose UI
   tests and the screenshots they save ([guide §13](docs/engineering.md#13-testing)), not on the
   phone. A version ends with `scripts/install-android.sh --debug`; agents do no on-device testing.
   Android-only behaviour (service, notification, alarms, screen off, shortcuts, keep screen on) goes
   in a pending phone-check list in the dev-log entry, for the user. Why: driving the phone and
   waiting for real timers took most of each version's time.
8. **Don't commit** unless the user asks. Never add `local.properties`, signing keys or secrets.
9. **Licence:** the project is GPL-3.0-or-later ([guide §14](docs/engineering.md#14-licensing)). If
   you copy code from Hush or Tomato, keep the original copyright line in the file header and name
   the source in the commit message.
10. **Add a line to the [changelog](docs/changelog.md)** under "Unreleased" for every change a user
    would notice: one or two lines on what changed, not how. It isn't a work log. Releases follow
    semantic versioning.
11. **Docs:** `README.md` is for users only: what Fit does, installing it, and credits. Developer
    documentation lives in [`docs/`](docs/README.md). Keep this file to rules and pointers; put
    details in `docs/`. Never name or link local, uncommitted notes in committed files.

## Where things are

| Topic | File |
| --- | --- |
| Prerequisites, building, running, install script, signing | [docs/building.md](docs/building.md) |
| Every Gradle and adb command | [docs/commands.md](docs/commands.md) |
| Engineering guide: versions, modules, content contract, architecture, timers, Compose, Android, persistence, Koin, tests, licence | [docs/engineering.md](docs/engineering.md) |
| The Markdown format for exercises, routines, programs, recovery sessions and lessons | [docs/content-format.md](docs/content-format.md) |
| GitHub releases, signing secrets, CI | [docs/releasing.md](docs/releasing.md) |
| What changed in each release | [docs/changelog.md](docs/changelog.md) |

Before you finish: `./gradlew ktlintCheck check :androidApp:assembleDebug` (plus the commands for
what you touched) must pass.
Local, uncommitted context for this checkout: read `.private/README.md` before larger changes, and
add a detailed entry to `.private/dev-log.md` after finishing one (the public `docs/changelog.md`
only gets a short line).
