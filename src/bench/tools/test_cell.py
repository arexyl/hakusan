#!/usr/bin/python3 -I
"""Tests of the block order of cell.py.

Run it as a script. The isolated interpreter leaves the directory of a script
off the module path, so the test loads cell.py by its file.
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest

_SPEC = importlib.util.spec_from_file_location(
    "cell", pathlib.Path(__file__).resolve().parent / "cell.py"
)

assert _SPEC is not None and _SPEC.loader is not None

cell = importlib.util.module_from_spec(_SPEC)
sys.modules["cell"] = cell
_SPEC.loader.exec_module(cell)


class Order(unittest.TestCase):
    def test_two_backends_alternate_which_runs_first(self) -> None:
        self.assertEqual(
            cell.order(["a", "b"], 6),
            ["a", "b", "b", "a", "a", "b", "b", "a", "a", "b", "b", "a"],
        )

    def test_each_backend_runs_as_often_first_as_second(self) -> None:
        sequence = cell.order(["a", "b"], 6)
        pairs = [sequence[index : index + 2] for index in range(0, 12, 2)]

        self.assertEqual(pairs.count(["a", "b"]), 3)
        self.assertEqual(pairs.count(["b", "a"]), 3)

    def test_one_backend_runs_every_block(self) -> None:
        self.assertEqual(cell.order(["a"], 3), ["a", "a", "a"])


class Outcomes(unittest.TestCase):
    def record(self, outcome: str, *requests: str) -> dict:
        return {
            "outcome": outcome,
            "requests": [{"outcome": request} for request in requests],
        }

    def test_a_block_with_a_request_that_succeeded_keeps_its_outcome(self) -> None:
        self.assertEqual(
            cell.outcome(self.record("completed", "failed", "ok")), "completed"
        )

    def test_a_completed_block_without_such_a_request_did_not_complete(self) -> None:
        for requests in ((), ("failed", "failed")):
            self.assertNotEqual(
                cell.outcome(self.record("completed", *requests)), "completed"
            )

    def test_a_block_that_ended_otherwise_keeps_its_outcome(self) -> None:
        self.assertEqual(cell.outcome(self.record("stopped", "timeout")), "stopped")
        self.assertEqual(cell.outcome(self.record("failed")), "failed")


if __name__ == "__main__":
    unittest.main()
