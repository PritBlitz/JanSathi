"""Tests for the re-check's decisions (no network). Run: python -I ingest/test_recheck.py"""
import datetime as dt
import importlib.util
import sys
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("recheck", Path(__file__).with_name("recheck.py"))
recheck = importlib.util.module_from_spec(spec)
sys.modules["recheck"] = recheck
spec.loader.exec_module(recheck)

TODAY = "2026-11-04"


class NextEntry(unittest.TestCase):
    def test_a_change_is_recorded_once_and_keeps_its_first_date(self):
        first = recheck.next_entry(None, {"status": "changed"}, TODAY)
        self.assertEqual(first, {"status": "changed", "since": TODAY})
        self.assertEqual(recheck.next_entry(first, {"status": "changed"}, "2026-11-05")["since"], TODAY)

    def test_matching_the_recorded_page_again_clears_the_warning(self):
        self.assertIsNone(recheck.next_entry({"status": "changed", "since": TODAY}, {"status": "unchanged"}, TODAY))

    def test_counters_alone_are_not_a_change(self):
        self.assertIsNone(recheck.next_entry(None, {"status": "counters_only"}, TODAY))

    def test_a_dismissal_lasts_only_until_the_words_change(self):
        dismissed = {"status": "dismissed", "since": TODAY, "stable_hash": "a", "by": "r", "note": "n"}
        self.assertEqual(recheck.next_entry(dismissed, {"status": "changed", "stable_hash": "a"}, TODAY), dismissed)
        self.assertEqual(recheck.next_entry(dismissed, {"status": "changed", "stable_hash": "b"}, TODAY)["status"], "changed")

    def test_a_failed_fetch_says_nothing_about_the_page(self):
        changed = {"status": "changed", "since": TODAY}
        self.assertEqual(recheck.next_entry(changed, {"status": "failed"}, "2026-11-05"), changed)
        self.assertIsNone(recheck.next_entry(None, {"status": "failed"}, TODAY))

    def test_robots_refusal_becomes_a_manual_check(self):
        self.assertEqual(recheck.next_entry(None, {"status": "disallowed_by_robots"}, TODAY), {"status": "manual_check", "since": TODAY})


class StableHash(unittest.TestCase):
    def test_numbers_and_dates_do_not_count(self):
        self.assertEqual(recheck.stable_hash("Houses 1,09,06,907 as on 25/10/2026"), recheck.stable_hash("Houses 1,09,06,777 as on 04/11/2026"))

    def test_words_do(self):
        self.assertNotEqual(recheck.stable_hash("Houses built"), recheck.stable_hash("Houses sanctioned"))


class ReverifyDue(unittest.TestCase):
    scheme = {
        "status": "published",
        "reverify_days": 30,
        "sources": [{"id": "g", "verified_at": "2026-10-01"}],
        "rules": [{"hard": True, "source": "g"}],
    }

    def test_on_the_last_day_it_is_not_due(self):
        self.assertEqual(recheck.reverify_due(self.scheme, dt.date(2026, 10, 31)), [])

    def test_one_day_past_it_is(self):
        self.assertEqual(recheck.reverify_due(self.scheme, dt.date(2026, 11, 1)), ["g"])


if __name__ == "__main__":
    unittest.main()
