#!/usr/bin/python3 -I
"""Runs the blocks of one cell of the measurement harness in their order.

A cell compares a candidate backend with a reference backend on one workload,
or validates one backend alone. Its timed blocks alternate as AB, BA, AB, BA,
AB, BA, so that neither backend always runs first or right after the other.
They record no memory events. The memory of the cell is measured in blocks of
their own, with the events, in the same alternation.

The tool runs block.py once per block and stops at the first block that does
not complete, because a failed block fails its cell and is never replaced by
a retry.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys
import time

TOOLS = pathlib.Path(__file__).resolve().parent


def order(backends: list[str], blocks: int) -> list[str]:
    """Returns the backend of each block of a cell with `blocks` blocks per
    backend: the backends in their order, then reversed, and so on."""
    sequence: list[str] = []

    for index in range(blocks):
        sequence += backends if index % 2 == 0 else reversed(backends)

    return sequence


def run_block(
    arguments: argparse.Namespace, name: str, backend: str, memory: bool
) -> str:
    """Runs one block and returns the outcome that its record states."""
    command = [
        sys.executable,
        "-I",
        str(TOOLS / "block.py"),
        "block",
        f"--cell={arguments.cell}",
        f"--backend={backend}",
        f"--warmups={arguments.warmups}",
        f"--attempts={arguments.attempts}",
        f"--settle-ms={arguments.settle_ms}",
        f"--refresh-hz={arguments.refresh_hz}",
        f"--name={name}",
        f"--output={arguments.output}",
        f"--transport={arguments.transport}",
    ]

    if arguments.hold_refresh:
        command.append("--hold-refresh")

    if arguments.hold_pointer:
        command.append("--hold-pointer")

    if not memory:
        command.append("--no-memory")

    if subprocess.run(command, check=False).returncode != 0:
        return "not run"

    record = json.loads(
        (arguments.output / f"{name}.json").read_text(encoding="utf-8")
    )

    return record["outcome"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--cell", required=True)

    parser.add_argument(
        "--backend",
        action="append",
        required=True,
        help="a backend of the cell: the candidate first, then the reference",
    )

    parser.add_argument(
        "--blocks", type=int, default=6, help="timed blocks per backend"
    )

    parser.add_argument(
        "--memory-blocks", type=int, default=2, help="memory blocks per backend"
    )

    parser.add_argument("--warmups", type=int, default=5)
    parser.add_argument("--attempts", type=int, default=20)
    parser.add_argument("--settle-ms", type=int, default=500)
    parser.add_argument("--refresh-hz", type=int, required=True)
    parser.add_argument("--hold-refresh", action="store_true")
    parser.add_argument("--hold-pointer", action="store_true")

    parser.add_argument(
        "--pause-seconds", type=float, default=0, help="the wait between blocks"
    )

    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--transport", required=True)
    arguments = parser.parse_args()

    if len(arguments.backend) not in (1, 2):
        parser.error("a cell has one backend or two")

    timed = order(arguments.backend, arguments.blocks)
    memory = order(arguments.backend, arguments.memory_blocks)

    plan = [
        (f"{arguments.cell}-{number:02}-{backend}", backend, False)
        for number, backend in enumerate(timed, 1)
    ] + [
        (f"{arguments.cell}-m{number:02}-{backend}", backend, True)
        for number, backend in enumerate(memory, 1)
    ]

    for index, (name, backend, with_memory) in enumerate(plan):
        if index != 0:
            time.sleep(arguments.pause_seconds)

        outcome = run_block(arguments, name, backend, with_memory)

        print(f"{name}: {outcome}", flush=True)

        if outcome != "completed":
            print(f"cell.py: the cell stops at {name}", file=sys.stderr)

            return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
