# Changelog

All notable changes to `bt-bridge-agent-android` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/) (see [`docs/VERSIONING.md`](docs/VERSIONING.md)).

## [Unreleased]

### Changed
- Modernized the Android toolchain: AGP 9.1.1, Gradle 9.3.1, Kotlin 2.2.10 (AGP built-in),
  compileSdk/targetSdk 37, core-ktx 1.19.0, kotlinx-serialization 1.11.0, Java 17. Removed the
  standalone Kotlin Gradle plugin (AGP 9 provides it).

### Removed
- Dead WeatherFlow frame parser (`WeatherFlowReading`, `parseWeatherFlowFrame`) from
  `Protocol.kt` — superseded by the generic template runtime. The `WF_NOTIFY_CHAR`
  auto-subscribe is retained until a device template drives subscriptions.

## [0.9.0] — 2026-06-10

First versioned release. Adds the template runtime: the agent now renders BLE data using display
templates pushed from the broker, instead of hardcoded device parsing.

### Added
- **Template data model** — `RenderedField`, `RenderedFrame`, `FieldWarning`.
- **`TemplateRenderer`** — parses a BLE notification byte array through a display template:
  field types `raw`, `scale_offset`, `bitmask`, `enum`, `expr`, `formula`; all integer/float
  encodings; a safe recursive-descent expression evaluator; US-locale number formatting; and an
  encoding-width bounds guard so malformed/truncated frames degrade rather than crash.
- **`GattAnalyser`** — always-available raw-hex fallback renderer for devices with no template.
- **`TemplateStore`** — on-device persistence of pushed templates (`filesDir/templates/`) with an
  in-memory cache, path-traversal-safe writes (id/version validation + canonical containment).
- **Protocol additions** — `push_templates`, `template_data`, `apply_template`, `set_view` commands
  and `template_request`, `template_applied`, `view_changed`, `hello` events.
- **ViewModel wiring** — request missing templates on push, store received templates, build a
  renderer on `apply_template`, render notifications generically, and emit `view_changed` on view
  selection. The agent now includes the advertised device `name` in `services_discovered` so the
  broker can match device-template signatures that require a name prefix.
- **UI** — `TemplateDisplayPanel` showing rendered fields, a view selector, and warning banners.
- JVM unit-test harness (JUnit + `org.json`); 26 unit tests.
- FOSS scaffolding: Apache-2.0 `LICENSE`/`NOTICE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`,
  `docs/VERSIONING.md`.

### Changed
- Default broker (agent TCP) port `9876 → 2653`.
- `versionName` set to `0.9.0` (pre-release; `versionCode` remains a separate store-ordering integer).
- Notification rendering runs on the main dispatcher; the active renderer is cleared on disconnect
  to prevent rendering one device's data through another's template.

### Removed
- The hardcoded WeatherFlow display panel from the UI (replaced by the generic template panel).
  `WeatherFlowReading`/`parseWeatherFlowFrame` remain in `Protocol.kt` pending a follow-on cleanup.

[Unreleased]: https://github.com/ColdBoreBallistics/bt-bridge-agent-android/compare/v0.9.0...HEAD
[0.9.0]: https://github.com/ColdBoreBallistics/bt-bridge-agent-android/releases/tag/v0.9.0
