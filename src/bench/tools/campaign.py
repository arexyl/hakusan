#!/usr/bin/python3 -I
"""Runs the cells of a plan one after the other, and resumes an interrupted run.

A plan is a JSON file with a list of entries, each a cell and its backends:

    {"entries": [{"cell": "ordinary", "backends": ["san", "image-decoder"]}]}

Every entry runs through cell.py into a directory of its own under the output
directory, which also holds the state of the entry. The options that the tool
does not know go to cell.py as they are.

A run that was interrupted, as by a loss of power or of the device, is started
again with the same command. It skips the entries that have a result. The
files of an entry that was interrupted move aside under a numbered name and
stay, and the entry runs again as a whole, because its blocks alternate. An
entry whose cell failed stays as it is and never runs again by itself: a
failed block fails its cell, and whether the cell is measured again is a
decision that the report of the run must show.

The exit status is 0 when every entry completed, 1 when the run ended with a
failed entry, 2 when the command cannot run as it is, and 3 when the run
stopped and can be resumed.
"""

from __future__ import annotations

import argparse
import contextlib
import datetime
import json
import os
import pathlib
import re
import subprocess
import sys
import time
from collections.abc import Callable, Iterator
from typing import NamedTuple

TOOLS = pathlib.Path(__file__).resolve().parent
STATE = "campaign.json"
LOCK = "campaign.lock"

# The names that become a directory name and an argument of cell.py.
WORD = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]*")

# The status with which argparse ends cell.py when it refuses its arguments.
USAGE = 2

# The exit status of cell.py and of block.py for work that ended from outside,
# as when adb no longer reaches the device. This tool exits with it too when
# its run stopped and can be resumed.
INTERRUPTED = 3

# The options of cell.py that an entry of the plan and this tool decide.
OWN_OPTIONS = ("--cell", "--backend", "--output")


class Failure(Exception):
    """The run cannot go on. The message names the reason."""


class Refused(Failure):
    """The command cannot run as it is, so starting it again changes nothing."""


class Entry(NamedTuple):
    """One cell of a plan."""

    cell: str
    backends: tuple[str, ...]

    @property
    def name(self) -> str:
        """The name of the directory of the entry."""
        return f"{self.cell}.{'+'.join(self.backends)}"


def load(text: str) -> list[Entry]:
    """Reads the entries of a plan from its JSON text."""
    try:
        plan = json.loads(text)
    except json.JSONDecodeError as failure:
        raise Refused(f"the plan is no JSON: {failure}") from failure

    listed = plan.get("entries") if isinstance(plan, dict) else None

    if not isinstance(listed, list) or not listed:
        raise Refused("the plan lists no entries")

    entries = []

    for item in listed:
        if not isinstance(item, dict) or set(item) != {"cell", "backends"}:
            raise Refused(f"an entry names a cell and its backends, not {item!r}")

        cell, backends = item["cell"], item["backends"]

        if not isinstance(backends, list) or len(backends) not in (1, 2):
            raise Refused(f"the cell {cell!r} has one backend or two")

        for word in (cell, *backends):
            if not isinstance(word, str) or not WORD.fullmatch(word):
                raise Refused(f"{word!r} is not a plain name")

        if len(set(backends)) != len(backends):
            raise Refused(f"the cell {cell!r} names a backend twice")

        entries.append(Entry(cell, tuple(backends)))

    # A file system may hold names that differ only in case as one.
    names = [entry.name.casefold() for entry in entries]

    if len(set(names)) != len(names):
        raise Refused("the plan lists an entry twice")

    return entries


def forwardable(options: list[str]) -> list[str]:
    """Returns the options for cell.py, or refuses one that would replace
    what an entry of the plan decides."""
    for option in options:
        name = option.split("=", 1)[0]

        if name in OWN_OPTIONS:
            raise Refused(f"the plan and the output decide {name}")

    return options


def from_outside(status: int) -> bool:
    """Returns whether an exit status tells that the process was ended from
    outside and did not end itself.

    A negative status is the signal that ended it. Windows has no signals: a
    forced stop leaves the status -1 there and a fault an NTSTATUS error, and
    both arrive as unsigned numbers with the highest of 32 bits set.
    """
    return status == INTERRUPTED or status < 0 or status >= 0x80000000


def patiently(action: Callable[[], object]) -> None:
    """Runs a change of the file system that another process may hold up for
    a moment, as a scanner does on a file that was just written."""
    for _ in range(20):
        try:
            action()
        except PermissionError:
            time.sleep(0.1)
        else:
            return

    action()


