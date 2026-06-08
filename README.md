# bt-bridge-agent-android

Android BT bridge agent app for the BT Bridge test harness. Connects to
[bt-bridge-broker](https://github.com/ColdBoreBallistics/bt-bridge-broker) over TCP and
executes Bluetooth operations on demand — scanning, connecting, subscribing, reading, and writing.

All Bluetooth events are forwarded to the broker in real time. The broker drives the app; the app
executes and reports back.

See [PROTOCOL.md](https://github.com/ColdBoreBallistics/bt-bridge-broker/blob/main/PROTOCOL.md)
in `bt-bridge-broker` for the full wire protocol specification.

---

## Requirements

- Android device running Android 12 or later (API 31+)
- Bluetooth Low Energy hardware (all modern Android phones have this)
- Android Studio Meerkat or later (for building from source)
  OR a pre-built debug APK (see Releases or the internal file server)
- The desktop running `bt-bridge-broker` must be on the same local network

---

## Installing

### Option A — Install from the internal file server (recommended for testing)

1. On your Android device, open a browser and go to:
   `https://files.coldboreballisticsllc.com/Android/`

   You will need to trust the CBB local CA certificate first. Instructions:
   `https://files.coldboreballisticsllc.com/certs/rootCA.pem`
   Download this file, then install it as a trusted certificate:
   - **Android:** Settings → Security → Encryption & credentials → Install a certificate →
     CA certificate → select the downloaded `.pem` file.

2. Download the latest `BT_Bridge_<version>_debug.apk`.

3. Enable "Install from unknown sources" for your browser:
   - Settings → Apps → [your browser] → Install unknown apps → Allow.

4. Open the downloaded APK and tap **Install**.

### Option B — Build from source

1. **Install Android Studio.**
   Download from [developer.android.com/studio](https://developer.android.com/studio).
   Run the installer and follow the setup wizard. Accept the default SDK installation.

2. **Clone this repo:**
   ```bash
   git clone https://github.com/ColdBoreBallistics/bt-bridge-agent-android.git
   cd bt-bridge-agent-android
   ```

3. **Open in Android Studio:**
   File → Open → select the `bt-bridge-agent-android` directory → OK.
   Wait for Gradle sync to complete (first run downloads dependencies, ~2 minutes).

4. **Connect your Android device via USB** and enable USB Debugging:
   - Settings → About phone → tap "Build number" 7 times to enable Developer Options.
   - Settings → Developer options → USB debugging → On.

5. **Run:** Click the green ▶ Run button in Android Studio, or:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Usage

1. **Start the desktop broker first:**
   ```bash
   python3 ble_server.py --port 9876
   ```
   Note the machine's local IP address (e.g., `172.31.1.200`).

2. **Open BT Bridge on your Android device.**

3. **Grant Bluetooth permissions** when prompted on first launch.

4. **Enter the broker IP and port** (default port: `9876`).

5. **Tap Connect.** The broker prints `Mobile client connected from …` when the TCP link is up.

6. **Issue commands from the broker.** The app executes them and streams events back.
   The on-screen log shows all activity color-coded:
   - **Blue** — Bluetooth events sent to broker
   - **Green** — commands received from broker
   - **Amber** — connection state changes
   - **Red** — errors

---

## Architecture

```
MainActivity
  └── MainViewModel
        ├── TcpClient       — TCP connection to bt-bridge-broker (Kotlin coroutines + Socket)
        └── BleManager      — BLE Central operations (Android BLE APIs + coroutine dispatch)
              └── Protocol  — JSON message builders and command parsers
```

**TcpClient** is a TCP client (not server). It connects outward to `bt-bridge-broker`.
The broker is the TCP server; the agent app is the TCP client.

**BleManager** handles all BLE operations on behalf of the broker. Commands arrive via
`TcpClient.commands` Flow, are dispatched by `MainViewModel`, and results are emitted back
through `BleManager.onEvent` → `TcpClient.send`.

**BLE operation serialisation:** BLE GATT is single-operation-at-a-time. `BleManager` uses an
internal `opQueue` (a `MutableSharedFlow`) to serialise read/write/subscribe operations. Do not
send a second read or write command before the first `read_result` or `write_result` arrives.

---

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN` | Scanning for BLE peripherals |
| `BLUETOOTH_CONNECT` | Connecting to and communicating with BLE peripherals |
| `INTERNET` | TCP connection to the desktop broker |
| `ACCESS_NETWORK_STATE` | Checking network availability |

`BLUETOOTH_SCAN` is declared with `usesPermissionFlags="neverForLocation"` — location data
is never derived from BLE scan results.

---

## Known Device UUIDs

### WeatherFlow Tactical

| Role | UUID |
|---|---|
| Primary service | `961f0001-0000-1000-8000-00805f9b34fb` |
| Notify characteristic | `961f0005-0000-1000-8000-00805f9b34fb` |

### Niimbot B1 (confirmed)

| Role | UUID |
|---|---|
| UART service | `0000ff00-0000-1000-8000-00805f9b34fb` |
| Write characteristic | `0000ff02-0000-1000-8000-00805f9b34fb` |
| Notify characteristic | `0000ff01-0000-1000-8000-00805f9b34fb` |

### Niimbot B21 Pro

UUIDs to be confirmed against hardware. Expected to match B1 (same ISSC UART bridge).
Use `niimbot_b1_verify.py` from `bt-bridge-broker/examples/` with `--name B21`.

---

## Related Repos

| Repo | Description |
|---|---|
| [bt-bridge-broker](https://github.com/ColdBoreBallistics/bt-bridge-broker) | Desktop broker + test scripts (authoritative protocol spec) |
| [bt-bridge-agent-ios](https://github.com/ColdBoreBallistics/bt-bridge-agent-ios) | iOS implementation (same protocol) |
