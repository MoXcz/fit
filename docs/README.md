# Developer documentation

The [README](../README.md) at the root is for people who use Fit. This directory is for people who
work on it.

- [building.md](building.md): prerequisites, building and running, the install script and release
  signing.
- [commands.md](commands.md): every Gradle and adb command.
- [engineering.md](engineering.md): the engineering guide (versions, modules, the content contract,
  architecture, timers, Kotlin, Compose, Android, persistence, Koin, tests, licence).
- [content-format.md](content-format.md): the Markdown format for exercises, routines, programs,
  recovery sessions and lessons, and the rules for agents writing content.
- [releasing.md](releasing.md): GitHub releases, per-CPU APKs, signing secrets and CI.
- [changelog.md](changelog.md): what changed in each release.
- The [user guide](../shared/src/commonMain/composeResources/files/user-guide.md) ships in the app
  (More → Help), so it is written in the content format's Markdown subset; a test checks that.
- [AGENTS.md](../AGENTS.md): the rules for contributors and coding agents.
