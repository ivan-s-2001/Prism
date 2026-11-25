"""Private skill pairing. This authenticates skill-owner approval, not Yandex OAuth ID.

Only the already authenticated skill owner may call approve(). The pairing
secret is generated on the requesting phone and is never spoken to Alice.
"""
import hashlib
import re
import secrets
import sqlite3
import threading
import time


class PairingError(Exception):
    pass


def digest(secret):
    if not isinstance(secret, str) or not re.fullmatch(r"[a-f0-9]{64}", secret):
        raise PairingError("invalid_credential")
    return hashlib.sha256(secret.encode("ascii")).hexdigest()


class PairedDevices:
    def __init__(self, path, clock=time.monotonic):
        self.clock = clock
        self.lock = threading.RLock()
        self.pending = {}
        self.approvals = {}
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS devices (hash TEXT PRIMARY KEY, device TEXT UNIQUE NOT NULL, name TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS reservation (slot INTEGER PRIMARY KEY CHECK(slot=1), device TEXT NOT NULL)")
        self.db.commit()

    def _prune(self):
        now = self.clock()
        self.pending = {k: v for k, v in self.pending.items() if v[0] > now}
        self.approvals = {k: v for k, v in self.approvals.items() if v[1] > now}

    def begin(self, secret, name):
        key = digest(secret)
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 60:
            raise PairingError("invalid_name")
        with self.lock:
            self._prune()
            existing = next((code for code, entry in self.pending.items() if entry[1] == key), None)
            if existing:
                return existing
            if self.authenticate(secret):
                raise PairingError("already_paired")
            if len(self.pending) >= 32:
                raise PairingError("busy")
            code = f"{secrets.randbelow(100000000):08d}"
            while code in self.pending:
                code = f"{secrets.randbelow(100000000):08d}"
            self.pending[code] = (self.clock() + 180, key, name.strip())
            return code

    def approve(self, code, request_id):
        with self.lock:
            self._prune()
            if request_id in self.approvals:
                return self.approvals[request_id][0]
            entry = self.pending.get(code)
            if not entry:
                raise PairingError("expired_or_unknown")
            if self.db.execute("SELECT COUNT(*) FROM devices").fetchone()[0] >= 16:
                raise PairingError("device_limit")
            _, key, name = entry
            device = secrets.token_hex(16)
            with self.db:
                self.db.execute("INSERT INTO devices(hash,device,name) VALUES(?,?,?)", (key, device, name))
            self.pending = {k: v for k, v in self.pending.items() if v[1] != key}
            self.approvals[request_id] = (name, self.clock() + 300)
            return name

    def authenticate(self, secret):
        key = digest(secret)
        with self.lock:
            row = self.db.execute("SELECT device,name FROM devices WHERE hash=?", (key,)).fetchone()
            return {"id": row[0], "name": row[1]} if row else None

    def revoke(self, secret):
        with self.lock, self.db:
            self.db.execute("DELETE FROM devices WHERE hash=?", (digest(secret),))

    def reserved(self):
        with self.lock:
            row = self.db.execute("SELECT device FROM reservation WHERE slot=1").fetchone()
            return row[0] if row else None

    def reserve(self, device):
        with self.lock, self.db:
            current = self.reserved()
            if current not in (None, device):
                raise PairingError("host_reserved")
            self.db.execute("INSERT OR REPLACE INTO reservation(slot,device) VALUES(1,?)", (device,))

    def release(self, device):
        with self.lock, self.db:
            self.db.execute("DELETE FROM reservation WHERE slot=1 AND device=?", (device,))
