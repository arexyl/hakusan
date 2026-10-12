# Reader fixture tools

Generate first-party encoded images for reader contract and image qualification tests. These tools do not select a production decoder.

Use the recorded host profile: CPython 3.9.6, zlib 1.2.12, libjpeg-turbo 3.2.0 (build 20260630), libwebp 1.6.0, and LittleCMS 2.19. The executable paths are explicit. No packages are installed. Do not substitute Pillow or another encoder without reviewing the resulting corpus and provenance.

Run from the repository root:

```text
/usr/bin/python3 -I -B src/reader/fixtures/generate.py
/usr/bin/python3 -I -B src/reader/fixtures/verify.py --root build/work/reader/fixtures/corpus --references --regenerate
./gradlew :reader:promoteFixtures
```

Use a nonexistent or empty output directory. The generator writes only beneath `build/work/reader/fixtures/`, and its default output is `build/work/reader/fixtures/corpus`. It never writes into `share/reader/`. The generator rejects nonempty directories before writing, including partial output from a failed attempt. For updates, generate into the default output, verify it with `--root`, and review the complete change before replacing reviewed inputs. Then promote it with `./gradlew :reader:promoteFixtures`. The task refuses to run without a completed manifest, replaces the corpus in `share/reader/` while keeping its `README.md`, and runs `:reader:verifyFixtures` afterward. It is the only step that writes into `share/reader/`. A failed generation leaves unaccepted output without a completed manifest. Normal application builds must not regenerate fixtures.

To check the reviewed corpus itself, including the reference decodes and a byte-for-byte regeneration, run:

```text
/usr/bin/python3 -I -B src/reader/fixtures/verify.py --root share/reader --references --regenerate
```

The build never runs these tools. Instead, `./gradlew :reader:verifyFixtures` checks the size and SHA-256 of every artifact and the SHA-256 of every pinned tool script against `share/reader/manifest.json`, and fails on a missing, extra, or changed file. It needs no Python and runs in the host matrix.

Keep authoritative encoded specimens and their manifest in `share/reader/`. Keep temporary PPM/PAM rasters and codec output in `build/work/reader/fixtures/`. The generator deletes its temporary directory on completion or failure. It retains no uncompressed masters.

The manifest records tool versions, settings, authored source hashes, encoded hashes, master identity, sample hashes, reference patches, expected outcomes, and unresolved qualification gaps. The generator uses authored bitmap glyphs and integer patterns, not fonts or downloaded content.

PNG hold offsets follow a complete IDAT chunk after a row-aligned zlib synchronization flush. Later IDAT payload encodes required remaining rows. Verification reconstructs the declared prefix and remaining sample counts. This is not proof that a decoder presents useful pixels early.

JPEG and WebP reference patches come from the encoded specimen, not the original raster. Encoder and reference decoder share codec ancestry. Report this limitation when evaluating another decoder. Lossless PNG verification checks chunk integrity, row extents, sample hashes, and the declared pattern identity; it is not an independent production decoder test.

The color reference tool reads the serialized matrix/TRC profile and evaluates it with 50-digit decimal arithmetic. It uses the D50-adapted sRGB matrix, media-relative colorimetry, clipping, and final 8-bit quantization without black-point compensation. LittleCMS floating conversion independently cross-checks the selected patch outputs within two code values. The reference covers only its declared fixture profiles, not arbitrary ICC input.

Parametric functions 1–4 use nondegenerate coefficients and patches immediately below and above their serialized branch thresholds. Verification checks that each channel detects defaulted coefficients, ignored threshold branches, collapsed function forms, and swapped function-4 offsets beyond the two-code-value tolerance. These mutated transforms test patch sensitivity; they do not extend the admitted ICC subset. Verification also matches each embedded profile to the profile used by its oracle.

Small PNG sample oracles reconstruct Adam7 coordinates and packed samples before decimal depth reduction. They are separate from Android scaling. JPEG and WebP color-transport patches apply the color oracle to reference-decoded pixels. The finite gAMA/cHRM oracle accepts only the authored sRGB-primary cases. Admission and fault specimens declare expected policy outcomes rather than successful image output. Do not claim decoder color correctness from corpus verification. Read the manifest gaps; actual Android visual inspection remains explicit qualification work.
