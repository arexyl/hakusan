"""Write the metadata limits and the fault, variant, and admission fixtures."""

import struct
import zlib

from generate_host import run
from generate_payloads import (exif, four_component_jpeg, icc_profile, icc_tags,
                               jpeg_insert)
from generate_png import (chunk, compressor, PNG_AUTHORED, png_insert,
                          PNG_SIGNATURE)


def exif_faults(corpus, base):
    """Write invalid, duplicate, and unsafe EXIF in PNG, JPEG, and WebP.
    Reads qualification/jpeg/ycbcr420-baseline.jpg and
    qualification/webp/opaque-lossless.webp, written by the JPEG and WebP
    correctness families."""
    jpeg_path = corpus.output / "qualification/jpeg/ycbcr420-baseline.jpg"

    jpeg = jpeg_path.read_bytes()

    webp_path = corpus.output / "qualification/webp/opaque-lossless.webp"

    for name, payload, expectation in (
        ("invalid", exif(9), "success-no-transform"),
        ("duplicate", exif(6, True), "success-no-transform"),
        ("unsafe-ifd", b"II\x2a\0\xff\xff\xff\x7f", "invalid-data"),
    ):
        corpus.add("qualification/fault/png-exif-" + name + ".png",
                   png_insert(base, [(b"eXIf", payload)]),
                   category="metadata-fault", format="png",
                   expected=expectation)

        corpus.add("qualification/fault/jpeg-exif-" + name + ".jpg",
                   jpeg_insert(jpeg, 0xe1, b"Exif\0\0" + payload),
                   category="metadata-fault", format="jpeg",
                   expected=expectation)

        metadata = corpus.work / "invalid.exif"

        metadata.write_bytes(payload)

        relative = "qualification/fault/webp-exif-" + name + ".webp"

        run("webpmux", ["-set", "exif", metadata, webp_path,
                        "-o", corpus.output / relative])

        corpus.add(relative, category="metadata-fault", format="webp",
                   expected=expectation)


def metadata_limits(corpus, base):
    """Write the ICC and EXIF size and count limits on the color base."""
    profile = icc_profile()

    for size in (1048575, 1048576, 1048577):
        expanded = (struct.pack(">I", size) + profile[4:]
                    + b"\0" * (size - len(profile)))

        corpus.add("qualification/fault/icc-expanded-%d.png" % size,
                   png_insert(base, [(b"iCCP", b"controlled\0\0"
                                      + zlib.compress(expanded, 6))]),
                   category="metadata-limit", format="png",
                   expanded_icc_bytes=size,
                   expected=("budget-exceeded" if size > 1048576
                             else "color-convert"),
                   oracle_status="admission-policy-only")

        expanded = exif(1) + b"\0" * (size - len(exif(1)))

        corpus.add("qualification/fault/exif-expanded-%d.png" % size,
                   png_insert(base, [(b"eXIf", expanded)]),
                   category="metadata-limit", format="png",
                   expanded_exif_bytes=size,
                   expected=("budget-exceeded" if size > 1048576
                             else "success"))

    initial = struct.unpack_from(">I", profile, 128)[0]

    for count in (127, 128, 129):
        expanded = icc_tags(profile, [
            (("p%03d" % i).encode(), b"text\0\0\0\0padding\0")
            for i in range(count - initial)])

        corpus.add("qualification/fault/icc-tags-%d.png" % count,
                   png_insert(base, [(b"iCCP", b"controlled\0\0"
                                      + zlib.compress(expanded, 6))]),
                   category="metadata-limit", format="png", icc_tags=count,
                   expected=("budget-exceeded" if count > 128
                             else "color-convert"),
                   oracle_status="admission-policy-only")

    for count in (1023, 1024, 1025):
        entries = b"".join(struct.pack("<HHII", 0x2000 + i, 4, 1, 0)
                           for i in range(count))

        payload = (b"II\x2a\0\x08\0\0\0" + struct.pack("<H", count)
                   + entries + b"\0" * 4)

        corpus.add("qualification/fault/exif-entries-%d.png" % count,
                   png_insert(base, [(b"eXIf", payload)]),
                   category="metadata-limit", format="png",
                   exif_entries=count,
                   expected=("budget-exceeded" if count > 1024
                             else "success-no-transform"))


