"""Write the timed fixture family."""

from generate_host import JPEG, require, run, WEBP
from generate_page import Composite
from generate_png import png, PNG_TIMED


def tee(rows, path, width, height):
    """Pass rows through while writing them to a binary PPM."""
    with path.open("wb") as raster:
        raster.write(("P6\n%d %d\n255\n" % (width, height)).encode())

        for row in rows:
            raster.write(row)
            yield row


def timed(corpus):
    """Write the three timed pages as PNG, and the ordinary and large pages
    also as baseline and progressive JPEG and as lossy and lossless WebP,
    with reference decodes."""
    for name, width, height, hold in (
        ("ordinary", 1600, 2400, 1536),
        ("large", 4096, 6144, 3840),
        ("long", 1440, 40000, 1536),
    ):
        print("Generate " + name, flush=True)

        master = Composite(width, height)

        folder = "journey" if name != "large" else "qualification/timed"
        relative = folder + "/" + name + ".png"
        path = corpus.output / relative
        ppm = corpus.work / "master.ppm"

        rows = (master.row(y) for y in range(height))

        if name != "long":
            # The JPEG and WebP variants encode the same raster.
            rows = tee(rows, ppm, width, height)

        info = png(path, width, height, rows, metadata=[(b"sRGB", b"\0")],
                   hold_rows=hold, encoder=PNG_TIMED)

        size = path.stat().st_size

        low, high = PNG_TIMED["bits_per_pixel_band"]

        require(low * width * height <= size * 8 <= high * width * height,
                "Timed PNG density is outside its band: " + name)

        info["bits_per_pixel"] = round(size * 8 / (width * height), 4)

        common = {"category": "timed", "width": width, "height": height,
                  "master": master.description(), "expected": "success"}

        common["master"]["rgb_sha256"] = info["serialized_samples_sha256"]

        corpus.add(relative, format="png", variant="rgb8-srgb",
                   **common, **info)

        if name == "long":
            continue

        sites = master.patch_sites(16)

        for variant, tool, flags, suffix in (
            ("jpeg-baseline-420", "cjpeg", JPEG + ["-baseline"], ".jpg"),
            ("jpeg-progressive-420", "cjpeg", JPEG + ["-progressive"],
             "-progressive.jpg"),
            ("webp-lossy", "cwebp", WEBP, ".webp"),
            ("webp-lossless", "cwebp", WEBP + ["-lossless"],
             "-lossless.webp"),
        ):
            relative = folder + "/" + name + suffix

            if name == "ordinary" and "-" in suffix:
                relative = "qualification/timed/" + name + suffix

            destination = corpus.output / relative

            destination.parent.mkdir(parents=True, exist_ok=True)

            if tool == "cjpeg":
                run(tool, [*flags, "-outfile", destination, ppm])
            else:
                run(tool, [*flags, ppm, "-o", destination])

            kind = "jpeg" if tool == "cjpeg" else "webp"

            reference = corpus.reference(destination, kind, width, height,
                                         sites)

            if variant == "webp-lossless":
                require(reference["rgb_sha256"]
                        == common["master"]["rgb_sha256"],
                        "Lossless WebP differs from the master")

                reference["comparison"] = {"max_channel_error": 0,
                                           "scope": "all RGB pixels"}

            corpus.add(relative, **common, format=kind, variant=variant,
                       encoder={"tool": tool, "flags": flags},
                       reference=reference)

        ppm.unlink()
