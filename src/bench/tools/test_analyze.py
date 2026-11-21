#!/usr/bin/python3 -I
"""Tests of the statistics and of the counter reads of analyze.py.

Run it as a script. The isolated interpreter leaves the directory of a script
off the module path, so the test loads analyze.py by its file.
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest

_SPEC = importlib.util.spec_from_file_location(
    "analyze", pathlib.Path(__file__).resolve().parent / "analyze.py"
)

assert _SPEC is not None and _SPEC.loader is not None

analyze = importlib.util.module_from_spec(_SPEC)
sys.modules["analyze"] = analyze
_SPEC.loader.exec_module(analyze)


def row(block: str, memory: bool, display: float) -> dict:
    return {
        "block": block,
        "cell": "ordinary",
        "backend": "image-decoder",
        "step": 0,
        "warmup": False,
        "memory_events": memory,
        "display_ms": display,
    }


class Ranks(unittest.TestCase):
    def test_p95_of_120_attempts_is_the_114th_value(self) -> None:
        self.assertEqual(analyze.nearest_rank(120, 0.95), 114)
        self.assertEqual(analyze.nearest_rank(120, 0.5), 60)
        self.assertEqual(analyze.nearest_rank(1, 0.95), 1)

    def test_upper_bound_of_p95_is_the_119th_value(self) -> None:
        bound = analyze.upper_bound(120, 0.95, 0.95)

        self.assertEqual(bound.upper, 119)
        self.assertAlmostEqual(bound.coverage, 0.984, places=3)

    def test_interval_of_p95_runs_from_the_109th_to_the_119th_value(self) -> None:
        found = analyze.interval(120, 0.95, 0.95)

        self.assertEqual((found.lower, found.upper), (109, 119))
        self.assertAlmostEqual(found.coverage, 0.967, places=3)

    def test_interval_of_the_median_runs_from_the_49th_to_the_71st_value(self) -> None:
        found = analyze.interval(120, 0.5, 0.95)

        self.assertEqual((found.lower, found.upper), (49, 71))
        self.assertAlmostEqual(found.coverage, 0.955, places=3)

    def test_sixty_attempts_bound_p95_only_by_their_maximum(self) -> None:
        self.assertEqual(analyze.upper_bound(60, 0.95, 0.95).upper, 60)

    def test_a_small_sample_has_no_interval(self) -> None:
        self.assertIsNone(analyze.interval(5, 0.95, 0.95))
        self.assertIsNone(analyze.upper_bound(5, 0.95, 0.95))

    def test_describe_reads_the_values_at_the_ranks(self) -> None:
        described = analyze.describe([float(value) for value in range(120, 0, -1)])

        self.assertEqual(described["median"], 60.0)
        self.assertEqual(described["p95"], 114.0)
        self.assertEqual(described["p95_interval"]["values"], [109.0, 119.0])
        self.assertEqual(described["p95_upper_bound"]["values"][1], 119.0)
        self.assertEqual(described["maximum"], 120.0)


class Counters(unittest.TestCase):
    counter = analyze.Counter([(10, 1.0), (20, 5.0), (30, 2.0)])

    def test_a_read_takes_the_last_value_at_or_before_its_time(self) -> None:
        self.assertIsNone(self.counter.at(9))
        self.assertEqual(self.counter.at(10), 1.0)
        self.assertEqual(self.counter.at(29), 5.0)

    def test_a_peak_includes_the_value_that_held_at_its_start(self) -> None:
        self.assertEqual(self.counter.peak(21, 29), 5.0)
        self.assertEqual(self.counter.peak(10, 30), 5.0)
        self.assertEqual(self.counter.peak(30, 40), 2.0)
        self.assertIsNone(self.counter.peak(5, 40))


class Settle(unittest.TestCase):
    MS = 1_000_000

    def test_the_shortest_still_candidate_is_the_interval(self) -> None:
        # The value still falls by a megabyte between 125 and 250 ms.
        counter = analyze.Counter(
            [(0, 9e6), (200 * self.MS, 8e6), (2000 * self.MS, 8e6 + 4096)]
        )

        self.assertEqual(analyze.settle(counter, 0, 2000 * self.MS), 250)

    def test_a_candidate_needs_twice_its_length_before_the_mark(self) -> None:
        counter = analyze.Counter([(0, 9e6), (200 * self.MS, 8e6)])

        self.assertIsNone(analyze.settle(counter, 0, 400 * self.MS))
        self.assertEqual(analyze.settle(counter, 0, 500 * self.MS), 250)


class Overhead(unittest.TestCase):
    def check(self, without: list[float]) -> dict:
        rows = [row("a", True, 100.0), row("b", True, 102.0)]

        rows += [
            row(f"c{index}", False, value) for index, value in enumerate(without)
        ]

        (check,) = analyze.overhead(rows)

        return check

    def test_a_difference_inside_the_range_without_events_passes(self) -> None:
        check = self.check([98.0, 104.0])

        self.assertEqual(check["range_without_events_ms"], 6.0)
        self.assertTrue(check["passes"])

    def test_a_difference_at_the_range_fails(self) -> None:
        check = self.check([98.0, 100.0])

        self.assertEqual(check["with_events_ms"] - check["without_events_ms"], 2.0)
        self.assertEqual(check["range_without_events_ms"], 2.0)
        self.assertFalse(check["passes"])

    def test_a_cell_with_one_side_has_no_check(self) -> None:
        self.assertEqual(analyze.overhead([row("a", True, 100.0)]), [])


if __name__ == "__main__":
    unittest.main()
