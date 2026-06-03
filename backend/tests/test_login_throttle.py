"""Regression tests for the login brute-force throttle.

These guard the hardening in commit a620b9e: the per-IP+username bucket alone
was bypassable by forging X-Forwarded-For (one fresh bucket per request), so we
(a) stopped trusting X-Forwarded-For by default and (b) added a per-username
backstop that has no IP component. The tests below pin both behaviours so a
future refactor can't silently reopen the hole.

They exercise the pure throttle helpers + _client_ip directly (no DB / no
TestClient) — that's all the changed logic, and it keeps the test fast and
isolated from the real library.db.
"""
import time
import unittest

from app import main


class _StubClient:
    def __init__(self, host: str):
        self.host = host


class _StubRequest:
    """Minimal stand-in: _client_ip only touches .headers.get() and .client."""
    def __init__(self, headers=None, client_host="1.2.3.4"):
        self.headers = headers or {}
        self.client = _StubClient(client_host)


class LoginThrottleTest(unittest.TestCase):
    def setUp(self):
        main.LOGIN_FAILURES.clear()

    def tearDown(self):
        main.LOGIN_FAILURES.clear()

    def test_client_ip_ignores_forwarded_for_by_default(self):
        # A public caller can set any X-Forwarded-For it likes; with trust off
        # (the default) we must fall back to the real peer so the forged header
        # can't mint a fresh throttle bucket.
        self.assertFalse(main._TRUST_FORWARDED_FOR)
        req = _StubRequest(headers={"x-forwarded-for": "9.9.9.9"}, client_host="127.0.0.1")
        self.assertEqual(main._client_ip(req), "127.0.0.1")

    def test_per_username_backstop_survives_ip_rotation(self):
        # Simulate an attacker rotating the source IP every attempt: each lands
        # in a distinct ip+username bucket (so the per-IP limit never trips),
        # yet the per-username backstop keeps counting and ultimately blocks.
        username = "victim"
        user_key = f"user:{username.lower()}"
        for i in range(main.LOGIN_MAX_FAILURES_PER_USER):
            ip_key = f"10.0.0.{i}:{username}"
            main._record_login_failure(ip_key)
            main._record_login_failure(user_key)
            self.assertLess(
                len(main._pruned_login_failures(ip_key)),
                main.LOGIN_MAX_FAILURES,
                "per-IP bucket should never trip under IP rotation",
            )
        self.assertGreaterEqual(
            len(main._pruned_login_failures(user_key)),
            main.LOGIN_MAX_FAILURES_PER_USER,
            "per-username backstop must reach its limit despite IP rotation",
        )

    def test_old_failures_outside_window_are_pruned(self):
        key = "10.0.0.1:victim"
        stale = time.time() - (main.LOGIN_FAILURE_WINDOW_SECONDS + 10)
        main.LOGIN_FAILURES[key] = [stale, stale]
        self.assertEqual(main._pruned_login_failures(key), [])
        self.assertNotIn(key, main.LOGIN_FAILURES)


if __name__ == "__main__":
    unittest.main()
