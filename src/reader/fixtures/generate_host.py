"""Check the host profile and run its pinned tools."""

import hashlib
import subprocess
import sys
import zlib


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
