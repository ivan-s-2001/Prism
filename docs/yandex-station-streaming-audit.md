# Station streaming audit (2026-11-25)

Scope: stock Station 1, Lite, Midi; Android phone on LAN; no rooting, native
Spotify subscription or changes to firmware. This is a source review, not
hardware validation. No implementation can promise zero buffering latency
or sample-accurate synchronization over the undocumented stock Glagol API.

## Primary references

- https://github.com/music-assistant/server/tree/dev/music_assistant/providers/yandex_station
  (`player.py`, `glagol.py`, `session.py`): distinguish audio_client from
  legacy radio_play using supported_features. audio_play states may have an
  empty playerState.id; replacing an anonymous external stream needs a
  stopped-state boundary before a new playing report can identify it.
  Uses a generation to discard late resolved streams, checks command replies,
  and treats external pause as stopping with subsequent queue resume.
  WebSocket reconnect and Alice-interruption recovery are separate concerns.
- https://github.com/AlexxIT/YandexStation (core/utils.py and core/yandex_station.py):
  new audio_play directive; MP3 enum for file codecs including WAV/AAC/FLAC,
  HLS for playlists, optional stream.id, metadata; rewind position in seconds.
  Firmware updates can break radio_play. Do not assume all receivers echo IDs.
- https://github.com/volodarskij/yandex-station-local-streaming/blob/master/docs/04-http-stream.md
  and docs/05-lifecycle.md: finite HTTP response with exact Content-Length,
  distinct stream epochs, abort stale feeds, separate explicit skips from natural
  completion. Historic radio_play tests on Station 1, not universal modern firmware.
  Bytes handed to the socket indicate transfer, not sound coming out of the speaker.
- https://github.com/volodarskij/alice-spotify-bridge/blob/master/bridge/SPEC.md:
  serialized playback commands, stream epochs, stale-disconnect suppression,
  state stream and reconnect. Older radio_play implementation is not copied.
- https://github.com/SanyaPilot/YNDXRemote : Android local control reference;
  not evidence of third-party audio synchronization.
- https://github.com/klockky/spotiya : Connect/receiver UX; requires receiver-side
  software for its intended deployment, not a dependency for unmodified Stations.
- https://github.com/derangedabbot/yandex-station-2-airplay and
  https://github.com/derangedabbot/zvuk-yandex-station-player :
  root-oriented projects, but reports of stock audio_play offset_ms and set_pause
  are useful experimental leads. Verify with unmodified hardware before using.
- https://github.com/dimap/yapanel-web : root-oriented device/model profiles.
- https://github.com/pluGGsee/alice-station-control and
  https://github.com/ebuyan/yapi : native control references, not evidence of
  external-stream queue control.
- https://github.com/0xcodepunk/ya2dlna_streaming : opposite direction,
  Station to DLNA, not a drop-in receiver.
- https://github.com/snapcast/snapcast and https://github.com/Sendspin/spec :
  timestamped buffers and synchronized endpoint clocks. Stock Glagol does not
  expose these protocols. Do not claim sample-level multiroom sync.
- https://www.music-assistant.io/player-support/yandex-station/ :
  native next/previous voice does not target third-party queue, use Prism skill.

## Prism architecture

Station is authoritative for observed playback state and position. Phone is
queue/stream host and command initiator; its muted ExoPlayer must never decide
when the next song is audible. Do not add RTT or local elapsed time to the
last observed station position when presenting a strict non-leading UI.
No reported progress means retain last confirmed position and show waiting.
Gate automatic track changes on matching fresh receiver completion evidence;
never on phone STATE_ENDED, bytes sent, a timeout alone, or mere stop mid-track.
Manual skip is allowed immediately, cancels previous work and uses fresh URL.

For audio_client: prefer exact echoed stream ID; for empty IDs require an
explicit boundary from previous playback, a fresh HTTP GET delivering data
to the authenticated Station's IP on the new URL, and a subsequent playing
report. This is still correlated evidence rather than a hardware guarantee.
Unknown/nonempty foreign IDs never count. A legacy capability profile must
not silently trigger native radio when external playback is unverified.

Transport: use finite seekable WAV or tested original format; validate ranges
and exact byte count. Prewarm the next track but do not instruct the speaker to
play it early. Avoid decoding the same song in multiple independent pipelines
where possible. Preserve security (per-device certificate pins). Avoid holding
command locks while awaiting periodic polls; time out safely; refresh device
tokens off the interactive path. Cancel old stream workers on generation change.

Seek: try verified native rewind; if unsupported, experimentally test audio_play
offset_ms or a fresh finite stream starting at the requested position (report
absolute offset correctly). Pause captures the last confirmed receiver position,
stops the stream and resumes from that position. A sent frame is not a successful
command; surface rejected, pending, confirmed and unknown separately.

## Acceptance

Test each firmware on Station 1, Lite, Midi. Collect anonymized timestamps for
command, preparation, first HTTP GET/bytes, first matching playing report and
first position change. Measure real p50/p95, audible start (external observation),
20 rapid skips, 20 seeks during load, pause near end, duplicate titles, same-song
repeat, natural completion versus native Alice stop, disconnect and reconnect.
No premature queue transition or display beyond latest observed progress.
No overlap on explicit handoff. Physical multiroom skew requires measurement;
do not report it as perfect based on simultaneous WebSocket commands.
