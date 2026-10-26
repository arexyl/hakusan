#!/usr/bin/env python3
"""Generate reviewed, first-party reader inputs with pinned host tools."""

import argparse
import binascii
import hashlib
import json
import math
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

# Text-like strokes use digits only, so no stroke line can spell a region
# label, which starts with R.
TEXT_GLYPHS = "0123456789"

# The manifest freezes the filter rule and zlib settings of each PNG group.
PNG_TIMED = {
    "applies_to": "category timed",
    "filter": "adaptive",
    "filter_selection": (
        "Each scanline takes the filter type 0 to 4 whose filtered bytes, "
        "read as signed two's-complement values, have the smallest sum of "
        "absolute values. The lowest type wins a tie. The choice depends "
        "only on the scanline and the previous scanline of its pass."),
    "compression_level": 6, "window_bits": 15, "memory_level": 8,
    "strategy": "Z_FILTERED", "hold_flush": "Z_SYNC_FLUSH",
    "bits_per_pixel_band": [1, 3],
}

PNG_SPECIMEN = {
    "applies_to": "category png-correctness",
    "filter": "cycle",
    "filter_selection": (
        "Scanline k of pass p, both counted from zero, takes filter type "
        "(k + p) mod 5. A noninterlaced image is pass 0, and Adam7 passes "
        "keep their numbers 0 to 6, so each type also starts a pass."),
    "compression_level": 6, "window_bits": 15, "memory_level": 8,
    "strategy": "Z_DEFAULT_STRATEGY",
}

PNG_AUTHORED = {
    "applies_to": ("the small authored rasters behind every other PNG "
                   "artifact; a payload fault names its source"),
    "filter": "none",
    "filter_selection": "Every scanline takes filter type 0.",
    "compression_level": 6, "window_bits": 15, "memory_level": 8,
    "strategy": "Z_DEFAULT_STRATEGY",
}

STRATEGIES = {"Z_DEFAULT_STRATEGY": zlib.Z_DEFAULT_STRATEGY,
              "Z_FILTERED": zlib.Z_FILTERED}

# The absolute value of a filtered byte read as a signed value.
MAGNITUDE = bytes(min(value, 256 - value) for value in range(256))

TEXTURES = ("line-art", "screentone", "noise", "hatching", "text", "flat")

# Panel width weights per tier, repeated every four tiers. Twelve panels,
# twice the six textures, repeat one texture layout on each page and down
# the long segment.
TIERS = ((1, 1, 1), (2, 1, 1), (1, 2), (1, 1, 1, 1))

FLATS = ((242, 236, 224), (188, 188, 188), (250, 214, 170),
         (168, 200, 232), (112, 112, 112), (214, 236, 200))

# Lossy reference patches need content that a wrong decode changes beyond
# the comparison tolerance: twice its nearest-rank p99 of 8 code values.
SITE_CONTRAST = 16

CORNERS = ("corner-top-left", "corner-top-right",
           "corner-bottom-left", "corner-bottom-right")

# Grain offsets of the noise texture, indexed by a SHAKE128 output byte.
# Thirty-two levels place the timed PNG densities near the middle of their
# band.
GRAIN = [value % 32 - 16 for value in range(256)]


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


def gray_rgb(gray):
    rgb = bytearray(len(gray) * 3)

    rgb[0::3] = gray
    rgb[1::3] = gray
    rgb[2::3] = gray

    return bytes(rgb)


def shade(coverage):
    """Map ink coverage in sixteenths of a pixel to an 8-bit gray value."""
    return 255 - (max(0, min(16, coverage)) * 255 + 8) // 16


def nearest_first(center, low, high, step):
    """Return the positions center + k * step within low to high, nearest
    to center first and the lower one first on a tie."""
    first = center - (center - low) // step * step

    return sorted(range(first, high + 1, step),
                  key=lambda position: (abs(position - center), position))


