#!/usr/bin/python3 -I
"""Tests of the plan and of the resumption of campaign.py.

Run it as a script. The isolated interpreter leaves the directory of a script
off the module path, so the test loads campaign.py by its file.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest

_SPEC = importlib.util.spec_from_file_location(
    "campaign", pathlib.Path(__file__).resolve().parent / "campaign.py"
)

assert _SPEC is not None and _SPEC.loader is not None

campaign = importlib.util.module_from_spec(_SPEC)
sys.modules["campaign"] = campaign
_SPEC.loader.exec_module(campaign)

ORDINARY = campaign.Entry("ordinary", ("san", "image-decoder"))
EARLY = campaign.Entry("early", ("san",))
LARGE = campaign.Entry("large", ("san", "image-decoder"))


def plan(*entries: object) -> str:
    return json.dumps({"entries": list(entries)})


class Plans(unittest.TestCase):
    def test_a_plan_lists_its_cells_in_their_order(self) -> None:
        text = plan(
            {"cell": "ordinary", "backends": ["san", "image-decoder"]},
            {"cell": "early", "backends": ["san"]},
        )

        self.assertEqual(campaign.load(text), [ORDINARY, EARLY])
        self.assertEqual(ORDINARY.name, "ordinary.san+image-decoder")

    def test_one_cell_may_stand_twice_with_other_backends(self) -> None:
        text = plan(
            {"cell": "long-scroll", "backends": ["san", "region-decoder"]},
            {"cell": "long-scroll", "backends": ["san-strips", "region-decoder"]},
        )

        self.assertEqual(len({entry.name for entry in campaign.load(text)}), 2)

    def test_a_plan_that_cannot_run_is_refused(self) -> None:
        refused = [
            "no JSON",
            "[]",
            plan(),
            plan({"cell": "ordinary"}),
            plan({"cell": "ordinary", "backends": []}),
            plan({"cell": "ordinary", "backends": ["a", "b", "c"]}),
            plan({"cell": "ordinary", "backends": ["san", "san"]}),
            plan({"cell": "ordinary", "backends": "san"}),
            plan({"cell": "../ordinary", "backends": ["san"]}),
            plan({"cell": "ordinary", "backends": ["--blocks=1"]}),
            plan({"cell": "ordinary", "backends": ["san"], "blocks": 1}),
            plan(
                {"cell": "ordinary", "backends": ["san"]},
                {"cell": "ordinary", "backends": ["san"]},
            ),
            # One directory on a file system that folds the case of names.
            plan(
                {"cell": "ordinary", "backends": ["san"]},
                {"cell": "Ordinary", "backends": ["san"]},
            ),
        ]

        for text in refused:
            with self.subTest(text), self.assertRaises(campaign.Refused):
                campaign.load(text)

    def test_an_option_that_the_plan_decides_is_not_passed_on(self) -> None:
        passed = ["--refresh-hz=60", "--hold-pointer", "--blocks", "2"]

        self.assertEqual(campaign.forwardable(passed), passed)

        for option in ("--cell=large", "--cell", "--backend=san", "--output=x"):
            with self.subTest(option), self.assertRaises(campaign.Refused):
                campaign.forwardable([*passed, option])

    def test_the_tools_agree_on_the_status_of_an_interruption(self) -> None:
        statuses = {campaign.INTERRUPTED}

        for name, constant in (("block", "DETACHED"), ("cell", "INTERRUPTED")):
            spec = importlib.util.spec_from_file_location(
                name, pathlib.Path(__file__).resolve().parent / f"{name}.py"
            )

            assert spec is not None and spec.loader is not None

            module = importlib.util.module_from_spec(spec)

            spec.loader.exec_module(module)
            statuses.add(getattr(module, constant))

        self.assertEqual(len(statuses), 1)
        self.assertNotIn(campaign.USAGE, statuses)


class Runs(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()

        self.addCleanup(directory.cleanup)
        self.output = pathlib.Path(directory.name) / "blocks"
        self.started: list[str] = []
        self.statuses: dict[str, int] = {}
        self.attached = True

    def start(self, entry: object, directory: pathlib.Path) -> int:
        """Stands for cell.py: it leaves one file and the status of the test."""
        self.started.append(entry.name)
        (directory / "block.json").write_text("{}", encoding="utf-8")

        return self.statuses.get(entry.name, 0)

    def run_plan(self, *entries: object) -> dict[str, str]:
        with contextlib.redirect_stdout(io.StringIO()):
            return campaign.run_plan(
                list(entries), self.output, lambda: self.attached, self.start
            )

    def stored(self, entry: object) -> dict:
        path = self.output / entry.name / campaign.STATE

        return json.loads(path.read_text(encoding="utf-8"))

    def test_every_entry_runs_once_into_its_own_directory(self) -> None:
        results = self.run_plan(ORDINARY, EARLY)

        self.assertEqual(results, {ORDINARY.name: "completed", EARLY.name: "completed"})
        self.assertEqual(self.started, [ORDINARY.name, EARLY.name])
        self.assertEqual(self.stored(EARLY)["state"], "completed")
        self.assertEqual(self.stored(EARLY)["status"], 0)

        # A second run of the same plan starts nothing.
        self.assertEqual(self.run_plan(ORDINARY, EARLY), results)
        self.assertEqual(len(self.started), 2)

    def test_a_failed_cell_stays_and_the_entries_after_it_run(self) -> None:
        self.statuses[ORDINARY.name] = 1

        results = self.run_plan(ORDINARY, EARLY)

        self.assertEqual(results, {ORDINARY.name: "failed", EARLY.name: "completed"})
        self.assertEqual(self.stored(ORDINARY)["status"], 1)

        # It does not run again, and its files stay where they are.
        del self.statuses[ORDINARY.name]

        self.assertEqual(self.run_plan(ORDINARY, EARLY), results)
        self.assertEqual(self.started, [ORDINARY.name, EARLY.name])
        self.assertTrue((self.output / ORDINARY.name / "block.json").exists())

    def test_an_interrupted_entry_runs_again_and_keeps_its_files(self) -> None:
        self.run_plan(ORDINARY)

        for number in (1, 2):
            # The state of an entry whose cell never returned.
            directory = self.output / EARLY.name

            directory.mkdir()
            (directory / "partial.json").write_text("{}", encoding="utf-8")
            campaign.store(directory, {"state": "started", "started": "then"})

            results = self.run_plan(ORDINARY, EARLY, LARGE)
            kept = self.output / f"{EARLY.name}.interrupted-{number}"

            self.assertEqual(results[EARLY.name], "completed")
            self.assertTrue((kept / "partial.json").exists())
            self.assertFalse((directory / "partial.json").exists())
            self.assertEqual(self.stored(EARLY)["state"], "completed")

            # The next round interrupts the entry once more.
            (directory / campaign.STATE).unlink()
            (directory / "block.json").unlink()
            directory.rmdir()

        self.assertEqual(self.started.count(ORDINARY.name), 1)
        self.assertEqual(self.started.count(LARGE.name), 1)

    def test_a_directory_without_a_state_counts_as_interrupted(self) -> None:
        directory = self.output / ORDINARY.name

        directory.mkdir(parents=True)

        self.assertEqual(self.run_plan(ORDINARY), {ORDINARY.name: "completed"})
        self.assertTrue((self.output / f"{ORDINARY.name}.interrupted-1").is_dir())

    def test_a_state_that_cannot_be_read_counts_as_interrupted(self) -> None:
        for text in ("", "{", "[]", '{"started": "then"}', '{"state": "odd"}'):
            with self.subTest(text):
                directory = self.output / EARLY.name

                directory.mkdir(parents=True)
                (directory / campaign.STATE).write_text(text, encoding="utf-8")

                self.assertEqual(self.run_plan(EARLY), {EARLY.name: "completed"})

                # The next text needs the entry without its result.
                for file in directory.iterdir():
                    file.unlink()

                directory.rmdir()

        self.assertEqual(len(list(self.output.glob("*.interrupted-*"))), 5)

    def test_a_cell_that_ended_from_outside_stops_the_run_and_runs_again(
        self,
    ) -> None:
        for status in (campaign.INTERRUPTED, -15):
            with self.subTest(status):
                self.started.clear()
                self.statuses[ORDINARY.name] = status

                with self.assertRaises(campaign.Failure) as stopped:
                    self.run_plan(ORDINARY, EARLY)

                # It is no refusal, so the same command is worth another run.
                self.assertNotIsInstance(stopped.exception, campaign.Refused)
                self.assertEqual(self.stored(ORDINARY)["state"], "started")
                self.assertEqual(self.started, [ORDINARY.name])

                del self.statuses[ORDINARY.name]

                self.assertEqual(
                    self.run_plan(ORDINARY),
                    {ORDINARY.name: "completed"},
                )

                for file in (self.output / ORDINARY.name).iterdir():
                    file.unlink()

                (self.output / ORDINARY.name).rmdir()

        self.assertEqual(len(list(self.output.glob("*.interrupted-*"))), 2)

    def test_one_run_holds_the_output_directory(self) -> None:
        with campaign.exclusive(self.output):
            with self.assertRaises(campaign.Failure):
                with campaign.exclusive(self.output):
                    self.fail("a second run got the directory")

        # The hold ends with the run.
        with campaign.exclusive(self.output):
            pass

    def test_a_missing_device_stops_the_run_before_the_entry(self) -> None:
        self.run_plan(ORDINARY)
        self.attached = False

        # The entries with a result need no device.
        self.assertEqual(self.run_plan(ORDINARY), {ORDINARY.name: "completed"})

        with self.assertRaises(campaign.Failure):
            self.run_plan(ORDINARY, EARLY)

        self.assertFalse((self.output / EARLY.name).exists())
        self.assertEqual(self.started, [ORDINARY.name])

    def test_refused_arguments_leave_no_entry_behind(self) -> None:
        def refuse(entry: object, directory: pathlib.Path) -> int:
            return campaign.USAGE

        with self.assertRaises(campaign.Refused):
            campaign.run_plan([ORDINARY], self.output, lambda: True, refuse)

        self.assertFalse((self.output / ORDINARY.name).exists())
        self.assertEqual(self.started, [])


if __name__ == "__main__":
    unittest.main()
