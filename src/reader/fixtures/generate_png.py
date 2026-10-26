"""Encode PNG files under the frozen filter rules and zlib settings."""

import binascii
import hashlib
import struct
import zlib

from generate_host import require, sha


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

PASSES = [(0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8),
          (2, 0, 4, 4), (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2)]

CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}

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


def chunk(kind, data):
    return (struct.pack(">I", len(data)) + kind + data
            + struct.pack(">I", binascii.crc32(kind + data) & 0xffffffff))


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


def png_insert(data, additions):
    # Generated PNG IHDR always ends at byte 33.
    return data[:33] + b"".join(chunk(k, v) for k, v in additions) + data[33:]
