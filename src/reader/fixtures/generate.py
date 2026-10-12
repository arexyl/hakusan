#!/usr/bin/env python3
"""Generate reviewed, first-party reader inputs with pinned host tools."""

import argparse
import binascii
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
DEFAULT_OUTPUT = WORK / "corpus"
TOOLS = {
    "cjpeg": "/opt/homebrew/bin/cjpeg",
    "djpeg": "/opt/homebrew/bin/djpeg",
    "cwebp": "/opt/homebrew/bin/cwebp",
    "dwebp": "/opt/homebrew/bin/dwebp",
    "webpmux": "/opt/homebrew/bin/webpmux",
    "transicc": "/opt/homebrew/bin/transicc",
}
JPEG = ["-quality", "90", "-dct", "int", "-sample", "2x2,1x1,1x1"]
WEBP = ["-q", "90", "-m", "4", "-metadata", "none", "-exact",
        "-noasm", "-quiet"]
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PASSES = [(0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8),
          (2, 0, 4, 4), (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2)]
CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}
LEGAL_DEPTHS = {0: [1, 2, 4, 8, 16], 2: [8, 16],
                3: [1, 2, 4, 8], 4: [8, 16], 6: [8, 16]}
COLORS = [(0, 0, 0), (255, 255, 255), (224, 32, 32),
          (32, 192, 64), (32, 64, 224), (224, 192, 32),
          (192, 32, 192), (32, 192, 192), (32, 32, 32),
          (96, 96, 96), (160, 160, 160), (224, 224, 224)]
COLOR_PATCHES = [(0, 0, 0), (255, 255, 255), (255, 0, 0),
                 (0, 255, 0), (0, 0, 255), (32, 96, 192),
                 (192, 96, 32), (128, 128, 128)]
