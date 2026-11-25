import tempfile
import unittest
from pathlib import Path
from connect_api import ConnectApi, validate_command
from connect_sessions import Conflict
from paired_devices import PairedDevices, PairingError


class ConnectApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = str(Path(self.temp.name) / "devices.db")
        self.api = ConnectApi(self.path)
        self.addCleanup(self.api.devices.db.close)
        self.one = "a" * 64
        self.two = "b" * 64

    def pair(self, credential, request_id):
        status, payload = self.api.post("/v2/pair", credential, {"name": "Phone"})
        self.assertEqual(status, 200)
        self.assertEqual(self.api.post("/v2/status", credential, {})[0], 401)
        self.api.approve(payload["code"], request_id)
        self.assertEqual(self.api.post("/v2/status", credential, {})[0], 200)
        return payload["code"]

    def test_pairing_requires_owner_approval(self):
        code = self.pair(self.one, "one")
        self.assertEqual(self.api.post("/v2/status", self.two, {})[0], 401)
        self.assertEqual(self.api.approve(code, "one"), "Phone")
        with self.assertRaises(PairingError):
            self.api.approve(code, "different")

    def test_owner_lease_and_remote_controller(self):
        self.pair(self.one, "one")
        self.pair(self.two, "two")
        _, claim = self.api.post("/v2/claim", self.one, {})
        g = claim["generation"]
        self.assertEqual(self.api.post("/v2/claim", self.two, {})[0], 409)
        command = {"generation": g, "id": "click", "command": {"action": "next"}}
        self.assertTrue(self.api.post("/v2/command", self.two, command)[1]["accepted"])
        self.assertTrue(self.api.post("/v2/command", self.two, command)[1]["duplicate"])
        pull = {"generation": g, "state": {"title": "Song", "playing": True}}
        self.assertEqual(self.api.post("/v2/pull", self.two, pull)[0], 409)
        self.assertEqual(self.api.post("/v2/heartbeat", self.one, pull)[1]["command"], None)
        self.assertEqual(self.api.post("/v2/pull", self.one, pull)[1]["command"]["action"], "next")
        self.assertEqual(self.api.post("/v2/pull", self.one, pull)[1]["command"], None)
        self.assertEqual(self.api.post("/v2/status", self.two, {})[1]["session"]["state"]["title"], "Song")
        self.assertEqual(self.api.post("/v2/release", self.one, {"generation": g, "stopped": False})[0], 409)
        self.assertEqual(self.api.post("/v2/release", self.two, {"generation": g, "stopped": True})[0], 409)
        self.assertEqual(self.api.post("/v2/release", self.one, {"generation": g, "stopped": True})[0], 200)
        self.assertEqual(self.api.post("/v2/claim", self.two, {})[0], 200)

    def test_restart_keeps_reservation_and_credentials(self):
        self.pair(self.one, "one")
        self.pair(self.two, "two")
        self.api.post("/v2/claim", self.one, {})
        another = ConnectApi(self.path)
        try:
            self.assertEqual(another.post("/v2/status", self.two, {})[0], 200)
            self.assertEqual(another.post("/v2/claim", self.two, {})[0], 409)
        finally:
            another.devices.db.close()

    def test_revoked_cannot_control(self):
        self.pair(self.one, "one")
        self.assertEqual(self.api.post("/v2/revoke", self.one, {})[0], 200)
        self.assertEqual(self.api.post("/v2/claim", self.one, {})[0], 401)

    def test_bad_commands_rejected(self):
        for cmd in ({}, {"action": "next", "value": 1}, {"action": "volume", "value": 101},
                    {"action": "volume", "value": True}, {"action": "search_play", "query": " "},
                    {"action": "seek_relative", "value": -3601}, {"action": "unknown"}):
            with self.assertRaises(Conflict):
                validate_command(cmd)

    def test_queue_and_seek_contract(self):
        self.pair(self.one, "one")
        self.pair(self.two, "two")
        g = self.api.post("/v2/claim", self.one, {})[1]["generation"]
        queue = [{"index": 0, "id": "first", "title": "Song", "artist": "Artist"},
                 {"index": 1, "id": "second", "title": "Song", "artist": "Artist"}]
        state = {"queue": queue, "queue_count": 2, "current_index": 0, "track_id": "first"}
        self.assertEqual(self.api.post("/v2/heartbeat", self.one, {"generation": g, "state": state})[0], 200)
        self.assertEqual(self.api.post("/v2/status", self.two, {})[1]["session"]["state"]["queue"], queue)
        for i, command in enumerate((
            {"action": "select_track", "index": 1, "track_id": "second"},
            {"action": "seek_absolute", "value": 45, "track_id": "first"},
            {"action": "disconnect"},
        )):
            response = self.api.post("/v2/command", self.two, {"generation": g, "id": str(i), "command": command})
            self.assertEqual(response[0], 200)
            delivered = self.api.post("/v2/pull", self.one, {"generation": g, "state": state})[1]["command"]
            self.assertEqual(delivered, {**command, "id": response[1]["id"]})
        for invalid in (
            {"queue": queue * 21}, {"queue": [{"index": True, "id": "x", "title": "x", "artist": "x"}]},
            {"queue": [{"index": 0, "id": "x", "title": "x", "artist": "x", "secret": "x"}]},
            {"result": {"id": "x", "status": "fabricated"}},
            {"queue_count": -1},
        ):
            self.assertEqual(self.api.post("/v2/heartbeat", self.one, {"generation": g, "state": invalid})[0], 409)

    def test_bad_queue_commands(self):
        for command in (
            {"action": "select_track", "index": -1, "track_id": "x"},
            {"action": "select_track", "index": True, "track_id": "x"},
            {"action": "select_track", "index": 0, "track_id": ""},
            {"action": "seek_absolute", "value": 12},
            {"action": "seek_absolute", "value": -1, "track_id": "x"},
            {"action": "disconnect", "value": 1},
        ):
            with self.assertRaises(Conflict):
                validate_command(command)

    def test_pair_expiry(self):
        now = [0]
        devices = PairedDevices(":memory:", clock=lambda: now[0])
        try:
            code = devices.begin(self.one, "Phone")
            now[0] = 181
            with self.assertRaises(PairingError):
                devices.approve(code, "request")
            self.assertIsNone(devices.authenticate(self.one))
        finally:
            devices.db.close()


if __name__ == "__main__":
    unittest.main()
