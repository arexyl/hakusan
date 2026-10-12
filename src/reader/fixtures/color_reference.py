#!/usr/bin/env python3
"""Evaluate finite fixture ICC matrix/TRC profiles with 50-digit arithmetic."""

import argparse
from decimal import Decimal, localcontext, ROUND_HALF_UP
import json
from pathlib import Path
import struct


D = Decimal
SRGB_D50 = [
    [D("0.4360747"), D("0.3850649"), D("0.1430804")],
    [D("0.2225045"), D("0.7168786"), D("0.0606169")],
    [D("0.0139322"), D("0.0971045"), D("0.7141733")],
]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(data, offset):
    return D(struct.unpack_from(">i", data, offset)[0]) / 65536


def inverse(matrix):
    work = [row[:] + [D(i == j) for j in range(3)]
            for i, row in enumerate(matrix)]
    for column in range(3):
        pivot = max(range(column, 3), key=lambda i: abs(work[i][column]))
        work[column], work[pivot] = work[pivot], work[column]
        divisor = work[column][column]
        require(divisor != 0, "Singular matrix")
        work[column] = [value / divisor for value in work[column]]
        for row in range(3):
            if row != column:
                factor = work[row][column]
                work[row] = [a - factor * b
                             for a, b in zip(work[row], work[column])]
    return [row[3:] for row in work]


def curve(payload, x):
    kind = payload[:4]
    if kind == b"curv":
        count = struct.unpack_from(">I", payload, 8)[0]
        if count == 0:
            return x
        if count == 1:
            gamma = D(struct.unpack_from(">H", payload, 12)[0]) / 256
            return x ** gamma
        values = [D(v) / 65535 for v in struct.unpack_from(
            ">" + "H" * count, payload, 12)]
        location = x * (count - 1)
        index = min(int(location), count - 2)
        return values[index] + (values[index + 1] - values[index]) * (
            location - index)
    require(kind == b"para", "Unsupported oracle curve")
    function = struct.unpack_from(">H", payload, 8)[0]
    require(function in range(5), "Unsupported oracle parametric function")
    count = (1, 3, 4, 5, 7)[function]
    p = [fixed(payload, 12 + i * 4) for i in range(count)]
    gamma = p[0]
    if function == 0:
        return x ** gamma
    a, b = p[1:3]
    if function in (1, 2):
        c = p[3] if function == 2 else D(0)
        return (a * x + b) ** gamma + c if x >= -b / a else c
    c, boundary = p[3:5]
    if function == 3:
        return (a * x + b) ** gamma if x >= boundary else c * x
    e, f = p[5:7]
    return (a * x + b) ** gamma + e if x >= boundary else c * x + f


def convert(profile, samples):
    require(profile[36:40] == b"acsp" and profile[20:24] == b"XYZ ",
            "Oracle requires ICC XYZ PCS")
    require(profile[12:16] in (b"mntr", b"scnr"),
            "Oracle requires an input or display profile")
    tags = {}
    count = struct.unpack_from(">I", profile, 128)[0]
    for index in range(count):
        name, offset, length = struct.unpack_from(">4sII", profile,
                                                  132 + 12 * index)
        require(offset + length <= len(profile), "Unsafe profile")
        tags[name] = profile[offset:offset + length]
    require(not any(name in tags for name in (b"A2B0", b"A2B1", b"D2B0")),
            "Oracle does not evaluate LUT transforms")
    with localcontext() as context:
        context.prec = 50
        output_inverse = inverse(SRGB_D50)
        result = []
        cache = {}
        for sample in samples:
            key = tuple(sample)
            if key in cache:
                result.append(cache[key])
                continue
            encoded = [D(value) / 255 for value in sample]
            if profile[16:20] == b"GRAY":
                linear = curve(tags[b"kTRC"], encoded[0])
                xyz = [linear * fixed(tags[b"wtpt"], 8 + i * 4)
                       for i in range(3)]
            else:
                require(profile[16:20] == b"RGB ", "Unsupported oracle model")
                linear = [curve(tags[tag], value) for tag, value in zip(
                    (b"rTRC", b"gTRC", b"bTRC"), encoded)]
                matrix = [[fixed(tags[tag], 8 + row * 4)
                           for tag in (b"rXYZ", b"gXYZ", b"bXYZ")]
                          for row in range(3)]
                xyz = [sum(a * b for a, b in zip(row, linear))
                       for row in matrix]
            rgb = [sum(a * b for a, b in zip(row, xyz))
                   for row in output_inverse]
            output = []
            for value in rgb:
                clipped = max(D(0), min(D(1), value))
                encoded_value = (clipped * D("12.92")
                                 if clipped <= D("0.0031308") else
                                 D("1.055") * clipped ** (D(1) / D("2.4"))
                                 - D("0.055"))
                output.append(int((encoded_value * 255).quantize(
                    D(1), rounding=ROUND_HALF_UP)))
            result.append(output)
            cache[key] = output
        return result


def png_description(description, samples):
    """Evaluate only the authored sRGB-primary gAMA/cHRM specimens."""
    require(description.get("chromaticities") in (
        None, [31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000]),
        "Oracle only supports the declared sRGB chromaticities")
    if "gamma" not in description:
        return samples
    with localcontext() as context:
        context.prec = 50
        gamma = D(100000) / description["gamma"]
        result = []
        for sample in samples:
            output = []
            for value in sample:
                linear = (D(value) / 255) ** gamma
                encoded = (linear * D("12.92") if linear <= D("0.0031308")
                           else D("1.055") * linear ** (D(1) / D("2.4"))
                           - D("0.055"))
                output.append(int((encoded * 255).quantize(
                    D(1), rounding=ROUND_HALF_UP)))
            result.append(output)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    parser.add_argument("samples", help="JSON array of 8-bit RGB/gray samples")
    parser.add_argument("--png-description", action="store_true")
    args = parser.parse_args()
    samples = json.loads(args.samples)
    if args.png_description:
        result = png_description(json.loads(args.profile.read_text()), samples)
    else:
        result = convert(args.profile.read_bytes(), samples)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
