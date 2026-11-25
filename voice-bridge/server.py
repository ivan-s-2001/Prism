"""Personal Prism Alice bridge. Python 3.11+, no external packages.
Only run behind HTTPS reverse proxy. Never log request paths or tokens."""
import hmac
import json
import os
import re
import secrets
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def parse_command(text):
    s = " ".join(text.lower().replace("ё", "е").strip(" .?!,").split())
    s = re.sub(r"^(?:пожалуйста |призма |призму )+", "", s)
    if s in ("дальше", "следующий", "следующая", "следующий трек", "следующая песня", "пропусти"):
        return {"action": "next"}
    if s in ("назад", "предыдущий", "предыдущий трек", "предыдущая песня"):
        return {"action": "previous"}
    if s in ("пауза", "поставь на паузу", "приостанови", "останови музыку", "стоп", "останови"):
        return {"action": "pause"}
    if s in ("играй", "продолжи", "продолжить", "продолжи музыку", "включи музыку"):
        return {"action": "resume"}
    if s in ("громче", "тише"):
        return {"action": "volume_delta", "value": 10 if s == "громче" else -10}
    m = re.fullmatch(r"(?:громкость|установи громкость)(?: на)? (\d{1,3})(?: ?(?:%|процент\w*))?", s)
    if m and 0 <= int(m[1]) <= 100:
        return {"action": "volume", "value": int(m[1])}
    m = re.fullmatch(r"(?:перемотай|перемотать) (?:на )?(?:(вперед|назад) (?:на )?)?(\d{1,4}) (секунд\w*|минут\w*)(?: (вперед|назад))?", s)
    if m:
        n = int(m[2]) * (60 if m[3].startswith("минут") else 1)
        if 0 < n <= 3600:
            return {"action": "seek_relative", "value": -n if "назад" in (m[1], m[4]) else n}
    m = re.fullmatch(r"(?:включи|найди|поставь|сыграй|играй) (.{1,200})", s)
    if m:
        return {"action": "search_play", "query": m[1]}
    return None

class Bridge:
    def __init__(self):
        self.lock = threading.Lock()
        self.pending = deque(maxlen=16)
        self.seen = {}
        self.last_poll = 0.0

    def enqueue(self, key, command):
        now = time.monotonic()
        with self.lock:
            self.seen = {k: t for k, t in self.seen.items() if now - t < 300}
            if key in self.seen:
                return "duplicate"
            if now - self.last_poll > 12:
                return "offline"
            while self.pending and now - self.pending[0][0] > 10:
                self.pending.popleft()
            if len(self.pending) >= 16 or len(self.seen) >= 512:
                return "busy"
            self.seen[key] = now
            self.pending.append((now, {"id": secrets.token_hex(12), **command}))
            return "queued"

    def pull(self):
        now = time.monotonic()
        with self.lock:
            self.last_poll = now
            while self.pending:
                created, command = self.pending.popleft()
                if now - created <= 10:
                    return command
            return None

def equal(a, b):
    return isinstance(a, str) and hmac.compare_digest(a.encode(), b.encode())

def main():
    secret = os.environ["PRISM_WEBHOOK_SECRET"]
    token = os.environ["PRISM_PHONE_TOKEN"]
    owner = os.environ["PRISM_ALICE_USER_ID"]
    skill = os.environ["PRISM_ALICE_SKILL_ID"]
    if (not re.fullmatch(r"[a-f0-9]{64}", secret) or
        not re.fullmatch(r"[a-f0-9]{64}", token) or secret == token or not owner or not skill):
        raise SystemExit("Set distinct random 64-character hex secrets, owner user ID and skill ID.")
    bridge = Bridge()

    class Handler(BaseHTTPRequestHandler):
        server_version = "PrismVoice/0.1"
        def log_message(self, *_):
            pass

        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def reply(self, status, data):
            body = json.dumps(data, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/health":
                return self.reply(200, {"ok": True})
            if self.path != "/v1/pull" or not equal(self.headers.get("Authorization"), "Bearer " + token):
                return self.reply(404, {"error": "not_found"})
            self.reply(200, {"command": bridge.pull()})

        def do_POST(self):
            if not equal(self.path, "/alice/" + secret):
                return self.reply(404, {"error": "not_found"})
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 65536:
                    return self.reply(400, {"error": "invalid_body"})
                data = json.loads(self.rfile.read(size))
                session = data["session"]
                request = data["request"]
                user = session.get("user") or {}
                if not equal(session.get("skill_id"), skill) or not equal(user.get("user_id"), owner):
                    return self.reply(403, {"error": "forbidden"})
                if data.get("version") != "1.0" or request.get("type") != "SimpleUtterance":
                    raise ValueError()
                sid, mid = session["session_id"], session["message_id"]
                if not isinstance(sid, str) or len(sid) > 128 or not isinstance(mid, int) or mid < 0:
                    raise ValueError()
                command = request.get("command", "")
                if not isinstance(command, str) or len(command) > 500:
                    raise ValueError()
            except (ValueError, KeyError, TypeError, AttributeError, OSError):
                return self.reply(400, {"error": "invalid_request"})
            end = command.lower().strip(" .!?") in ("выход", "закрой навык", "до свидания")
            intent = None if end else parse_command(command)
            if end:
                answer = "До свидания."
            elif intent:
                result = bridge.enqueue((sid, mid), intent)
                answer = {"queued": "Передаю команду в Призму.", "duplicate": "Команда уже передана.",
                          "offline": "Призма сейчас не на связи. Откройте плеер на телефоне.",
                          "busy": "Слишком много команд. Попробуйте еще раз."}[result]
            else:
                answer = "Скажите: дальше, назад, пауза, продолжи, громкость 30 или включи название песни."
            self.reply(200, {"version": "1.0", "response": {"text": answer, "end_session": end}})

    server = ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("PRISM_PORT", "8765"))), Handler)
    server.serve_forever()

if __name__ == "__main__":
    main()
