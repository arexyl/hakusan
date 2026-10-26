"""Write the color profile and color transport fixtures."""

import json
from pathlib import Path
import struct
import subprocess
import sys
import zlib

from generate_host import require, run
from generate_payloads import fixed, icc_profile, icc_tags, jpeg_insert
from generate_png import png, png_insert


COLOR_PATCHES = [(0, 0, 0), (255, 255, 255), (255, 0, 0),
                 (0, 255, 0), (0, 0, 255), (32, 96, 192),
                 (192, 96, 32), (128, 128, 128)]


def profile_fixture(corpus, name, profile, gray=False, **metadata):
    relative = "qualification/color/" + name + ".icc"

    corpus.add(relative, profile, category="icc-profile", format="icc",
               gray=gray, expected="supported-color-profile", **metadata)

    values = ([(i * 255 // 7,) for i in range(8)] if gray
              else COLOR_PATCHES)

    function = metadata.get("function")

    if function in (1, 2, 3, 4):
        # Include the nearest 8-bit samples on either side of each join.
        levels = ([0, 32, 56, 57, 64, 128, 192, 255]
                  if function in (1, 2) else
                  [0, 16, 32, 63, 64, 96, 192, 255])

        values = ([(value,) for value in levels] if gray else
                  [(value, levels[(i + 2) % 8], levels[(i + 5) % 8])
                   for i, value in enumerate(levels)])

    row = b"".join(bytes(value) * 20 for value in values)

    path = corpus.work / "color.png"

    png(path, 160, 64, (row for _ in range(64)), color=0 if gray else 2)

    source = path.read_bytes()

    profile_path = corpus.output / relative

    reference = subprocess.run([
        sys.executable, "-I", "-B",
        str(Path(__file__).with_name("color_reference.py")),
        str(profile_path), json.dumps(values),
    ], capture_output=True, check=True, timeout=30)

    expected = json.loads(reference.stdout)

    numeric = ("\n".join(" ".join(map(str, value)) for value in values)
               + "\n").encode()

    lcms = run("transicc", ["-n", "-c0", "-t1", "-s",
                           "-i" + str(profile_path), "-o*sRGB"], numeric)

    lines = [line for line in lcms.decode().splitlines() if line.strip()]

    corroboration = [[float(value) for value in line.split()]
                     for line in lines]

    require(len(corroboration) == len(expected), "LCMS result count")

    require(all(abs(actual - target) <= 2 for observed, target_values
                in zip(corroboration, expected)
                for actual, target in zip(observed, target_values)),
            "Color oracle differs from LCMS")

    corpus.add("qualification/color/" + name + ".png",
               png_insert(source, [(b"iCCP", b"controlled\0\0"
                                    + zlib.compress(profile, 6))]),
               category="color", format="png", expected="color-convert",
               width=160, height=64, profile=relative,
               oracle_status="decimal50-and-lcms-cross-check",
               color_reference={
                   "method": "decoded-profile-matrix-trc-decimal50",
                   "intent": "media-relative-colorimetric",
                   "black_point_compensation": False,
                   "rgb_channel_tolerance": 2,
                   "lcms_tool": "transicc",
                   "lcms_flags": ["-n", "-c0", "-t1", "-s"],
                   "lcms_rgb": corroboration,
                   "patches": [{"x": i * 20 + 4, "y": 16,
                                "width": 12, "height": 32,
                                "input": list(value),
                                "expected_rgb": expected[i]}
                               for i, value in enumerate(values)],
               })


def color_base_png(work):
    """Write the 160 x 64 RGB PNG of the color patches, which the PNG color
    and metadata-limit specimens extend, and return its bytes."""
    color_base = work / "color-base.png"

    row = b"".join(bytes(value) * 20 for value in COLOR_PATCHES)

    png(color_base, 160, 64, (row for _ in range(64)))

    base = color_base.read_bytes()

    return base


def profile_variants(corpus):
    for version, gray, function in (
        [(2, False, None), (2, True, None),
         (4, False, None), (4, True, None)]
        + [(4, gray, function) for gray in (False, True)
           for function in range(5)]
    ):
        name = "v%d-%s-%s" % (
            version, "gray" if gray else "rgb",
            "curve" if function is None else "param%d" % function)

        profile = icc_profile(version, gray, function)

        profile_fixture(corpus, name, profile, gray, profile_version=version,
                        function=function)


def icc_transport(corpus, profile):
    # Container-specific ICC transport is covered without a cross product.
    jpeg_path = corpus.output / "qualification/jpeg/ycbcr420-baseline.jpg"

    jpeg = jpeg_path.read_bytes()

    webp_path = corpus.output / "qualification/webp/opaque-lossless.webp"
    jpeg_relative = "qualification/color/jpeg-icc.jpg"

    (corpus.output / jpeg_relative).write_bytes(jpeg_insert(
        jpeg, 0xe2, b"ICC_PROFILE\0\x01\x01" + profile))

    corpus.add(jpeg_relative,
               category="color", format="jpeg", expected="color-convert",
               width=128, height=96,
               **transport_reference(corpus, jpeg_relative, "jpeg", 128, 96))

    profile_path = corpus.work / "profile.icc"

    profile_path.write_bytes(profile)

    destination = corpus.output / "qualification/color/webp-icc.webp"

    run("webpmux", ["-set", "icc", profile_path, webp_path,
                    "-o", destination])

    corpus.add("qualification/color/webp-icc.webp", category="color",
               format="webp", expected="color-convert", width=67, height=53,
               **transport_reference(corpus,
                                     "qualification/color/webp-icc.webp",
                                     "webp", 67, 53))


def chunk_policy(corpus, base, profile):
    chromaticities = struct.pack(">8I", 31270, 32900, 64000, 33000,
                                 30000, 60000, 15000, 6000)

    cases = [
        ("gamma-only", [(b"gAMA", struct.pack(">I", 45455))],
         "color-convert"),
        ("chrm-only", [(b"cHRM", chromaticities)], "color-convert"),
        ("cicp-srgb", [(b"cICP", bytes((1, 13, 0, 1)))], "success"),
        ("cicp-p3", [(b"cICP", bytes((12, 13, 0, 1)))],
         "warned-srgb-fallback"),
        ("cicp-pq", [(b"cICP", bytes((1, 16, 0, 1)))],
         "unsupported-hdr"),
        ("cicp-hlg", [(b"cICP", bytes((1, 18, 0, 1)))],
         "unsupported-hdr"),
        ("cicp-narrow", [(b"cICP", bytes((1, 13, 0, 0)))],
         "unsupported-color-model"),
        ("cicp-unknown", [(b"cICP", bytes((1, 2, 0, 1)))],
         "unsupported-color-model"),
        ("gamma-zero", [(b"gAMA", b"\0" * 4)], "warned-srgb-fallback"),
        ("icc-malformed", [(b"iCCP", b"controlled\0\0"
                            + zlib.compress(icc_profile(malformed=True)))],
         "warned-srgb-fallback"),
        ("cicp-precedes-gamma", [(b"cICP", bytes((1, 13, 0, 1))),
                                  (b"gAMA", struct.pack(">I", 100000))],
         "success"),
        ("hdr-precedes-srgb", [(b"cICP", bytes((1, 16, 0, 1))),
                                (b"sRGB", b"\0")], "unsupported-hdr"),
    ]

    for primaries in (4, 5, 6, 7, 8, 9, 11, 22):
        cases.append(("cicp-sdr-primary-%d" % primaries,
                      [(b"cICP", bytes((primaries, 13, 0, 1)))],
                      "warned-srgb-fallback"))

    for transfer in (1, 4, 5, 6, 7, 14, 15):
        cases.append(("cicp-sdr-transfer-%d" % transfer,
                      [(b"cICP", bytes((1, transfer, 0, 1)))],
                      "warned-srgb-fallback"))

    cases += [
        ("srgb-precedes-gamma", [(b"sRGB", b"\0"),
                                 (b"gAMA", struct.pack(">I", 100000))],
         "success"),
        ("icc-precedes-gamma", [(b"iCCP", b"controlled\0\0"
                                 + zlib.compress(profile, 6)),
                                (b"gAMA", struct.pack(">I", 100000))],
         "color-convert"),
        ("cicp-precedes-icc", [(b"cICP", bytes((1, 13, 0, 1))),
                                (b"iCCP", b"controlled\0\0"
                                 + zlib.compress(profile, 6))], "success"),
        ("mdcv-alone", [(b"mDCV", struct.pack(">8H2I", 32000, 16500,
                                              15000, 30000, 7500, 3000,
                                              15635, 16450,
                                              1000000, 1000))],
         "success"),
    ]

    for name, additions, expectation in cases:
        oracle = {"oracle_status": "declared-policy"}

        if expectation in ("success", "warned-srgb-fallback"):
            oracle = {"oracle_status": "srgb-patch-identity",
                      "policy_patches": policy_patches(COLOR_PATCHES)}

        if expectation == "color-convert":
            description = {}
            profile_path = corpus.work / "case-profile.icc"
            is_profile = False

            for kind, payload in additions:
                if kind == b"gAMA":
                    description["gamma"] = struct.unpack(">I", payload)[0]

                if kind == b"cHRM":
                    description["chromaticities"] = list(struct.unpack(
                        ">8I", payload))

                if kind == b"iCCP":
                    start = payload.index(b"\0") + 2

                    profile_path.write_bytes(zlib.decompress(
                        payload[start:]))

                    is_profile = True

            args = []

            if not is_profile:
                profile_path = corpus.work / "description.json"

                profile_path.write_text(json.dumps(description))

                args = ["--png-description"]

            reference = subprocess.run([
                sys.executable, "-I", "-B",
                str(Path(__file__).with_name("color_reference.py")),
                str(profile_path), json.dumps(COLOR_PATCHES), *args,
            ], capture_output=True, check=True, timeout=30)

            oracle = {"oracle_status": "decimal50-color-description",
                      "policy_patches": policy_patches(
                          json.loads(reference.stdout))}

        corpus.add("qualification/color/" + name + ".png",
                   png_insert(base, additions),
                   category="color", format="png",
                   width=160, height=64, expected=expectation, **oracle)


def unsupported_profiles(corpus, base, profile):
    # A bounded LUT wins over matrix/TRC tags; it must not be ignored.
    matrix = b"".join(fixed(1 if i % 4 == 0 else 0) for i in range(9))

    lut = (b"mft1\0\0\0\0" + bytes((3, 3, 2, 0)) + matrix
           + bytes(range(256)) * 3
           + bytes(value for red in (0, 255) for green in (0, 255)
                   for blue in (0, 255) for value in (red, green, blue))
           + bytes(range(256)) * 3)

    unsupported = {"lut-and-matrix": icc_tags(profile, [(b"A2B0", lut)])}

    for name, position, replacement in (
        ("lab-pcs", 20, b"Lab "), ("device-link", 12, b"link"),
        ("output-class", 12, b"prtr"), ("abstract", 12, b"abst"),
        ("named-color", 12, b"nmcl"), ("iccmax", 8, b"\x05\0\0\0"),
    ):
        unsupported[name] = (profile[:position] + replacement
                             + profile[position + 4:])

    for name, body in unsupported.items():
        corpus.add("qualification/color/unsupported-" + name + ".png",
                   png_insert(base, [(b"iCCP", b"controlled\0\0"
                                      + zlib.compress(body, 6))]),
                   category="color", format="png",
                   expected="warned-srgb-fallback",
                   width=160, height=64,
                   oracle_status="srgb-patch-identity",
                   policy_patches=policy_patches(COLOR_PATCHES))


def supported_profiles(corpus, profile):
    # Additional supported profile classes and sampled/identity curveType.
    supported = {"input-class": profile[:12] + b"scnr" + profile[16:]}

    wide_columns = {
        b"rXYZ": (0.51512146, 0.24119568, -0.001052856),
        b"gXYZ": (0.2919769, 0.69224548, 0.041885376),
        b"bXYZ": (0.15710449, 0.0665741, 0.78407288),
    }

    wide_tags = []

    for index in range(struct.unpack_from(">I", profile, 128)[0]):
        tag, offset, length = struct.unpack_from(">4sII", profile,
                                                  132 + 12 * index)

        payload = profile[offset:offset + length]

        if tag in wide_columns:
            payload = b"XYZ \0\0\0\0" + b"".join(
                fixed(value) for value in wide_columns[tag])

        wide_tags.append((tag, payload))

    supported["wide-rgb-matrix"] = icc_tags(
        profile[:128] + b"\0" * 4, wide_tags)

    for name, curve in (
        ("identity-curve", b"curv\0\0\0\0" + struct.pack(">I", 0)),
        ("sampled-curve", b"curv\0\0\0\0" + struct.pack(">I5H", 5,
                                                           0, 4096, 16384,
                                                           36863, 65535)),
    ):
        original_count = struct.unpack_from(">I", profile, 128)[0]

        retained = []

        for index in range(original_count):
            tag, offset, length = struct.unpack_from(">4sII", profile,
                                                      132 + 12 * index)

            payload = (curve if tag in (b"rTRC", b"gTRC", b"bTRC")
                       else profile[offset:offset + length])

            retained.append((tag, payload))

        empty = profile[:128] + b"\0" * 4

        supported[name] = icc_tags(empty, retained)

    for name, body in supported.items():
        profile_fixture(corpus, name, body)


def color(corpus, base):
    """Write the color fixtures: the ICC profile specimens, each on its own
    patch raster; the ICC transport specimens, built from the JPEG and WebP
    correctness specimens; and the chunk-policy and unsupported-profile
    specimens on the color base. Reads qualification/jpeg/ycbcr420-baseline.jpg
    and qualification/webp/opaque-lossless.webp, written by the JPEG and WebP
    correctness families."""
    profile_variants(corpus)

    profile = icc_profile()

    icc_transport(corpus, profile)
    chunk_policy(corpus, base, profile)
    unsupported_profiles(corpus, base, profile)
    supported_profiles(corpus, profile)


def policy_patches(expected):
    return [{"x": index * 20 + 4, "y": 16, "width": 12, "height": 32,
             "input": list(COLOR_PATCHES[index]),
             "expected_rgb": list(value), "max_channel_error": 2}
            for index, value in enumerate(expected)]


def transport_reference(corpus, relative, kind, width, height):
    path = corpus.output / relative

    reference = corpus.reference(path, kind, width, height)

    profile = "qualification/color/v4-rgb-curve.icc"
    patches = []

    for patch in reference["patches"]:
        source = bytes.fromhex(patch["samples_hex"])

        channels = reference["channels"]

        samples = [list(source[i:i + 3])
                   for i in range(0, len(source), channels)]

        result = subprocess.run([
            sys.executable, "-I", "-B",
            str(Path(__file__).with_name("color_reference.py")),
            str(corpus.output / profile), json.dumps(samples),
        ], capture_output=True, check=True, timeout=60)

        expected = json.loads(result.stdout)

        patches.append({
            "x": patch["x"], "y": patch["y"],
            "width": patch["width"], "height": patch["height"],
            "input_rgb_hex": bytes(v for rgb in samples for v in rgb).hex(),
            "expected_rgb_hex": bytes(
                v for rgb in expected for v in rgb).hex(),
        })

    return {"oracle_status": "reference-decode-and-decimal50-color",
            "reference": reference,
            "color_patch_reference": {"profile": profile,
                                      "max_channel_error": 2,
                                      "patches": patches}}
