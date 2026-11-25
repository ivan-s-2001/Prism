# Prism Connect: Yandex Station (experimental)

This branch starts an independent local transport for Prism's existing Connect interface. No new UI or working playback route is wired yet.

## Milestone 1
- Glagol command envelope, ping, stop, volume and radio_play encoder.
- JVM tests for command serialization, UTF-8 and protobuf length encoding.
- Run: `./gradlew :app:testDebugUnitTest --tests 'com.prism.music.connect.yandex.GlagolProtocolTest'`

## Next milestones
1. Discover `_yandexio._tcp.local.` via Android NSD, resolve TXT deviceId/platform and match owned devices. Respect local-network permissions and stop discovery when not in use.
2. Sign in via Yandex device authorization flow, following Music Assistant as reference; keep tokens in Android Keystore-backed storage, never log session cookies. Fetch per-device Glagol token and refresh as needed.
3. Use a dedicated OkHttp WSS client with validated device-specific certificate/trust; do not disable TLS verification globally. Handle state frames, keepalive and reconnect.
4. Serve a known local test audio file with correct Content-Type and Content-Length, via a short-lived unpredictable URL scoped to the active LAN session. Avoid serving arbitrary files. Use HTTP because the Station receiver requires it. Test MP3 CBR first; evaluate WAV/FLAC later.
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
