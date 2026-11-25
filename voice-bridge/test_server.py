import time
import unittest
from server import parse_command, Bridge

class Commands(unittest.TestCase):
    def test_transport(self):
        self.assertEqual(parse_command("Дальше!"), {"action": "next"})
        self.assertEqual(parse_command("назад"), {"action": "previous"})
        self.assertEqual(parse_command("пауза"), {"action": "pause"})
        self.assertEqual(parse_command("продолжи"), {"action": "resume"})
    def test_seek(self):
        self.assertEqual(parse_command("перемотай назад на 30 секунд"), {"action": "seek_relative", "value": -30})
        self.assertEqual(parse_command("перемотай на 30 секунд назад"), {"action": "seek_relative", "value": -30})
        self.assertEqual(parse_command("перемотай на 2 минуты"), {"action": "seek_relative", "value": 120})
    def test_search_volume(self):
        self.assertEqual(parse_command("Включи Metallica"), {"action": "search_play", "query": "metallica"})
        self.assertEqual(parse_command("громкость 25 процентов"), {"action": "volume", "value": 25})
        self.assertIsNone(parse_command("громкость 101"))
    def test_relay(self):
        relay = Bridge()
        cmd = {"action": "next"}
        self.assertEqual(relay.enqueue(("a", 1), cmd), "offline")
        self.assertIsNone(relay.pull())
        self.assertEqual(relay.enqueue(("a", 1), cmd), "queued")
        self.assertEqual(relay.enqueue(("a", 1), cmd), "duplicate")
        self.assertEqual(relay.pull()["action"], "next")
        self.assertIsNone(relay.pull())
    def test_expiry(self):
        relay = Bridge()
        relay.pending.append((time.monotonic() - 11, {"action": "pause"}))
        self.assertIsNone(relay.pull())

if __name__ == "__main__":
    unittest.main()
