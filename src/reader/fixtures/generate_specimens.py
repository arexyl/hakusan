"""Write the PNG, JPEG, WebP, and orientation specimens."""

import json
from pathlib import Path
import subprocess
import sys

from generate_host import require, run, sha, WEBP
from generate_payloads import exif, jpeg_insert
from generate_png import CHANNELS, png, png_insert, PNG_SPECIMEN
from generate_rasters import COLORS, sample, small_rows


LEGAL_DEPTHS = {0: [1, 2, 4, 8, 16], 2: [8, 16],
                3: [1, 2, 4, 8], 4: [8, 16], 6: [8, 16]}


def png_correctness(corpus):
    """Write each legal PNG color type and bit depth, plain and Adam7, with
    sample references from the verifier."""
    for color, depths in LEGAL_DEPTHS.items():
        for depth in depths:
            for interlace in (False, True):
                layout = "adam7" if interlace else "plain"

                relative = ("qualification/png/type%d-depth%d-%s.png" %
                            (color, depth, layout))

                palette = None
                transparency = None

                if color == 3:
                    count = min(16, 1 << depth)

                    palette = b"".join(bytes(COLORS[i % len(COLORS)])
                                       for i in range(count))

                    transparency = bytes(i * 255 // (count - 1)
                                         for i in range(count))
                elif color in (0, 2):
                    transparency = b"\0\0" * CHANNELS[color]

                info = png(
                    corpus.output / relative, 67, 53,
                    small_rows(67, 53, color, depth, interlace),
                    color, depth, interlace, [(b"sRGB", b"\0")],
                    palette, transparency, encoder=PNG_SPECIMEN,
                )

                oracle = subprocess.run([
                    sys.executable, "-I", "-B",
                    str(Path(__file__).with_name("verify.py")),
                    "--png-reference", str(corpus.output / relative),
                ], capture_output=True, check=True, timeout=30)

                corpus.add(relative, category="png-correctness", format="png",
                           width=67, height=53, color_type=color, depth=depth,
                           interlace=interlace, expected="success",
                           sample_reference=json.loads(oracle.stdout), **info)


def jpeg_correctness(corpus, ppm):
    """Encode the small master as baseline and progressive JPEG in gray, RGB,
    and three YCbCr chroma samplings, with reference decodes."""
    for color, flags in (("gray", ["-grayscale"]),
                         ("rgb", ["-rgb"]),
                         ("ycbcr420", ["-sample", "2x2,1x1,1x1"]),
                         ("ycbcr422", ["-sample", "2x1,1x1,1x1"]),
                         ("ycbcr444", ["-sample", "1x1,1x1,1x1"])):
        for progressive in (False, True):
            coding = "progressive" if progressive else "baseline"
            relative = "qualification/jpeg/%s-%s.jpg" % (color, coding)
            destination = corpus.output / relative

            destination.parent.mkdir(parents=True, exist_ok=True)

            settings = ["-quality", "90", "-dct", "int", *flags,
                        "-restart", "4B",
                        "-progressive" if progressive else "-baseline"]

            run("cjpeg", [*settings, "-outfile", destination, ppm])

            corpus.add(relative, category="jpeg-correctness", format="jpeg",
                       width=128, height=96, expected="success",
                       encoder={"tool": "cjpeg", "flags": settings},
                       reference=corpus.reference(destination, "jpeg", 128, 96))


def webp_correctness(corpus):
    """Write opaque and alpha WebP, lossy and lossless, with reference
    decodes."""
    small_png = corpus.work / "rgba.png"

    for alpha in (False, True):
        png(small_png, 67, 53, small_rows(67, 53, 6 if alpha else 2,
                                        8, False),
            color=6 if alpha else 2)

        for lossless in (False, True):
            relative = ("qualification/webp/%s-%s.webp" %
                        ("alpha" if alpha else "opaque",
                         "lossless" if lossless else "lossy"))

            destination = corpus.output / relative

            destination.parent.mkdir(parents=True, exist_ok=True)

            settings = WEBP + (["-lossless"] if lossless else [])

            run("cwebp", [*settings, small_png, "-o", destination])

            reference = corpus.reference(destination, "webp", 67, 53)

            if lossless:
                reference["comparison"] = {
                    "max_channel_error": 0,
                    "scope": "RGBA, ignoring RGB under zero alpha",
                }

                expected = bytearray()

                for y in range(53):
                    for x in range(67):
                        values = sample(x, y, 67, 53, 6 if alpha else 2, 8)

                        expected.extend(values if alpha else (*values, 255))

                require(sha(expected) == reference["pixel_sha256"],
                        "Lossless small WebP differs from master RGBA")

            corpus.add(relative, category="webp-correctness", format="webp",
                       width=67, height=53, expected="success",
                       alpha=alpha, lossless=lossless,
                       encoder={"tool": "cwebp", "flags": settings},
                       reference=reference)


def orientation(corpus, base):
    """Write each EXIF orientation in PNG, JPEG, and WebP. Reads
    qualification/jpeg/ycbcr420-baseline.jpg and
    qualification/webp/opaque-lossless.webp, written by the JPEG and WebP
    correctness families."""
    jpeg_path = corpus.output / "qualification/jpeg/ycbcr420-baseline.jpg"

    jpeg = jpeg_path.read_bytes()

    webp_path = corpus.output / "qualification/webp/opaque-lossless.webp"

    for orientation in range(1, 9):
        payload = exif(orientation)

        corpus.add("qualification/orientation/png-%d.png" % orientation,
                   png_insert(base, [(b"eXIf", payload)]),
                   category="orientation", format="png", width=67, height=53,
                   orientation=orientation, expected="success",
                   oriented_geometry=([53, 67] if orientation >= 5
                                      else [67, 53]))

        corpus.add("qualification/orientation/jpeg-%d.jpg" % orientation,
                   jpeg_insert(jpeg, 0xe1, b"Exif\0\0" + payload),
                   category="orientation", format="jpeg",
                   width=128, height=96, orientation=orientation,
                   expected="success",
                   oriented_geometry=([96, 128] if orientation >= 5
                                      else [128, 96]))

        metadata = corpus.work / "orientation.exif"

        metadata.write_bytes(payload)

        relative = "qualification/orientation/webp-%d.webp" % orientation
        destination = corpus.output / relative

        destination.parent.mkdir(parents=True, exist_ok=True)

        run("webpmux", ["-set", "exif", metadata, webp_path,
                        "-o", destination])

        corpus.add(relative, category="orientation", format="webp",
                   width=67, height=53, orientation=orientation,
                   expected="success", exif_order="after-image-payload",
                   oriented_geometry=([53, 67] if orientation >= 5
                                      else [67, 53]))
