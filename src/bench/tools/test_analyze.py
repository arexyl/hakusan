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
        "pointer_held": False,
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


class Entries(unittest.TestCase):
    def test_a_terminal_names_the_category_of_a_failure(self) -> None:
        failed = {"terminal": {"outcome": "failed", "failure": "input_failed"}}
        canceled = {"terminal": {"outcome": "canceled", "failure": None}}

        self.assertEqual(analyze.terminal(failed), "failed input_failed")
        self.assertEqual(analyze.terminal(canceled), "canceled")
        self.assertEqual(analyze.terminal({}), "")

    def test_a_request_that_completed_under_a_cancellation_is_named_apart(
        self,
    ) -> None:
        completed = {"outcome": "completed", "failure": None}

        self.assertEqual(
            analyze.terminal({"terminal": completed, "clock": {"request": 1}}),
            "completed",
        )

        self.assertEqual(
            analyze.terminal(
                {"terminal": completed, "clock": {"request": 1, "canceled": 2}}
            ),
            "completed before its cancellation",
        )

    def test_the_peaks_come_from_the_terminal_read_and_the_rest_from_the_close(
        self,
    ) -> None:
        reads = {
            "reset": {"peak_live_bytes": 1, "peak_committed_bytes": 2, "live_bytes": 3},
            "terminal": {
                "peak_live_bytes": 40,
                "peak_committed_bytes": 50,
                "live_bytes": 60,
            },
            "close": {"peak_live_bytes": 40, "peak_committed_bytes": 50, "live_bytes": 7},
        }

        self.assertEqual(
            analyze.ledger({"ledger": reads}),
            {
                "ledger_peak_live_bytes": 40,
                "ledger_peak_committed_bytes": 50,
                "ledger_close_live_bytes": 7,
            },
        )

        self.assertEqual(analyze.ledger({}), {})

    def test_the_useful_draw_is_the_first_one_with_the_rows(self) -> None:
        draws = [
            {"ts": 30, "rest": [3, 1536]},
            {"ts": 10, "rest": [1, 800]},
            {"ts": 20, "rest": [2, 1423]},
        ]

        self.assertEqual(analyze.useful_draw(draws, 1423), draws[2])
        self.assertIsNone(analyze.useful_draw(draws, 1537))


class Cancellations(unittest.TestCase):
    def test_the_quiescent_job_ends_between_the_cancellation_and_the_return(
        self,
    ) -> None:
        jobs = [analyze.Job(90, 0, 2400), analyze.Job(120, 7, 199)]

        self.assertEqual(analyze.quiescence(jobs, 100, 130), jobs[1])
        self.assertIsNone(analyze.quiescence(jobs, 100, 110))
        self.assertIsNone(analyze.quiescence(jobs, 80, 130))

    def test_the_rows_after_a_cancellation_exclude_the_last_copy_before_it(
        self,
    ) -> None:
        job = analyze.Job(120, 7, 260)
        copies = [analyze.Copy(50, 0, 64), analyze.Copy(90, 64, 135)]

        self.assertEqual(analyze.rows_after(job, copies, 100), 61)
        self.assertEqual(analyze.rows_after(job, copies, 60), 196)
        self.assertEqual(analyze.rows_after(job, [], 100), 260)

    def test_a_summary_counts_only_a_completed_request_without_a_time_as_a_gap(
        self,
    ) -> None:
        canceled = {
            "block": "a",
            "cell": "cancel-wait",
            "backend": "san",
            "step": 0,
            "warmup": False,
            "outcome": "ok",
            "terminal": "canceled",
            "pointer_held": False,
            "gaps": "",
            "cancel_settled_ms": 2.0,
            "cancel_rows_bound": 0,
        }

        (summary,) = analyze.summarize([canceled, {**canceled, "terminal": "completed"}])

        self.assertEqual(summary["terminals"], {"canceled": 1, "completed": 1})
        self.assertEqual(summary["timing_gaps"], 1)
        self.assertEqual(summary["cancel_settled_ms"]["count"], 2)
        self.assertEqual(summary["cancel_rows_bound"]["maximum"], 0)

    def test_a_summary_describes_the_level_of_gpu_memory_beside_its_growth(
        self,
    ) -> None:
        request = {
            "block": "a",
            "cell": "ordinary",
            "backend": "san",
            "step": 0,
            "warmup": False,
            "outcome": "ok",
            "terminal": "completed",
            "pointer_held": False,
            "gaps": "",
            "display_ms": 100.0,
            "frame_period_ms": 8.3,
            "gpu_peak_bytes": 0.0,
            "gpu_level_bytes": 93_000_000.0,
        }

        (summary,) = analyze.summarize(
            [request, {**request, "gpu_level_bytes": 95_000_000.0}]
        )

        self.assertEqual(summary["gpu_peak_bytes"]["maximum"], 0.0)
        self.assertEqual(summary["gpu_level_bytes"]["median"], 94_000_000.0)
        self.assertEqual(summary["gpu_level_bytes"]["maximum"], 95_000_000.0)

    def test_a_summary_describes_the_level_of_anonymous_memory_at_a_request(
        self,
    ) -> None:
        request = {
            "block": "a",
            "cell": "ordinary",
            "backend": "san",
            "step": 0,
            "warmup": False,
            "outcome": "ok",
            "terminal": "completed",
            "pointer_held": False,
            "gaps": "",
            "anon_peak_bytes": 15_000_000.0,
            "anon_level_bytes": 120_000_000.0,
        }

        (summary,) = analyze.summarize(
            [request, {**request, "anon_level_bytes": 150_000_000.0}]
        )

        self.assertEqual(summary["anon_peak_bytes"]["maximum"], 15_000_000.0)
        self.assertEqual(summary["anon_level_bytes"]["median"], 135_000_000.0)
        self.assertEqual(summary["anon_level_bytes"]["maximum"], 150_000_000.0)

    def test_the_blocks_under_a_held_pointer_are_summarized_apart(self) -> None:
        free = {
            "block": "a",
            "cell": "ordinary",
            "backend": "san",
            "step": 0,
            "warmup": False,
            "outcome": "ok",
            "terminal": "completed",
            "pointer_held": False,
            "memory_events": True,
            "gaps": "",
            "display_ms": 100.0,
            "frame_period_ms": 16.6,
        }

        held = {
            **free,
            "block": "b",
            "pointer_held": True,
            "memory_events": False,
            "display_ms": 70.0,
            "frame_period_ms": 8.3,
        }

        without, under = analyze.summarize([free, held])

        self.assertEqual(
            (without["pointer_held"], without["frame_periods_ms"]), (False, [16.6])
        )

        self.assertEqual(
            (under["pointer_held"], under["display_ms"]["median"]), (True, 70.0)
        )

        # The blocks with the memory events under one condition are no side
        # of a check of the blocks without them under the other.
        self.assertEqual(analyze.overhead([free, held]), [])


if __name__ == "__main__":
    unittest.main()
