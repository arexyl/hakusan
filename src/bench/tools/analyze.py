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
import collections
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

select s.ts, s.dur, s.name
from slice s
join thread_track tt on s.track_id = tt.id
join thread t using (utid)
where (s.name glob 'reader.job *' or s.name glob 'reader.copy *'
       or s.name glob 'reader.withdraw *')
  and t.upid in (select upid from harness)
order by s.ts;
"""

ANON = "mem.rss.anon"
SWAP = "mem.swap"
GPU = "GPU Memory"

# The version of the records that this analysis reads, which the harness
# raises with every change of a field or of the steps of a cell.
SCHEMA = 8

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


class Job(NamedTuple):
    """The end of a job of the reader: its time, the status of its last step,
    and the image rows that it decoded."""

    end: int
    status: int
    decoded: int


class Copy(NamedTuple):
    """The start of a copy of the reader, and the rows that it copies."""

    start: int
    first: int
    count: int


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
        marks, surfaces, displays, counters, frames, losses, events, sections = tables(
            path
        )

        # The sections of the reader's worker name no request, so a request
        # finds its own by their times.
        self.jobs = [
            Job(int(row["ts"]) + int(row["dur"]), *map(int, row["name"].split(" ")[1:]))
            for row in sections
            if row["name"].startswith("reader.job ")
        ]

        self.copies = [
            Copy(int(row["ts"]), *map(int, row["name"].split(" ")[2:]))
            for row in sections
            if row["name"].startswith("reader.copy ")
        ]

        # When the reader took back a revision that waited at the end of a job.
        self.withdrawals = [
            int(row["ts"])
            for row in sections
            if row["name"].startswith("reader.withdraw ")
        ]

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


def terminal(entry: dict[str, Any]) -> str:
    """Returns how the request of an entry ended, a failure with its
    category. A request that did not end as its step expects has none."""
    ended = entry.get("terminal")

    if ended is None:
        return ""

    if ended["failure"] is not None:
        return f"{ended['outcome']} {ended['failure']}"

    # A decode that ended before its cancellation reached it has no final
    # output: the reader makes no last copy for a cancelled request.
    if ended["outcome"] == "completed" and "canceled" in entry.get("clock", {}):
        return "completed before its cancellation"

    return ended["outcome"]


def ledger(entry: dict[str, Any]) -> dict[str, int]:
    """Returns the values of the ledger reads of an entry: the peaks of the
    request from its terminal read, and what the engine holds once the
    attempt has closed."""
    reads = entry.get("ledger", {})
    values = {}

    if "terminal" in reads:
        values["ledger_peak_live_bytes"] = reads["terminal"]["peak_live_bytes"]
        values["ledger_peak_committed_bytes"] = reads["terminal"]["peak_committed_bytes"]

    if "close" in reads:
        values["ledger_close_live_bytes"] = reads["close"]["live_bytes"]

    return values


def useful_draw(draws: list[dict[str, Any]], rows: int) -> dict[str, Any] | None:
    """Returns the first draw of an output with at least the useful rows. The
    rest of a draw mark is its revision and its rows."""
    useful = [draw for draw in draws if draw["rest"][1] >= rows]

    return min(useful, key=lambda draw: draw["ts"]) if useful else None


def quiescence(jobs: list[Job], cancel: int, returned: int) -> Job | None:
    """Returns the job that ended between the cancellation of its request
    and the return of the execution, where the request is quiescent."""
    ended = [job for job in jobs if cancel <= job.end <= returned]

    return ended[0] if len(ended) == 1 else None


def rows_after(job: Job, copies: list[Copy], cancel: int) -> int:
    """Returns an upper bound of the rows that a job decoded after the
    cancellation of its request: its decoded rows without the ones that the
    last copy before the cancellation already covered. Copies of an earlier
    request cover rows too, so the caller passes the ones of this request."""
    covered = [copy.first + copy.count for copy in copies if copy.start < cancel]

    return max(job.decoded - (covered[-1] if covered else 0), 0)


def early(
    trace: Trace, entry: dict[str, Any], start: dict[str, Any], row: dict[str, Any]
) -> list[str]:
    """Adds the times of the useful output of a held request to its row, and
    returns the gaps. The useful output counts only when its frame was
    presented before the held input went on or the request was cancelled."""
    useful = entry.get("wanted", {}).get("useful_rows")

    if useful is None:
        return []

    request = entry["request"]
    draw = useful_draw(trace.marks.get(("bench.present", request), []), useful)

    if draw is None:
        return ["no draw of the useful output"]

    presented, gaps = trace.presentation(draw)
    resume = trace.mark("bench.resume", request)
    went_on = resume or trace.mark("bench.cancel", request)

    row["useful_draw_ms"] = (draw["ts"] - start["ts"]) / 1e6

    if presented is not None:
        row["useful_display_ms"] = (presented - start["ts"]) / 1e6

    if went_on is None:
        gaps.append("no mark of the end of the hold")
    elif presented is not None and presented > went_on["ts"]:
        gaps.append("the useful output was presented after the hold ended")

    # The time from the request contains the hold of the harness.
    if resume is not None and "display_ms" in row:
        row["final_after_resume_ms"] = (
            row["display_ms"] - (resume["ts"] - start["ts"]) / 1e6
        )

    return gaps


def cancellation(trace: Trace, request: int, row: dict[str, Any]) -> list[str]:
    """Adds the latencies of a cancelled request to its row, and returns the
    gaps. A request that completed before its cancellation has none."""
    cancel = trace.mark("bench.cancel", request)

    if cancel is None or row["terminal"] != "canceled":
        return []

    returned = trace.mark("bench.returned", request)

    if returned is None:
        return ["no return of the cancelled request"]

    row["cancel_settled_ms"] = (returned["ts"] - cancel["ts"]) / 1e6

    job = quiescence(trace.jobs, cancel["ts"], returned["ts"])

    if job is None:
        return ["no one job ended between the cancellation and the return"]

    start = trace.mark("bench.request", request)
    own = [copy for copy in trace.copies if start is None or copy.start >= start["ts"]]

    row["cancel_quiescent_ms"] = (job.end - cancel["ts"]) / 1e6
    row["cancel_rows_bound"] = rows_after(job, own, cancel["ts"])

    return []


def after_job(
    jobs: list[Job], withdrawals: list[int], frames: list[int], start: int, draw: int
) -> dict[str, Any]:
    """Returns what lies between the end of the job of a request and the draw
    of its final output, for a request that began at start and whose final
    output a frame drew at draw. A request without a job of the reader has
    nothing here.

    The late frames are the frame callbacks that began after the job had
    ended and before the one that drew the final output. With none, the last
    rows reached the first display frame that could show them. A withdrawal
    is the reader taking back a revision that waited at the end of the job.
    """
    ended = [job.end for job in jobs if start <= job.end <= draw]

    if not ended:
        return {}

    # A request that learns its geometry first has a job before its own.
    end = ended[-1]
    between = sum(end <= frame <= draw for frame in frames)

    return {
        "job_ms": (end - start) / 1e6,
        "final_after_job_ms": (draw - end) / 1e6,
        # The frame of the draw began before the draw, so it counts as one.
        "late_frames": max(between - 1, 0),
        "withdrawals": sum(start <= withdrawal <= draw for withdrawal in withdrawals),
    }


def resident(
    attempt: list[dict[str, Any]], entry: dict[str, Any], resets: bool
) -> tuple[dict[str, int], list[str]]:
    """Returns the memory values of a request whose harness read them from
    /proc/self, with the gaps.

    The peak is the highest resident memory since the reset before the
    request, above the resident memory right after that reset, so it covers
    file and shared pages beside the anonymous ones. The level and the
    retained values are anonymous memory, as in a trace. The last request of
    an attempt carries the retained values.
    """
    first = attempt[0].get("memory", {}).get("request")
    reads = entry.get("memory", {})
    start = reads.get("request")
    end = reads.get("end")

    if first is None or start is None or end is None:
        return {}, ["no memory read at the request or at its end"]

    values = {"anon_level_bytes": start["anon_bytes"]}
    gaps = []

    if resets:
        values["resident_peak_bytes"] = end["peak_bytes"] - start["rss_bytes"]
    else:
        gaps.append("the platform denied the reset of the peak")

    if start["swap_bytes"] != end["swap_bytes"]:
        gaps.append("the swap counter changed during the request")

    if entry is attempt[-1]:
        settled = reads.get("settled")
        values["anon_retained_close_bytes"] = end["anon_bytes"] - first["anon_bytes"]

        if settled is None:
            gaps.append("no memory read at the end of the settle interval")
        else:
            values["anon_retained_settled_bytes"] = (
                settled["anon_bytes"] - first["anon_bytes"]
            )

        # Both retained values subtract the read at the first request.
        if any(
            read["swap_bytes"] != first["swap_bytes"]
            for read in (end, settled)
            if read is not None
        ):
            gaps.append("the swap counter changed during the attempt")

    return values, gaps


def analyze_block(
    directory: pathlib.Path, name: str
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Returns how a block ended, and one row per request of it."""
    record = json.loads((directory / f"{name}.json").read_text(encoding="utf-8"))
    host = json.loads((directory / f"{name}.host.json").read_text(encoding="utf-8"))

    # Another version has other fields or other steps in a cell of this name.
    if record["schema"] != SCHEMA:
        raise Failure(f"{name}: a record of version {record['schema']}, not {SCHEMA}")

    # Where the resident memory of the block comes from: the events of its
    # trace, the reads of its harness from /proc/self, or nowhere.
    memory = host["memory"]

    if memory not in ("trace", "proc", "none"):
        raise Failure(f"{name}: no such source of memory as {memory!r}")

    ending = {
        "block": name,
        "outcome": record["outcome"],
        "failure": record.get("failure"),
        "memory": memory,
    }

    # A block that failed before its first request recorded no conditions.
    if "conditions" not in record:
        return ending, []

    if record["conditions"]["harness"]["debuggable"]:
        raise Failure(f"{name}: a debuggable harness measures nothing")

    if (memory == "proc") != ("memory" in record):
        raise Failure(f"{name}: the harness and the host name two sources of memory")

    if memory == "proc":
        # The kernel counts resident memory in pages.
        ending["resident_resolution_bytes"] = record["conditions"]["device"][
            "page_size"
        ]

    trace = Trace(directory / f"{name}.perfetto-trace")

    block_gaps = [f"the trace lost data: {loss}" for loss in sorted(trace.losses)]

    block_gaps += [
        f"the trace holds no {event} event"
        for event in {
            "trace": ("rss_stat_throttled", "gpu_mem_total"),
            "proc": ("gpu_mem_total",),
            "none": (),
        }[memory]
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
            "memory": memory,
            # A block that was stored before the option existed held none.
            "pointer_held": host.get("held_pointer", False),
            "request": request,
            "attempt": entry["attempt"],
            "step": entry["step"],
            "warmup": entry["warmup"],
            "outcome": entry["outcome"],
            "terminal": terminal(entry),
            "thermal_status": entry["conditions"]["thermal_status"],
        }

        start = trace.mark("bench.request", request)
        decode = trace.mark("bench.decode", request)
        draws = trace.marks.get(("bench.present", request), [])

        if start is None:
            gaps.append("no request mark")
        elif entry["outcome"] != "ok":
            gaps.append(f"the request ended as {entry['outcome']}")
        elif row["terminal"] != "completed":
            # Its last output is a partial one or none, so it has no time to
            # a final output.
            pass
        elif not draws:
            gaps.append("no draw of an output")
        else:
            # The final output is the last revision that was drawn.
            draw = max(draws, key=lambda mark: mark["rest"][0])
            presented, reasons = trace.presentation(draw)

            gaps.extend(reasons)
            row["draw_ms"] = (draw["ts"] - start["ts"]) / 1e6
            row["frame_period_ms"] = trace.cadence(draw["ts"])

            row.update(
                after_job(
                    trace.jobs, trace.withdrawals, trace.frames, start["ts"], draw["ts"]
                )
            )

            if presented is not None:
                row["display_ms"] = (presented - start["ts"]) / 1e6

        if decode is not None:
            row["decode_ms"] = decode["dur"] / 1e6

        # Only a backend that reads the file itself counts its bytes, with up
        # to one portion per input that its decoder no longer took.
        if "input" in entry:
            row["acquired_bytes"] = entry["input"]["acquired_bytes"]
            row["reacquired_bytes"] = entry["input"]["reacquired_bytes"]

        row.update(ledger(entry))

        # The close of the content of the attempt, which the harness times
        # itself, after presentation released every output.
        if "closing" in entry["clock"] and entry["outcome"] == "ok":
            row["close_ms"] = (entry["clock"]["closed"] - entry["clock"]["closing"]) / 1e6

        if start is not None and entry["outcome"] == "ok":
            gaps.extend(early(trace, entry, start, row))
            gaps.extend(cancellation(trace, request, row))

        # A block without the memory events has no memory values, which is no
        # gap. One whose harness reads /proc/self takes only the memory of the
        # GPU from its trace.
        counters = {
            "trace": ((ANON, "anon"), (GPU, "gpu")),
            "proc": ((GPU, "gpu"),),
            "none": (),
        }[memory]

        if memory == "proc":
            values, reasons = resident(attempt, entry, record["memory"]["peak_reset"])

            row.update(values)
            gaps.extend(reasons)

        if start is not None and counters:
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
                for counter, column in counters:
                    samples = trace.counters[counter]
                    base = samples.at(start["ts"])
                    peak = samples.peak(start["ts"], end["ts"])

                    if base is None or peak is None:
                        gaps.append(f"no {column} memory value at the request")

                        continue

                    row[f"{column}_peak_bytes"] = peak - base

                    # A renderer that reuses its textures moves the counter
                    # only while the process starts, so the growth of a later
                    # request is 0 and the level alone shows what it holds.
                    if counter == GPU:
                        row["gpu_level_bytes"] = peak

                    # Memory that a backend keeps between its requests is no
                    # growth above a request, so the value at the request
                    # alone shows it.
                    if counter == ANON:
                        row["anon_level_bytes"] = base

                    # The retained values belong to the attempt, so its last
                    # request carries them.
                    if following is None and close is not None and settled is not None:
                        row[f"{column}_retained_close_bytes"] = difference(
                            samples, first["ts"], close["ts"]
                        )

                        row[f"{column}_retained_settled_bytes"] = difference(
                            samples, first["ts"], settled["ts"]
                        )

                # The settle interval and the swap rule need the counters of
                # the resident memory, which only its events give.
                if memory == "trace":
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
    "memory",
    "pointer_held",
    "request",
    "attempt",
    "step",
    "warmup",
    "outcome",
    "terminal",
    "decode_ms",
    "draw_ms",
    "display_ms",
    "frame_period_ms",
    "useful_draw_ms",
    "useful_display_ms",
    "final_after_resume_ms",
    "cancel_quiescent_ms",
    "cancel_settled_ms",
    "cancel_rows_bound",
    "close_ms",
    "job_ms",
    "final_after_job_ms",
    "late_frames",
    "withdrawals",
    "acquired_bytes",
    "reacquired_bytes",
    "ledger_peak_live_bytes",
    "ledger_peak_committed_bytes",
    "ledger_close_live_bytes",
    "resident_peak_bytes",
    "anon_peak_bytes",
    "anon_level_bytes",
    "anon_retained_close_bytes",
    "anon_retained_settled_bytes",
    "gpu_peak_bytes",
    "gpu_level_bytes",
    "gpu_retained_close_bytes",
    "gpu_retained_settled_bytes",
    "settle_ms",
    "thermal_status",
    "gaps",
]