def payload_faults(corpus, base):
    original = corpus.output / "journey/ordinary.png"

    data = original.read_bytes()

    entry = next(e for e in corpus.entries
                 if e["path"] == "journey/ordinary.png")

    hold = entry["hold"]["offset"]

    cases = [("png-truncated-prefix.png", data[:hold], "truncated"),
             ("png-trailing.png", base + b"trailing", "invalid-data"),
             ("png-duplicate-ihdr.png", base[:33] + base[8:33] + base[33:],
              "invalid-data")]

    damaged = bytearray(base)

    damaged[-13] ^= 1  # The last IDAT CRC, not optional metadata.
    cases.append(("png-payload-crc.png", bytes(damaged), "invalid-data"))

    palette_source = "qualification/png/type3-depth4-plain.png"

    palette = (corpus.output / palette_source).read_bytes()

    offset = palette.index(b"PLTE")

    damaged = bytearray(palette)

    damaged[offset + 4] ^= 1
    cases.append(("png-palette-crc.png", bytes(damaged), "invalid-data"))

    alpha = palette.index(b"tRNS")

    damaged = bytearray(palette)

    damaged[alpha + 4] ^= 1
    cases.append(("png-alpha-crc.png", bytes(damaged), "invalid-data"))

    optional = bytearray(png_insert(
        base, [(b"gAMA", struct.pack(">I", 45455))]))

    optional[45] ^= 1

    cases.append(("png-optional-color-crc.png", bytes(optional),
                  "warned-srgb-fallback"))

    # A valid enclosing IDAT may contain unused bytes after zlib EOF.
    offset = 8
    idats = []

    while offset < len(base):
        size = struct.unpack_from(">I", base, offset)[0]

        if base[offset + 4:offset + 8] == b"IDAT":
            idats.append((offset, size))

        offset += size + 12

    offset, size = idats[-1]
    tail = b"unused-deflate-tail"
    payload = base[offset + 8:offset + 8 + size] + tail

    unused = (base[:offset] + chunk(b"IDAT", payload)
              + base[offset + size + 12:])

    corpus.add("qualification/fault/png-unused-idat.png", unused,
               category="integrity-policy", format="png", expected="success",
               unused_idat_bytes=len(tail),
               note="Count unused final IDAT bytes without rejecting them")

    offset, size = idats[0]

    invalid_stream = bytearray(base[offset + 8:offset + 8 + size])

    invalid_stream[0] ^= 0xff

    invalid_png = (base[:offset] + chunk(b"IDAT", invalid_stream)
                   + base[offset + size + 12:])

    cases.append(("png-invalid-zlib.png", invalid_png, "invalid-data"))

    # Intact chunks and zlib data, but one middle scanline declares
    # filter type 5, which the PNG specification does not define.
    scanlines = bytearray(zlib.decompress(b"".join(
        base[start + 8:start + 8 + length] for start, length in idats)))

    scanlines[26 * (1 + 67 * 3)] = 5

    deflate = compressor(PNG_AUTHORED)

    payload = deflate.compress(bytes(scanlines)) + deflate.flush()

    corpus.add("qualification/fault/png-invalid-filter.png",
               base[:33] + chunk(b"IDAT", payload) + chunk(b"IEND", b""),
               category="payload-fault", format="png",
               expected="invalid-data", source="generated-small-png",
               invalid_filter={"scanline": 26, "filter_type": 5})

    for name, body, expectation in cases:
        if "prefix" in name:
            source = "journey/ordinary.png"
        elif name in ("png-palette-crc.png", "png-alpha-crc.png"):
            source = palette_source
        else:
            source = "generated-small-png"

        corpus.add("qualification/fault/" + name, body,
                   category="payload-fault", expected=expectation,
                   source=source)


