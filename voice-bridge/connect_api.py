"""Private single-owner Connect API, behind the existing HTTPS proxy.

Every paired device has an independent revocable credential. Account is
server-selected, never supplied by a phone. No automatic host takeover.
"""
import threading
from connect_sessions import ConnectSessions, Conflict
from paired_devices import PairedDevices, PairingError


def validate_command(command):
    if not isinstance(command, dict):
        raise Conflict("invalid_command")
    action = command.get("action")
    if action in ("next", "previous", "pause", "resume") and set(command) == {"action"}:
        return dict(command)
    if action in ("volume", "volume_delta", "seek_relative") and set(command) == {"action", "value"}:
        value = command["value"]
        lo, hi = {"volume": (0, 100), "volume_delta": (-20, 20), "seek_relative": (-3600, 3600)}[action]
        if type(value) is int and lo <= value <= hi:
            return dict(command)
    if action == "search_play" and set(command) == {"action", "query"}:
        query = command["query"]
        if isinstance(query, str) and 1 <= len(query.strip()) <= 200:
            return {"action": action, "query": query.strip()}
    raise Conflict("invalid_command")


class ConnectApi:
    ACCOUNT = "private-owner"

    def __init__(self, path):
        self.devices = PairedDevices(path)
        self.sessions = ConnectSessions()
        self.lock = threading.RLock()
        self.last_host = None

    def approve(self, code, request_id):
        with self.lock:
            return self.devices.approve(code, request_id)

    def alice(self, request_id, command):
        with self.lock:
            state = self.sessions.view(self.ACCOUNT)
            if state is None:
                return "offline"
            try:
                ok = self.sessions.submit(self.ACCOUNT, state["generation"],
                                          "alice:" + request_id, validate_command(command))
                return "queued" if ok else "duplicate"
            except Conflict:
                return "busy"

    def post(self, path, bearer, data):
        if not isinstance(data, dict):
            return 400, {"error": "invalid_body"}
        try:
            with self.lock:
                if path == "/v2/pair":
                    return 200, {"code": self.devices.begin(bearer, data.get("name"))}
                device = self.devices.authenticate(bearer)
                if not device:
                    return 401, {"error": "not_paired"}
                did = device["id"]
                a = self.ACCOUNT
                if path == "/v2/status":
                    return 200, {"device": device, "session": self.sessions.view(a),
                                 "reserved": self.last_host is not None}
                if path == "/v2/claim":
                    if self.last_host not in (None, did):
                        raise Conflict("host_reserved")
                    g = self.sessions.claim(a, did)
                    self.last_host = did
                    return 200, {"generation": g}
                if path == "/v2/pull":
                    g = data.get("generation")
                    state = data.get("state")
                    if not isinstance(state, dict) or set(state) - {"title", "artist", "station", "playing", "position", "duration", "volume", "loading"}:
                        raise Conflict("invalid_state")
                    for field in ("title", "artist", "station"):
                        if field in state and (not isinstance(state[field], str) or len(state[field]) > 256):
                            raise Conflict("invalid_state")
                    for field in ("playing", "loading"):
                        if field in state and type(state[field]) is not bool:
                            raise Conflict("invalid_state")
                    for field in ("position", "duration", "volume"):
                        if field in state and state[field] is not None and (
                            type(state[field]) is not int or not 0 <= state[field] <= (100 if field == "volume" else 31536000000)):
                            raise Conflict("invalid_state")
                    self.sessions.heartbeat(a, did, g, state)
                    return 200, {"command": self.sessions.pull(a, did, g)}
                if path == "/v2/command":
                    rid = data.get("id")
                    if not isinstance(rid, str) or not 1 <= len(rid) <= 64:
                        raise Conflict("invalid_request")
                    ok = self.sessions.submit(a, data.get("generation"), did + ":" + rid,
                                              validate_command(data.get("command")))
                    return 200, {"accepted": ok, "duplicate": not ok}
                if path == "/v2/release":
                    # Caller is responsible for verified physical stop before release.
                    if data.get("stopped") is not True:
                        raise Conflict("stop_not_confirmed")
                    if self.last_host != did:
                        raise Conflict("not_host")
                    current = self.sessions.view(a)
                    if current is not None:
                        self.sessions.release(a, did, data.get("generation"))
                    self.last_host = None
                    return 200, {"ok": True}
                if path == "/v2/revoke":
                    if self.last_host == did:
                        raise Conflict("release_first")
                    self.devices.revoke(bearer)
                    return 200, {"ok": True}
                return 404, {"error": "not_found"}
        except PairingError as e:
            return 400, {"error": str(e)}
        except Conflict as e:
            return 409, {"error": str(e)}
