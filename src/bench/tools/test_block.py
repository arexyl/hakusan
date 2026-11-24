#!/usr/bin/python3 -I
"""Tests of the trace configurations of block.py.

Run it as a script. The isolated interpreter leaves the directory of a script
off the module path, so the test loads block.py by its file.
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest

TOOLS = pathlib.Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location("block", TOOLS / "block.py")

assert _SPEC is not None and _SPEC.loader is not None

block = importlib.util.module_from_spec(_SPEC)
sys.modules["block"] = block
_SPEC.loader.exec_module(block)

RESIDENT = ['ftrace_events: "kmem/rss_stat"', "throttle_rss_stat: true"]
GPU = ['ftrace_events: "gpu_mem/gpu_mem_total"']


class Configuration(unittest.TestCase):
    def settings(self, memory: str) -> list[str]:
        """Returns the settings of the configuration, without the comments."""
        text = block.trace_configuration(TOOLS / "trace.textproto", memory).decode()
        lines = [line.split("#")[0].strip() for line in text.splitlines()]

        return [line for line in lines if line]

    def test_a_trace_block_records_the_resident_memory_and_the_gpu_memory(
        self,
    ) -> None:
        settings = self.settings("trace")

        for line in RESIDENT + GPU:
            self.assertEqual(settings.count(line), 1, line)

    def test_a_proc_block_leaves_only_the_resident_memory_out(self) -> None:
        self.assertEqual(
            self.settings("proc"),
            [line for line in self.settings("trace") if line not in RESIDENT],
        )

    def test_a_block_without_memory_leaves_every_memory_event_out(self) -> None:
        self.assertEqual(
            self.settings("none"),
            [line for line in self.settings("trace") if line not in RESIDENT + GPU],
        )


if __name__ == "__main__":
    unittest.main()
