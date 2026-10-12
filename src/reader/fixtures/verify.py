#!/usr/bin/env python3
"""Verify reviewed fixture identity, PNG samples, and pinned references."""

import argparse
import binascii
from decimal import Decimal, localcontext, ROUND_HALF_UP
import hashlib
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


def verify_png(entry, data):
    chunks = png_chunks(data)
    require(chunks[0][0] == b"IHDR", "Missing first IHDR")
    require(chunks[-1][:2] == (b"IEND", b""), "Missing IEND")
    width, height, depth, color, method, filtering, interlace = struct.unpack(
        ">IIBBBBB", chunks[0][1])
    require(method == filtering == 0, "Unexpected PNG method")
    if "width" in entry:
        require((width, height) == (entry["width"], entry["height"]),
                "PNG geometry mismatch")
    if "filtered_scanlines_sha256" in entry:
        digest = hashlib.sha256()
        samples = hashlib.sha256()
        channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
        passes = ([(0, 0, 1, 1)] if not interlace else
                  [(0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8),
                   (2, 0, 4, 4), (0, 2, 2, 4), (1, 0, 2, 2),
                   (0, 1, 1, 2)])
        lengths = []
        for left, top, dx, dy in passes:
            count = max(0, (width - left + dx - 1) // dx)
            length = (count * channels * depth + 7) // 8
            if count:
                lengths.extend([length] * len(range(top, height, dy)))
        pending = bytearray()
        index = 0
        for block in inflated(chunks):
            digest.update(block)
            pending.extend(block)
            consumed = 0
            while index < len(lengths):
                length = lengths[index]
                if len(pending) - consumed < length + 1:
                    break
                require(pending[consumed] == 0, "Unexpected row filter")
                samples.update(pending[consumed + 1:consumed + length + 1])
                consumed += length + 1
                index += 1
            del pending[:consumed]
        require(index == len(lengths) and not pending,
                "PNG row extent mismatch")
        require(digest.hexdigest() == entry["filtered_scanlines_sha256"],
                "PNG filtered samples changed")
        require(samples.hexdigest() == entry["serialized_samples_sha256"],
                "PNG serialized samples changed")
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


def png_reference(data):
    """Reconstruct small authored filter-zero specimens for sample oracles."""
    chunks = png_chunks(data)
    width, height, depth, color, _, _, interlace = struct.unpack(
        ">IIBBBBB", chunks[0][1])
    require(width <= 256 and height <= 256, "Oracle only accepts small images")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    passes = ([(0, 0, 1, 1)] if not interlace else
              [(0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8),
               (2, 0, 4, 4), (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2)])
    raw = b"".join(inflated(chunks))
    pixels = {}
    position = 0
    for left, top, dx, dy in passes:
        xs = list(range(left, width, dx))
        if not xs:
            continue
        length = (len(xs) * channels * depth + 7) // 8
        for y in range(top, height, dy):
            require(raw[position] == 0, "Oracle only accepts filter zero")
            row = raw[position + 1:position + 1 + length]
            position += length + 1
            for index, x in enumerate(xs):
                values = []
                for channel in range(channels):
                    bit = (index * channels + channel) * depth
                    if depth == 16:
                        value = struct.unpack_from(">H", row, bit // 8)[0]
                    elif depth == 8:
                        value = row[bit // 8]
                    else:
                        value = (row[bit // 8] >> (8 - depth - bit % 8)) & (
                            (1 << depth) - 1)
                    values.append(value)
                pixels[(x, y)] = values
    require(position == len(raw) and len(pixels) == width * height,
            "Oracle raster coverage mismatch")
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
        stride = 1 + width * channels
        raw = b"".join(inflated(chunks))
        require(len(raw) == height * stride, "Color raster size mismatch")
        for patch in constant:
            expected = bytes(patch["input"]) * patch["width"]
            for y in range(patch["y"], patch["y"] + patch["height"]):
                start = y * stride + 1 + patch["x"] * channels
                require(raw[start:start + len(expected)] == expected,
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
    timed = [entry for entry in entries if entry["category"] == "timed"]
    require(len(timed) == 11, "Expected eleven timed specimens")
    for entry in timed:
        cell = entry["master"]["glyph_cell_source_pixels"]
        require(cell * 1080 / entry["width"] >= 2,
                "Timed glyph strokes are smaller than two display pixels")
        require(cell * 7 * 1080 / entry["width"] >= 16,
                "Timed glyphs are smaller than sixteen display pixels")
        period = entry["master"]["label_period_source_rows"]
        require(all(int(label[1:]) * period + 13 * cell <= entry["height"]
                    for label in entry["master"]["labels"]),
                "A declared label panel is clipped by the image boundary")
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
            if entry.get("format") == "png" and entry["category"] not in (
                "payload-fault", "admission-header",
            ):
                verify_png(entry, data)
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
