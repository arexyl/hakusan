#!/usr/bin/python3 -I
"""Runs one block of the measurement harness on the attached device.

The tool restarts the harness process, records one system trace around the
block, and stores the trace, the record of the harness, and its own notes of
the run in a directory. adb selects the device through ANDROID_SERIAL.

A trace also records other processes of the device. Keep it local.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import time

PACKAGE = "app.hakusan.bench"
ACTIVITY = f"{PACKAGE}/.BenchActivity"
RECORDS = f"/sdcard/Android/data/{PACKAGE}/files"
TRACE = "/data/misc/perfetto-traces/hakusan-bench.perfetto-trace"
MEMORY_MARKER = "# memory"

# The names that reach a device command line: no character that a shell reads.
WORD = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

# The harness stops a block at the first request over ten seconds, so a
# request that the tool still waits for after this long has hung.
REQUEST_SECONDS = 12
START_SECONDS = 60


class Failure(Exception):
    """A step of the run failed. The message names the step."""


def adb(*arguments: str, stdin: bytes | None = None, check: bool = True) -> bytes:
    """Runs adb and returns its standard output."""
    result = subprocess.run(
        ("adb", *arguments),
        input=stdin,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )

    if check and result.returncode != 0:
        message = result.stderr.decode(errors="replace").strip()

        raise Failure(f"adb {' '.join(arguments)}: {message}")

    return result.stdout


def shell(command: str, stdin: bytes | None = None, check: bool = True) -> str:
    """Runs one command line in the device shell and returns its output."""
    return adb("shell", command, stdin=stdin, check=check).decode(errors="replace")


def running() -> bool:
    """Returns whether a harness process exists."""
    return shell(f"pidof {PACKAGE}", check=False).strip() != ""


def stop_harness() -> None:
    """Ends every harness process, so that the block starts a new one."""
    shell(f"am force-stop {PACKAGE}")

    for _ in range(50):
        if not running():
            return

        time.sleep(0.1)

    raise Failure("the harness process survived force-stop")


def compilation() -> list[str]:
    """Returns the lines of the package's entry in the dexopt state."""
    entry: list[str] = []

    for line in shell("dumpsys package dexopt").splitlines():
        if line.strip() == f"[{PACKAGE}]":
            entry.append(line.strip())
        elif entry:
            # The lines of an entry are indented below its package line.
            if not line.startswith("    "):
                break

            entry.append(line.strip())

    if not entry:
        raise Failure("the dexopt state names no harness package")

    return entry


def trace_configuration(path: pathlib.Path, memory: bool) -> bytes:
    """Returns the configuration, without the memory events if not memory."""
    lines = path.read_text(encoding="utf-8").splitlines()

    if not memory:
        lines = [line for line in lines if not line.endswith(MEMORY_MARKER)]

    return ("\n".join(lines) + "\n").encode()


def start_trace(configuration: bytes) -> int:
    """Starts the trace and returns the process ID of its recorder."""
    shell(f"rm -f {TRACE}")

    output = shell(
        f"perfetto --txt -c - --background-wait -o {TRACE}", stdin=configuration
    )

    try:
        return int(output.strip().splitlines()[-1])
    except (IndexError, ValueError):
        raise Failure(f"perfetto reported no process: {output!r}") from None


def stop_trace(recorder: int) -> None:
    """Ends the recorder and waits until it has written the trace."""
    shell(f"kill -TERM {recorder}")

    for _ in range(300):
        if shell(f"test -d /proc/{recorder} && echo up", check=False) == "":
            return

        time.sleep(0.1)

    raise Failure("the trace recorder did not exit")


def read(path: str) -> bytes:
    """Returns a device file, checked against the size the device states."""
    size = int(shell(f"stat -c %s {path}"))
    content = adb("exec-out", "cat", path)

    if len(content) != size:
        raise Failure(f"{path}: read {len(content)} of {size} bytes")

    return content


