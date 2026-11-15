# Releasing

A version tag builds the app on GitHub Actions (`.github/workflows/release.yml`) and publishes a
GitHub release with:

- `fit-<version>-arm64-v8a.apk`, `-armeabi-v7a.apk`, `-x86_64.apk`: one APK per CPU type (almost
  every current phone is arm64-v8a).
- `fit-<version>-universal.apk`: works on every phone, twice the size.
- `SHA256SUMS`.

Before building, it runs the CI workflow (`.github/workflows/ci.yml`: `ktlintCheck check
:androidApp:assembleDebug`, the same as on every push to `main` and every pull request). Every APK
must be signed with the key whose certificate SHA-256 is `RELEASE_CERT_SHA256` at the top of
`release.yml`, or the run fails. Every file gets a build provenance attestation:

```sh
gh attestation verify fit-1.0.0-arm64-v8a.apk -R MoXcz/fit
```

R8's `mapping.txt` is kept as the `mapping` artifact of the workflow run (90 days).

## Actions

Every third-party action is pinned to a commit SHA, with the version in a comment. Dependabot
(`.github/dependabot.yml`) opens a pull request when an action has a new version that is at least two
weeks old.

## Version codes

Each APK gets `app-versionCode × 10 + CPU digit`: universal 0, armeabi-v7a 1, arm64-v8a 2, x86_64 3.
Raise `app-versionCode` by one for every release.

## One-time setup: the `release` environment

1. Settings → Environments → New environment `release`. Add yourself as a required reviewer; limit
   deployments to tags matching `v*`.
2. Add the environment secrets (from `keystore.properties` and the keystore file):

   | Secret | Value |
   | --- | --- |
   | `FIT_KEYSTORE_BASE64` | the keystore file, base64 (`base64 -w0 <keystore file>`) |
   | `FIT_KEYSTORE_PASSWORD` | `storePassword` |
   | `FIT_KEY_ALIAS` | `keyAlias` |
   | `FIT_KEY_PASSWORD` | `keyPassword` |

Don't keep repository-level copies of these secrets.
3. Set `RELEASE_CERT_SHA256` at the top of `release.yml` to the key's certificate digest
   (`apksigner verify --print-certs` on a signed release APK, the `SHA-256 digest` line). The
   release run fails while it's empty.

## Making a release

1. Raise `app-versionName` and `app-versionCode` in `gradle/libs.versions.toml`, rename "Unreleased"
   in the changelog, and commit.
2. Tag and push: `git tag v1.0.0 && git push origin main v1.0.0`. The workflow fails if the tag
   doesn't match `app-versionName`.
3. Approve the `release` deployment in the Actions tab; only then does the job get the key.
4. Check the release page once the workflow finishes.
