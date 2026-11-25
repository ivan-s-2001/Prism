import concurrent.futures
import unittest
from connect_sessions import ConnectSessions, Conflict


class Sessions(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        self.s = ConnectSessions(clock=lambda: self.now)

    def test_concurrent_claim(self):
        def claim(device):
            try:
                return self.s.claim("account", device)
            except Conflict:
                return None
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(claim, [f"phone{i}" for i in range(8)]))
        self.assertEqual(sum(x is not None for x in results), 1)

    def test_second_phone_controls_without_stealing(self):
        g = self.s.claim("a", "phone1")
        with self.assertRaises(Conflict):
            self.s.claim("a", "phone2")
        self.assertTrue(self.s.submit("a", g, "request", {"action": "next"}))
        self.assertFalse(self.s.submit("a", g, "request", {"action": "next"}))
        with self.assertRaises(Conflict):
            self.s.pull("a", "phone2", g)
        self.assertEqual(self.s.pull("a", "phone1", g)["action"], "next")
        self.assertIsNone(self.s.pull("a", "phone1", g))

    def test_stale_host_cannot_modify_new_session(self):
        g = self.s.claim("a", "phone1")
        self.s.submit("a", g, "old", {"action": "next"})
        self.now += 13
        h = self.s.claim("a", "phone2")
        self.assertNotEqual(g, h)
        with self.assertRaises(Conflict):
            self.s.heartbeat("a", "phone1", g, {"playing": True})
        with self.assertRaises(Conflict):
            self.s.submit("a", g, "stale", {"action": "next"})
        self.assertIsNone(self.s.pull("a", "phone2", h))

    def test_expired_commands(self):
        g = self.s.claim("a", "phone1")
        self.s.submit("a", g, "old", {"action": "pause"})
        self.now += 11
        self.assertIsNone(self.s.pull("a", "phone1", g))

    def test_account_isolation_and_revision(self):
        a = self.s.claim("a", "phone1")
        b = self.s.claim("b", "phone2")
        self.assertEqual(self.s.heartbeat("a", "phone1", a, {"playing": True}), 1)
        self.assertEqual(self.s.view("a")["revision"], 1)
        self.assertEqual(self.s.view("b")["state"], {})
        with self.assertRaises(Conflict):
            self.s.submit("b", a, "cross", {"action": "next"})
        self.assertIsNone(self.s.pull("b", "phone2", b))

    def test_explicit_release(self):
        g = self.s.claim("a", "phone1")
        with self.assertRaises(Conflict):
            self.s.release("a", "phone2", g)
        self.s.release("a", "phone1", g)
        self.assertIsNone(self.s.view("a"))
        self.assertNotEqual(g, self.s.claim("a", "phone2"))


if __name__ == "__main__":
    unittest.main()
