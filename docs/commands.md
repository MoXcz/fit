# Build and verify

Prerequisites (JDK 21 and a **user-owned, writable** Android SDK with platform `android-37.0`,
build-tools `36.0.0`, platform-tools and an NDK), the install script and release signing are
described in [building.md](building.md). Always use the wrapper (`./gradlew`). There's no global
Gradle.

| What | Command |
| --- | --- |
| Fast logic, Room, Koin and content tests | `./gradlew :shared:jvmTest` |
| Shared tests on the Android host | `./gradlew :shared:testAndroidHostTest` |
| Only the UI tests (screenshots in `shared/build/ui-screenshots/`) | `./gradlew :shared:jvmTest --tests 'app.fit.ui.test.*'` |
| Android app unit tests (Koin verify, helpers) | `./gradlew :androidApp:testDebugUnitTest` (also in `check`) |
| Build the app | `./gradlew :androidApp:assembleDebug` |
| Android Lint | `./gradlew :androidApp:lintDebug` |
| Format check / auto-fix | `./gradlew ktlintCheck` / `./gradlew ktlintFormat` |
| Everything (run before you finish) | `./gradlew ktlintCheck check :androidApp:assembleDebug` |
| Release build (R8; signed if `keystore.properties` exists) | `./gradlew :androidApp:assembleRelease` |
| Install the debug app | `./gradlew :androidApp:installDebug` |
| Install the signed release | `ANDROID_SERIAL=<device> ./gradlew :androidApp:installRelease` |
| Install on a phone (device checks, crash check) | `scripts/install-android.sh [--debug] [--pair HOST:PORT CODE] [--connect HOST:PORT]` |
| Find a phone with wireless debugging | `adb mdns services` |
| Publish a release | push a `v<version>` tag; see [releasing.md](releasing.md) |
| CI on GitHub | `.github/workflows/ci.yml` runs `./gradlew ktlintCheck check :androidApp:assembleDebug` |

If a task name doesn't exist (KMP test task names change between AGP versions), list them with
`./gradlew :shared:tasks --group verification` and update this table.

**Debug and release** are separate installs (`app.fit.debug`, "Fit (debug)", and `app.fit`) with
separate data.

**Why the SDK must be user-owned:** AGP runs SDK binaries (`aidl`, the NDK's `llvm-strip`) as the
build user and downloads missing components. A root-owned SDK such as `/opt/android-sdk` breaks
both. Install it with `sdkmanager` as your user (e.g. `~/Android/Sdk`) or `chown -R` it.

**The Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) is Gradle's
own Apache-2.0 code. `gradle/wrapper/gradle-wrapper.properties` pins the distribution URL and its
`distributionSha256Sum`; update both together.