def state(directory: pathlib.Path) -> str | None:
    """Returns the stored state of an entry, or None when it never started."""
    if not directory.exists():
        return None

    # A run that ended before its first state, or while the file system still
    # held a state back, was interrupted like one that left a whole state.
    try:
        stored = json.loads((directory / STATE).read_text(encoding="utf-8"))["state"]
    except (OSError, ValueError, KeyError, TypeError):
        return "started"

    return stored if stored in ("completed", "failed") else "started"


def store(directory: pathlib.Path, value: dict[str, object]) -> None:
    """Stores the state of an entry, whole or not at all."""
    partial = directory / f"{STATE}.partial"

    with partial.open("w", encoding="utf-8") as file:
        file.write(json.dumps(value, indent=2) + "\n")
        file.flush()

        # Without it, a loss of power may keep the new name and lose the text.
        os.fsync(file.fileno())

    patiently(lambda: partial.replace(directory / STATE))


def aside(directory: pathlib.Path) -> pathlib.Path:
    """Moves the files of an interrupted entry aside, and returns where."""
    number = 1

    while True:
        kept = directory.with_name(f"{directory.name}.interrupted-{number}")

        if not kept.exists():
            break

        number += 1

    patiently(lambda: directory.rename(kept))

    return kept


@contextlib.contextmanager
def exclusive(output: pathlib.Path) -> Iterator[None]:
    """Holds the output directory for one run. A second run would move the
    files of the cell that the first one measures aside and end its recorder.

    The system releases the hold when the process ends, however it ends.
    """
    output.mkdir(parents=True, exist_ok=True)

    with (output / LOCK).open("a+") as file:
        try:
            if os.name == "nt":
                import msvcrt

                file.seek(0)
                msvcrt.locking(file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise Failure(f"another run works in {output}") from None

        yield


def now() -> str:
    """Returns the time, for the record of how long an entry ran."""
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


def run_plan(
    entries: list[Entry],
    output: pathlib.Path,
    attached: Callable[[], bool],
    start: Callable[[Entry, pathlib.Path], int],
) -> dict[str, str]:
    """Runs the entries without a result, and returns the result of each.

    `attached` tells whether the device is there, and `start` runs the cell of
    an entry into a directory and returns its exit status.
    """
    results: dict[str, str] = {}

    for entry in entries:
        directory = output / entry.name
        stored = state(directory)

        if stored in ("completed", "failed"):
            results[entry.name] = stored
            print(f"{entry.name}: {stored} in an earlier run", flush=True)

            continue

        # A cell that cannot start for want of a device has not failed.
        if not attached():
            raise Failure(f"adb reaches no device before {entry.name}")

        if stored is not None:
            kept = aside(directory)

            print(f"{entry.name}: interrupted, kept as {kept.name}", flush=True)

        directory.mkdir(parents=True)

        started = now()

        store(directory, {"state": "started", "started": started})

        status = start(entry, directory)

        if status == USAGE:
            (directory / STATE).unlink()
            directory.rmdir()

            raise Refused("cell.py refused its arguments")

        # The entry keeps its first state, so the next run measures it again.
        if from_outside(status):
            raise Failure(f"{entry.name} ended from outside")

        result = "completed" if status == 0 else "failed"

        store(
            directory,
            {"state": result, "started": started, "ended": now(), "status": status},
        )

        results[entry.name] = result
        print(f"{entry.name}: {result}", flush=True)

    return results


def tool(name: str, *arguments: str) -> int:
    """Runs another tool of this directory and returns its exit status."""
    command = (sys.executable, "-I", str(TOOLS / name), *arguments)

    return subprocess.run(command, check=False).returncode


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__.splitlines()[0],
        epilog="Every other option goes to cell.py, which needs --refresh-hz"
        " and --transport.",
        allow_abbrev=False,
    )

    parser.add_argument("--plan", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    arguments, others = parser.parse_known_args()

    def start(entry: Entry, directory: pathlib.Path) -> int:
        return tool(
            "cell.py",
            f"--cell={entry.cell}",
            *(f"--backend={backend}" for backend in entry.backends),
            f"--output={directory}",
            *others,
        )

    try:
        forwardable(others)

        entries = load(arguments.plan.read_text(encoding="utf-8"))

        with exclusive(arguments.output):
            results = run_plan(
                entries,
                arguments.output,
                lambda: tool("block.py", "attached") == 0,
                start,
            )
    except Refused as failure:
        print(f"campaign.py: {failure}", file=sys.stderr)

        return USAGE
    except (Failure, OSError) as failure:
        print(f"campaign.py: stopped: {failure}", file=sys.stderr)

        return INTERRUPTED

    return 0 if all(result == "completed" for result in results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