GLYPHS = {
    "0": [14, 17, 19, 21, 25, 17, 14],
    "1": [4, 12, 4, 4, 4, 4, 14],
    "2": [14, 17, 1, 2, 4, 8, 31],
    "3": [30, 1, 1, 14, 1, 1, 30],
    "4": [2, 6, 10, 18, 31, 2, 2],
    "5": [31, 16, 16, 30, 1, 1, 30],
    "6": [14, 16, 16, 30, 17, 17, 14],
    "7": [31, 1, 2, 4, 8, 8, 8],
    "8": [14, 17, 17, 14, 17, 17, 14],
    "9": [14, 17, 17, 15, 1, 1, 14],
    "R": [30, 17, 17, 30, 20, 18, 17],
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(tool, args, input_bytes=None):
    result = subprocess.run(
        [TOOLS[tool], *map(str, args)], input=input_bytes,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
        timeout=180, env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"},
    )
    return result.stdout


def tool_versions():
    require(sys.implementation.name == "cpython", "Use CPython")
    require(sys.version_info[:3] == (3, 9, 6), "Use CPython 3.9.6")
    require(zlib.ZLIB_RUNTIME_VERSION == "1.2.12", "Use zlib 1.2.12")
    versions = {"python": "CPython 3.9.6", "zlib": "1.2.12"}
    for tool in TOOLS:
        if tool == "transicc":
            result = subprocess.run(
                [TOOLS[tool]], input=b"", capture_output=True, check=True,
                timeout=10, env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"},
            )
            first = (result.stdout + result.stderr).decode().splitlines()[0]
            require("[LittleCMS 2.19]" in first, "Use LittleCMS 2.19")
            versions[tool] = first
            continue
        result = subprocess.run(
            [TOOLS[tool], "-version"], capture_output=True, check=True,
            timeout=10, env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"},
        )
        value = (result.stdout + result.stderr).decode().strip()
        expected = ("libjpeg-turbo version 3.2.0 (build 20260630)"
                    if tool in ("cjpeg", "djpeg") else "1.6.0")
        require(value.splitlines()[0] == expected, tool + " changed")
        versions[tool] = value
    return versions


def chunk(kind, data):
    return (struct.pack(">I", len(data)) + kind + data
            + struct.pack(">I", binascii.crc32(kind + data) & 0xffffffff))


class Pattern:
    """A row-addressable master with no font or random-number dependency."""

    def __init__(self, width, height):
        self.width = width
        self.height = height
        self.cell = max(4, (width * 2 + 1079) // 1080,
                        (width * 16 + 7559) // 7560)
        self.period = self.cell * 96
        self.rows = {}

    def row(self, y):
        band = (y // (self.cell * 12)) % len(COLORS)
        if band not in self.rows:
            raw = bytearray()
            for x in range(self.width):
                color = COLORS[(x // (self.cell * 12) + band)
                               % len(COLORS)]
                raw.extend(color)
            self.rows[band] = bytes(raw)
        row = bytearray(self.rows[band])
        local = y % self.period
        # A white label panel, with at least one cell between glyphs.
        left = self.cell * 8
        right = min(self.width - self.cell * 8, left + self.cell * 38)
        complete_panel = y - local + self.cell * 13 <= self.height
        if complete_panel and self.cell * 2 <= local < self.cell * 13:
            row[left * 3:right * 3] = b"\xff" * ((right - left) * 3)
            glyph_y = local // self.cell - 4
            if 0 <= glyph_y < 7:
                label = "R%04d" % (y // self.period)
                for index, letter in enumerate(label):
                    mask = GLYPHS[letter][glyph_y]
                    for bit in range(5):
                        if mask & (1 << (4 - bit)):
                            start = left + (2 + index * 6 + bit) * self.cell
                            row[start * 3:(start + self.cell) * 3] = (
                                b"\0" * (self.cell * 3)
                            )
        # Distinct corner codes and a two-cell margin expose orientation.
        side = self.cell * 6
        if y < side:
            row[:side * 3] = bytes((255, 0, 0)) * side
            row[-side * 3:] = bytes((0, 255, 0)) * side
        elif y >= self.height - side:
            row[:side * 3] = bytes((0, 0, 255)) * side
            row[-side * 3:] = bytes((255, 255, 0)) * side
        return bytes(row)

    def description(self):
        return {
            "recipe": "labeled-color-bands-v1",
            "width": self.width, "height": self.height,
            "glyph_cell_source_pixels": self.cell,
            "label_period_source_rows": self.period,
            "labels": ["R%04d" % i for i in
                       range((self.height - 1) // self.period + 1)
                       if i * self.period + 13 * self.cell <= self.height],
            "corner_rgb": [[255, 0, 0], [0, 255, 0],
                           [0, 0, 255], [255, 255, 0]],
        }


def png(path, width, height, rows, color=2, depth=8, interlace=False,
        metadata=(), palette=None, transparency=None, hold_rows=None):
    """Write PNG chunks; callers supply sample rows, not filtered bytes."""
    digest = hashlib.sha256()
    raw_digest = hashlib.sha256()
    compressor = zlib.compressobj(6, zlib.DEFLATED, 15, 8,
                                 zlib.Z_DEFAULT_STRATEGY)
    hold = None
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as output:
        output.write(PNG_SIGNATURE)
        header = struct.pack(">IIBBBBB", width, height, depth, color,
                             0, 0, int(interlace))
        output.write(chunk(b"IHDR", header))
        for kind, payload in metadata:
            output.write(chunk(kind, payload))
        if palette is not None:
            output.write(chunk(b"PLTE", palette))
        if transparency is not None:
            output.write(chunk(b"tRNS", transparency))
        for index, row in enumerate(rows):
            raw_digest.update(row)
            filtered = b"\0" + row
            digest.update(filtered)
            compressed = compressor.compress(filtered)
            if compressed:
                output.write(chunk(b"IDAT", compressed))
            if hold_rows and index + 1 == hold_rows:
                output.write(chunk(b"IDAT", compressor.flush(
                    zlib.Z_SYNC_FLUSH)))
                hold = {"offset": output.tell(), "source_rows": hold_rows}
        output.write(chunk(b"IDAT", compressor.flush()))
        output.write(chunk(b"IEND", b""))
    if hold:
        with path.open("rb") as source:
            hold["prefix_sha256"] = sha(source.read(hold["offset"]))
        hold["withheld_rows"] = height - hold_rows
    return {"serialized_samples_sha256": raw_digest.hexdigest(),
            "filtered_scanlines_sha256": digest.hexdigest(),
            "hold": hold}


def sample(x, y, width, height, color, depth):
    maximum = (1 << depth) - 1
    if color == 3:
        return ((x // 7 + y // 9) % min(16, maximum + 1),)
    values = ((x * 5 + y * 3) % 256, (x * 11 + y * 7) % 256,
              (x * 17 + y * 13) % 256)
    if x < 5 and y < 5:
        values = (255, 0, 0)
    if x >= width - 5 and y >= height - 5:
        values = (0, 255, 0)
    gray = values[0]
    low, high = width // 4, width * 3 // 4
    alpha = (0 if x <= low else 255 if x >= high
             else (x - low) * 255 // (high - low))
    base = {0: (gray,), 2: values, 4: (gray, alpha),
            6: (*values, alpha)}[color]
    result = [(value * maximum + 127) // 255 for value in base]
    if depth == 16:
        result = [min(65535, value + (x * 31 + y * 17 + index * 11) % 257)
                  for index, value in enumerate(result)]
        if color in (4, 6) and (x <= low or x >= high):
            result[-1] = 0 if x <= low else 65535
    return tuple(result)


def pack_samples(values, depth):
    if depth == 16:
        return struct.pack(">" + "H" * len(values), *values)
    if depth == 8:
        return bytes(values)
    result = bytearray()
    accumulator = 0
    bits = 0
    for value in values:
        accumulator = (accumulator << depth) | value
        bits += depth
        if bits == 8:
            result.append(accumulator)
            accumulator = bits = 0
    if bits:
        result.append(accumulator << (8 - bits))
    return bytes(result)


def small_rows(width, height, color, depth, interlace):
    passes = PASSES if interlace else [(0, 0, 1, 1)]
    for left, top, dx, dy in passes:
        for y in range(top, height, dy):
            values = []
            for x in range(left, width, dx):
                values.extend(sample(x, y, width, height, color, depth))
            if values:
                yield pack_samples(values, depth)


def exif(orientation=1, duplicate=False):
    count = 2 if duplicate else 1
    header = b"II\x2a\0\x08\0\0\0" + struct.pack("<H", count)
    entry = struct.pack("<HHIHH", 0x112, 3, 1, orientation, 0)
    return header + entry * count + b"\0" * 4


def png_insert(data, additions):
    # Generated PNG IHDR always ends at byte 33.
    return data[:33] + b"".join(chunk(k, v) for k, v in additions) + data[33:]


def jpeg_insert(data, marker, payload):
    require(len(payload) <= 65533, "JPEG marker payload too large")
    return data[:2] + bytes((255, marker)) + struct.pack(
        ">H", len(payload) + 2) + payload + data[2:]


def fixed(value):
    return struct.pack(">i", round(value * 65536))


def icc_profile(version=4, gray=False, function=None, malformed=False):
    """Construct finite RGB/gray display profiles, not a general ICC writer."""
    def xyz(values):
        return b"XYZ \0\0\0\0" + b"".join(map(fixed, values))

    gamma = 2.2
    params = [gamma]
    if function in (1, 2):
        params += [1.125, -0.25]
    if function == 2:
        params += [0.03125]
    if function in (3, 4):
        # Continuous curves with visibly different lower and upper branches.
        # Binary fractions preserve the joins in serialized s15Fixed16 form.
        params = [2.0, 0.75, 0.125, 0.390625, 0.25]
    if function == 4:
        params[3] = 0.265625
        params += [0.03125, 0.0625]
    if function is None:
        trc = b"curv\0\0\0\0" + struct.pack(">IH", 1, round(gamma * 256))
    else:
        trc = (b"para\0\0\0\0" + struct.pack(">HH", function, 0)
               + b"".join(map(fixed, params)))
    desc = b"Controlled reader profile\0"
    description = (b"desc\0\0\0\0" + struct.pack(">I", len(desc))
                   + desc + b"\0" * 78)
    copyright_tag = b"text\0\0\0\0First-party fixture\0"
    if version == 4:
        def mluc(text):
            encoded = text.encode("utf-16-be")
            return (b"mluc\0\0\0\0" + struct.pack(">II", 1, 12)
                    + b"enUS" + struct.pack(">II", len(encoded), 28) + encoded)
        description = mluc("Controlled reader profile")
        copyright_tag = mluc("First-party fixture")
    tags = [(b"desc", description),
            (b"cprt", copyright_tag),
            (b"wtpt", xyz((0.9642, 1.0, 0.8249)))]
    if gray:
        tags.append((b"kTRC", trc))
    else:
        tags += [(b"rXYZ", xyz((0.4360747, 0.2225045, 0.0139322))),
                 (b"gXYZ", xyz((0.3850649, 0.7168786, 0.0971045))),
                 (b"bXYZ", xyz((0.1430804, 0.0606169, 0.7141733)))]
        tags += [(name, trc) for name in (b"rTRC", b"gTRC", b"bTRC")]
    offset = 132 + len(tags) * 12
    table = bytearray(struct.pack(">I", len(tags)))
    bodies = bytearray()
    for name, payload in tags:
        table += name + struct.pack(">II", offset, len(payload))
        padded = payload + b"\0" * (-len(payload) % 4)
        bodies += padded
        offset += len(padded)
    header = bytearray(128)
    header[:4] = struct.pack(">I", offset)
    header[8:12] = bytes((version, 0x30 if version == 4 else 0x10, 0, 0))
    header[12:24] = b"mntr" + (b"GRAY" if gray else b"RGB ") + b"XYZ "
    header[24:36] = struct.pack(">6H", 2026, 1, 1, 0, 0, 0)
    header[36:40] = b"acsp"
    header[64:68] = struct.pack(">I", 1)
    header[68:80] = b"".join(map(fixed, (0.9642, 1, 0.8249)))
    profile = bytes(header + table + bodies)
    if malformed:
        profile = profile[:136] + struct.pack(">I", 0xfffffff0) + profile[140:]
    return profile


def icc_tags(profile, additions):
    count = struct.unpack_from(">I", profile, 128)[0]
    tags = []
    for index in range(count):
        name, offset, size = struct.unpack_from(
            ">4sII", profile, 132 + 12 * index)
        tags.append((name, profile[offset:offset + size]))
    tags.extend(additions)
    offset = 132 + 12 * len(tags)
    table = bytearray(struct.pack(">I", len(tags)))
    bodies = bytearray()
    for name, payload in tags:
        table.extend(name + struct.pack(">II", offset, len(payload)))
        padded = payload + b"\0" * (-len(payload) % 4)
        bodies.extend(padded)
        offset += len(padded)
    return struct.pack(">I", offset) + profile[4:128] + table + bodies


def four_component_jpeg(transform):
    """Encode one constant four-component MCU for unsupported-model tests."""
    def segment(marker, body):
        return bytes((255, marker)) + struct.pack(">H", len(body) + 2) + body
    adobe = b"Adobe" + struct.pack(">HHHB", 100, 0, 0, transform)
    quantization = b"\0" + bytes([1] * 64)
    frame = struct.pack(">BHHB", 8, 1, 1, 4) + b"".join(
        bytes((component, 0x11, 0)) for component in range(1, 5))
    # One zero DC category and one AC end-of-block code, each one bit long.
    table = bytes((1,)) + b"\0" * 15 + b"\0"
    huffman = b"\0" + table + b"\x10" + table
    scan = (bytes((4,)) + b"".join(bytes((component, 0))
                                  for component in range(1, 5))
            + bytes((0, 63, 0)))
    return (b"\xff\xd8" + segment(0xee, adobe) + segment(0xdb, quantization)
            + segment(0xc0, frame) + segment(0xc4, huffman)
            + segment(0xda, scan) + b"\0\xff\xd9")


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

    def reference(self, path, kind, width, height):
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
        points = []
        for y in (height // 8, height // 2, height * 7 // 8):
            for x in (width // 8, width // 2, width * 7 // 8):
                offset = (y * width + x) * channels
                points.append({
                    "x": x, "y": y,
                    "samples": list(pixels[offset:offset + channels]),
                })
        patches = []
        side = min(16, width // 8, height // 8)
        for point in points:
            x, y = point["x"], point["y"]
            patch = b"".join(
                pixels[((y + dy) * width + x) * channels:
                       ((y + dy) * width + x + side) * channels]
                for dy in range(side))
            patches.append({"x": x, "y": y, "width": side,
                            "height": side, "samples_hex": patch.hex()})
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

    def timed(self):
        for name, width, height, hold in (
            ("ordinary", 1600, 2400, 1536),
            ("large", 4096, 6144, 3840),
            ("long", 1440, 40000, 1536),
        ):
            print("Generate " + name, flush=True)
            pattern = Pattern(width, height)
            folder = "journey" if name != "large" else "qualification/timed"
            relative = folder + "/" + name + ".png"
            path = self.output / relative
            info = png(path, width, height,
                       (pattern.row(y) for y in range(height)),
                       metadata=[(b"sRGB", b"\0")], hold_rows=hold)
            common = {"category": "timed", "width": width, "height": height,
                      "master": pattern.description(), "expected": "success"}
            common["master"]["rgb_sha256"] = info["serialized_samples_sha256"]
            self.add(relative, format="png", variant="rgb8-srgb",
                     **common, **info)
            if name == "long":
                continue
            ppm = self.work / "master.ppm"
            with ppm.open("wb") as output:
                output.write(("P6\n%d %d\n255\n" % (width, height)).encode())
                for y in range(height):
                    output.write(pattern.row(y))
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
                destination = self.output / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                if tool == "cjpeg":
                    run(tool, [*flags, "-outfile", destination, ppm])
                else:
                    run(tool, [*flags, ppm, "-o", destination])
                kind = "jpeg" if tool == "cjpeg" else "webp"
                reference = self.reference(destination, kind, width, height)
                if variant == "webp-lossless":
                    require(reference["rgb_sha256"]
                            == common["master"]["rgb_sha256"],
                            "Lossless WebP differs from the master")
                    reference["comparison"] = {"max_channel_error": 0,
                                               "scope": "all RGB pixels"}
                self.add(relative, **common, format=kind, variant=variant,
                         encoder={"tool": tool, "flags": flags},
                         reference=reference)
            ppm.unlink()

    def png_variants(self):
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
                        self.output / relative, 67, 53,
                        small_rows(67, 53, color, depth, interlace),
                        color, depth, interlace, [(b"sRGB", b"\0")],
                        palette, transparency,
                    )
                    oracle = subprocess.run([
                        sys.executable, "-I", "-B",
                        str(Path(__file__).with_name("verify.py")),
                        "--png-reference", str(self.output / relative),
                    ], capture_output=True, check=True, timeout=30)
                    self.add(relative, category="png-correctness", format="png",
                             width=67, height=53, color_type=color, depth=depth,
                             interlace=interlace, expected="success",
                             sample_reference=json.loads(oracle.stdout), **info)

    def small_codecs(self):
        pattern = Pattern(128, 96)
        ppm = self.work / "small.ppm"
        ppm.write_bytes(b"P6\n128 96\n255\n" + b"".join(
            pattern.row(y) for y in range(96)))
        for color, flags in (("gray", ["-grayscale"]),
                             ("rgb", ["-rgb"]),
                             ("ycbcr420", ["-sample", "2x2,1x1,1x1"]),
                             ("ycbcr422", ["-sample", "2x1,1x1,1x1"]),
                             ("ycbcr444", ["-sample", "1x1,1x1,1x1"])):
            for progressive in (False, True):
                coding = "progressive" if progressive else "baseline"
                relative = "qualification/jpeg/%s-%s.jpg" % (color, coding)
                destination = self.output / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                settings = ["-quality", "90", "-dct", "int", *flags,
                            "-restart", "4B",
                            "-progressive" if progressive else "-baseline"]
                run("cjpeg", [*settings, "-outfile", destination, ppm])
                self.add(relative, category="jpeg-correctness", format="jpeg",
                         width=128, height=96, expected="success",
                         encoder={"tool": "cjpeg", "flags": settings},
                         reference=self.reference(destination, "jpeg", 128, 96))
        small_png = self.work / "rgba.png"
        for alpha in (False, True):
            png(small_png, 67, 53, small_rows(67, 53, 6 if alpha else 2,
                                            8, False),
                color=6 if alpha else 2)
            for lossless in (False, True):
                relative = ("qualification/webp/%s-%s.webp" %
                            ("alpha" if alpha else "opaque",
                             "lossless" if lossless else "lossy"))
                destination = self.output / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                settings = WEBP + (["-lossless"] if lossless else [])
                run("cwebp", [*settings, small_png, "-o", destination])
                reference = self.reference(destination, "webp", 67, 53)
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
                self.add(relative, category="webp-correctness", format="webp",
                         width=67, height=53, expected="success",
                         alpha=alpha, lossless=lossless,
                         encoder={"tool": "cwebp", "flags": settings},
                         reference=reference)

    def metadata(self):
        base_path = self.work / "metadata.png"
        png(base_path, 67, 53, small_rows(67, 53, 2, 8, False))
        base = base_path.read_bytes()
        jpeg_path = self.output / "qualification/jpeg/ycbcr420-baseline.jpg"
        jpeg = jpeg_path.read_bytes()
        webp_path = self.output / "qualification/webp/opaque-lossless.webp"
        for orientation in range(1, 9):
            payload = exif(orientation)
            self.add("qualification/orientation/png-%d.png" % orientation,
                     png_insert(base, [(b"eXIf", payload)]),
                     category="orientation", format="png", width=67, height=53,
                     orientation=orientation, expected="success",
                     oriented_geometry=([53, 67] if orientation >= 5
                                        else [67, 53]))
            self.add("qualification/orientation/jpeg-%d.jpg" % orientation,
                     jpeg_insert(jpeg, 0xe1, b"Exif\0\0" + payload),
                     category="orientation", format="jpeg",
                     width=128, height=96, orientation=orientation,
                     expected="success",
                     oriented_geometry=([96, 128] if orientation >= 5
                                        else [128, 96]))
            metadata = self.work / "orientation.exif"
            metadata.write_bytes(payload)
            relative = "qualification/orientation/webp-%d.webp" % orientation
            destination = self.output / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            run("webpmux", ["-set", "exif", metadata, webp_path,
                            "-o", destination])
            self.add(relative, category="orientation", format="webp",
                     width=67, height=53, orientation=orientation,
                     expected="success", exif_order="after-image-payload",
                     oriented_geometry=([53, 67] if orientation >= 5
                                        else [67, 53]))
        for name, payload, expectation in (
            ("invalid", exif(9), "success-no-transform"),
            ("duplicate", exif(6, True), "success-no-transform"),
            ("unsafe-ifd", b"II\x2a\0\xff\xff\xff\x7f", "invalid-data"),
        ):
            self.add("qualification/fault/png-exif-" + name + ".png",
                     png_insert(base, [(b"eXIf", payload)]),
                     category="metadata-fault", format="png",
                     expected=expectation)
            self.add("qualification/fault/jpeg-exif-" + name + ".jpg",
                     jpeg_insert(jpeg, 0xe1, b"Exif\0\0" + payload),
                     category="metadata-fault", format="jpeg",
                     expected=expectation)
            metadata = self.work / "invalid.exif"
            metadata.write_bytes(payload)
            relative = "qualification/fault/webp-exif-" + name + ".webp"
            run("webpmux", ["-set", "exif", metadata, webp_path,
                            "-o", self.output / relative])
            self.add(relative, category="metadata-fault", format="webp",
                     expected=expectation)
        self.color_profiles(base, jpeg, webp_path)
        return base

    def profile_fixture(self, name, profile, gray=False, **metadata):
        relative = "qualification/color/" + name + ".icc"
        self.add(relative, profile, category="icc-profile", format="icc",
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
        path = self.work / "color.png"
        png(path, 160, 64, (row for _ in range(64)), color=0 if gray else 2)
        source = path.read_bytes()
        profile_path = self.output / relative
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
        self.add("qualification/color/" + name + ".png",
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

    def color_profiles(self, base, jpeg, webp_path):
        color_base = self.work / "color-base.png"
        row = b"".join(bytes(value) * 20 for value in COLOR_PATCHES)
        png(color_base, 160, 64, (row for _ in range(64)))
        base = color_base.read_bytes()
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
            self.profile_fixture(name, profile, gray, profile_version=version,
                                 function=function)
        profile = icc_profile()
        # Container-specific ICC transport is covered without a cross product.
        jpeg_relative = "qualification/color/jpeg-icc.jpg"
        (self.output / jpeg_relative).write_bytes(jpeg_insert(
            jpeg, 0xe2, b"ICC_PROFILE\0\x01\x01" + profile))
        self.add(jpeg_relative,
                 category="color", format="jpeg", expected="color-convert",
                 width=128, height=96,
                 **self.transport_reference(jpeg_relative, "jpeg", 128, 96))
        profile_path = self.work / "profile.icc"
        profile_path.write_bytes(profile)
        destination = self.output / "qualification/color/webp-icc.webp"
        run("webpmux", ["-set", "icc", profile_path, webp_path,
                        "-o", destination])
        self.add("qualification/color/webp-icc.webp", category="color",
                 format="webp", expected="color-convert", width=67, height=53,
                 **self.transport_reference("qualification/color/webp-icc.webp",
                                            "webp", 67, 53))
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
                          "policy_patches": self.policy_patches(COLOR_PATCHES)}
            if expectation == "color-convert":
                description = {}
                profile_path = self.work / "case-profile.icc"
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
                    profile_path = self.work / "description.json"
                    profile_path.write_text(json.dumps(description))
                    args = ["--png-description"]
                reference = subprocess.run([
                    sys.executable, "-I", "-B",
                    str(Path(__file__).with_name("color_reference.py")),
                    str(profile_path), json.dumps(COLOR_PATCHES), *args,
                ], capture_output=True, check=True, timeout=30)
                oracle = {"oracle_status": "decimal50-color-description",
                          "policy_patches": self.policy_patches(
                              json.loads(reference.stdout))}
            self.add("qualification/color/" + name + ".png",
                     png_insert(base, additions),
                     category="color", format="png",
                     width=160, height=64, expected=expectation, **oracle)
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
            self.add("qualification/color/unsupported-" + name + ".png",
                     png_insert(base, [(b"iCCP", b"controlled\0\0"
                                        + zlib.compress(body, 6))]),
                     category="color", format="png",
                     expected="warned-srgb-fallback",
                     width=160, height=64,
                     oracle_status="srgb-patch-identity",
                     policy_patches=self.policy_patches(COLOR_PATCHES))
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
            self.profile_fixture(name, body)
        self.metadata_limits(base, profile)

    @staticmethod
    def policy_patches(expected):
        return [{"x": index * 20 + 4, "y": 16, "width": 12, "height": 32,
                 "input": list(COLOR_PATCHES[index]),
                 "expected_rgb": list(value), "max_channel_error": 2}
                for index, value in enumerate(expected)]

    def transport_reference(self, relative, kind, width, height):
        path = self.output / relative
        reference = self.reference(path, kind, width, height)
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
                str(self.output / profile), json.dumps(samples),
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

    def metadata_limits(self, base, profile):
        for size in (1048575, 1048576, 1048577):
            expanded = (struct.pack(">I", size) + profile[4:]
                        + b"\0" * (size - len(profile)))
            self.add("qualification/fault/icc-expanded-%d.png" % size,
                     png_insert(base, [(b"iCCP", b"controlled\0\0"
                                        + zlib.compress(expanded, 6))]),
                     category="metadata-limit", format="png",
                     expanded_icc_bytes=size,
                     expected=("budget-exceeded" if size > 1048576
                               else "color-convert"),
                     oracle_status="admission-policy-only")
            expanded = exif(1) + b"\0" * (size - len(exif(1)))
            self.add("qualification/fault/exif-expanded-%d.png" % size,
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
            self.add("qualification/fault/icc-tags-%d.png" % count,
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
            self.add("qualification/fault/exif-entries-%d.png" % count,
                     png_insert(base, [(b"eXIf", payload)]),
                     category="metadata-limit", format="png",
                     exif_entries=count,
                     expected=("budget-exceeded" if count > 1024
                               else "success-no-transform"))

    def faults(self, base):
        original = self.output / "journey/ordinary.png"
        data = original.read_bytes()
        entry = next(e for e in self.entries
                     if e["path"] == "journey/ordinary.png")
        hold = entry["hold"]["offset"]
        cases = [("png-truncated-prefix.png", data[:hold], "truncated"),
                 ("png-trailing.png", base + b"trailing", "invalid-data"),
                 ("png-duplicate-ihdr.png", base[:33] + base[8:33] + base[33:],
                  "invalid-data")]
        damaged = bytearray(base)
        damaged[-13] ^= 1  # The last IDAT CRC, not optional metadata.
        cases.append(("png-payload-crc.png", bytes(damaged), "invalid-data"))
        palette_path = self.output / "qualification/png/type3-depth4-plain.png"
        palette = palette_path.read_bytes()
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
        payload = base[offset + 8:offset + 8 + size] + b"unused-deflate-tail"
        unused = (base[:offset] + chunk(b"IDAT", payload)
                  + base[offset + size + 12:])
        self.add("qualification/fault/png-unused-idat.png", unused,
                 category="integrity-policy", format="png", expected="success",
                 note="Count unused final IDAT bytes without rejecting them")
        offset, size = idats[0]
        invalid_stream = bytearray(base[offset + 8:offset + 8 + size])
        invalid_stream[0] ^= 0xff
        invalid_png = (base[:offset] + chunk(b"IDAT", invalid_stream)
                       + base[offset + size + 12:])
        cases.append(("png-invalid-zlib.png", invalid_png, "invalid-data"))
        for name, body, expectation in cases:
            self.add("qualification/fault/" + name, body,
                     category="payload-fault", expected=expectation,
                     source="journey/ordinary.png" if "prefix" in name
                     else "generated-small-png")
        for kind, relative in (("jpeg", "journey/ordinary.jpg"),
                               ("webp", "journey/ordinary.webp")):
            body = (self.output / relative).read_bytes()
            for name, content, expected in (
                ("truncated", body[:len(body) // 2], "truncated"),
                ("trailing", body + b"trailing", "invalid-data"),
            ):
                self.add("qualification/fault/%s-%s.%s" %
                         (kind, name, "jpg" if kind == "jpeg" else kind),
                         content, category="payload-fault", format=kind,
                         expected=expected, source=relative)
        jpeg_path = self.output / "qualification/jpeg/ycbcr420-baseline.jpg"
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
            self.add("qualification/fault/jpeg-" + name + ".jpg", body,
                     category="metadata-fault", format="jpeg",
                     expected="warned-srgb-fallback")
        ppm = self.work / "small.ppm"
        for name, flags in (("arithmetic", ["-arithmetic"]),
                            ("12bit", ["-precision", "12"]),
                            ("lossless", ["-lossless", "1"])):
            relative = "qualification/fault/jpeg-" + name + ".jpg"
            destination = self.output / relative
            run("cjpeg", [*flags, "-outfile", destination, ppm])
            self.add(relative, category="unsupported-variant", format="jpeg",
                     expected="unsupported-variant",
                     encoder={"tool": "cjpeg", "flags": flags})
        for name, transform in (("cmyk", 0), ("ycck", 2)):
            relative = "qualification/fault/jpeg-" + name + ".jpg"
            self.add(relative, four_component_jpeg(transform),
                     category="unsupported-variant", format="jpeg",
                     width=1, height=1, expected="unsupported-color-model",
                     encoder={"tool": "authored-single-mcu",
                              "adobe_transform": transform})
            # The reference must accept the coding before it is a model test.
            run("djpeg", ["-pnm", "-outfile", self.work / "excluded.ppm",
                          self.output / relative])
        for width in (65534, 65535, 65536):
            body = PNG_SIGNATURE + chunk(b"IHDR", struct.pack(
                ">IIBBBBB", width, 1, 8, 2, 0, 0, 0))
            self.add("qualification/fault/axis-%d.png" % width, body,
                     category="admission-header", format="png", width=width,
                     height=1, expected=("budget-exceeded" if width > 65535
                                        else "header-admitted-not-complete"))
        for width, height in ((7999, 8001), (8000, 8000), (5213, 12277)):
            body = PNG_SIGNATURE + chunk(b"IHDR", struct.pack(
                ">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            self.add("qualification/fault/pixels-%d.png" % (width * height),
                     body, category="admission-header", format="png",
                     width=width, height=height,
                     expected=("budget-exceeded" if width * height > 64000000
                               else "header-admitted-not-complete"))
        # An actual APNG, with its default image as the first animation frame.
        ihdr = base[8:33]
        first = chunk(b"fcTL", struct.pack(">IIIIIHHBB", 0, 67, 53,
                                           0, 0, 1, 10, 0, 0))
        animated = (PNG_SIGNATURE + ihdr
                    + chunk(b"acTL", struct.pack(">II", 1, 0))
                    + first + base[33:])
        self.add("qualification/fault/animated.png", animated,
                 category="unsupported-variant", format="png",
                 expected="unsupported-variant")
        webp = self.output / "qualification/webp/opaque-lossless.webp"
        destination = self.output / "qualification/fault/animated.webp"
        run("webpmux", ["-frame", webp, "+100", "-frame", webp, "+100",
                        "-loop", "0", "-o", destination])
        self.add("qualification/fault/animated.webp",
                 category="unsupported-variant",
                 format="webp", expected="unsupported-variant")

    def finish(self):
        sources = {}
        for name in ("generate.py", "verify.py", "color_reference.py"):
            path = Path(__file__).with_name(name)
            if path.exists():
                sources[name] = sha(path.read_bytes())
        manifest = {
            "schema_version": 1,
            "provenance": {
                "ownership": "First-party deterministic authored patterns",
                "license": "AGPL-3.0-or-later",
                "source_sha256": sources, "tools": self.versions,
                "png_encoder": {"filter": 0, "compression_level": 6,
                                "window_bits": 15, "memory_level": 8,
                                "strategy": "Z_DEFAULT_STRATEGY",
                                "hold_flush": "Z_SYNC_FLUSH"},
                "decoder_ancestry": {
                    "jpeg": "libjpeg-turbo encoder and reference decoder",
                    "webp": "libwebp encoder and reference decoder",
                    "png": "stdlib structural/sample reconstruction only",
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    output = args.output.resolve()
    require(WORK in output.parents,
            "Output must be beneath build/work/reader/fixtures")
    require(not output.exists() or (
        output.is_dir() and not any(output.iterdir())),
        "Output must be nonexistent or empty; preserve existing output")
    versions = tool_versions()
    output.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="generate-", dir=WORK) as temporary:
        corpus = Corpus(output, Path(temporary), versions)
        corpus.timed()
        corpus.png_variants()
        corpus.small_codecs()
        base = corpus.metadata()
        corpus.faults(base)
        corpus.finish()


if __name__ == "__main__":
    main()
