# Prism Connect v2

## Account model

Use a separately registered Prism OAuth app with Yandex ID (login:info;
authorization code + PKCE, state and HTTPS redirect). Verify identity via
https://login.yandex.ru/info; use the verified Yandex ID as account key.
Do not accept caller-supplied account identifiers. Do not send the existing
Quasar/Music access token to the Prism relay. Station-specific authorization
is separate unless supported scopes can be obtained together. Store local
refresh credentials encrypted with Android Keystore, exclude from backup,
and handle expiry, refresh and explicit logout. Show account identity and
Stations in normal Settings / Accounts. Keep YouTube login independent.

Existing Alice session.user.user_id is scoped to the skill, not the
Yandex ID API user ID. For this private installation bind the skill owner
to the verified owner account explicitly during setup. For public multi-user
use implement Dialogs OAuth account linking, not a global shared secret.
Issue independent revocable device sessions; never embed or distribute
PRISM_PHONE_TOKEN as a universal key.

## One playback owner, multiple controllers

One active host per account; a host can serve a group of Stations. Other
authenticated phones display the same authoritative queue, track, elapsed
time, play state, group membership and per-speaker volumes. They submit
commands, not their own audio streams. Opening the app or changing Wi-Fi
does not acquire or steal a playing session. Queue edits use a revision
precondition; play/next/previous/volume commands have unique IDs and a
short expiry. The host serializes them with local controls and Alice
commands, then publishes actual outcomes and authoritative state. Do not
claim success from relay acceptance alone.

connect_sessions.py is an isolated in-memory foundation, not yet served
by server.py. It models atomic host claims, account isolation, lease
generations, heartbeat, expiry, revision and duplicate request suppression.
The production API must authenticate every caller before passing IDs to it.
Add persisted device registration, revocation, command acknowledgements,
bounded metadata validation and queue revision checks before deployment.

Explicit handoff: old host stops audio, confirms and releases; new host
claims with a new generation and resumes the queue after preparing sources.
A lost heartbeat must stop the old host from issuing further commands and
serving audio before another host starts. Buffered audio or a network
partition may prevent immediate remote stop; do not advertise seamless
takeover or guaranteed zero overlap, and require manual confirmation when
the prior host cannot be reached. The coordinator alone cannot fence
Glagol sockets. Use monotonic local lease deadlines, conservative margins,
and explicit stop confirmation on hardware.

Remote phones can control an existing online host even outside the LAN,
but audio delivery remains on the host's network. If all phones are off,
playback cannot continue unless a separate always-on local host is added.
Keep current v1 voice bridge unchanged until v2 authentication and Android
integration are verified. Existing private skill does not need a new name.
