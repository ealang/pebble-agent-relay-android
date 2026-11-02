# Releasing

The repo builds artifacts. Signing them is a separate, generic step done
afterwards with a key that is never checked in, never read by Gradle, and
not needed to build. Keep the two apart: anyone can reproduce the build;
only the key holder can produce an installable release.

## 1. Prerequisites

- The TDLib native libraries for all four ABIs under `app/src/main/jniLibs/`
  (gitignored). Build them once with `scripts/build-tdlib.sh`; see that
  script's header for the knobs. Release packs every ABI present, so make
  sure all four are there — a missing ABI silently ships a smaller app.
- `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` in `local.properties` or the
  environment. Without them the build still succeeds and the app reports
  that it has no credentials. See the README for where they come from.
- Gradle home somewhere persistent if the build machine is disposable:
  `export GRADLE_USER_HOME=/path/that/survives`.

## 2. Bump the version

In `app/build.gradle.kts`:

- `versionCode` — integer, strictly increasing, one per upload to Play.
  Never reuse one, even for a rejected upload.
- `versionName` — what people see (`0.2`, `1.0`, ...).

Commit the bump; tag it after the release is out (`git tag v0.2`).

## 3. Build

```bash
./gradlew clean
./gradlew lintRelease assembleRelease bundleRelease
```

Two invocations, not one: with `clean` in the same command line as the
other tasks Gradle's scheduler deadlocks ("Unable to make progress running
work").

Lint first: its `ExpiredTargetSdkVersion` check fails the build once
`targetSdk` falls below what Play currently accepts (Google raises the bar
every year, deadline 31 August), and `OldTargetApi` warns when a newer
target exists. When bumping `targetSdk`, read that Android version's
"behavior changes for apps targeting" page and test on a device or
emulator running it — the new behaviours only switch on there.

Outputs, all **unsigned**:

| Path | What |
|---|---|
| `app/build/outputs/apk/release/app-universal-release-unsigned.apk` | every ABI, ~100 MB — GitHub release, any phone |
| `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk` | one ABI each, ~30 MB — GitHub release |
| `app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk` | |
| `app/build/outputs/apk/release/app-x86_64-release-unsigned.apk` | |
| `app/build/outputs/apk/release/app-x86-release-unsigned.apk` | |
| `app/build/outputs/bundle/release/app-release.aab` | the Play upload |

Release builds split per ABI; debug builds do not (they use `DEV_ABIS`
instead). The split APKs share one `versionCode`, which is fine for
sideloading. Play only ever receives the bundle.

## 4. Sign

Standard Android tooling; nothing project-specific. `apksigner` and
`zipalign` ship with the SDK build-tools, `jarsigner` with the JDK.

**APKs** (each one you intend to publish):

```bash
BT=$ANDROID_HOME/build-tools/36.0.0

# Gradle already aligns; this just proves it (-P 16: 16 KB page alignment
# for the uncompressed native libs, which Play requires).
$BT/zipalign -c -P 16 4 app-arm64-v8a-release-unsigned.apk

$BT/apksigner sign \
  --ks release.keystore --ks-key-alias <alias> \
  --out agent-relay-0.2-arm64-v8a.apk \
  app-arm64-v8a-release-unsigned.apk

$BT/apksigner verify --print-certs agent-relay-0.2-arm64-v8a.apk
```

`apksigner` prompts for the passwords; `--ks-pass env:VAR` /
`--key-pass env:VAR` for scripting.

**The bundle** is a JAR-style signature, so it is `jarsigner`, not
`apksigner`:

```bash
jarsigner -keystore release.keystore -sigalg SHA256withRSA -digestalg SHA-256 \
  app-release.aab <alias>
```

If Play App Signing holds the app signing key, the bundle is signed with the
*upload* key and Play re-signs it; the APKs for GitHub must then be signed
with the *app signing* key (upload your own key to Play when enrolling so
both are the same key — otherwise Play installs and sideloads are different
identities and will not update over each other).

## 5. The key

One keystore, generated once, used for everything, backed up outside any
build machine with its passwords. Losing it means no update can ever
install over an existing copy.

```bash
keytool -genkeypair -v -keystore release.keystore -alias <alias> \
  -keyalg RSA -keysize 4096 -validity 10000
```

Nothing in the repo refers to it. `*.keystore` is gitignored as a backstop.

## 6. Publish

- **GitHub**: create a release for the tag, attach the signed APKs.
- **Play Console**: upload `app-release.aab` to the chosen track. New
  apps must target API 36 or higher (Play rule since 2026-08-31);
  `targetSdk` in `app/build.gradle.kts` is what Play checks.

## Installing a release over a debug build

The first release-signed install on a phone that has the debug build is an
uninstall first (different signing certificate). The Telegram login and any
queued messages are lost with it; log in again afterwards.