def container_faults(corpus):
    for kind, relative in (("jpeg", "journey/ordinary.jpg"),
                           ("webp", "journey/ordinary.webp")):
        body = (corpus.output / relative).read_bytes()

        for name, content, expected in (
            ("truncated", body[:len(body) // 2], "truncated"),
            ("trailing", body + b"trailing", "invalid-data"),
        ):
            corpus.add("qualification/fault/%s-%s.%s" %
                       (kind, name, "jpg" if kind == "jpeg" else kind),
                       content, category="payload-fault", format=kind,
                       expected=expected, source=relative)


def jpeg_icc_faults(corpus):
    jpeg_path = corpus.output / "qualification/jpeg/ycbcr420-baseline.jpg"

    jpeg = jpeg_path.read_bytes()

    profile = icc_profile()

    for name, markers in (
        ("icc-duplicate", [b"ICC_PROFILE\0\x01\x01" + profile] * 2),
        ("icc-inconsistent-count", [b"ICC_PROFILE\0\x01\x02" + profile]),
        ("icc-zero-sequence", [b"ICC_PROFILE\0\x00\x01" + profile]),
    ):
        body = jpeg

        for marker in markers:
            body = jpeg_insert(body, 0xe2, marker)

        corpus.add("qualification/fault/jpeg-" + name + ".jpg", body,
                   category="metadata-fault", format="jpeg",
                   expected="warned-srgb-fallback")


def unsupported_variants(corpus, ppm):
    for name, flags in (("arithmetic", ["-arithmetic"]),
                        ("12bit", ["-precision", "12"]),
                        ("lossless", ["-lossless", "1"])):
        relative = "qualification/fault/jpeg-" + name + ".jpg"
        destination = corpus.output / relative

        run("cjpeg", [*flags, "-outfile", destination, ppm])

        corpus.add(relative, category="unsupported-variant", format="jpeg",
                   expected="unsupported-variant",
                   encoder={"tool": "cjpeg", "flags": flags})

    for name, transform in (("cmyk", 0), ("ycck", 2)):
        relative = "qualification/fault/jpeg-" + name + ".jpg"

        corpus.add(relative, four_component_jpeg(transform),
                   category="unsupported-variant", format="jpeg",
                   width=1, height=1, expected="unsupported-color-model",
                   encoder={"tool": "authored-single-mcu",
                            "adobe_transform": transform})

        # The reference must accept the coding before it is a model test.
        run("djpeg", ["-pnm", "-outfile", corpus.work / "excluded.ppm",
                      corpus.output / relative])


def admission_headers(corpus):
    for width in (65534, 65535, 65536):
        body = PNG_SIGNATURE + chunk(b"IHDR", struct.pack(
            ">IIBBBBB", width, 1, 8, 2, 0, 0, 0))

        corpus.add("qualification/fault/axis-%d.png" % width, body,
                   category="admission-header", format="png", width=width,
                   height=1, expected=("budget-exceeded" if width > 65535
                                      else "header-admitted-not-complete"))

    for width, height in ((7999, 8001), (8000, 8000), (5213, 12277)):
        body = PNG_SIGNATURE + chunk(b"IHDR", struct.pack(
            ">IIBBBBB", width, height, 8, 2, 0, 0, 0))

        corpus.add("qualification/fault/pixels-%d.png" % (width * height),
                   body, category="admission-header", format="png",
                   width=width, height=height,
                   expected=("budget-exceeded" if width * height > 64000000
                             else "header-admitted-not-complete"))


def animation(corpus, base):
    # An actual APNG, with its default image as the first animation frame.
    ihdr = base[8:33]

    first = chunk(b"fcTL", struct.pack(">IIIIIHHBB", 0, 67, 53,
                                       0, 0, 1, 10, 0, 0))

    animated = (PNG_SIGNATURE + ihdr
                + chunk(b"acTL", struct.pack(">II", 1, 0))
                + first + base[33:])

    corpus.add("qualification/fault/animated.png", animated,
               category="unsupported-variant", format="png",
               expected="unsupported-variant")

    webp = corpus.output / "qualification/webp/opaque-lossless.webp"
    destination = corpus.output / "qualification/fault/animated.webp"

    run("webpmux", ["-frame", webp, "+100", "-frame", webp, "+100",
                    "-loop", "0", "-o", destination])

    corpus.add("qualification/fault/animated.webp",
               category="unsupported-variant",
               format="webp", expected="unsupported-variant")


def faults(corpus, base, ppm):
    """Write the PNG, JPEG, and WebP payload faults, the JPEG ICC faults, the
    unsupported variants, the admission headers, and the animations. Reads
    journey/ordinary.png with its hold offset, journey/ordinary.jpg, and
    journey/ordinary.webp, written by the timed family;
    qualification/png/type3-depth4-plain.png, written by the PNG correctness
    family; and qualification/jpeg/ycbcr420-baseline.jpg and
    qualification/webp/opaque-lossless.webp, written by the JPEG and WebP
    correctness families."""
    payload_faults(corpus, base)
    container_faults(corpus)
    jpeg_icc_faults(corpus)
    unsupported_variants(corpus, ppm)
    admission_headers(corpus)
    animation(corpus, base)
