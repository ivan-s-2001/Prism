# Prism — private Alice voice bridge (experimental)

A personal, single-phone relay, not a public service. Python 3.11+ standard library only. Music and Yandex OAuth credentials stay on the phone. The bridge sends commands only to the debug Prism playback service when a Yandex Station is connected. Commands expire after 10 seconds; duplicate Alice session/message IDs are suppressed for five minutes. The skill says it is forwarding a command, not that playback succeeded.

## Setup

1. Create a private Alice skill in https://dialogs.yandex.ru/developer/. Choose a unique activation name (e.g. Призма). Copy its actual skill ID and the account's session.user.user_id from an authenticated test request in the developer console. Do not use the obsolete session.user_id field or application_id. If a test request lacks session.user, sign in with the same Yandex account and inspect a real authorized request before enabling the bridge. Do not allow arbitrary users.
2. On the server, run the following commands from this directory:
   ```sh
   python3 -m unittest -v
   sudo install -d -m 755 /opt/prism-voice
   sudo install -m 644 server.py /opt/prism-voice/server.py
   sudo install -m 644 prism-voice.service /etc/systemd/system/prism-voice.service
   sudo install -m 600 /dev/null /etc/prism-voice.env
   sudoedit /etc/prism-voice.env
   ```
   Generate two distinct secrets locally using `openssl rand -hex 32` twice. Put them in the protected environment file along with the IDs:
   ```ini
   PRISM_WEBHOOK_SECRET=replace_with_first_random_hex
   PRISM_PHONE_TOKEN=replace_with_second_random_hex
   PRISM_ALICE_USER_ID=replace_with_skill_specific_user_id
   PRISM_ALICE_SKILL_ID=replace_with_skill_id
   PRISM_PORT=8765
   ```
   Check port availability first. Do not overwrite an existing environment file on upgrade. Do not commit secrets.
3. Add this location within an existing HTTPS Nginx server block (not the HTTP redirect block). Retain a valid certificate. Do not expose port 8765 publicly.
   ```nginx
   location ^~ /prism-voice/ {
       access_log off;
       error_log /dev/null crit;
       client_max_body_size 64k;
       proxy_pass http://127.0.0.1:8765/;
       proxy_set_header Host $host;
       proxy_read_timeout 10s;
   }
   ```
   Disable any upstream access logging of the secret webhook URL. Use a dedicated hostname or rate limiting for wider deployments. This backend is intended for personal use, not a multi-user installation.
   ```sh
   sudo systemctl daemon-reload
   sudo systemctl enable --now prism-voice
   curl --fail http://127.0.0.1:8765/health
   sudo nginx -t && sudo systemctl reload nginx
   ```
4. Configure the private skill webhook to `https://YOUR-HOST/prism-voice/alice/PRISM_WEBHOOK_SECRET`, substituting the first secret locally. Publish the private skill after checking its actual request format and authorized user.
5. In the debug Station Lab, select Configure Alice voice bridge. Enter `https://YOUR-HOST/prism-voice/` and the second secret (PRISM_PHONE_TOKEN). Authenticate to Yandex, open normal Prism, choose a Station. Keep the playback service active. Configuration is in memory only; enable again after a process restart.
6. Say "Алиса, запусти навык Призма", then "включи Metallica", "дальше", "назад", "пауза", "продолжи", "перемотай на 30 секунд назад", "громкость 25", or "тише". The activation name is subject to Dialogs availability. Short commands outside the skill are not captured. Seek depends on receiver capabilities.

## Limits

No production group integration or individual group voice volume yet. No ability to wake a dead Android process. One authorized polling phone; do not share its token. The parser expects numeric digits for explicit volume and seek; voice recognition variants need testing. The server and client are not yet verified together on hardware. The private skill uses a random webhook URL plus the configured skill and user IDs; it does not implement public OAuth account linking. For multiple users, implement official OAuth linking and per-user device pairing before publishing publicly.
