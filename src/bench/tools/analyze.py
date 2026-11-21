#!/usr/bin/python3 -I
"""Derives the results of measured blocks from their records and traces.

A block is the three files that block.py stores under one name. The tool
writes one row per request into requests.csv and the statistics of each cell,
backend, and step into summary.json, both beside the blocks. It never drops a
request: a value that the trace cannot establish is a gap, and the row says
why.

The display time of a request runs from its request mark to the presentation
of the frame that drew its final output. That frame is found in three steps:
the draw section lies in a frame callback of the main thread whose name holds
the frame's vsync ID, the surface frame of the harness with that ID names a
display frame, and the end of that display frame is the presentation.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import math
import pathlib
import statistics
import subprocess
import sys
from typing import Any, NamedTuple

TRACE_PROCESSOR = "trace_processor_shell"

# The trace processor prints one table per query, in this order.
QUERIES = """
create perfetto table harness as
select distinct t.upid
from slice s
join thread_track tt on s.track_id = tt.id
join thread t using (utid)
where s.name glob 'bench.request *';

select
  s.ts, s.dur, s.name, t.name as thread,
  (select a.name from ancestor_slice(s.id) a where a.depth = 0) as root
from slice s
join thread_track tt on s.track_id = tt.id
join thread t using (utid)
where s.name glob 'bench.*' and t.upid in (select upid from harness)
order by s.ts;

select
  a.surface_frame_token as token, a.display_frame_token as display,
  a.ts, a.present_type,
  (select string_value from args
   where arg_set_id = a.arg_set_id and key = 'Is Buffer?') as buffer
from actual_frame_timeline_slice a
where a.upid in (select upid from harness)
  and a.layer_name glob '*BenchActivity*'
order by a.ts;

select display_frame_token as display, ts + dur as end, jank_type
from actual_frame_timeline_slice
where surface_frame_token is null
order by ts + dur;

select ct.name, c.ts, c.value
from counter c
join process_counter_track ct on c.track_id = ct.id
where ct.upid in (select upid from harness)
  and ct.name in ('mem.rss.anon', 'mem.swap', 'GPU Memory')
order by c.ts;

select s.ts
from slice s
join thread_track tt on s.track_id = tt.id
join thread t using (utid)
where s.name glob 'Choreographer#doFrame [0-9]*' and s.depth = 0
  and t.upid in (select upid from harness)
order by s.ts;

select name, sum(value) as value
from stats
where name in (
  'traced_buf_chunks_discarded', 'traced_buf_trace_writer_packet_loss',
  'traced_buf_packets_dropped', 'ftrace_cpu_overrun_delta',
  'ftrace_cpu_dropped_events_delta'
)
group by name
having sum(value) != 0;

