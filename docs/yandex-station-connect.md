# Prism Connect: Yandex Station (experimental)

This branch contains an experimental local transport for Prism's existing Connect interface. A debug-only hardware diagnostic activity now includes experimental external-browser OAuth device-code sign-in. Production playback and production account sign-in are not wired. The code has not been built or tested on hardware.

## Milestone 1
- Glagol command envelope, ping, stop, volume and radio_play encoder.
- JVM tests for command serialization, UTF-8 and protobuf length encoding.
- Run: `./gradlew :app:testDebugUnitTest --tests 'com.prism.music.connect.yandex.GlagolProtocolTest'`

## Added transport components
- Android NSD discovery (IPv4 private/link-local addresses only).
- Quasar API adapter consuming an OAuth token; verifies device ID/platform against the account list.
- Device-certificate-pinned WSS transport; fails closed if certificate metadata is unavailable or does not match.
- Per-session finite MP3 HTTP endpoint bound to the phone LAN address and restricted to selected Station IP; fresh random URL and byte-range support.
- StationSmokeSession composes these components and exposes ping, volume, play and stop. A queued frame or downloaded bytes does not prove audible playback.
- Ownership matching JVM tests. Tests have not been executed.

## Next milestones
1. Validate discovery on real hardware: discover `_yandexio._tcp.local.` via Android NSD, resolve TXT deviceId/platform and match owned devices. Respect local-network permissions and stop discovery when not in use.
2. Implement interactive account login and sign in via Yandex device authorization flow, following Music Assistant as reference; keep tokens in Android Keystore-backed storage, never log session cookies. Fetch per-device Glagol token and refresh as needed.
3. Validate the dedicated OkHttp WSS client with validated device-specific certificate/trust; do not disable TLS verification globally. Handle state frames, keepalive and reconnect.
4. Exercise the HTTP endpoint: serve a known local test audio file with correct Content-Type and Content-Length, via a short-lived unpredictable URL scoped to the active LAN session. Avoid serving arbitrary files. Use HTTP because the Station receiver requires it. Test MP3 CBR first; evaluate WAV/FLAC later.
5. Test actual playback before exposing the route. The radio_play command is unofficial and may be rejected by some firmware.
6. Reuse Prism Connect route picker, playback queue, media session and foreground playback service. Keep queue progression on the phone; use Station state to distinguish interruption and completion. Never send simultaneous local playback unintentionally.
7. Add diagnostics, pause/resume, next/previous and seek (restart with a new stream URL when native controls do not apply). Voice search and multiroom are out of scope for MVP.

## Hardware acceptance
Record each Station's model/firmware, mDNS result, auth, ping, volume, test audio HTTP request, actual audible playback, stop and subsequent stream. Test on Station 1, Lite and Midi, including on an account without Plus. Do not label any as supported until tested. A firmware-specific failure report exists: https://github.com/AlexxIT/YandexStation/issues/800

## References
- https://github.com/volodarskij/yandex-station-local-streaming (protocol research)
- https://www.music-assistant.io/player-support/yandex-station/ (existing provider and limitations)
- https://github.com/trudenboy/ma-provider-yandex-station (implementation reference)
- https://github.com/AlexxIT/YandexStation

No firmware modifications, external server or account secrets in the repository.

## Station Lab debug APK

GitHub Actions: [Yandex Station diagnostics](https://github.com/ivan-s-2001/Prism/actions/workflows/yandex-station-diagnostics.yml).
The workflow runs Yandex unit tests and builds a side-by-side debug APK named Prism Station Lab (`com.prism.music.station`). Download the artifact only after a successful build.

Local build:
```sh
./gradlew :app:testDebugUnitTest --tests 'com.prism.music.connect.yandex.*' :app:assembleDebug -PsideBySide=station -PsideByLabel="Prism Station Lab"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.prism.music.station/com.prism.music.connect.yandex.StationDiagnosticsActivity
```

1. Connect phone and Station to the same Wi-Fi (no guest isolation).
2. Open the debug diagnostics via ADB; the activity is protected by the DUMP permission and absent from release builds.
3. Request a device code, open the official Yandex page in the system browser and approve access. The experimental OAuth token is held in memory only. No WebView cookies are used.
4. Scan, select a Station, verify account ownership and connect. If verification fails, do not bypass certificate verification.
5. Select a short local CBR MP3 test file and Play test MP3.
6. Refresh diagnostics, note frame/byte counters and confirm audible playback. Stop before disconnect.
7. Repeat for Station 1, Lite and Midi; record firmware and whether the account has Plus. Do not share raw HTTP bodies, certificates' private keys, cookies or tokens.

The existing Prism Cast implementation uses RendererLink, MediaServer and shadow playback. Production integration should reuse those once hardware confirms the protocol, with explicit capability handling for unsupported seek, queue preloading and pause. The diagnostic does not alter Prism's playback queue or expose a production route.

Previous debug APK compiled and installed on the test phone; LAN discovery was confirmed. The old Quasar hostname failed DNS resolution. New OAuth and quasar.yandex.net changes have not yet been built or tested.

## Production acceptance: normal Prism listening on Station

The Station must be an output device in the existing Prism Connect picker, not a separate MP3 player. While connected, users must be able to:
- Search the existing Prism catalog, start any playable song, album, playlist or radio, and edit the normal queue without disconnecting.
- Use play/pause, previous/next, volume, notification controls and lock-screen controls; expose seek only when verified.
- Preserve lyrics, progress, queue order and autoplay; reconcile remote interruptions and prevent double playback on phone and Station.
- Switch outputs back to the phone without losing the queue. Do not silently restart playback after a network failure.
- Use the existing Prism source resolution pipeline and MediaServer/RendererLink where compatible. Transcode or serve a finite compatible audio representation only after validating the receiver format.
- Confirm that the account does not require Plus for external playback. Voice commands for catalog search through Alice are a separate feature and not part of the initial acceptance.

Do not treat diagnostic playback or command acknowledgements as proof of this production acceptance.


## Multiroom hardware validation

Station Lab has a multiroom test section: add up to eight account-verified
Stations, select one local MP3, then start playback concurrently. Each
member has its own pinned WebSocket, short-lived device token, private HTTP
route, delivery counter, error and independent volume control. Group stop
and a group volume command also fan out independently. Failed commands on
one member do not cancel commands to the others.

Acceptance: on two or more physical Stations verify the actual chosen MP3
(not Yandex radio), bytes served per member, independent volume changes,
removal of one member without interruption to the others, and group stop.
The current protocol is audio_play, not legacy radio_play. No acoustic
synchronization, production group queue integration, persistent groups,
or subscription-free operation is claimed until tested. The production
Cast currently still owns one RendererLink; group playback is limited to
the debug Lab pending these hardware results.
