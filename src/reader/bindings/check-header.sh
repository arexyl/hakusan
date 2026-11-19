#!/bin/sh
# Checks the bindings header before a bindings commit: that clang-format would
# leave it unchanged, that every row of the cross-check table holds for it on
# both Android ABIs, and that its SAN_ macros are exactly the integer rows.
#
# Usage: check-header.sh [BINDINGS_DIRECTORY]
#
# Needs clang-format, zig, and HAKU_SDK_MIN, which the development shell
# provides. The exit status is the verdict.

set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../../.." && pwd)
bindings=${1:-$root/share/bindings}
work=$root/build/work/reader/bindings-check

# The targets are the Android ABIs at the minimum SDK level.
level=${HAKU_SDK_MIN:?run this inside the development shell}

# Zig keeps every cache under build/, and takes no libc description or package
# store from the environment.
unset ZIG_LIBC ZIG_LOCAL_PKG_DIR
export ZIG_GLOBAL_CACHE_DIR="$root/build/cache/zig/global"
export ZIG_LOCAL_CACHE_DIR="$root/build/cache/zig/local"

# -ffreestanding keeps the check independent of an NDK: the header includes
# only <stdint.h>, which Zig bundles. -Wpadded stays until a layout decision
# admits explicit padding.
compile() {
  target=$1
  shift

  zig cc -target "$target" -std=c11 -ffreestanding -pedantic-errors -Wall \
    -Wextra -Wpadded -Wstrict-prototypes -Werror -I "$bindings/include" "$@"
}

# One _Static_assert per table row of an ABI. The unit includes the header
# twice, to prove that it is self-contained and that its include guard works.
# shellcheck disable=SC2016 # The dollar signs belong to awk.
assertions='
  NR == 1 { print "#include \"san.h\""; print "#include \"san.h\""; next }
  $3 != "*" && $3 != abi { next }
  { d = index($2, "."); s = substr($2, 1, d - 1); f = substr($2, d + 1); m = $1 " " $2 }
  $1 == "i32" || $1 == "i64" { printf "_Static_assert(%s == %s, \"%s\");\n", $2, $4, m }
  $1 == "type" && d { printf "_Static_assert(_Generic(((%s *)0)->%s, %s: 1, default: 0), \"%s\");\n", s, f, $4, m }
  $1 == "type" && !d { printf "_Static_assert(_Generic((%s)0, %s: 1, default: 0), \"%s\");\n", $2, $4, m }
  $1 == "size" { printf "_Static_assert(sizeof(%s) == %s, \"%s\");\n", $2, $4, m }
  $1 == "align" { printf "_Static_assert(_Alignof(%s) == %s, \"%s\");\n", $2, $4, m }
  $1 == "offset" { printf "_Static_assert(__builtin_offsetof(%s, %s) == %s, \"%s\");\n", s, f, $4, m }
'

mkdir -p "$work"

clang-format --version
clang-format --dry-run --Werror "$bindings/include/san.h"

awk -F '\t' '$1 == "i32" || $1 == "i64" { print $2 }' "$bindings/abi.tsv" >"$work/rows.unsorted"
sort "$work/rows.unsorted" >"$work/rows.txt"

for pair in "arm64-v8a aarch64-linux-android" "x86_64 x86_64-linux-android"; do
  abi=${pair% *}
  target=${pair#* }.$level

  awk -F '\t' -v abi="$abi" "$assertions" "$bindings/abi.tsv" >"$work/$abi.c"

  compile "$target" -c -o "$work/$abi.o" "$work/$abi.c"
  compile "$target" -E -dM -o "$work/$abi.dm" "$work/$abi.c"

  awk '$1 == "#define" && $2 ~ /^SAN_/ && $2 != "SAN_H" { print $2 }' "$work/$abi.dm" >"$work/$abi.unsorted"
  sort "$work/$abi.unsorted" >"$work/$abi.macros"

  diff "$work/rows.txt" "$work/$abi.macros"

  printf '%s: %s rows hold on %s\n' "$abi" "$(grep -c '^_Static_assert' "$work/$abi.c")" "$target"
done

zig version

# A cache outside build/ means an invocation escaped the layout.
for litter in "$root/.zig-cache" "$root/zig-out"; do
  if [ -e "$litter" ]; then
    printf '%s must not exist\n' "$litter" >&2
    exit 1
  fi
done
