#!/usr/bin/env python3
"""Verify reviewed fixture identity, PNG samples, and pinned references."""

import argparse
import binascii
from decimal import Decimal, localcontext, ROUND_HALF_UP
import hashlib
from itertools import accumulate
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import zlib


ROOT = Path(__file__).resolve().parents[3]
WORK = ROOT / "build/work/reader/fixtures"
SIGNATURE = b"\x89PNG\r\n\x1a\n"

PASSES = [(0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8), (2, 0, 4, 4),
          (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2)]

CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}

# Timed PNG files encode at 1 to 3 bits per pixel: file bytes times 8
# divided by width times height.
TIMED_PNG_BAND = [1, 3]

TEXTURES = {"flat", "line-art", "screentone", "hatching", "noise", "text"}

# Re-deriving every adaptive filter choice with scalar code would dominate
# verification time. A changed score or tie rule changes the choice on
# several sampled scanlines of each timed PNG, so every 32nd scanline
# exposes it.
ADAPTIVE_SAMPLE = 32

# The absolute value of a filtered byte read as a signed value.
MAGNITUDE = bytes(min(value, 256 - value) for value in range(256))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}

    for key, value in pairs:
        require(key not in result, "Duplicate JSON key: " + key)
        result[key] = value

    return result


def png_chunks(data):
    require(data[:8] == SIGNATURE, "Invalid PNG signature")

    position = 8
    chunks = []

    while position < len(data):
        require(position + 12 <= len(data), "Truncated PNG chunk")

        length = struct.unpack_from(">I", data, position)[0]

        end = position + length + 12

        require(end <= len(data), "Truncated PNG chunk payload")

        kind = data[position + 4:position + 8]
        payload = data[position + 8:end - 4]

        crc = struct.unpack_from(">I", data, end - 4)[0]

        require(crc == binascii.crc32(kind + payload) & 0xffffffff,
                "Invalid PNG CRC: " + kind.decode("ascii", "replace"))

        chunks.append((kind, payload, end))

        position = end

    return chunks


def inflated(chunks):
    stream = zlib.decompressobj()

    for kind, payload, _ in chunks:
        if kind != b"IDAT":
            continue

        pending = payload

        while pending:
            block = stream.decompress(pending, 262144)

            pending = stream.unconsumed_tail

            if block:
                yield block

        require(not stream.unused_data, "Unexpected unused compressed data")

    final = stream.flush()

    if final:
        yield final

    require(stream.eof, "Incomplete PNG compressed stream")


