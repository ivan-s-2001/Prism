import concurrent.futures
import random
import unittest
from connect_sessions import ConnectSessions, Conflict
from server import Bridge, parse_command, equal


class VoiceMatrix(unittest.TestCase):
    def test_transport_variants(self):
        groups = {
            "next": ["дальше", "следующий", "следующая песня", "пропусти"],
            "previous": ["назад", "предыдущий трек", "предыдущая песня"],
            "pause": ["пауза", "останови музыку", "стоп", "поставь на паузу"],
            "resume": ["продолжи", "играй", "включи музыку"],
        }
        for action, phrases in groups.items():
            for phrase in phrases:
                for variant in (phrase, phrase.upper(), "  " + phrase + "! ", "пожалуйста " + phrase):
                    with self.subTest(phrase=variant):
                        self.assertEqual(parse_command(variant), {"action": action})

    def test_all_volume_values(self):
        for n in range(101):
            for phrase in (f"громкость {n}", f"громкость {n} процентов", f"установи громкость на {n}%"):
                self.assertEqual(parse_command(phrase), {"action": "volume", "value": n})
        for n in (-1, 101, 999, 1000):
            self.assertIsNone(parse_command(f"громкость {n}"))

    def test_seek_directions_and_bounds(self):
        for n in range(1, 61):
            for phrase, sign, scale in (
                (f"перемотай на {n} секунд", 1, 1),
                (f"перемотай назад на {n} секунд", -1, 1),
                (f"перемотай на {n} секунд назад", -1, 1),
                (f"перемотай на {n} минут", 1, 60),
                (f"перемотай на {n} минут назад", -1, 60),
            ):
                self.assertEqual(parse_command(phrase), {"action": "seek_relative", "value": n * sign * scale})
        for phrase in ("перемотай на 0 секунд", "перемотай на 61 минуту", "перемотай на 9999 секунд"):
            self.assertIsNone(parse_command(phrase))

    def test_search_bounds(self):
        for query in ("Metallica", "кино группа крови", "Daft Punk", "a" * 200):
            self.assertEqual(parse_command("включи " + query), {"action": "search_play", "query": query.lower()})
        self.assertIsNone(parse_command("включи " + "a" * 201))
        self.assertIsNone(parse_command(""))
        self.assertIsNone(parse_command("привет"))

    def test_legacy_concurrent_duplicate(self):
        b = Bridge()
        b.pull()
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(lambda _: b.enqueue(("s", 1), {"action": "next"}), range(128)))
        self.assertEqual(results.count("queued"), 1)
        self.assertEqual(results.count("duplicate"), 127)
        self.assertEqual(b.pull()["action"], "next")
        self.assertIsNone(b.pull())

    def test_legacy_capacity(self):
        b = Bridge()
        b.pull()
        for n in range(16):
            self.assertEqual(b.enqueue(("s", n), {"action": "pause"}), "queued")
        self.assertEqual(b.enqueue(("s", 16), {"action": "next"}), "busy")
        self.assertEqual(sum(b.pull() is not None for _ in range(16)), 16)
        self.assertIsNone(b.pull())

    def test_auth_comparison(self):
        self.assertTrue(equal("owner", "owner"))
        for value in (None, [], {}, 1, "Owner", "owner "):
            self.assertFalse(equal(value, "owner"))


class ConnectMatrix(unittest.TestCase):
    def test_snapshot_isolation(self):
        c = ConnectSessions()
        g = c.claim("account", "host")
        state = {"queue": [{"id": "A"}]}
        c.heartbeat("account", "host", g, state)
        state["queue"][0]["id"] = "B"
        view = c.view("account")
        view["state"]["queue"].clear()
        self.assertEqual(c.view("account")["state"]["queue"], [{"id": "A"}])

    def test_invalid_command_ids(self):
        c = ConnectSessions()
        g = c.claim("a", "h")
        for rid in ("", "x" * 129, None, 42):
            with self.assertRaises(Conflict):
                c.submit("a", g, rid, {"action": "next"})
        for cmd in ({}, {"action": None}, {"action": "next", "id": "forged"}, []):
            with self.assertRaises(Conflict):
                c.submit("a", g, "r", cmd)

    def test_capacity_order_and_deep_copy(self):
        c = ConnectSessions()
        g = c.claim("a", "h")
        for n in range(32):
            cmd = {"action": "volume", "value": n}
            self.assertTrue(c.submit("a", g, str(n), cmd))
            cmd["value"] = 100
        with self.assertRaises(Conflict):
            c.submit("a", g, "overflow", {"action": "next"})
        for n in range(32):
            self.assertEqual(c.pull("a", "h", g), {"action": "volume", "value": n, "id": str(n)})
        self.assertIsNone(c.pull("a", "h", g))

    def test_seeded_session_stress(self):
        # Reproducible randomized permutations, not hardware simulation.
        rng = random.Random(67129)
        c = ConnectSessions(clock=lambda: 100.0)
        for n in range(10000):
            account = f"a{n % 8}"
            host = f"phone{rng.randrange(8)}"
            g = c.claim(account, host)
            requests = [(f"{n}-{i}", {"action": rng.choice(("next", "previous", "pause", "resume", "search_play", "volume"))}) for i in range(4)]
            for rid, cmd in requests:
                self.assertTrue(c.submit(account, g, rid, cmd))
                self.assertFalse(c.submit(account, g, rid, cmd))
            with self.assertRaises(Conflict):
                c.claim(account, host + "-other")
            for rid, cmd in requests:
                self.assertEqual(c.pull(account, host, g), {**cmd, "id": rid})
            self.assertIsNone(c.pull(account, host, g))
            c.release(account, host, g)
            self.assertIsNone(c.view(account))

if __name__ == "__main__":
    unittest.main()
