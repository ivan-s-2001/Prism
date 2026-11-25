# Prism Connect acceptance gate

This is a required release checklist, not a report of passed tests. Existing
hardware access is via the user's Android phone and Station 1, Lite, Midi.
Use distinct recognizable tracks, including tracks with identical titles.

## Automated

- Python: `python -m unittest discover -v` in voice-bridge.
- Android: `./gradlew :app:testDebugUnitTest --tests 'com.prism.music.connect.yandex.*' :app:assembleDebug -PsideBySide=station`.
- The Actions diagnostics workflow runs both jobs on branch pushes.
- Connect session load: 10,000 sessions, 4 commands and 4 duplicate deliveries
  each; account isolation, FIFO, capacity, stale lease, expiry, concurrent claim.
- Voice parser: transport phrases, all 101 volume values with three phrasings,
  300 seek combinations, malformed/out-of-range commands and search limits.
- State parser: actual playing vs hasPause, missing state, foreign stream,
  identical titles, invalid numerics, all volume steps. These are synthetic
  fixtures, not captures from physical receivers.

## Physical end-to-end (each of Lite, Station 1, Midi)

| ID | Action | Required outcome |
|---|---|---|
| A01 | Sign in, close/reopen app | Account restored securely; no manual token |
| A02 | Sign out/revoke credentials | No new Station requests; UI resets |
| A03 | Discovery on same LAN | Correct account-owned names, no duplicates |
| A04 | Wrong account or invalid certificate | Connection refused, no audio |
| A05 | Connect, cancel during connect, reconnect rapidly | Old attempt cannot become active |
| A06 | Play distinct local MP3 and catalog song | Correct audible song, not Yandex radio |
| A07 | Same title, different track | Correct stream identity, queue index |
| A08 | Pause during preparation/buffering | No late unexpected start |
| A09 | Pause, resume at 30s | Actual stop and correct resume position |
| A10 | Skip next/previous rapidly | No duplicate skips or old track restart |
| A11 | Seek forward/backward/bounds | Actual position agrees; unsupported seek reported |
| A12 | Voice search successful/not found/offline | Correct result, existing queue preserved on failure |
| A13 | Voice next/pause/volume in skill | State and audio agree; no native radio |
| A14 | Physical pause/volume/foreign music | Phone follows observed state, no automatic reclaim |
| A15 | Disconnect while playing/paused/loading | Stop confirmed or explicit error; local sound behavior correct |
| A16 | Wi-Fi loss, station reboot, screen off | No silent double playback; recover or clear error |
| A17 | Group start, remove one, per-member failure | Others continue, per-speaker actual volume and status |
| A18 | Group pause/resume/skip/seek | Correct on every member; measure acoustic drift |
| A19 | Same account on second phone | Shared state, no competing stream |
| A20 | Conflicting phone and Alice commands | One serial authoritative queue, deduplication |
| A21 | Explicit handoff and network partition | Prior host stopped before new owner starts; fail closed if uncertain |
| A22 | Different accounts / revoked device | No cross-account visibility or control |
| A23 | UI with 0/1/3/8 Stations, accessibility | Correct loading/error/connected state, labeled controls, no clipped rows |
| A24 | No Yandex Plus | Independently verify external music on each model |
| A25 | Existing DLNA, Google Cast, Prism TV and local playback | No regression after shared Cast changes |

Record model, firmware, Prism commit, active subscription, timestamps,
sanitized state and request IDs, and observed audio. Never include OAuth
tokens, webhook secret, phone token, URLs containing secrets or account IDs.

## Current blockers

Production Yandex ID login, persisted credentials, multi-phone controller API,
group playback in the standard output picker, end-to-end command outcomes
and handoff are not implemented. The legacy voice relay and group diagnostic
are not proof of these features. Exact pause/resume/seek behavior and stream
ID echo depend on firmware and must be validated. Do not promote to main or
claim zero conflicts before the above checks pass.
