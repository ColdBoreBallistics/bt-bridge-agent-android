# Versioning & Release Policy

The BT Bridge project follows [Semantic Versioning 2.0.0](https://semver.org/). This document is
the versioning policy for **`bt-bridge-agent-android`**; the canonical cross-project copy lives in
[`bt-bridge-broker/docs/VERSIONING.md`](https://github.com/ColdBoreBallistics/bt-bridge-broker/blob/main/docs/VERSIONING.md)
and this repo conforms to it.

## 1. Version format

`MAJOR.MINOR.PATCH`, optionally with a pre-release suffix (`-rc1`, `-beta1`).

| Component | Increment when |
|---|---|
| **MAJOR** | A backward-incompatible change to the agent wire protocol (`PROTOCOL.md` in `bt-bridge-broker`) or a breaking change to on-device behavior contracts. |
| **MINOR** | Backward-compatible new functionality (new template field-type support, new command/event handling, UI capability). |
| **PATCH** | Backward-compatible bug fixes and internal changes. |

### The 0.x pre-release phase

While the version is **`0.y.z`** the app is pre-1.0 and behavior is not frozen — a `0.MINOR` bump
may include breaking changes. **`1.0.0`** is the gate to a stable contract and (per project policy)
the transition from pre-production to production posture.

## 2. Version source of truth

The app version is **`versionName` in `app/build.gradle.kts`**. This is the SemVer string and must
equal the git release tag (without the `v` prefix).

`versionCode` in the same file is a **separate monotonic integer** for Play Store build ordering —
it is incremented on every store upload and is **not** the SemVer version. Do not conflate them.

## 3. Tags & releases

- **Tag format:** `v<version>` (e.g. `v0.9.0`), annotated, created only from `main`.
- A release = git tag + GitHub Release; release notes come from `CHANGELOG.md`.
- The release workflow attaches the debug APK (`BT_Bridge_<version>-<shortsha>_debug.apk`).
- Published tags are immutable — never move/delete; cut a new patch instead.

## 4. Branching & release flow

- `main` is always releasable (CI green).
- Work on short-lived topic branches (`feat/...`, `fix/...`) → reviewed PR → squash-merge to `main`.
- Release: bump `versionName`, finalize the `CHANGELOG.md` `[<version>]` section, merge to `main`,
  tag `v<version>`, push.
- The protocol is shared with `bt-bridge-broker`: a MAJOR/MINOR protocol change must be released in
  coordination so a deployed broker and agent stay compatible (see the canonical policy §7).

## 5. Release checklist

- [ ] `./gradlew :app:testDebugUnitTest` green (CI passing on `main`).
- [ ] `versionName` set to `<version>`; `versionCode` bumped for any store upload.
- [ ] `CHANGELOG.md` `[<version>]` section finalized.
- [ ] No unguarded debug output (`Log.d`/`println`/`System.out` not the functional TCP write); no secrets.
- [ ] (For a store release) signed release AAB built and smoke-tested.

---

*Conforms to the canonical BT Bridge versioning policy in `bt-bridge-broker/docs/VERSIONING.md`.*
