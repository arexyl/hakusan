#!/usr/bin/python3 -I
"""Add the AAPT2 archives of the other hosts to the verification metadata.

Gradle records only the AAPT2 JAR of the host it runs on, so the build would
fail verification on another host. For each other host this reads the SHA-256
that Google Maven publishes beside the JAR, downloads the JAR, and records the
checksum when the two agree. It accepts the published files only after the
published value for a JAR that Gradle recorded equals the recorded one.

A later Gradle run that writes the metadata restores Gradle's own layout.

Usage: aapt2_hosts.py VERIFICATION_METADATA
"""

from __future__ import annotations

import datetime
import hashlib
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

SCHEMA = "https://schema.gradle.org/dependency-verification"
INSTANCE = "http://www.w3.org/2001/XMLSchema-instance"
GROUP = "com.android.tools.build"
REPOSITORY = f"https://dl.google.com/android/maven2/{GROUP.replace('.', '/')}"
HOSTS = ("linux", "osx", "windows")
TIMEOUT_SECONDS = 120

ARTIFACT = f"{{{SCHEMA}}}artifact"
COMPONENT = f"{{{SCHEMA}}}component"
SHA256 = f"{{{SCHEMA}}}sha256"


class Mismatch(Exception):
    """A checksum differs from the one Google Maven publishes."""


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=TIMEOUT_SECONDS) as response:
        return response.read()


def location(version: str, name: str) -> str:
    return f"{REPOSITORY}/aapt2/{version}/{name}"


def published(version: str, name: str) -> str:
    """Return the SHA-256 that Google Maven publishes for an AAPT2 JAR."""
    text = fetch(f"{location(version, name)}.sha256").decode("ascii")

    return text.split()[0].lower()


def recorded(artifact: ET.Element) -> str:
    checksum = artifact.find(SHA256)

    return "" if checksum is None else checksum.get("value", "")


def add_hosts(component: ET.Element, today: str) -> list[str]:
    """Check the recorded JARs of a component and add the other hosts'."""
    version = component.get("version", "")
    names = {host: f"aapt2-{version}-{host}.jar" for host in HOSTS}

    artifacts = {
        artifact.get("name", ""): artifact
        for artifact in component.findall(ARTIFACT)
    }

    present = [host for host in HOSTS if names[host] in artifacts]

    if not present:
        raise Mismatch(f"aapt2 {version} records no host JAR to compare")

    for host in present:
        name = names[host]

        if recorded(artifacts[name]) != published(version, name):
            raise Mismatch(f"{name}: recorded and published SHA-256 differ")

    added = []

    for host in HOSTS:
        name = names[host]

        if host in present:
            continue

        expected = published(version, name)
        actual = hashlib.sha256(fetch(location(version, name))).hexdigest()

        if actual != expected:
            raise Mismatch(f"{name}: the JAR differs from its published SHA-256")

        origin = (
            "Google Maven published SHA-256, "
            f"matched the downloaded JAR on {today}"
        )
        artifact = ET.SubElement(component, ARTIFACT, name=name)

        ET.SubElement(artifact, SHA256, value=expected, origin=origin)
        added.append(name)

    component[:] = sorted(component, key=lambda item: item.get("name", ""))

    return added


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: aapt2_hosts.py VERIFICATION_METADATA", file=sys.stderr)

        return 2

    path = Path(sys.argv[1])

    ET.register_namespace("", SCHEMA)
    ET.register_namespace("xsi", INSTANCE)

    tree = ET.parse(path)
    today = datetime.date.today().isoformat()

    components = [
        component
        for component in tree.getroot().iter(COMPONENT)
        if component.get("group") == GROUP and component.get("name") == "aapt2"
    ]

    if not components:
        print(f"{path}: no aapt2 component", file=sys.stderr)

        return 1

    added = []

    try:
        for component in components:
            added += add_hosts(component, today)
    except (Mismatch, OSError) as error:
        print(f"aapt2_hosts: {error}", file=sys.stderr)

        return 1

    ET.indent(tree, space="   ")
    tree.write(path, encoding="UTF-8", xml_declaration=True)

    for name in added:
        print(f"added {name}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
