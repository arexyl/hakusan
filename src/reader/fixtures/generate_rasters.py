"""Author the small rasters behind the PNG, JPEG, and metadata specimens."""

import struct

from generate_png import PASSES, png


COLORS = [(0, 0, 0), (255, 255, 255), (224, 32, 32),
          (32, 192, 64), (32, 64, 224), (224, 192, 32),
          (192, 32, 192), (32, 192, 192), (32, 32, 32),
          (96, 96, 96), (160, 160, 160), (224, 224, 224)]

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


def small_master(work):
    """Write the 128 x 96 master of the small JPEG specimens, and return its
    path."""
    pattern = Pattern(128, 96)

    ppm = work / "small.ppm"

    ppm.write_bytes(b"P6\n128 96\n255\n" + b"".join(
        pattern.row(y) for y in range(96)))

    return ppm


def small_png(work):
    """Write the 67 x 53 RGB PNG that the PNG orientation and fault specimens
    extend, and return its bytes."""
    base_path = work / "metadata.png"

    png(base_path, 67, 53, small_rows(67, 53, 2, 8, False))

    base = base_path.read_bytes()

    return base
