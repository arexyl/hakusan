"""Write corpus artifacts, reference decodes, and the manifest."""

import json
from pathlib import Path

from generate_host import require, run, sha
from generate_png import PNG_AUTHORED, PNG_SPECIMEN, PNG_TIMED


class Corpus:
    def __init__(self, output, work, versions):
        self.output = output
        self.work = work
        self.entries = []
        self.versions = versions

    def add(self, relative, data=None, **metadata):
        path = self.output / relative

        path.parent.mkdir(parents=True, exist_ok=True)

        if data is not None:
            path.write_bytes(data)

        encoded = path.read_bytes()

        entry = {"path": relative, "bytes": len(encoded),
                 "sha256": sha(encoded), **metadata}

        self.entries.append(entry)

        return entry

    def reference(self, path, kind, width, height, sites=None):
        """Pin a reference decode and its patches at the given sites, or at
        a fixed 3 x 3 grid of sites when none are given."""
        reference = self.work / "reference.pnm"

        if kind == "jpeg":
            run("djpeg", ["-dct", "int", "-pnm", "-outfile", reference,
                          path])
        else:
            run("dwebp", [path, "-pam", "-nodither", "-o", reference])

        raw = reference.read_bytes()

        if raw.startswith(b"P7\n"):
            header, pixels = raw.split(b"ENDHDR\n", 1)

            channels = int(next(line.split()[1] for line in header.splitlines()
                                if line.startswith(b"DEPTH ")))
        else:
            # The pinned djpeg PNM writer emits no comments.
            header, dimensions, maximum, pixels = raw.split(b"\n", 3)

            require(maximum == b"255", "Unexpected reference precision")

            require(dimensions == ("%d %d" % (width, height)).encode(),
                    "Unexpected reference dimensions")

            channels = 3 if header == b"P6" else 1

        require(len(pixels) == width * height * channels,
                "Reference pixel extent mismatch")

        if sites is None:
            side = min(16, width // 8, height // 8)

            sites = [{"x": x, "y": y, "width": side, "height": side}
                     for y in (height // 8, height // 2, height * 7 // 8)
                     for x in (width // 8, width // 2, width * 7 // 8)]

        points = []
        patches = []

        for site in sites:
            x, y = site["x"], site["y"]
            offset = (y * width + x) * channels

            points.append({
                "x": x, "y": y,
                "samples": list(pixels[offset:offset + channels]),
            })

            patch = b"".join(
                pixels[((y + dy) * width + x) * channels:
                       ((y + dy) * width + x + site["width"]) * channels]
                for dy in range(site["height"]))

            patches.append({**site, "samples_hex": patch.hex()})

        reference.unlink()

        rgb = pixels

        if channels == 4:
            rgb = bytearray(width * height * 3)

            for channel in range(3):
                rgb[channel::3] = pixels[channel::4]

        return {"tool": "djpeg" if kind == "jpeg" else "dwebp",
                "pixel_sha256": sha(pixels), "channels": channels,
                "rgb_sha256": sha(rgb),
                "points": points, "patches": patches,
                "comparison": {"mean_absolute_error_max": 2,
                               "nearest_rank_p99_max": 8,
                               "scope": "opaque RGB patch samples"}}

    def finish(self, tool_scripts):
        sources = {}

        for name in tool_scripts:
            path = Path(__file__).with_name(name)

            require(path.is_file(), "Missing tool script: " + name)
            sources[name] = sha(path.read_bytes())

        manifest = {
            "schema_version": 1,
            "provenance": {
                "ownership": "First-party deterministic authored patterns",
                "license": "AGPL-3.0-or-later",
                "source_sha256": sources, "tools": self.versions,
                "png_encoder": {"timed": PNG_TIMED,
                                "png-correctness": PNG_SPECIMEN,
                                "small-authored": PNG_AUTHORED},
                "decoder_ancestry": {
                    "jpeg": "libjpeg-turbo encoder and reference decoder",
                    "webp": "libwebp encoder and reference decoder",
                    "png": (
                        "zlib deflate in the encoder and zlib inflate in the "
                        "verifier, which the candidate native PNG decoder "
                        "also uses; the verifier's own standard-library code "
                        "reverses all five filter types and reconstructs "
                        "samples, sharing no code with libpng"),
                },
            },
            "display": {"viewport": [1080, 1920], "fit": "width",
                        "first_useful_region": [0, 0, 1080, 960],
                        "visual_inspection": "pending Android presentation",
                        "lossless_8_bit": "exact except RGB under zero alpha",
                        "color_depth_patch_tolerance": 2},
            "gaps": [
                "Android readability, scaling, color, and presentation proof",
                "Native candidate decoding and resource qualification",
            ],
            "artifacts": sorted(self.entries, key=lambda e: e["path"]),
        }

        (self.output / "manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")

        print("Generated %d assets, %d bytes" % (
            len(self.entries), sum(e["bytes"] for e in self.entries)),
            flush=True)