def scanline_layout(width, height, depth, color, interlace):
    """Return the number, scanline count, and scanline length of each
    nonempty pass."""
    layout = []

    for number, (left, top, dx, dy) in enumerate(
            PASSES if interlace else [(0, 0, 1, 1)]):
        columns = len(range(left, width, dx))

        count = len(range(top, height, dy))

        if columns and count:
            layout.append((number, count,
                           (columns * CHANNELS[color] * depth + 7) // 8))

    return layout


def scanlines(chunks, layout):
    """Yield the pass number, index, filter type, and filtered bytes of
    each scanline, and require the exact extent of the inflated data."""
    blocks = inflated(chunks)

    pending = bytearray()

    start = 0

    for number, count, length in layout:
        for index in range(count):
            while len(pending) - start <= length:
                block = next(blocks, None)

                require(block is not None, "PNG row extent mismatch")
                del pending[:start]

                start = 0

                pending += block

            yield (number, index, pending[start],
                   bytes(pending[start + 1:start + 1 + length]))

            start += length + 1

    require(len(pending) == start and next(blocks, None) is None,
            "PNG row extent mismatch")


def add_bytes(first, second):
    """Return the bytewise sum modulo 256 of two equal-length strings."""
    # Sums of the low seven bits never carry into the next byte, and the
    # exclusive or then sets each top bit modulo 256.
    count = len(first)

    low = int.from_bytes(b"\x7f" * count, "big")

    a = int.from_bytes(first, "big")

    b = int.from_bytes(second, "big")

    return (((a & low) + (b & low)) ^ ((a ^ b) & ~low)).to_bytes(count, "big")


def paeth(a, b, c):
    """Return the Paeth predictor of the PNG specification."""
    estimate = a + b - c

    pa = abs(estimate - a)

    pb = abs(estimate - b)

    pc = abs(estimate - c)

    if pa <= pb and pa <= pc:
        return a

    if pb <= pc:
        return b

    return c


def reconstruct_sub(line, prior):
    return bytes(accumulate(line, lambda a, x: (a + x) & 255))


def reconstruct_average(line, prior):
    row = []
    a = 0

    for x, b in zip(line, prior):
        a = (x + ((a + b) >> 1)) & 255

        row.append(a)

    return bytes(row)


def reconstruct_paeth(line, prior):
    row = []
    a = c = 0

    for x, b in zip(line, prior):
        a = (x + paeth(a, b, c)) & 255

        row.append(a)

        c = b

    return bytes(row)


RECONSTRUCT = {1: reconstruct_sub, 3: reconstruct_average,
               4: reconstruct_paeth}


def unfilter(kind, line, prior, unit):
    """Reverse one of the five PNG filter types for one scanline.

    The filter unit is the byte count of a complete pixel, at least one.
    A pass's first scanline has a zero previous scanline.
    """
    require(kind < 5, "Invalid PNG filter type %d" % kind)

    if kind == 0:
        return line

    if kind == 2:
        return add_bytes(line, prior)

    row = bytearray(len(line))

    # Bytes one filter unit apart form independent prediction chains.
    for offset in range(unit):
        row[offset::unit] = RECONSTRUCT[kind](line[offset::unit],
                                              prior[offset::unit])

    return bytes(row)


def png_rows(chunks):
    """Yield the pass number, index, filter type, filtered bytes, previous
    samples, and samples of each scanline."""
    width, height, depth, color, _, _, interlace = struct.unpack(
        ">IIBBBBB", chunks[0][1])

    unit = max(1, CHANNELS[color] * depth // 8)

    prior = b""

    for number, index, kind, line in scanlines(
            chunks, scanline_layout(width, height, depth, color, interlace)):
        if index == 0:
            prior = bytes(len(line))

        row = unfilter(kind, line, prior, unit)

        yield number, index, kind, line, prior, row

        prior = row


def adaptive_choice(row, prior, unit):
    """Return the filter type that the adaptive rule selects: the smallest
    sum of filtered bytes read as signed values, absolute, and the lowest
    type on a tie."""
    left = bytes(unit) + row[:-unit]

    corner = bytes(unit) + prior[:-unit]

    predictions = (bytes(len(row)), left, prior,
                   [(a + b) >> 1 for a, b in zip(left, prior)],
                   [paeth(a, b, c) for a, b, c in zip(left, prior, corner)])

    scores = [sum(MAGNITUDE[(x - p) & 255] for x, p in zip(row, predicted))
              for predicted in predictions]

    return scores.index(min(scores))


def verify_png(entry, data, rule):
    """Check a decodable PNG's structure, row extents, and filter rule, and
    its recorded scanline and sample hashes when the entry has them."""
    chunks = png_chunks(data)

    require(chunks[0][0] == b"IHDR", "Missing first IHDR")
    require(chunks[-1][:2] == (b"IEND", b""), "Missing IEND")

    width, height, depth, color, method, filtering, interlace = struct.unpack(
        ">IIBBBBB", chunks[0][1])

    require(method == filtering == 0, "Unexpected PNG method")

    if "width" in entry:
        require((width, height) == (entry["width"], entry["height"]),
                "PNG geometry mismatch")

    image = chunks

    unused = entry.get("unused_idat_bytes", 0)

    if unused:
        # The zlib stream must end exactly before the declared unused tail
        # of the final IDAT.
        last = max(index for index, (kind, _, _) in enumerate(chunks)
                   if kind == b"IDAT")

        kind, payload, end = chunks[last]

        require(len(payload) > unused, "Unused IDAT tail exceeds its chunk")

        image = (chunks[:last] + [(kind, payload[:-unused], end)]
                 + chunks[last + 1:])

    digest = hashlib.sha256()

    samples = hashlib.sha256()

    counts = [0] * 5

    unit = max(1, CHANNELS[color] * depth // 8)

    for serial, (number, index, kind, line, prior, row) in enumerate(
            png_rows(image)):
        digest.update(bytes((kind,)) + line)
        samples.update(row)
        counts[kind] += 1

        if rule == "none":
            require(kind == 0, "Unfiltered PNG group uses a filter")
        elif rule == "cycle":
            require(kind == (index + number) % 5,
                    "PNG specimen filter cycle changed")
        elif rule == "adaptive" and serial % ADAPTIVE_SAMPLE == 0:
            require(kind == adaptive_choice(row, prior, unit),
                    "Adaptive PNG filter choice changed")

    require(rule != "cycle" or all(counts),
            "A PNG specimen lacks a filter type")

    if "filtered_scanlines_sha256" in entry:
        require(digest.hexdigest() == entry["filtered_scanlines_sha256"],
                "PNG filtered scanlines changed")

        require(samples.hexdigest() == entry["serialized_samples_sha256"],
                "PNG serialized samples changed")

        require(counts == entry["scanline_filter_counts"],
                "PNG filter type counts changed")

    hold = entry.get("hold")

    if hold:
        offset = hold["offset"]

        require(sha(data[:offset]) == hold["prefix_sha256"], "Prefix changed")

        require(any(kind == b"IDAT" and end == offset
                    for kind, _, end in chunks),
                "Hold is not after complete IDAT")

        stream = zlib.decompressobj()

        size = 0

        for kind, payload, end in chunks:
            if end <= offset and kind == b"IDAT":
                size += len(stream.decompress(payload))

        require(not stream.eof, "Hold delays only completion")

        require(size == hold["source_rows"] * (1 + width * 3),
                "Hold does not contain exactly the declared RGB8 rows")

        require(hold["source_rows"] * 1080 / width >= 960,
                "Prefix cannot cover useful output")

        later_bytes = sum(len(payload) for kind, payload, end in chunks
                          if kind == b"IDAT" and end > offset)

        require(later_bytes > 6 and hold["source_rows"] < height,
                "No required image payload withheld")

        remaining = 0

        for kind, payload, end in chunks:
            if end > offset and kind == b"IDAT":
                remaining += len(stream.decompress(payload))

        require(stream.eof, "Released payload does not complete zlib")

        require(remaining == (height - hold["source_rows"]) * (1 + width * 3),
                "Withheld payload does not encode declared remaining rows")


def verify_icc(data):
    require(len(data) >= 132, "ICC header truncated")
    require(struct.unpack_from(">I", data)[0] == len(data), "ICC size mismatch")
    require(data[36:40] == b"acsp", "ICC magic mismatch")

    count = struct.unpack_from(">I", data, 128)[0]

    require(count <= 128 and 132 + count * 12 <= len(data), "ICC table invalid")

    signatures = set()

    for index in range(count):
        signature, offset, length = struct.unpack_from(">4sII", data,
                                                       132 + index * 12)

        require(signature not in signatures, "Duplicate ICC tag")
        signatures.add(signature)

        require(offset % 4 == 0 and offset + length <= len(data),
                "ICC tag outside profile")


def verify_jpeg(entry, data):
    require(data[:2] == b"\xff\xd8" and data[-2:] == b"\xff\xd9",
            "JPEG terminal structure mismatch")

    offset = 2
    frame = None

    while offset < len(data):
        require(data[offset] == 255, "Invalid JPEG marker prefix")

        marker = data[offset + 1]

        length = struct.unpack_from(">H", data, offset + 2)[0]

        require(length >= 2 and offset + 2 + length <= len(data),
                "JPEG marker outside image")

        payload = data[offset + 4:offset + length + 2]

        if marker in (0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
                      0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf):
            require(frame is None and len(payload) >= 6, "Invalid JPEG frame")

            precision, height, width, components = struct.unpack_from(
                ">BHHB", payload)

            require(len(payload) == 6 + 3 * components,
                    "Invalid JPEG component table")

            frame = (width, height)

            if entry.get("variant", "").endswith("420"):
                require(precision == 8 and components == 3,
                        "Timed JPEG coding mismatch")

                require([payload[7 + 3 * i] for i in range(3)]
                        == [0x22, 0x11, 0x11], "Timed JPEG sampling mismatch")

                progressive = "progressive" in entry["variant"]

                require(marker == (0xc2 if progressive else 0xc0),
                        "Timed JPEG frame type mismatch")

        offset += length + 2

        if marker == 0xda:
            break

    require(frame is not None, "JPEG frame missing")

    if "width" in entry:
        require(frame == (entry["width"], entry["height"]),
                "JPEG geometry mismatch")


def verify_webp(entry, data):
    require(data[:4] == b"RIFF" and data[8:12] == b"WEBP",
            "WebP signature mismatch")

    require(struct.unpack_from("<I", data, 4)[0] + 8 == len(data),
            "WebP extent mismatch")

    offset = 12
    geometry = None
    chunks = []

    while offset < len(data):
        require(offset + 8 <= len(data), "Truncated WebP chunk")

        kind = data[offset:offset + 4]

        length = struct.unpack_from("<I", data, offset + 4)[0]

        end = offset + 8 + length

        require(end + length % 2 <= len(data), "WebP chunk outside RIFF")

        payload = data[offset + 8:end]

        chunks.append(kind)

        if kind == b"VP8 ":
            require(payload[3:6] == b"\x9d\x01\x2a", "Invalid VP8 frame")

            width, height = struct.unpack_from("<HH", payload, 6)

            geometry = (width & 0x3fff, height & 0x3fff)
        elif kind == b"VP8L":
            require(payload[0] == 0x2f, "Invalid VP8L frame")

            bits = struct.unpack_from("<I", payload, 1)[0]

            geometry = ((bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1)
        elif kind == b"VP8X":
            require(length == 10, "Invalid VP8X length")

            geometry = (int.from_bytes(payload[4:7], "little") + 1,
                        int.from_bytes(payload[7:10], "little") + 1)

        offset = end + length % 2

    require(offset == len(data), "WebP padding mismatch")

    if "width" in entry:
        require(geometry == (entry["width"], entry["height"]),
                "WebP geometry mismatch")

    if entry.get("exif_order"):
        image_index = max(i for i, kind in enumerate(chunks)
                          if kind in (b"VP8 ", b"VP8L"))

        require(chunks.index(b"EXIF") > image_index, "EXIF is not late")


def verify_patch_sites(entry):
    """Check that a timed raster's reference patches sample each texture
    clear of its region's label, and the inside of each corner marker."""
    master = entry["master"]

    regions = {region["label"]: region for region in master["regions"]}

    side = master["corner_side_source_pixels"]
    far_x, far_y = entry["width"] - side, entry["height"] - side

    corners = {"corner-top-left": (0, 0), "corner-top-right": (far_x, 0),
               "corner-bottom-left": (0, far_y),
               "corner-bottom-right": (far_x, far_y)}

    covered = []

    for patch in entry["reference"]["patches"]:
        x, y = patch["x"], patch["y"]
        right, bottom = x + patch["width"], y + patch["height"]

        if patch["region"] in corners:
            require(patch["content"] == "corner-marker",
                    "A corner patch declares other content")

            left, top = corners[patch["region"]]
            area = (left, top, left + side, top + side)

            covered.append(patch["region"])
        else:
            region = regions.get(patch["region"])

            require(region is not None
                    and region["texture"] == patch["content"],
                    "A reference patch names another region")

            label_x, label_y, label_width, label_height = region["label_box"]

            require(right <= label_x or label_x + label_width <= x
                    or bottom <= label_y or label_y + label_height <= y,
                    "A reference patch overlaps a label")

            area = (region["x"], region["y"], region["x"] + region["width"],
                    region["y"] + region["height"])

            covered.append(region["texture"])

        require(area[0] <= x and right <= area[2]
                and area[1] <= y and bottom <= area[3],
                "A reference patch leaves its declared area")

    require(sorted(covered) == sorted(TEXTURES | set(corners)),
            "Reference patches do not cover each texture and corner once")


def verify_invalid_filter(entry, data):
    """Check that a fault specimen is intact except for its declared
    invalid filter type."""
    chunks = png_chunks(data)

    require(chunks[0][0] == b"IHDR" and chunks[-1][:2] == (b"IEND", b""),
            "Invalid filter specimen structure changed")

    width, height, depth, color, _, _, interlace = struct.unpack(
        ">IIBBBBB", chunks[0][1])

    fault = entry["invalid_filter"]

    require(fault["filter_type"] > 4, "Declared filter type is valid")

    invalid = [(serial, kind) for serial, (_, _, kind, _) in enumerate(
        scanlines(chunks, scanline_layout(width, height, depth, color,
                                          interlace)))
               if kind > 4]

    require(invalid == [(fault["scanline"], fault["filter_type"])],
            "Invalid filter specimen changed")


def png_reference(data):
    """Reconstruct small authored specimens for sample oracles."""
    chunks = png_chunks(data)

    width, height, depth, color, _, _, interlace = struct.unpack(
        ">IIBBBBB", chunks[0][1])

    require(width <= 256 and height <= 256, "Oracle only accepts small images")

    channels = CHANNELS[color]
    passes = PASSES if interlace else [(0, 0, 1, 1)]
    pixels = {}

    for number, index, _, _, _, row in png_rows(chunks):
        left, top, dx, dy = passes[number]
        y = top + index * dy

        for column, x in enumerate(range(left, width, dx)):
            values = []

            for channel in range(channels):
                bit = (column * channels + channel) * depth

                if depth == 16:
                    value = struct.unpack_from(">H", row, bit // 8)[0]
                elif depth == 8:
                    value = row[bit // 8]
                else:
                    value = (row[bit // 8] >> (8 - depth - bit % 8)) & (
                        (1 << depth) - 1)

                values.append(value)

            pixels[(x, y)] = values

    require(len(pixels) == width * height, "Oracle raster coverage mismatch")

    palette = next((payload for kind, payload, _ in chunks
                    if kind == b"PLTE"), b"")

    transparent = next((payload for kind, payload, _ in chunks
                        if kind == b"tRNS"), b"")

    points = []

    with localcontext() as context:
        context.prec = 50

        for x, y in ((0, 0), (width - 1, 0), (5, 7),
                     (width // 2, height // 2), (width - 1, height - 1)):
            samples = pixels[(x, y)]
            maximum = (1 << depth) - 1

            converted = [int((Decimal(value) * 255 / maximum).quantize(
                Decimal(1), rounding=ROUND_HALF_UP)) for value in samples]

            if color == 3:
                index = samples[0]

                rgba = list(palette[index * 3:index * 3 + 3]) + [
                    transparent[index] if index < len(transparent) else 255]
            else:
                rgb = converted[:3] if color in (2, 6) else [converted[0]] * 3
                alpha = converted[-1] if color in (4, 6) else 255

                if transparent and color in (0, 2):
                    key = list(struct.unpack(">" + "H" * channels, transparent))

                    if samples == key:
                        alpha = 0

                rgba = rgb + [alpha]

            points.append({"x": x, "y": y, "source_samples": samples,
                           "expected_rgba": rgba})

    return {"method": "decoded-samples-decimal50-srgb-depth-reduction",
            "ignore_rgb_when_alpha_zero": True,
            "max_channel_error": 2 if depth == 16 else 0, "points": points}


def verify_reference(entry, path, temporary):
    reference = entry["reference"]
    target = temporary / "reference.pnm"
    tool = reference["tool"]

    if tool == "djpeg":
        args = ["-dct", "int", "-pnm", "-outfile", str(target), str(path)]
    else:
        args = [str(path), "-pam", "-nodither", "-o", str(target)]

    subprocess.run(["/opt/homebrew/bin/" + tool, *args],
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
                   timeout=180, env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"})

    raw = target.read_bytes()

    pixels = (raw.split(b"ENDHDR\n", 1)[1] if raw.startswith(b"P7\n")
              else raw.split(b"\n", 3)[3])

    require(sha(pixels) == reference["pixel_sha256"],
            "Reference decode changed")

    width = entry["width"]
    channels = reference["channels"]

    require(len(pixels) == width * entry["height"] * channels,
            "Reference extent changed")

    for patch in reference["patches"]:
        x, y, side = patch["x"], patch["y"], patch["width"]

        samples = b"".join(
            pixels[((y + dy) * width + x) * channels:
                   ((y + dy) * width + x + side) * channels]
            for dy in range(patch["height"]))

        require(samples.hex() == patch["samples_hex"],
                "Reference patch changed")

    target.unlink()


def color_convert(profile, samples, png=False):
    args = ["--png-description"] if png else []

    result = subprocess.run([
        sys.executable, "-I", "-B",
        str(Path(__file__).with_name("color_reference.py")),
        str(profile), json.dumps(samples), *args,
    ], capture_output=True, check=True, timeout=60)

    return json.loads(result.stdout)


def verify_parametric_patches(profile, inputs, expected, tolerance, temporary):
    """Reject patches that hide wrong coefficients within output tolerance."""
    gray = profile[16:20] == b"GRAY"
    names = (b"kTRC",) if gray else (b"rTRC", b"gTRC", b"bTRC")
    tags = {}

    count = struct.unpack_from(">I", profile, 128)[0]

    for index in range(count):
        name, offset, _ = struct.unpack_from(
            ">4sII", profile, 132 + 12 * index)

        if name in names and profile[offset:offset + 4] == b"para":
            tags[name] = offset

    if not tags:
        return

    require(set(tags) == set(names), "Incomplete parametric channel coverage")

    errors = []

    for channel, name in enumerate(names):
        offset = tags[name]

        function = struct.unpack_from(">H", profile, offset + 8)[0]

        keys = ("g", "gab", "gabc", "gabcd", "gabcdef")[function]

        parameters = [Decimal(struct.unpack_from(
            ">i", profile, offset + 12 + i * 4)[0]) / 65536
                      for i in range(len(keys))]

        if function:
            boundary = (-parameters[2] / parameters[1]
                        if function in (1, 2) else parameters[4])

            require(0 < boundary < 1, "Parametric join is outside samples")

            encoded_boundary = boundary * 255

            below = int(encoded_boundary)

            if below == encoded_boundary:
                below -= 1

            values = {sample[channel] for sample in inputs}

            require(below in values and below + 1 in values,
                    "Missing adjacent samples around parametric join")

        mutants = []

        for index, key in enumerate(keys):
            # These intentionally wrong transforms are sensitivity probes,
            # not new profiles admitted by the supported ICC subset.
            mutant = bytearray(profile)

            replacement = 65536 if key in "ga" else 0

            struct.pack_into(">i", mutant, offset + 12 + index * 4,
                             replacement)

            mutants.append((key + "-default", mutant))

        if function in (3, 4):
            mutant = bytearray(profile)

            struct.pack_into(">i", mutant, offset + 28, 65536)
            mutants.append(("d-one", mutant))

        if function == 4:
            mutant = bytearray(profile)

            mutant[offset + 32:offset + 40] = (
                profile[offset + 36:offset + 40]
                + profile[offset + 32:offset + 36])

            mutants.append(("swap-e-f", mutant))

        if function in (1, 2, 4):
            mutant = bytearray(profile)

            struct.pack_into(">H", mutant, offset + 8,
                             3 if function == 4 else 0)

            mutants.append(("collapsed-function", mutant))

        for label, mutant in mutants:
            path = temporary / "parametric-mutant.icc"

            path.write_bytes(mutant)

            actual = color_convert(path, inputs)

            error = max(abs(a - b) for observed, target in zip(actual, expected)
                        for a, b in zip(observed, target))

            require(error > tolerance,
                    "Parametric %d %s %s is hidden within tolerance %d"
                    % (function, name.decode("ascii"), label, tolerance))

            errors.append(error)

    print("Parametric %d %s: %d mutations detected; minimum maximum error %d"
          % (function, "gray" if gray else "rgb", len(errors), min(errors)))


def verify_color(entry, data, root, temporary):
    constant = entry.get("policy_patches") or entry.get(
        "color_reference", {}).get("patches")

    if constant:
        chunks = png_chunks(data)

        width, height, depth, color, _, _, interlace = struct.unpack(
            ">IIBBBBB", chunks[0][1])

        require(depth == 8 and color in (0, 2) and interlace == 0,
                "Unexpected constant-patch encoding")

        channels = 1 if color == 0 else 3

        rows = [row for _, _, _, _, _, row in png_rows(chunks)]

        require(len(rows) == height, "Color raster size mismatch")

        for patch in constant:
            expected = bytes(patch["input"]) * patch["width"]

            start = patch["x"] * channels

            for y in range(patch["y"], patch["y"] + patch["height"]):
                require(rows[y][start:start + len(expected)] == expected,
                        "Color input patch differs from pixels")

        inputs = [patch["input"] for patch in constant]

        if "color_reference" in entry:
            profile_path = root / entry["profile"]

            profile = profile_path.read_bytes()

            embedded = [payload for kind, payload, _ in chunks
                        if kind == b"iCCP"]

            require(len(embedded) == 1, "Expected one color profile")

            start = embedded[0].index(b"\0") + 2

            require(zlib.decompress(embedded[0][start:]) == profile,
                    "Embedded color profile differs from oracle profile")

            actual = color_convert(profile_path, inputs)

            tolerance = entry["color_reference"]["rgb_channel_tolerance"]

            require(tolerance == 2, "Color qualification tolerance changed")

            verify_parametric_patches(
                profile, inputs, actual, tolerance, temporary)
        elif entry["oracle_status"] == "srgb-patch-identity":
            actual = inputs
        else:
            description = {}
            profile = None

            for kind, payload, _ in chunks:
                if kind == b"gAMA":
                    description["gamma"] = struct.unpack(">I", payload)[0]
                elif kind == b"cHRM":
                    description["chromaticities"] = list(struct.unpack(
                        ">8I", payload))
                elif kind == b"iCCP":
                    start = payload.index(b"\0") + 2

                    profile = zlib.decompress(payload[start:])

            path = temporary / "color-description"

            if profile:
                path.write_bytes(profile)
            else:
                path.write_text(json.dumps(description))

            actual = color_convert(path, inputs, png=profile is None)

        require(actual == [patch["expected_rgb"] for patch in constant],
                "Color patch oracle changed")

    if "color_patch_reference" in entry:
        oracle = entry["color_patch_reference"]
        decoded_patches = entry["reference"]["patches"]

        require(len(oracle["patches"]) == len(decoded_patches),
                "Color transport patch count mismatch")

        for patch, decoded in zip(oracle["patches"], decoded_patches):
            raw = bytes.fromhex(patch["input_rgb_hex"])

            inputs = [list(raw[index:index + 3])
                      for index in range(0, len(raw), 3)]

            source = bytes.fromhex(decoded["samples_hex"])

            channels = entry["reference"]["channels"]

            decoded_rgb = [list(source[i:i + 3])
                           for i in range(0, len(source), channels)]

            require(inputs == decoded_rgb, "Color input differs from decode")

            actual = color_convert(root / oracle["profile"], inputs)

            expected = bytes(v for rgb in actual for v in rgb).hex()

            require(expected == patch["expected_rgb_hex"],
                    "Color transport oracle changed")


def verify_corpus(root, references):
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"),
                          object_pairs_hook=unique_object)

    require(manifest["schema_version"] == 1, "Unknown manifest schema")

    for name, digest in manifest["provenance"]["source_sha256"].items():
        require(name in ("generate.py", "verify.py", "color_reference.py"),
                "Unexpected generator source")

        require(sha(Path(__file__).with_name(name).read_bytes()) == digest,
                "Generator source changed: " + name)

    entries = manifest["artifacts"]

    paths = {entry["path"] for entry in entries}

    require(len(paths) == len(entries), "Duplicate fixture paths")

    actual = {str(path.relative_to(root)) for path in root.rglob("*")
              if path.is_file()
              and path.name not in ("manifest.json", "README.md")}

    require(paths == actual, "Unlisted or missing corpus files")

    encoders = manifest["provenance"]["png_encoder"]

    require(sorted(encoders) == ["png-correctness", "small-authored",
                                 "timed"], "Unexpected PNG encoder groups")

    rules = {group: encoders[group]["filter"] for group in encoders}

    require(rules == {"timed": "adaptive", "png-correctness": "cycle",
                      "small-authored": "none"}, "PNG filter rules changed")

    require(encoders["timed"]["bits_per_pixel_band"] == TIMED_PNG_BAND,
            "Timed PNG density band changed")

    timed = [entry for entry in entries if entry["category"] == "timed"]

    require(len(timed) == 11, "Expected eleven timed specimens")

    for entry in timed:
        master = entry["master"]
        cell = master["glyph_cell_source_pixels"]

        require(cell * 1080 / entry["width"] >= 2,
                "Timed glyph strokes are smaller than two display pixels")

        require(cell * 7 * 1080 / entry["width"] >= 16,
                "Timed glyphs are smaller than sixteen display pixels")

        regions = master["regions"]

        labels = [region["label"] for region in regions]

        require(labels == master["labels"] and len(set(labels)) == len(labels),
                "Timed region labels are not unique")

        require({region["texture"] for region in regions} == TEXTURES,
                "A timed raster lacks a texture")

        for region in regions:
            left, top = region["x"], region["y"]
            right, bottom = left + region["width"], top + region["height"]

            require(0 <= left and right <= entry["width"] and 0 <= top
                    and bottom <= entry["height"],
                    "A timed region leaves the image")

            x, y, width, height = region["label_box"]

            require((width, height) == (33 * cell, 11 * cell)
                    and left <= x and x + width <= right
                    and top <= y and y + height <= bottom,
                    "A label box is clipped by its region")

        if "reference" in entry:
            verify_patch_sites(entry)

    require(sum(e["category"] == "png-correctness" for e in entries) == 30,
            "Expected thirty PNG color/depth/interlace specimens")

    require(sum(e["category"] == "orientation" for e in entries) == 24,
            "Expected eight orientations in three formats")

    journey = {path for path in paths if path.startswith("journey/")}

    require(journey == {"journey/ordinary.png", "journey/ordinary.jpg",
                        "journey/ordinary.webp", "journey/long.png"},
            "Unexpected Debug journey assets")

    WORK.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="verify-", dir=WORK) as temp:
        for entry in entries:
            relative = Path(entry["path"])

            require(not relative.is_absolute() and ".." not in relative.parts,
                    "Unsafe fixture path")

            path = root / relative

            require(not path.is_symlink(), "Fixture symlink is not permitted")

            data = path.read_bytes()

            require(len(data) == entry["bytes"]
                    and sha(data) == entry["sha256"],
                    "Artifact changed: " + str(relative))

            if entry["category"] == "timed" and entry["format"] == "png":
                pixels = entry["width"] * entry["height"]
                low, high = TIMED_PNG_BAND

                require(low * pixels <= len(data) * 8 <= high * pixels,
                        "Timed PNG density is outside %d to %d bits per "
                        "pixel: %s" % (low, high, relative))

                require(entry["bits_per_pixel"]
                        == round(len(data) * 8 / pixels, 4),
                        "Timed PNG density record changed")

            # Payload faults and header-only admission specimens are not
            # decodable images; every other PNG belongs to an encoder group.
            if entry.get("format") == "png" and entry["category"] not in (
                "payload-fault", "admission-header",
            ):
                group = (entry["category"] if entry["category"] in rules
                         else "small-authored")

                verify_png(entry, data, rules[group])

            if "invalid_filter" in entry:
                verify_invalid_filter(entry, data)

            if entry.get("format") == "icc":
                verify_icc(data)

            if "sample_reference" in entry:
                require(png_reference(data) == entry["sample_reference"],
                        "PNG sample oracle changed")

            verify_color(entry, data, root, Path(temp))

            if entry.get("format") == "webp" and entry["expected"] not in (
                "truncated", "invalid-data",
            ):
                verify_webp(entry, data)

            if entry.get("format") == "jpeg" and entry["expected"] not in (
                "truncated", "invalid-data",
            ):
                verify_jpeg(entry, data)

            if references and "reference" in entry:
                verify_reference(entry, path, Path(temp))

    print("Verified %d artifacts, %d bytes; references=%s" % (
        len(entries), sum(e["bytes"] for e in entries), references))

    print("Open qualification gaps: " + "; ".join(manifest["gaps"]))

    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)

    parser.add_argument("--root", type=Path, default=ROOT / "share/reader")
    parser.add_argument("--references", action="store_true")
    parser.add_argument("--regenerate", action="store_true")
    parser.add_argument("--png-reference", type=Path)

    args = parser.parse_args()

    if args.png_reference:
        print(json.dumps(png_reference(args.png_reference.read_bytes())))

        return

    root = args.root.resolve()

    manifest = verify_corpus(root, args.references)

    if args.regenerate:
        with tempfile.TemporaryDirectory(prefix="reproduce-", dir=WORK) as temp:
            regenerated = Path(temp) / "corpus"

            subprocess.run([
                sys.executable, "-I", "-B",
                str(Path(__file__).with_name("generate.py")),
                "--output", str(regenerated),
            ], check=True, timeout=900)

            other = json.loads((regenerated / "manifest.json").read_text())

            require(manifest == other, "Regenerated manifest differs")

            for entry in manifest["artifacts"]:
                require(sha((regenerated / entry["path"]).read_bytes())
                        == entry["sha256"], "Regenerated fixture differs")

        print("Byte-for-byte deterministic regeneration passed")


if __name__ == "__main__":
    main()