select name, count(*) as count
from ftrace_event
where name in ('rss_stat_throttled', 'rss_stat', 'gpu_mem_total')
group by name;
"""

ANON = "mem.rss.anon"
SWAP = "mem.swap"
GPU = "GPU Memory"

# The longest distance of two frame callbacks that still count as consecutive.
CADENCE_LIMIT_NS = 100_000_000

# The throttled event of resident memory fires when a value enters another
# bucket of this size, so a read is that far from the true value at most, and
# a difference of two reads twice that far.
ANON_RESOLUTION_BYTES = 512 * 1024

# The candidates for the settle interval, in milliseconds. A candidate holds
# for an attempt when the retained memory after it and after twice its length
# differ by less than the resolution.
SETTLE_CANDIDATES_MS = (125, 250, 500, 1000)


class Failure(Exception):
    """The blocks cannot be analyzed. The message names the reason."""


class Interval(NamedTuple):
    """The ranks of a distribution-free confidence interval, from 1."""

    lower: int
    upper: int
    coverage: float


def nearest_rank(count: int, quantile: float) -> int:
    """Returns the rank of a quantile among count sorted values, from 1."""
    return max(1, math.ceil(quantile * count))


def below(count: int, quantile: float, rank: int) -> float:
    """Returns the probability that fewer than rank values lie below the
    quantile of their distribution."""
    return sum(
        math.comb(count, i) * quantile**i * (1 - quantile) ** (count - i)
        for i in range(rank)
    )


def upper_bound(count: int, quantile: float, level: float) -> Interval | None:
    """Returns the lowest rank that bounds the quantile from above with at
    least the level, or None when even the maximum does not."""
    for rank in range(1, count + 1):
        coverage = below(count, quantile, rank)

        if coverage >= level:
            return Interval(1, rank, coverage)

    return None


def interval(count: int, quantile: float, level: float) -> Interval | None:
    """Returns the shortest two-sided interval of ranks that holds the
    quantile with at least the level.

    Of two shortest intervals it takes the one with the higher coverage, and
    then the one with the lower ranks. It returns None when no pair of ranks
    reaches the level.
    """
    best: Interval | None = None
    cumulative = [below(count, quantile, rank) for rank in range(count + 1)]

    for lower in range(1, count + 1):
        for upper in range(lower + 1, count + 1):
            coverage = cumulative[upper] - cumulative[lower]

            if coverage < level:
                continue

            candidate = Interval(lower, upper, coverage)

            if best is None or (upper - lower, -coverage) < (
                best.upper - best.lower,
                -best.coverage,
            ):
                best = candidate

            break

    return best


def describe(values: list[float]) -> dict[str, Any]:
    """Returns the order statistics of one sample with their intervals."""
    ordered = sorted(values)
    count = len(ordered)

    def at(rank: int) -> float:
        return ordered[rank - 1]

    def bounds(found: Interval | None) -> dict[str, Any] | None:
        if found is None:
            return None

        return {
            "ranks": [found.lower, found.upper],
            "values": [at(found.lower), at(found.upper)],
            "coverage": round(found.coverage, 4),
        }

    return {
        "count": count,
        "median": at(nearest_rank(count, 0.5)),
        "median_interval": bounds(interval(count, 0.5, 0.95)),
        "p95": at(nearest_rank(count, 0.95)),
        "p95_interval": bounds(interval(count, 0.95, 0.95)),
        "p95_upper_bound": bounds(upper_bound(count, 0.95, 0.95)),
        "maximum": ordered[-1],
    }


def tables(trace: pathlib.Path) -> list[list[dict[str, str]]]:
    """Runs the queries on a trace and returns one list of rows per table."""
    result = subprocess.run(
        (TRACE_PROCESSOR, "-Q", QUERIES, str(trace)),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        encoding="utf-8",
    )

    if result.returncode != 0:
        raise Failure(f"{trace.name}: {result.stderr.strip()}")

    return [
        list(csv.DictReader(io.StringIO(table)))
        for table in result.stdout.strip("\n").split("\n\n")
    ]


def number(text: str) -> int | None:
    """Reads an integer cell, where the trace processor prints null as text."""
    return None if text in ("", "[NULL]") else int(float(text))


class Counter:
    """The samples of one counter, in time order."""

    def __init__(self, samples: list[tuple[int, float]]) -> None:
        self.samples = samples

    def at(self, ts: int) -> float | None:
        """Returns the last value at or before ts."""
        value = None

        for sample_ts, sample in self.samples:
            if sample_ts > ts:
                break

            value = sample

        return value

    def peak(self, start: int, end: int) -> float | None:
        """Returns the highest value that held between start and end."""
        first = self.at(start)

        if first is None:
            return None

        inside = [value for ts, value in self.samples if start < ts <= end]

        return max([first, *inside])


class Trace:
    """What one trace holds about the harness."""

    def __init__(self, path: pathlib.Path) -> None:
        marks, surfaces, displays, counters, frames, losses, events = tables(path)

        self.marks: dict[tuple[str, int], list[dict[str, Any]]] = {}

        for row in marks:
            kind, request, *rest = row["name"].split(" ")

            self.marks.setdefault((kind, int(request)), []).append(
                {
                    "ts": int(row["ts"]),
                    "dur": int(row["dur"]),
                    "rest": [int(value) for value in rest],
                    "thread": row["thread"],
                    "root": row["root"],
                }
            )

        self.surfaces = [
            {
                "token": number(row["token"]),
                "display": number(row["display"]),
                "ts": int(row["ts"]),
                "presented": row["present_type"] != "Dropped Frame",
                "buffer": row["buffer"] == "Yes",
            }
            for row in surfaces
        ]

        self.displays = {
            number(row["display"]): (int(row["end"]), row["jank_type"])
            for row in displays
        }

        self.counters = {
            name: Counter(
                [
                    (int(row["ts"]), float(row["value"]))
                    for row in counters
                    if row["name"] == name
                ]
            )
            for name in (ANON, SWAP, GPU)
        }

        self.frames = [int(row["ts"]) for row in frames]
        self.losses = {row["name"]: float(row["value"]) for row in losses}
        self.events = {row["name"]: int(row["count"]) for row in events}

    def mark(self, kind: str, request: int) -> dict[str, Any] | None:
        """Returns the one mark of a kind for a request, or None."""
        found = self.marks.get((kind, request), [])

        return found[0] if len(found) == 1 else None

    def presentation(self, draw: dict[str, Any]) -> tuple[int | None, list[str]]:
        """Returns when the frame of a draw section was presented, with the
        reasons that make the time a gap."""
        root = draw["root"].split(" ")

        # A late frame nests a callback that names a later timeline, so only
        # the outermost callback names the vsync ID of the buffer.
        if root[0] != "Choreographer#doFrame" or len(root) != 2:
            return None, ["the draw lies in no frame callback"]

        vsync = int(root[1])
        gaps: list[str] = []

        own = [
            frame
            for frame in self.surfaces
            if frame["token"] == vsync and frame["buffer"] and frame["presented"]
        ]

        if own:
            surface = own[0]
        else:
            later = [
                frame
                for frame in self.surfaces
                if frame["ts"] > draw["ts"] and frame["buffer"] and frame["presented"]
            ]

            if not later:
                return None, ["no surface frame presents the draw"]

            surface = later[0]
            gaps.append("the frame of the draw was dropped; a later frame presents it")

        display = self.displays.get(surface["display"])

        if display is None:
            return None, ["the surface frame names no display frame"]

        end, jank = display

        # Such a display frame had no valid present fence, so its end is the
        # end of composition and not a presentation.
        if "Unknown" in jank and "Display HAL" in jank:
            return None, ["the display frame has no present fence"]

        return end, gaps

    def cadence(self, ts: int) -> float | None:
        """Returns the median distance of the frame callbacks that follow ts,
        in milliseconds."""
        following = [frame for frame in self.frames if frame >= ts][:5]

        steps = [
            later - earlier
            for earlier, later in zip(following, following[1:])
            if later - earlier < CADENCE_LIMIT_NS
        ]

        return statistics.median(steps) / 1e6 if steps else None


def difference(counter: Counter, start: int, end: int) -> float | None:
    """Returns how far the value at end lies above the value at start."""
    first = counter.at(start)
    last = counter.at(end)

    return None if first is None or last is None else last - first


def settle(counter: Counter, close: int, settled: int) -> int | None:
    """Returns the shortest candidate interval after which the counter holds
    still, in milliseconds, or None when no candidate that fits before the
    settled mark does."""
    for candidate in SETTLE_CANDIDATES_MS:
        once = close + candidate * 1_000_000
        twice = close + 2 * candidate * 1_000_000

        if twice > settled:
            break

        moved = difference(counter, once, twice)

        if moved is not None and abs(moved) < ANON_RESOLUTION_BYTES:
            return candidate

    return None


def analyze_block(
    directory: pathlib.Path, name: str
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Returns how a block ended, and one row per request of it."""
    record = json.loads((directory / f"{name}.json").read_text(encoding="utf-8"))
    host = json.loads((directory / f"{name}.host.json").read_text(encoding="utf-8"))

    ending = {
        "block": name,
        "outcome": record["outcome"],
        "failure": record.get("failure"),
    }

    # A block that failed before its first request recorded no conditions.
    if "conditions" not in record:
        return ending, []

    if record["conditions"]["harness"]["debuggable"]:
        raise Failure(f"{name}: a debuggable harness measures nothing")

    trace = Trace(directory / f"{name}.perfetto-trace")

    block_gaps = [f"the trace lost data: {loss}" for loss in sorted(trace.losses)]

    if host["memory_events"]:
        block_gaps += [
            f"the trace holds no {event} event"
            for event in ("rss_stat_throttled", "gpu_mem_total")
            if event not in trace.events
        ]
    requests = record["requests"]
    rows = []

    for index, entry in enumerate(requests):
        request = entry["request"]
        attempt = [other for other in requests if other["attempt"] == entry["attempt"]]
        gaps = list(block_gaps)

        row: dict[str, Any] = {
            "block": name,
            "cell": record["cell"],
            "backend": record["backend"],
            "memory_events": host["memory_events"],
            "request": request,
            "attempt": entry["attempt"],
            "step": entry["step"],
            "warmup": entry["warmup"],
            "outcome": entry["outcome"],
            "thermal_status": entry["conditions"]["thermal_status"],
        }

        start = trace.mark("bench.request", request)
        decode = trace.mark("bench.decode", request)
        draws = trace.marks.get(("bench.present", request), [])

        if start is None:
            gaps.append("no request mark")
        elif entry["outcome"] != "ok":
            gaps.append(f"the request ended as {entry['outcome']}")
        elif not draws:
            gaps.append("no draw of an output")
        else:
            # The final output is the last revision that was drawn.
            draw = max(draws, key=lambda mark: mark["rest"][0])
            presented, reasons = trace.presentation(draw)

            gaps.extend(reasons)
            row["draw_ms"] = (draw["ts"] - start["ts"]) / 1e6
            row["frame_period_ms"] = trace.cadence(draw["ts"])

            if presented is not None:
                row["display_ms"] = (presented - start["ts"]) / 1e6

        if decode is not None:
            row["decode_ms"] = decode["dur"] / 1e6

        # A block of an overhead check without the memory events has no
        # memory values, which is no gap.
        if start is not None and host["memory_events"]:
            first = trace.mark("bench.request", attempt[0]["request"])
            close = trace.mark("bench.close", attempt[-1]["request"])
            settled = trace.mark("bench.settled", attempt[-1]["request"])
            following = requests[index + 1] if entry is not attempt[-1] else None

            end = (
                trace.mark("bench.request", following["request"])
                if following is not None
                else close
            )

            if end is None or first is None:
                gaps.append("no end mark for the memory of the request")
            else:
                for counter, column in ((ANON, "anon"), (GPU, "gpu")):
                    samples = trace.counters[counter]
                    base = samples.at(start["ts"])
                    peak = samples.peak(start["ts"], end["ts"])

                    if base is None or peak is None:
                        gaps.append(f"no {column} memory value at the request")

                        continue

                    row[f"{column}_peak_bytes"] = peak - base

                    # The retained values belong to the attempt, so its last
                    # request carries them.
                    if following is None and close is not None and settled is not None:
                        row[f"{column}_retained_close_bytes"] = difference(
                            samples, first["ts"], close["ts"]
                        )

                        row[f"{column}_retained_settled_bytes"] = difference(
                            samples, first["ts"], settled["ts"]
                        )

                if following is None and close is not None and settled is not None:
                    row["settle_ms"] = settle(
                        trace.counters[ANON], close["ts"], settled["ts"]
                    )

                swap = trace.counters[SWAP]

                if swap.at(start["ts"]) != swap.at(end["ts"]):
                    gaps.append("the swap counter changed during the request")

        row["gaps"] = "; ".join(gaps)
        rows.append(row)

    return ending, rows


