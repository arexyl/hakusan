#!/bin/sh
# Regenerates the dependency locks and the verification metadata from an empty
# component set, then proves them with strict runs from a second empty Gradle
# user home. A warm home hides the parent POMs and BOM files that a fresh
# resolution reads, so neither home may exist before the run.
#
# Usage: ANDROID_SERIAL=<serial> regenerate-verification.sh HOMES_DIRECTORY
#
# Run it inside the device shell with one virtual device booted: the Unified
# Test Platform resolves its configurations only for a connected run, so a
# host-only generation would drop their checksums. HOMES_DIRECTORY must lie
# outside the repository; its `generate` and `verify` homes are replaced.

set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
homes=${1:?usage: regenerate-verification.sh HOMES_DIRECTORY}
logs=$root/build/regenerate
metadata=$root/gradle/verification-metadata.xml

: "${ANDROID_SERIAL:?boot one virtual device and set ANDROID_SERIAL}"

case $homes in
  "$root" | "$root"/*)
    printf '%s lies inside the repository\n' "$homes" >&2
    exit 2
    ;;
  /*) ;;
  *)
    printf '%s is not an absolute path\n' "$homes" >&2
    exit 2
    ;;
esac

# Runs Gradle in one of the two homes and keeps its output in a log.
step() {
  name=$1
  home=$2
  shift 2

  printf '%s\n' "$name"

  if ! "$root/gradlew" --project-dir "$root" --gradle-user-home "$homes/$home" \
    --no-daemon --no-build-cache --no-parallel --console=plain "$@" \
    >"$logs/$name.log" 2>&1; then
    printf '%s failed: see %s\n' "$name" "$logs/$name.log" >&2
    exit 1
  fi
}

rm -rf -- "$homes/generate" "$homes/verify"
mkdir -p "$homes/generate" "$homes/verify" "$logs"

# Gradle merges into an existing file, so a stale component would survive.
rm -f -- "$metadata"

# The reports resolve the configurations that the matrix leaves alone. The two
# unqualified tasks run in every project.
step generate-host generate --write-verification-metadata sha256 --write-locks \
  :clean buildEnvironment dependencies :hostMatrix

/usr/bin/python3 -I "$root/gradle/aapt2_hosts.py" "$metadata"

# This run also writes the metadata back in Gradle's own layout.
step generate-device generate --no-configuration-cache \
  --write-verification-metadata sha256 --write-locks :deviceMatrix

step verify-host verify --dependency-verification=strict :clean :hostMatrix

step verify-device verify --no-configuration-cache \
  --dependency-verification=strict :deviceMatrix

printf 'regenerated and proven\n'
