"""Compose the paneled page of the timed fixtures."""

import hashlib
import math

from generate_host import require
from generate_rasters import GLYPHS


# Text-like strokes use digits only, so no stroke line can spell a region
# label, which starts with R.
TEXT_GLYPHS = "0123456789"

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