COLUMNS = [
    "block",
    "cell",
    "backend",
    "memory_events",
    "request",
    "attempt",
    "step",
    "warmup",
    "outcome",
    "decode_ms",
    "draw_ms",
    "display_ms",
    "frame_period_ms",
    "anon_peak_bytes",
    "anon_retained_close_bytes",
    "anon_retained_settled_bytes",
    "gpu_peak_bytes",
    "gpu_retained_close_bytes",
    "gpu_retained_settled_bytes",
    "settle_ms",
    "thermal_status",
    "gaps",
]


def summarize(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Returns the statistics of each cell, backend, and step position over
    its measured requests. Step positions are never pooled."""
    groups: dict[tuple[str, str, int], list[dict[str, Any]]] = {}

    for row in rows:
        if not row["warmup"]:
            key = (row["cell"], row["backend"], row["step"])
            groups.setdefault(key, []).append(row)

    summaries = []

    for (cell, backend, step), group in sorted(groups.items()):
        timed = [row for row in group if "display_ms" in row]
        blocks: dict[str, list[float]] = {}

        for row in timed:
            blocks.setdefault(row["block"], []).append(row["display_ms"])

        summary: dict[str, Any] = {
            "cell": cell,
            "backend": backend,
            "step": step,
            "requests": len(group),
            "not_ok": sum(row["outcome"] != "ok" for row in group),
            "timing_gaps": len(group) - len(timed),
            "with_any_gap": sum(row["gaps"] != "" for row in group),
            "block_medians_ms": {
                block: statistics.median(values) for block, values in blocks.items()
            },
        }

        if timed:
            summary["display_ms"] = describe([row["display_ms"] for row in timed])

            summary["frame_periods_ms"] = sorted(
                {
                    round(row["frame_period_ms"], 1)
                    for row in timed
                    if row["frame_period_ms"] is not None
                }
            )

        attempts = [row for row in group if "settle_ms" in row]

        if attempts:
            # The interval of the cell is the longest that an attempt needs,
            # and it is unknown when one attempt has none.
            intervals = [row["settle_ms"] for row in attempts]

            summary["settle_ms"] = None if None in intervals else max(intervals)

        for column in COLUMNS:
            if column.endswith("_bytes"):
                values = [row[column] for row in group if row.get(column) is not None]

                if values:
                    summary[column] = {
                        "count": len(values),
                        "median": statistics.median(values),
                        "maximum": max(values),
                    }

        summaries.append(summary)

    return summaries


def overhead(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Compares the blocks with the memory events against those without.

    The check of a cell and backend passes when the combined medians of the
    display time differ by less than the range of the block medians without
    the events.
    """
    groups: dict[tuple[str, str, int], dict[bool, dict[str, list[float]]]] = {}

    for row in rows:
        if not row["warmup"] and "display_ms" in row:
            key = (row["cell"], row["backend"], row["step"])
            sides = groups.setdefault(key, {True: {}, False: {}})
            sides[row["memory_events"]].setdefault(row["block"], []).append(
                row["display_ms"]
            )

    checks = []

    for (cell, backend, step), sides in sorted(groups.items()):
        if not sides[True] or not sides[False]:
            continue

        def combined(blocks: dict[str, list[float]]) -> float:
            return statistics.median(
                [value for values in blocks.values() for value in values]
            )

        medians = [statistics.median(values) for values in sides[False].values()]
        spread = max(medians) - min(medians)
        distance = abs(combined(sides[True]) - combined(sides[False]))

        checks.append(
            {
                "cell": cell,
                "backend": backend,
                "step": step,
                "with_events_ms": combined(sides[True]),
                "without_events_ms": combined(sides[False]),
                "range_without_events_ms": spread,
                "passes": distance < spread,
            }
        )

    return checks


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("directory", type=pathlib.Path, help="the stored blocks")
    arguments = parser.parse_args()
    directory: pathlib.Path = arguments.directory

    names = sorted(
        path.name[: -len(".host.json")] for path in directory.glob("*.host.json")
    )

    try:
        if not names:
            raise Failure(f"{directory} holds no block")

        analyzed = [analyze_block(directory, name) for name in names]
    except Failure as failure:
        print(f"analyze.py: {failure}", file=sys.stderr)

        return 1

    endings = [ending for ending, _ in analyzed]
    rows = [row for _, block_rows in analyzed for row in block_rows]

    with (directory / "requests.csv").open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=COLUMNS, restval="")

        writer.writeheader()
        writer.writerows(rows)

    summary = {
        "anon_resolution_bytes": ANON_RESOLUTION_BYTES,
        "blocks": endings,
        "cells": summarize(rows),
        "overhead_checks": overhead(rows),
    }

    (directory / "summary.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8"
    )

    for ending in endings:
        if ending["outcome"] != "completed":
            print(f"{ending['block']}: {ending['outcome']} {ending['failure'] or ''}")

    for cell in summary["cells"]:
        display = cell.get("display_ms")

        print(
            f"{cell['cell']} {cell['backend']} step {cell['step']}:"
            f" {cell['requests']} requests, {cell['timing_gaps']} timing gaps,"
            f" {cell['not_ok']} not ok"
            + (
                f", median {display['median']:.1f} ms, p95 {display['p95']:.1f} ms,"
                f" maximum {display['maximum']:.1f} ms"
                if display
                else ""
            )
        )

    return 0


if __name__ == "__main__":
    sys.exit(main())
