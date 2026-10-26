"""Build EXIF, ICC, and JPEG payloads."""

import struct

from generate_host import require


def exif(orientation=1, duplicate=False):
    count = 2 if duplicate else 1

    header = b"II\x2a\0\x08\0\0\0" + struct.pack("<H", count)

    entry = struct.pack("<HHIHH", 0x112, 3, 1, orientation, 0)

    return header + entry * count + b"\0" * 4


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
