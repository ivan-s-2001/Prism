"""Isolated Connect v2 foundation. Not exposed by the legacy webhook yet.

Caller must supply an authenticated account/device identity, never trust raw
client account IDs. All state transitions run under one lock. Intended for
one server process; persistence and production authentication are separate.
"""
import secrets
from copy import deepcopy
import threading
import time
from collections import deque
from dataclasses import dataclass, field


class Conflict(Exception):
    pass


@dataclass
class Session:
    host: str
    generation: str
    expires: float
    revision: int = 0
    state: dict = field(default_factory=dict)
    commands: deque = field(default_factory=deque)
    seen: dict = field(default_factory=dict)


class ConnectSessions:
    def __init__(self, clock=time.monotonic, ttl=12):
        self.clock = clock
        self.ttl = ttl
        self.lock = threading.RLock()
        self.sessions = {}

    def _current(self, account):
        current = self.sessions.get(account)
        if current is None or current.expires <= self.clock():
            return None
        return current

    def claim(self, account, device):
        """Opening a second phone never steals the active host."""
        with self.lock:
            current = self._current(account)
            if current:
                if current.host != device:
                    raise Conflict("host_active")
                return current.generation
            generation = secrets.token_hex(16)
            self.sessions[account] = Session(device, generation, self.clock() + self.ttl)
            return generation

    def _host(self, account, device, generation):
        s = self._current(account)
        if s is None or s.host != device or s.generation != generation:
            raise Conflict("stale_host")
        return s

    def heartbeat(self, account, device, generation, state=None):
        with self.lock:
            s = self._host(account, device, generation)
            s.expires = self.clock() + self.ttl
            if state is not None:
                s.state = deepcopy(state)
                s.revision += 1
            return s.revision

    def view(self, account):
        with self.lock:
            s = self._current(account)
            if s is None:
                return None
            return {"host": s.host, "generation": s.generation,
                    "revision": s.revision, "state": deepcopy(s.state)}

    def submit(self, account, generation, request_id, command):
        """Controller and Alice both use this path; duplicate IDs are ignored."""
        with self.lock:
            s = self._current(account)
            if s is None or s.generation != generation:
                raise Conflict("session_changed")
            if not isinstance(request_id, str) or not 0 < len(request_id) <= 128:
                raise Conflict("invalid_request")
            if not isinstance(command, dict) or not isinstance(command.get("action"), str) or "id" in command:
                raise Conflict("invalid_command")
            now = self.clock()
            s.seen = {k: t for k, t in s.seen.items() if now - t < 300}
            if request_id in s.seen:
                return False
            while s.commands and s.commands[0][0] <= now:
                s.commands.popleft()
            if len(s.commands) >= 32 or len(s.seen) >= 512:
                raise Conflict("queue_full")
            s.seen[request_id] = now
            s.commands.append((now + 10, {**deepcopy(command), "id": request_id}))
            return True

    def pull(self, account, device, generation):
        with self.lock:
            s = self._host(account, device, generation)
            s.expires = self.clock() + self.ttl
            now = self.clock()
            while s.commands:
                expiry, command = s.commands.popleft()
                if expiry > now:
                    return dict(command)
            return None

    def release(self, account, device, generation):
        """Release only after stopping audio; controller cannot force release."""
        with self.lock:
            self._host(account, device, generation)
            del self.sessions[account]