# The times beside the display time that a summary describes as it does that
# one, each over the requests that have it.
LATENCIES = (
    "useful_display_ms",
    "final_after_resume_ms",
    "cancel_quiescent_ms",
    "cancel_settled_ms",
    "close_ms",
    "job_ms",
    "final_after_job_ms",
)


def summarize(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Returns the statistics of each cell, backend, and step position over
    its measured requests. Step positions are never pooled, and neither are
    the blocks under a held pointer with the blocks without one, because a
    display may refresh at another rate under a touch. The times of a cell
    come from its blocks without memory where it has such blocks, and its
    memory from every block that read it."""
    groups: dict[tuple[str, str, int, bool], list[dict[str, Any]]] = {}

    for row in rows:
        if not row["warmup"]:
            key = (row["cell"], row["backend"], row["step"], row["pointer_held"])
            groups.setdefault(key, []).append(row)

    summaries = []

    for (cell, backend, step, pointer), group in sorted(groups.items()):
        # Reading its memory may slow a request, so a cell that has blocks
        # without memory takes its times from those alone. A cell whose blocks
        # all read memory, as in a validation, has no other times.
        plain = [row for row in group if row["memory"] == "none"]
        clocked = plain or group
        timed = [row for row in clocked if "display_ms" in row]
        blocks: dict[str, list[float]] = {}

        for row in timed:
            blocks.setdefault(row["block"], []).append(row["display_ms"])

        # Only a request that completed has a final output to time. One that
        # ended otherwise as its step expects is no gap.
        untimed = sum(
            row["outcome"] != "ok" or row["terminal"] == "completed"
            for row in clocked
            if "display_ms" not in row
        )

        summary: dict[str, Any] = {
            "cell": cell,
            "backend": backend,
            "step": step,
            "pointer_held": pointer,
            "requests": len(group),
            "timed_requests": len(clocked),
            "times_from_blocks_without_memory": bool(plain),
            "not_ok": sum(row["outcome"] != "ok" for row in group),
            "terminals": dict(
                sorted(collections.Counter(row["terminal"] for row in group).items())
            ),
            "timing_gaps": untimed,
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

        for column in LATENCIES:
            values = [row[column] for row in clocked if column in row]

            if values:
                summary[column] = describe(values)

        late = [row["late_frames"] for row in clocked if "late_frames" in row]

        if late:
            # How many requests drew their final output so many frames after
            # the first one that began once their job had ended.
            summary["late_frames"] = dict(sorted(collections.Counter(late).items()))

            summary["withdrawals"] = sum(
                row["withdrawals"] for row in clocked if "withdrawals" in row
            )

        for column in COLUMNS:
            if column.endswith("_bytes") or column == "cancel_rows_bound":
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
    the events. A block whose harness reads /proc/self is no side of it.
    """
    groups: dict[
        tuple[str, str, int, bool], dict[bool, dict[str, list[float]]]
    ] = {}

    for row in rows:
        if (
            not row["warmup"]
            and "display_ms" in row
            and row["memory"] in ("trace", "none")
        ):
            key = (row["cell"], row["backend"], row["step"], row["pointer_held"])
            sides = groups.setdefault(key, {True: {}, False: {}})
            sides[row["memory"] == "trace"].setdefault(row["block"], []).append(
                row["display_ms"]
            )

    checks = []

    for (cell, backend, step, pointer), sides in sorted(groups.items()):
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
                "pointer_held": pointer,
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
            f"{cell['cell']} {cell['backend']} step {cell['step']}"
            f"{' under a held pointer' if cell['pointer_held'] else ''}:"
            f" {cell['timed_requests']} timed requests,"
            f" {cell['timing_gaps']} timing gaps,"
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