def wait_for_record(name: str, seconds: int) -> bytes:
    """Waits until the harness has written its record, and returns it."""
    deadline = time.monotonic() + seconds
    path = f"{RECORDS}/{name}"

    while time.monotonic() < deadline:
        if shell(f"test -f {path} && echo up", check=False) != "":
            return read(path)

        if not running():
            # The harness finishes right after it has written the record.
            if shell(f"test -f {path} && echo up", check=False) != "":
                return read(path)

            raise Failure("the harness process ended without a record")

        time.sleep(0.5)

    raise Failure(f"no record after {seconds} seconds")


def plain(value: str) -> str:
    """Accepts a value that the device shell reads as one plain word."""
    if not WORD.fullmatch(value):
        raise argparse.ArgumentTypeError(f"{value!r} is not a plain name")

    return value


def install(arguments: argparse.Namespace) -> None:
    """Installs the harness and compiles all of it ahead of time."""
    adb("install", "-r", str(arguments.apk))
    shell(f"cmd package compile -m speed -f {PACKAGE}")
    print("\n".join(compilation()))


def block(arguments: argparse.Namespace) -> None:
    """Runs one block and stores its files."""
    output: pathlib.Path = arguments.output
    name: str = arguments.name
    configuration = trace_configuration(arguments.trace, arguments.memory)
    requests = arguments.warmups + arguments.attempts

    output.mkdir(parents=True, exist_ok=True)
    stop_harness()
    shell(f"rm -f {RECORDS}/{name}.json {RECORDS}/{name}.json.partial")

    notes = {
        "name": name,
        "memory_events": arguments.memory,
        "trace_configuration_sha256": hashlib.sha256(configuration).hexdigest(),
        "compilation": compilation(),
        "transport": arguments.transport,
    }

    recorder = start_trace(configuration)

    try:
        try:
            shell(
                f"am start -W -n {ACTIVITY}"
                f" --es cell {arguments.cell} --es backend {arguments.backend}"
                f" --ei warmups {arguments.warmups}"
                f" --ei attempts {arguments.attempts}"
                f" --ei settle_ms {arguments.settle_ms} --es record {name}.json"
            )

            record = wait_for_record(
                f"{name}.json", START_SECONDS + requests * REQUEST_SECONDS
            )
        finally:
            # The harness stops also when the recorder does not.
            try:
                stop_trace(recorder)
            finally:
                stop_harness()

        trace = read(TRACE)
    finally:
        # The trace holds other processes too, so none stays on the device.
        shell(f"rm -f {TRACE} {RECORDS}/{name}.json", check=False)

    (output / f"{name}.json").write_bytes(record)
    (output / f"{name}.perfetto-trace").write_bytes(trace)

    (output / f"{name}.host.json").write_text(
        json.dumps(notes, indent=2) + "\n", encoding="utf-8"
    )

    print(json.loads(record)["outcome"])


def main() -> int:
    tools = pathlib.Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    installer = commands.add_parser("install", help=install.__doc__)
    installer.add_argument("apk", type=pathlib.Path)
    installer.set_defaults(run=install)

    runner = commands.add_parser("block", help=block.__doc__)
    runner.add_argument("--cell", type=plain, required=True)
    runner.add_argument("--backend", type=plain, required=True)
    runner.add_argument("--warmups", type=int, default=5)
    runner.add_argument("--attempts", type=int, default=20)
    runner.add_argument("--settle-ms", type=int, default=500)
    runner.add_argument("--name", type=plain, required=True, help="the file stem")
    runner.add_argument("--output", type=pathlib.Path, required=True)
    runner.add_argument("--transport", required=True, help="how adb is attached")
    runner.add_argument("--trace", type=pathlib.Path, default=tools / "trace.textproto")

    runner.add_argument(
        "--no-memory",
        dest="memory",
        action="store_false",
        help="record no memory events, for an overhead check",
    )

    runner.set_defaults(run=block)

    arguments = parser.parse_args()

    try:
        arguments.run(arguments)
    except Failure as failure:
        print(f"block.py: {failure}", file=sys.stderr)

        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
