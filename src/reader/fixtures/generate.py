#!/usr/bin/env python3
"""Generate reviewed, first-party reader inputs with pinned host tools."""

import argparse
from pathlib import Path
import sys
import tempfile

# Isolated mode (-I) keeps the directory of this script off the import
# path. Put it first, so that the generator modules beside this file, which
# the manifest pins, are the ones imported. Never write bytecode beside
# them, whatever the interpreter's default.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import generate_color
import generate_corpus
import generate_faults
import generate_host
import generate_rasters
import generate_specimens
import generate_timed


ROOT = Path(__file__).resolve().parents[3]
WORK = ROOT / "build/work/reader/fixtures"
DEFAULT_OUTPUT = WORK / "corpus"

# The manifest pins each tool script by name and digest. List every one, so
# that only reviewed files are pinned. The verifier fails on any other Python
# file in this directory whose name does not start with a dot.
TOOL_SCRIPTS = ("color_reference.py", "generate.py", "generate_color.py",
                "generate_corpus.py", "generate_faults.py", "generate_host.py",
                "generate_page.py", "generate_payloads.py", "generate_png.py",
                "generate_rasters.py", "generate_specimens.py",
                "generate_timed.py", "verify.py")


def main():
    parser = argparse.ArgumentParser(description=__doc__)

    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)

    args = parser.parse_args()

    output = args.output.resolve()

    generate_host.require(WORK in output.parents,
                          "Output must be beneath build/work/reader/fixtures")

    generate_host.require(not output.exists() or (
        output.is_dir() and not any(output.iterdir())),
        "Output must be nonexistent or empty; preserve existing output")

    versions = generate_host.tool_versions()

    output.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="generate-", dir=WORK) as temporary:
        corpus = generate_corpus.Corpus(output, Path(temporary), versions)

        generate_timed.timed(corpus)
        generate_specimens.png_correctness(corpus)

        small = generate_rasters.small_master(corpus.work)

        generate_specimens.jpeg_correctness(corpus, small)
        generate_specimens.webp_correctness(corpus)

        base = generate_rasters.small_png(corpus.work)

        generate_specimens.orientation(corpus, base)
        generate_faults.exif_faults(corpus, base)

        color_base = generate_color.color_base_png(corpus.work)

        generate_color.color(corpus, color_base)
        generate_faults.metadata_limits(corpus, color_base)
        generate_faults.faults(corpus, base, small)
        corpus.finish(TOOL_SCRIPTS)


if __name__ == "__main__":
    main()
