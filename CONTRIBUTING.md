# Contributing to BT Bridge Android Agent

Thanks for your interest in contributing! The BT Bridge Android Agent is the on-device app that
connects to the [BT Bridge Broker](https://github.com/ColdBoreBallistics/bt-bridge-broker) over
TCP, performs the actual BLE operations (scan, connect, read, write, subscribe), and renders device
data using templates pushed from the broker.

This guide assumes no prior experience with this project.

## Table of contents

1. [Project layout](#1-project-layout)
2. [Development setup](#2-development-setup)
3. [Building and running](#3-building-and-running)
4. [Tests](#4-tests)
5. [Coding standards](#5-coding-standards)
6. [Commit and PR conventions](#6-commit-and-pr-conventions)
7. [Templates are a separate repo](#7-templates-are-a-separate-repo)
8. [Code of Conduct & licensing](#8-code-of-conduct--licensing)

---

## 1. Project layout

```
app/src/main/java/com/coldboreballisticsllc/btbridge/
  MainActivity.kt        Compose entry point
  MainViewModel.kt       app state, command routing, template handling
  BleManager.kt          Android BLE operations
  TcpClient.kt           broker TCP connection
  Protocol.kt            command parsing + event builders (wire format)
  template/
    TemplateStore.kt     on-device template persistence
    TemplateRenderer.kt  parse BLE bytes via a display template
    GattAnalyser.kt      raw hex fallback renderer
    RenderedField.kt     render result data classes
  ui/MainScreen.kt       Compose UI
app/src/test/            JVM unit tests (JUnit + org.json)
```

## 2. Development setup

Requires the Android SDK and JDK 11+. Build with the Gradle wrapper (`./gradlew`) — do not install
a separate Gradle.

```bash
git clone git@github.com:ColdBoreBallistics/bt-bridge-agent-android.git
cd bt-bridge-agent-android
./gradlew :app:assembleDebug
```

Open the project in Android Studio for the full IDE experience, or build from the command line.

## 3. Building and running

```bash
./gradlew assembleDebug
# Debug APK: app/build/outputs/apk/debug/app-debug.apk
```

Install on a device (BLE requires real hardware — the emulator has no Bluetooth):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The agent connects to the broker's **agent TCP port (2653)** by default. Point it at your broker's
IP in the app's connection field.

## 4. Tests

This project uses **test-driven development** for the template runtime and protocol logic. JVM unit
tests live in `app/src/test/` and use JUnit 4 with the real `org.json` library.

```bash
./gradlew :app:testDebugUnitTest
```

All tests must pass before a PR is merged. BLE and UI behavior that can't be unit-tested should be
described in the PR and verified manually on a device.

## 5. Coding standards

- **Kotlin idioms** — null-safety with `?`/`?:`, data classes for value types, sealed classes for
  command/event hierarchies.
- **Keep `Protocol.kt` wire-compatible** with the broker. Additive changes only; coordinate any
  protocol change with the broker repo and `PROTOCOL.md`.
- **No unguarded debug output.** Diagnostic `Log.d`/`println`/`System.out` in non-test code must be
  wrapped in `if (BuildConfig.DEBUG)`. The in-app UI log (`addLog`) and the TCP `println` (which
  writes protocol JSON to the socket) are **not** debug output — do not guard or remove them.
- **No secrets** in code or resources.

## 6. Commit and PR conventions

- **Conventional Commits**: `type(scope): subject` (e.g. `feat(template): add bitmask renderer`).
  Types: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`, `ci`, `build`.
- One focused change per PR. Reference any related issue.
- CI must be green (build + unit tests) before merge.

## 7. Templates are a separate repo

Device/display/codec/component **templates** live in
[`bt-bridge-templates`](https://github.com/ColdBoreBallistics/bt-bridge-templates), not here. The
agent receives templates from the broker at runtime and renders with them. To contribute a
*template* (no coding required), see that repo's `CONTRIBUTING.md`.

## 8. Versioning & releases

This project follows [Semantic Versioning](https://semver.org/); see [`docs/VERSIONING.md`](docs/VERSIONING.md)
for the bump rules, tag format (`v<version>`), and release flow. User-facing changes go in
[`CHANGELOG.md`](CHANGELOG.md) (Keep a Changelog format) under `Unreleased`. To report a security
issue, see [`SECURITY.md`](SECURITY.md) — do not open a public issue.

## 9. Code of Conduct & licensing

By contributing you agree your contribution is licensed under [Apache-2.0](LICENSE) and that you
will abide by our [Code of Conduct](CODE_OF_CONDUCT.md).