def site_contrast(pixels, uniform):
    """Return the channel spread of a uniform RGB patch, or zero when it is
    not uniform, or the value range of a texture patch."""
    if not uniform:
        return max(pixels) - min(pixels)

    if pixels != pixels[:3] * (len(pixels) // 3):
        return 0

    return max(pixels[:3]) - min(pixels[:3])


def flat(page, width, height, index):
    row = bytes(FLATS[index // len(TEXTURES) % len(FLATS)]) * width

    return lambda y: row


def line_art(page, width, height, index):
    """Antialiased concentric circles of 1 to 3 display pixels around two
    centers, with coordinates in sixteenths of a pixel."""
    step = page.scaled(36)

    circles = []

    shift = index // len(TEXTURES) % 3

    for x, y, start in ((width * (2 + shift) // 5, height // 2, step),
                        (width // 6, height * 3 // 4, step * 3 // 2)):
        reach = max(x, width - x) + max(y, height - y)

        for number, radius in enumerate(range(start, reach, step)):
            circles.append((16 * x, 16 * y, 16 * radius,
                            8 * page.scaled(1 + number % 3)))

    def row(y):
        gray = bytearray(b"\xff" * width)

        center_y = 16 * y + 8

        for x16, y16, radius, half in circles:
            dy = center_y - y16
            outer = radius + half + 8

            if abs(dy) >= outer:
                continue

            inner = radius - half - 8

            reach = math.isqrt(outer * outer - dy * dy)

            gap = (math.isqrt(inner * inner - dy * dy)
                   if inner > abs(dy) else 0)

            spans = [(x16 - reach, x16 - gap), (x16 + gap, x16 + reach)]

            for low, high in spans:
                for x in range(max(0, (low - 8) // 16),
                               min(width, (high - 8) // 16 + 2)):
                    dx = 16 * x + 8 - x16

                    distance = math.isqrt(dx * dx + dy * dy)

                    value = shade(half + 8 - abs(distance - radius))

                    if value < gray[x]:
                        gray[x] = value

        return gray_rgb(gray)

    return row


def screentone(page, width, height, index):
    """An antialiased dot screen whose dots grow across the panel."""
    pitch = max(3, page.scaled(6))

    radii = [16 * pitch * (10 + 52 * (cell * pitch + pitch // 2) // width)
             // 100 for cell in range(width // pitch + 1)]

    rows = []

    for y in range(pitch):
        dy = 16 * y + 8 - 8 * pitch

        gray = bytearray(width)

        for x in range(width):
            dx = 16 * (x % pitch) + 8 - 8 * pitch

            gray[x] = shade(radii[x // pitch] + 8
                            - math.isqrt(dx * dx + dy * dy))

        rows.append(gray_rgb(gray))

    return lambda y: rows[y % pitch]


def hatching(page, width, height, index):
    """Antialiased diagonal hatching, crossed on the right half."""
    pitch = max(4, page.scaled(8))

    half = 8 * page.scaled(1)

    # The horizontal distance to a 45-degree line, divided by the square
    # root of two, is the distance across the line.
    profile = bytes(shade(half + 8 - min(t, pitch - t) * 16 * 70711 // 100000)
                    for t in range(pitch))

    line = profile * (width // pitch + 2)
    split = width // 2
    rows = []

    for y in range(pitch):
        falling = line[-y % pitch:-y % pitch + width]
        rising = line[y % pitch:y % pitch + width]

        rows.append(gray_rgb(falling[:split] + bytes(
            map(min, falling[split:], rising[split:]))))

    return lambda y: rows[y % pitch]


def noise(page, width, height, index):
    """Gray grain over a vertical ramp from a counter-mode SHAKE128 stream."""
    tables = {}

    def row(y):
        base = 72 + 120 * y // max(1, height - 1)

        if base not in tables:
            tables[base] = bytes(max(0, min(255, base + GRAIN[value]))
                                 for value in range(256))

        grain = hashlib.shake_128(b"noise:%d:%d" % (index, y)).digest(width)

        return gray_rgb(grain.translate(tables[base]))

    return row


def text(page, width, height, index):
    """Lines of small bitmap glyphs below the label, a stand-in for text."""
    size = max(2, page.scaled(2))

    pitch = 11 * size
    top = 13 * page.cell
    lines = (height - top - 2 * size) // pitch
    columns = (width - 4 * size) // (6 * size)
    cache = {}

    def row(y):
        line, offset = divmod(y - top, pitch)

        glyph_row = offset // size

        if y < top or line >= lines or glyph_row >= 7:
            return gray_rgb(b"\xff" * width)

        if (line, glyph_row) not in cache:
            cache.clear()

            gray = bytearray(b"\xff" * width)

            letters = hashlib.shake_128(
                b"text:%d:%d" % (index, line)).digest(columns)

            for column, value in enumerate(letters):
                if value % 5 == 0:
                    continue

                letter = TEXT_GLYPHS[value // 5 % len(TEXT_GLYPHS)]

                mask = GLYPHS[letter][glyph_row]

                for bit in range(5):
                    if mask & (16 >> bit):
                        start = (2 + (column * 6 + bit)) * size

                        gray[start:start + size] = b"\0" * size

            cache[(line, glyph_row)] = gray_rgb(gray)

        return cache[(line, glyph_row)]

    return row


RECIPES = {"line-art": line_art, "screentone": screentone, "noise": noise,
           "hatching": hatching, "text": text, "flat": flat}


class Panel:
    """A bordered region with one texture and a unique label."""

    def __init__(self, page, index, x, y, width, height):
        self.index = index
        self.texture = TEXTURES[index % len(TEXTURES)]
        self.label = "R%04d" % index
        self.x, self.y, self.width, self.height = x, y, width, height
        self.border = page.scaled(3)
        self.cell = page.cell

        inner_width = width - 2 * self.border
        inner_height = height - 2 * self.border

        # Two cells of white field surround the five glyphs of the label.
        self.box = (self.border + self.cell, self.border + self.cell,
                    33 * self.cell, 11 * self.cell)

        require(inner_width >= 35 * self.cell
                and inner_height >= 26 * self.cell, "Panel too small")

        self.texture_row = RECIPES[self.texture](
            page, inner_width, inner_height, index)

        self.label_rows = {}

    def label_row(self, y):
        glyph_row = y // self.cell - 2

        if glyph_row not in self.label_rows:
            row = bytearray(b"\xff" * (self.box[2] * 3))

            if 0 <= glyph_row < 7:
                for index, letter in enumerate(self.label):
                    mask = GLYPHS[letter][glyph_row]

                    for bit in range(5):
                        if mask & (16 >> bit):
                            start = (2 + index * 6 + bit) * self.cell

                            row[start * 3:(start + self.cell) * 3] = (
                                b"\0" * (self.cell * 3))

            self.label_rows[glyph_row] = bytes(row)

        return self.label_rows[glyph_row]

    def row(self, y):
        border = self.border

        if y < border or y >= self.height - border:
            return b"\0" * (self.width * 3)

        row = bytearray(b"\0" * (border * 3))

        row += self.texture_row(y - border)
        row += b"\0" * (border * 3)

        left, top, width, height = self.box

        if top <= y < top + height:
            row[left * 3:(left + width) * 3] = self.label_row(y - top)

        return row

    def patch_site(self, side):
        """Return the panel-relative reference patch site nearest the
        texture's center, in steps of side and below the label, whose
        content reaches SITE_CONTRAST."""
        uniform = self.texture == "flat"

        for y in nearest_first((self.height - side) // 2,
                               self.box[1] + self.box[3],
                               self.height - self.border - side, side):
            block = [self.row(y + dy) for dy in range(side)]

            for x in nearest_first((self.width - side) // 2, self.border,
                                   self.width - self.border - side, side):
                pixels = b"".join(row[x * 3:(x + side) * 3] for row in block)

                if site_contrast(pixels, uniform) >= SITE_CONTRAST:
                    return x, y

        raise ValueError("A texture lacks a reference patch site")

    def description(self):
        left, top, width, height = self.box

        return {"label": self.label, "texture": self.texture,
                "x": self.x, "y": self.y,
                "width": self.width, "height": self.height,
                "label_box": [self.x + left, self.y + top, width, height]}


class Composite:
    """A row-addressable comic page from authored integer recipes.

    Bordered panels in tiers carry six textures, and the page corners carry
    asymmetric markers. Lengths follow the display scale of the fit-width
    viewport, so each geometry shows the same kind of page.
    """

    def __init__(self, width, height):
        self.width = width
        self.height = height

        self.cell = max(4, (width * 2 + 1079) // 1080,
                        (width * 16 + 7559) // 7560)

        self.side = self.cell * 6
        self.margin = self.scaled(24)

        gutter = self.scaled(16)

        target = self.scaled(405)

        span = height - 2 * self.margin + gutter

        count = max(1, (span + target // 2) // target)

        self.pitch = span // count
        self.tiers = []
        self.panels = []

        for tier in range(count):
            weights = TIERS[tier % len(TIERS)]

            available = width - 2 * self.margin - gutter * (len(weights) - 1)

            left = self.margin
            panels = []

            for position, weight in enumerate(weights):
                size = (available * weight // sum(weights)
                        if position < len(weights) - 1
                        else width - self.margin - left)

                panels.append(Panel(self, len(self.panels), left,
                                    self.margin + tier * self.pitch,
                                    size, self.pitch - gutter))

                self.panels.append(panels[-1])
                left += size + gutter

            self.tiers.append(panels)

    def scaled(self, display):
        """Return the source pixels of a length in display pixels."""
        return max(1, (display * self.width + 540) // 1080)

    def row(self, y):
        row = bytearray(b"\xff" * (self.width * 3))

        tier = (y - self.margin) // self.pitch

        if y >= self.margin and tier < len(self.tiers):
            for panel in self.tiers[tier]:
                if y < panel.y + panel.height:
                    row[panel.x * 3:(panel.x + panel.width) * 3] = (
                        panel.row(y - panel.y))

        # Distinct corner colors in the page margin expose orientation.
        side = self.side

        if y < side:
            row[:side * 3] = bytes((255, 0, 0)) * side
            row[-side * 3:] = bytes((0, 255, 0)) * side
        elif y >= self.height - side:
            row[:side * 3] = bytes((0, 0, 255)) * side
            row[-side * 3:] = bytes((255, 255, 0)) * side

        return bytes(row)

    def description(self):
        return {
            "recipe": "composite-page-v1",
            "width": self.width, "height": self.height,
            "glyph_cell_source_pixels": self.cell,
            "corner_side_source_pixels": self.side,
            "corner_rgb": [[255, 0, 0], [0, 255, 0],
                           [0, 0, 255], [255, 255, 0]],
            "labels": [panel.label for panel in self.panels],
            "regions": [panel.description() for panel in self.panels],
        }

    def patch_sites(self, side):
        """Return the sites of the lossy reference patches.

        One patch samples the first region of each texture, clear of its
        border and label, and one the inside of each corner marker. A
        texture patch varies by at least SITE_CONTRAST code values. A flat
        or corner patch is one color whose channels differ by at least
        SITE_CONTRAST, so it also exposes chroma errors.
        """
        sites = []

        for texture in TEXTURES:
            panel = next(p for p in self.panels if p.texture == texture)

            x, y = panel.patch_site(side)

            sites.append({"region": panel.label, "content": texture,
                          "x": panel.x + x, "y": panel.y + y})

        inset = (self.side - side) // 2

        require(inset >= 0, "A reference patch exceeds a corner marker")

        far_x = self.width - self.side + inset
        far_y = self.height - self.side + inset

        for name, x, y in zip(CORNERS, (inset, far_x, inset, far_x),
                              (inset, inset, far_y, far_y)):
            pixels = b"".join(self.row(row)[x * 3:(x + side) * 3]
                              for row in range(y, y + side))

            require(site_contrast(pixels, True) >= SITE_CONTRAST,
                    "A corner marker lacks a uniform chroma")

            sites.append({"region": name, "content": "corner-marker",
                          "x": x, "y": y})

        for site in sites:
            site.update(width=side, height=side)

        return sites


def scanline_layout(width, height, color, depth, interlace):
    """Return the number, scanline count, and scanline length of each
    nonempty pass."""
    layout = []
    passes = PASSES if interlace else [(0, 0, 1, 1)]

    for number, (left, top, dx, dy) in enumerate(passes):
        columns = len(range(left, width, dx))

        count = len(range(top, height, dy))

        if columns and count:
            layout.append((number, count,
                           (columns * CHANNELS[color] * depth + 7) // 8))

    return layout


class ScanlineFilter:
    """Apply the PNG filter types 0 to 4 to the scanlines of one pass.

    Each byte occupies a 16-bit lane of one integer. Lane values stay below
    0x8000, each subtraction first raises its minuend lanes above the
    subtrahend, and masks drop bits that a shift moves across lanes, so
    whole-scanline integer arithmetic never mixes lanes.
    """

    def __init__(self, length, unit):
        self.length = length
        self.shift = 16 * unit
        self.one = int.from_bytes(b"\0\1" * length, "big")
        self.low = self.one * 0xff
        self.bias = self.one * 0x8000
        self.full = self.one * 0xffff

    def lanes(self, data):
        spread = bytearray(2 * self.length)

        spread[1::2] = data

        return int.from_bytes(spread, "big")

    def packed(self, lanes):
        return lanes.to_bytes(2 * self.length, "big")[1::2]

    def distance(self, p, q):
        """Return |p - q| in each lane."""
        forward = (p | self.bias) - q
        backward = (q | self.bias) - p
        keep = ((forward >> 15) & self.one) * 0xffff

        return ((forward & keep) | (backward & ~keep & self.full)) - self.bias

    def at_most(self, p, q):
        """Return 1 in each lane where p <= q and 0 elsewhere."""
        return (((q | self.bias) - p) >> 15) & self.one

    def residual(self, row, predictor):
        return self.packed(((row | self.one << 8) - predictor) & self.low)

    def candidates(self, row, prior):
        """Return the filtered bytes of types 0 to 4 for lane integers."""
        left = row >> self.shift
        corner = prior >> self.shift

        # Paeth distances of the PNG specification: pa = |b - c|,
        # pb = |a - c|, and pc = |a + b - 2c|.
        pa = self.distance(prior, corner)

        pb = self.distance(left, corner)

        pc = self.distance(left + prior, corner << 1)

        take_left = self.at_most(pa, pb) & self.at_most(pa, pc)

        take_up = (take_left ^ self.one) & self.at_most(pb, pc)

        take_corner = self.one ^ take_left ^ take_up

        paeth = (left & take_left * 0xffff | prior & take_up * 0xffff
                 | corner & take_corner * 0xffff)

        return [self.packed(row), self.residual(row, left),
                self.residual(row, prior),
                self.residual(row, (left + prior) >> 1 & self.low),
                self.residual(row, paeth)]


def tee(rows, path, width, height):
    """Pass rows through while writing them to a binary PPM."""
    with path.open("wb") as raster:
        raster.write(("P6\n%d %d\n255\n" % (width, height)).encode())

        for row in rows:
            raster.write(row)
            yield row


def compressor(encoder):
    return zlib.compressobj(encoder["compression_level"], zlib.DEFLATED,
                            encoder["window_bits"], encoder["memory_level"],
                            STRATEGIES[encoder["strategy"]])


def png(path, width, height, rows, color=2, depth=8, interlace=False,
        metadata=(), palette=None, transparency=None, hold_rows=None,
        encoder=PNG_AUTHORED):
    """Write PNG chunks; callers supply sample rows, not filtered bytes."""
    rule = encoder["filter"]

    require(rule in ("none", "cycle", "adaptive"), "Unknown PNG filter rule")

    digest = hashlib.sha256()

    raw_digest = hashlib.sha256()

    deflate = compressor(encoder)

    unit = max(1, CHANNELS[color] * depth // 8)

    counts = [0] * 5

    rows = iter(rows)

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

        for number, count, length in scanline_layout(
                width, height, color, depth, interlace):
            filters = ScanlineFilter(length, unit)

            prior = 0

            for index in range(count):
                row = next(rows, None)

                require(row is not None, "Missing PNG scanline")
                require(len(row) == length, "Scanline length mismatch")
                raw_digest.update(row)

                kind, filtered = 0, row

                if rule != "none":
                    lanes = filters.lanes(row)

                    options = filters.candidates(lanes, prior)

                    kind = (index + number) % 5

                    if rule == "adaptive":
                        scores = [sum(option.translate(MAGNITUDE))
                                  for option in options]

                        kind = scores.index(min(scores))

                    filtered = options[kind]
                    prior = lanes

                counts[kind] += 1

                scanline = bytes((kind,)) + filtered

                digest.update(scanline)

                compressed = deflate.compress(scanline)

                if compressed:
                    output.write(chunk(b"IDAT", compressed))

                if hold_rows and sum(counts) == hold_rows:
                    output.write(chunk(b"IDAT", deflate.flush(
                        zlib.Z_SYNC_FLUSH)))

                    hold = {"offset": output.tell(), "source_rows": hold_rows}

        require(next(rows, None) is None, "Unexpected extra scanline")
        output.write(chunk(b"IDAT", deflate.flush()))
        output.write(chunk(b"IEND", b""))

    if hold:
        with path.open("rb") as source:
            hold["prefix_sha256"] = sha(source.read(hold["offset"]))

        hold["withheld_rows"] = height - hold_rows

    return {"serialized_samples_sha256": raw_digest.hexdigest(),
            "filtered_scanlines_sha256": digest.hexdigest(),
            "scanline_filter_counts": counts,
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

    def timed(self):
        for name, width, height, hold in (
            ("ordinary", 1600, 2400, 1536),
            ("large", 4096, 6144, 3840),
            ("long", 1440, 40000, 1536),
        ):
            print("Generate " + name, flush=True)

            master = Composite(width, height)

            folder = "journey" if name != "large" else "qualification/timed"
            relative = folder + "/" + name + ".png"
            path = self.output / relative
            ppm = self.work / "master.ppm"

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

            self.add(relative, format="png", variant="rgb8-srgb",
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

                destination = self.output / relative

                destination.parent.mkdir(parents=True, exist_ok=True)

                if tool == "cjpeg":
                    run(tool, [*flags, "-outfile", destination, ppm])
                else:
                    run(tool, [*flags, ppm, "-o", destination])

                kind = "jpeg" if tool == "cjpeg" else "webp"

                reference = self.reference(destination, kind, width, height,
                                           sites)

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
                        palette, transparency, encoder=PNG_SPECIMEN,
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

        palette_source = "qualification/png/type3-depth4-plain.png"

        palette = (self.output / palette_source).read_bytes()

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

        self.add("qualification/fault/png-unused-idat.png", unused,
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

        self.add("qualification/fault/png-invalid-filter.png",
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

            self.add("qualification/fault/" + name, body,
                     category="payload-fault", expected=expectation,
                     source=source)

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
