# Building Fit

How to build, run and install Fit from source. [commands.md](commands.md) has the full command table.

## Prerequisites

- JDK 21. Gradle runs on it (bytecode targets 17).
- A **user-owned, writable** Android SDK, e.g. `~/Android/Sdk`, named in `local.properties`
  (`sdk.dir=/home/<you>/Android/Sdk`) or by `ANDROID_HOME`, with platform `android-37.0`,
  build-tools `36.0.0`, platform-tools and an NDK (for stripping native libraries).

## Build and run

```sh
./gradlew :androidApp:assembleDebug                      # debug APK
./gradlew :androidApp:installDebug                       # install it on a connected phone
./gradlew :androidApp:assembleRelease                    # release APK (R8; signed, see below)
./gradlew ktlintCheck check :androidApp:assembleDebug    # everything before a change is done
```

## Install script

```sh
scripts/install-android.sh                # signed release over adb, then start it
scripts/install-android.sh --debug        # the debug app (no keystore needed)
```

- `--pair HOST:PORT CODE`: pair with a phone's wireless debugging first.
- `--connect HOST:PORT`: connect to that phone over wireless debugging.

Without `--pair`/`--connect` it uses `ANDROID_SERIAL`, else the one device adb sees, else a phone that
advertises wireless debugging (`adb mdns services`). It starts Fit and reports a crash on start.

## Signing the release

`androidApp/build.gradle.kts` signs `release` with the key named in a git-ignored
`keystore.properties` at the repo root:

```properties
storeFile=/path/to/fit-release.jks
storePassword=...
keyAlias=fit
keyPassword=...
```

Without it the release build is still produced, unsigned; the install script refuses to install an
unsigned release. Keep the keystore outside the repo and **back it up**: an installed release can
only be updated with the same key, and losing it means uninstalling, which wipes the app's data.
