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


def row(block: str, memory: bool | str, display: float) -> dict:
    return {
        "block": block,
        "cell": "ordinary",
        "backend": "image-decoder",
        "step": 0,
        "warmup": False,
        "memory": memory if isinstance(memory, str) else "trace" if memory else "none",
        "requested_refresh_hz": 120.0,
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

    def test_a_block_that_reads_proc_is_no_side_of_a_check(self) -> None:
        rows = [row("a", "proc", 100.0), row("b", False, 102.0)]

        self.assertEqual(analyze.overhead(rows), [])
        self.assertEqual(len(analyze.overhead([*rows, row("c", True, 101.0)])), 1)


def read(rss: int, peak: int, anon: int, swap: int = 0) -> dict:
    return {
        "rss_bytes": rss,
        "peak_bytes": peak,
        "anon_bytes": anon,
        "swap_bytes": swap,
    }


class Resident(unittest.TestCase):
    def attempt(self) -> list[dict]:
        """Two requests, the second of which ends its attempt. A read right
        after a reset still differs from its peak, as two reads of a process
        that runs may."""
        return [
            {"memory": {"request": read(100, 103, 60), "end": read(130, 180, 90)}},
            {
                "memory": {
                    "request": read(130, 134, 90),
                    "end": read(120, 170, 75),
                    "settled": read(110, 171, 62),
                }
            },
        ]

    def test_the_peak_rises_above_the_resident_memory_after_the_reset(self) -> None:
        attempt = self.attempt()

        self.assertEqual(
            analyze.resident(attempt, attempt[0], True),
            ({"anon_level_bytes": 60, "resident_peak_bytes": 80}, []),
        )

    def test_the_last_request_carries_what_the_attempt_retains(self) -> None:
        attempt = self.attempt()

        self.assertEqual(
            analyze.resident(attempt, attempt[1], True),
            (
                {
                    "anon_level_bytes": 90,
                    "resident_peak_bytes": 40,
                    # Above the anonymous memory at the first request.
                    "anon_retained_close_bytes": 15,
                    "anon_retained_settled_bytes": 2,
                },
                [],
            ),
        )

    def test_a_denied_reset_leaves_no_peak(self) -> None:
        attempt = self.attempt()
        values, gaps = analyze.resident(attempt, attempt[0], False)

        self.assertEqual(values, {"anon_level_bytes": 60})
        self.assertEqual(gaps, ["the platform denied the reset of the peak"])

    def test_a_moved_swap_counter_is_a_gap_of_what_it_spans(self) -> None:
        attempt = self.attempt()
        attempt[0]["memory"]["end"]["swap_bytes"] = 4096
        attempt[1]["memory"]["settled"]["swap_bytes"] = 4096

        self.assertEqual(
            analyze.resident(attempt, attempt[0], True)[1],
            ["the swap counter changed during the request"],
        )

        self.assertEqual(
            analyze.resident(attempt, attempt[1], True)[1],
            ["the swap counter changed during the attempt"],
        )

    def test_swap_that_moved_before_the_last_request_is_a_gap_of_the_attempt(
        self,
    ) -> None:
        attempt = self.attempt()

        for point in ("request", "end", "settled"):
            attempt[1]["memory"][point]["swap_bytes"] = 4096

        # The peak of the last request spans no change, and what the attempt
        # retains at its close and after it does.
        self.assertEqual(
            analyze.resident(attempt, attempt[1], True)[1],
            ["the swap counter changed during the attempt"],
        )

        del attempt[1]["memory"]["settled"]

        self.assertIn(
            "the swap counter changed during the attempt",
            analyze.resident(attempt, attempt[1], True)[1],
        )

    def test_a_request_without_its_reads_has_no_value(self) -> None:
        attempt = self.attempt()
        del attempt[1]["memory"]["end"]

        self.assertEqual(
            analyze.resident(attempt, attempt[1], True),
            ({}, ["no memory read at the request or at its end"]),
        )

        del attempt[0]["memory"]

        self.assertEqual(analyze.resident(attempt, attempt[0], True)[0], {})

    def test_an_attempt_without_its_settled_read_retains_only_at_the_close(
        self,
    ) -> None:
        attempt = self.attempt()
        del attempt[1]["memory"]["settled"]
        values, gaps = analyze.resident(attempt, attempt[1], True)

        self.assertNotIn("anon_retained_settled_bytes", values)
        self.assertEqual(values["anon_retained_close_bytes"], 15)
        self.assertEqual(gaps, ["no memory read at the end of the settle interval"])


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
            "close": {
                "peak_live_bytes": 40,
                "peak_committed_bytes": 50,
                "live_bytes": 7,
                "kept_bytes": 5,
            },
        }

        self.assertEqual(
            analyze.ledger({"ledger": reads}),
            {
                "ledger_peak_live_bytes": 40,
                "ledger_peak_committed_bytes": 50,
                "ledger_close_live_bytes": 7,
                "ledger_close_kept_bytes": 5,
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


class AfterJob(unittest.TestCase):
    MS = 1_000_000

    def values(self, draw: int, withdrawals: tuple[int, ...] = ()) -> dict:
        """A request from 100 ms on whose job ends at 157 ms, with a frame
        callback every 16 ms from 110 ms on, in milliseconds."""
        jobs = [analyze.Job(90 * self.MS, 0, 5), analyze.Job(157 * self.MS, 0, 2400)]
        frames = [(110 + 16 * index) * self.MS for index in range(8)]

        return analyze.after_job(
            jobs,
            [at * self.MS for at in withdrawals],
            frames,
            100 * self.MS,
            draw * self.MS,
        )

    def test_a_final_output_in_the_first_frame_after_the_job_is_not_late(self) -> None:
        # The frame callbacks around the end of the job begin at 142 and 158.
        self.assertEqual(
            self.values(draw=159),
            {
                "job_ms": 57.0,
                "final_after_job_ms": 2.0,
                "late_frames": 0,
                "withdrawals": 0,
            },
        )

    def test_each_frame_callback_before_the_one_that_draws_is_a_late_frame(
        self,
    ) -> None:
        self.assertEqual(self.values(draw=175)["late_frames"], 1)
        self.assertEqual(self.values(draw=191)["late_frames"], 2)
        self.assertEqual(self.values(draw=175)["final_after_job_ms"], 18.0)

    def test_only_the_withdrawals_of_the_request_count(self) -> None:
        self.assertEqual(self.values(159, withdrawals=(95, 157, 300))["withdrawals"], 1)

    def test_the_last_job_before_the_draw_is_the_job_of_the_request(self) -> None:
        early = analyze.Job(120 * self.MS, 0, 0)
        late = analyze.Job(157 * self.MS, 0, 2400)
        frames = [158 * self.MS]

        values = analyze.after_job(
            [early, late], [], frames, 100 * self.MS, 159 * self.MS
        )

        self.assertEqual(values["job_ms"], 57.0)

    def test_a_request_without_a_job_has_no_value(self) -> None:
        self.assertEqual(
            analyze.after_job([], [], [150 * self.MS], 100 * self.MS, 159 * self.MS), {}
        )

        # A job that ended before the request is no job of it.
        self.assertEqual(
            analyze.after_job(
                [analyze.Job(90 * self.MS, 0, 5)], [], [], 100 * self.MS, 159 * self.MS
            ),
            {},
        )

    def test_a_job_that_ends_inside_the_frame_of_the_draw_is_not_late(self) -> None:
        # The callback began at 158, before the job ended at 158.5.
        jobs = [analyze.Job(158 * self.MS + self.MS // 2, 0, 2400)]

        values = analyze.after_job(
            jobs, [], [158 * self.MS], 100 * self.MS, 159 * self.MS
        )

        self.assertEqual(values["late_frames"], 0)

    def test_a_summary_counts_the_requests_by_their_late_frames(self) -> None:
        request = {
            "block": "a",
            "cell": "ordinary",
            "backend": "san",
            "step": 0,
            "warmup": False,
            "outcome": "ok",
            "terminal": "completed",
            "requested_refresh_hz": 120.0,
            "pointer_held": False,
            "memory": "none",
            "gaps": "",
            "display_ms": 100.0,
            "frame_period_ms": 16.6,
            "job_ms": 57.0,
            "final_after_job_ms": 2.0,
            "late_frames": 0,
            "withdrawals": 1,
        }

        late = {**request, "late_frames": 1, "withdrawals": 0}
        (summary,) = analyze.summarize([request, request, late])

        self.assertEqual(summary["late_frames"], {0: 2, 1: 1})
        self.assertEqual(summary["withdrawals"], 2)
        self.assertEqual(summary["job_ms"]["median"], 57.0)

        # A reference has no job of the reader, and its summary no such count.
        for key in ("job_ms", "final_after_job_ms", "late_frames", "withdrawals"):
            del request[key]

        self.assertNotIn("late_frames", analyze.summarize([request])[0])


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
            "requested_refresh_hz": 120.0,
            "pointer_held": False,
            "memory": "none",
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
            "requested_refresh_hz": 120.0,
            "pointer_held": False,
            "memory": "trace",
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
            "requested_refresh_hz": 120.0,
            "pointer_held": False,
            "memory": "trace",
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

    def test_a_cell_takes_its_times_from_its_blocks_without_memory(self) -> None:
        timed = {
            "block": "a",
            "cell": "ordinary",
            "backend": "san",
            "step": 0,
            "warmup": False,
            "outcome": "ok",
            "terminal": "completed",
            "requested_refresh_hz": 120.0,
            "pointer_held": False,
            "memory": "none",
            "gaps": "",
            "display_ms": 100.0,
            "frame_period_ms": 16.6,
            "close_ms": 1.0,
        }

        slower = {"display_ms": 108.0, "close_ms": 3.0, "anon_peak_bytes": 15.0}
        events = {**timed, **slower, "block": "m", "memory": "trace"}
        reads = {**timed, **slower, "block": "p", "memory": "proc"}
        untimed = {**events, "block": "n"}

        del untimed["display_ms"]

        (summary,) = analyze.summarize([timed, events, reads, untimed])

        self.assertEqual((summary["requests"], summary["timed_requests"]), (4, 1))
        self.assertTrue(summary["times_from_blocks_without_memory"])
        self.assertEqual(summary["display_ms"]["maximum"], 100.0)
        self.assertEqual(summary["close_ms"]["maximum"], 1.0)
        self.assertEqual(summary["block_medians_ms"], {"a": 100.0})
        self.assertEqual(summary["timing_gaps"], 0)
        self.assertEqual(summary["anon_peak_bytes"]["count"], 3)

        # A cell whose blocks all read memory has no other times.
        (validation,) = analyze.summarize([events, reads, untimed])

        self.assertEqual(validation["timed_requests"], 3)
        self.assertFalse(validation["times_from_blocks_without_memory"])
        self.assertEqual(validation["display_ms"]["maximum"], 108.0)
        self.assertEqual(validation["timing_gaps"], 1)

    def test_the_blocks_under_a_held_pointer_are_summarized_apart(self) -> None:
        free = {
            "block": "a",
            "cell": "ordinary",
            "backend": "san",
            "step": 0,
            "warmup": False,
            "outcome": "ok",
            "terminal": "completed",
            "requested_refresh_hz": 120.0,
            "pointer_held": False,
            "memory": "trace",
            "gaps": "",
            "display_ms": 100.0,
            "frame_period_ms": 16.6,
        }

        held = {
            **free,
            "block": "b",
            "pointer_held": True,
            "memory": "none",
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

    def test_the_blocks_at_two_requested_rates_are_summarized_apart(self) -> None:
        fast = {
            "block": "a",
            "cell": "ordinary",
            "backend": "san",
            "step": 0,
            "warmup": False,
            "outcome": "ok",
            "terminal": "completed",
            "requested_refresh_hz": 120.0,
            "pointer_held": False,
            "memory": "none",
            "gaps": "",
            "display_ms": 70.0,
            "frame_period_ms": 8.3,
        }

        slow = {
            **fast,
            "block": "b",
            "requested_refresh_hz": 60.0,
            "display_ms": 100.0,
            "frame_period_ms": 16.6,
        }

        # The lower rate sorts first. Neither block holds a pointer, so the
        # rate alone keeps the two conditions of one display apart.
        at_60, at_120 = analyze.summarize([fast, slow, slow])

        self.assertEqual(
            (at_60["requested_refresh_hz"], at_60["requests"]), (60.0, 2)
        )

        self.assertEqual(
            (at_120["requested_refresh_hz"], at_120["display_ms"]["median"]),
            (120.0, 70.0),
        )

        self.assertEqual(
            analyze.overhead([{**fast, "memory": "trace"}, slow]), []
        )

    def test_a_summary_reports_the_thermal_status_and_keeps_its_requests(
        self,
    ) -> None:
        cool = {
            "block": "a",
            "cell": "ordinary",
            "backend": "san",
            "step": 0,
            "warmup": False,
            "outcome": "ok",
            "terminal": "completed",
            "requested_refresh_hz": 120.0,
            "pointer_held": False,
            "memory": "none",
            "gaps": "",
            "display_ms": 70.0,
            "frame_period_ms": 8.3,
            "thermal_status": 0,
        }

        warm = {**cool, "display_ms": 140.0, "thermal_status": 2}

        # A request under a limit stays in its group, where it is slow.
        (summary,) = analyze.summarize([cool, cool, warm])

        self.assertEqual(
            (summary["thermal_requests"], summary["thermal_maximum"]), (1, 2)
        )

        self.assertEqual(summary["display_ms"]["maximum"], 140.0)

        # A row without the status, as of a block that recorded none.
        del cool["thermal_status"]

        (unread,) = analyze.summarize([cool])

        self.assertEqual(
            (unread["thermal_requests"], unread["thermal_maximum"]), (0, None)
        )


def conditions(model: str = "phone", page_size: int = 4096) -> dict:
    return {
        "os": {"sdk": 36, "incremental": "1"},
        "device": {"model": model, "soc": "soc", "page_size": page_size},
        "display": {
            "window": [1080, 2340],
            "modes": [{"width": 1080, "height": 2340, "refresh_hz": 120.0}],
            "requested_refresh_hz": 120.0,
            "refresh_hz": 120.0,
        },
        "harness": {
            "debuggable": False,
            "decode_thread": "decode",
            "decode_nice": 0,
            "take": "frame",
            "san": {"commit": "a"},
        },
    }


class Identity(unittest.TestCase):
    def ending(self, block: str, record: dict, host: dict) -> dict:
        return {"block": block, "identity": analyze.identity(record, host)}

    def test_blocks_of_one_device_and_one_build_share_their_identity(self) -> None:
        record = {"conditions": conditions()}
        endings = [
            self.ending("a", record, {"apk_sha256": "1"}),
            self.ending("b", record, {"apk_sha256": "1"}),
            # A block that failed before its first request has none.
            {"block": "c"},
        ]

        shared = analyze.shared_identity(endings)

        self.assertEqual(shared["device"]["model"], "phone")
        self.assertEqual(shared["apk_sha256"], "1")
        self.assertTrue(all("identity" not in ending for ending in endings))
        self.assertIsNone(analyze.shared_identity([{"block": "c"}]))

    def test_the_rate_that_a_block_requests_is_no_part_of_the_identity(self) -> None:
        other = conditions()
        other["display"]["requested_refresh_hz"] = 60.0
        other["display"]["refresh_hz"] = 60.0

        analyze.shared_identity(
            [
                self.ending("a", {"conditions": conditions()}, {}),
                self.ending("b", {"conditions": other}, {}),
            ]
        )

    def test_blocks_that_differ_in_what_they_share_fail_the_analysis(self) -> None:
        san = conditions()
        san["harness"]["san"] = {"commit": "b"}

        window = conditions()
        window["display"]["window"] = [1440, 3120]

        cases = {
            "device.model": ({"conditions": conditions(model="other")}, {}),
            # Two virtual devices may differ in nothing but their pages.
            "device.page_size": ({"conditions": conditions(page_size=16384)}, {}),
            "harness.san.commit": ({"conditions": san}, {}),
            "display.window": ({"conditions": window}, {}),
            "apk_sha256": ({"conditions": conditions()}, {"apk_sha256": "2"}),
        }

        for where, (record, host) in cases.items():
            with self.subTest(where), self.assertRaises(analyze.Failure) as raised:
                analyze.shared_identity(
                    [
                        self.ending("a", {"conditions": conditions()}, {}),
                        self.ending("b", record, host),
                    ]
                )

            self.assertIn(f"a and b differ in {where}:", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
