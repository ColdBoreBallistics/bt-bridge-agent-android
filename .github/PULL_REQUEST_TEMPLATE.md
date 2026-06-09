<!-- Thanks for contributing to the BT Bridge Android Agent! -->

## What this PR does

<!-- Brief description. Link any related issue. -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor / cleanup
- [ ] Documentation
- [ ] Tests / CI

## Checklist

- [ ] Conventional Commit message(s) (`type(scope): subject`)
- [ ] `./gradlew :app:testDebugUnitTest` passes
- [ ] New logic has unit tests (TDD: failing test first)
- [ ] No secrets committed
- [ ] No unguarded `Log.d`/`println`/`System.out` (wrapped in `if (BuildConfig.DEBUG)`); UI log and TCP protocol writes excepted
- [ ] `Protocol.kt` wire format unchanged, or change coordinated with the broker + `PROTOCOL.md`
- [ ] Tested on a real device if BLE/UI behavior changed (emulator has no Bluetooth)
