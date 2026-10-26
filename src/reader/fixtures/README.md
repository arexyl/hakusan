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

The build never runs these tools. Instead, `./gradlew :reader:verifyFixtures` checks the size and SHA-256 of every artifact and the SHA-256 of every pinned tool script against `share/reader/manifest.json`, and fails on a missing, extra, or changed file. It needs no Python and runs in the host matrix. List every tool script in `TOOL_SCRIPTS` in `generate.py`; the manifest pins exactly that list. `verify.py` fails when a pinned script is missing or changed, or when this directory holds another `.py` file whose name does not start with a dot. Keep every tool in this directory: neither check looks into subdirectories.

Keep authoritative encoded specimens and their manifest in `share/reader/`. Keep temporary PPM/PAM rasters and codec output in `build/work/reader/fixtures/`. The generator deletes its temporary directory on completion or failure. It retains no uncompressed masters.

The manifest records tool versions, settings, authored source hashes, encoded hashes, master identity, sample hashes, reference patches, expected outcomes, and unresolved qualification gaps. The generator uses authored bitmap glyphs and integer patterns, not fonts or downloaded content. Its deterministic noise comes from SHAKE128 in counter mode, not from a random-number generator.

Each timed geometry uses one composite page. Bordered panels in tiers carry six textures: flat panels, antialiased line art, screentone, hatching, deterministic noise, and text-like strokes. Each panel has a unique label, such as `R0000`, and the page corners carry markers of four distinct colors. Text-like strokes use digits only, so they never spell a region label. Lengths follow the display scale of the 1080-pixel fit-width viewport, so label glyphs are at least 16 display pixels high and their strokes and gaps at least 2. The manifest lists each region with its label, texture, rectangle, and label box. The JPEG and WebP variants encode the same raster.

Each timed PNG encodes at 1 to 3 bits per pixel: its file bytes times 8, divided by its width times height. The band keeps inflate work and transfer size near those of real pages. It is an engineering estimate, not a measurement of real sources. The generator and the verifier both enforce it, and the manifest records each value as `bits_per_pixel`.

The manifest freezes the filter rule and zlib settings of each PNG group in `provenance.png_encoder`:

- The timed PNGs use per-row adaptive filtering by the minimum sum of absolute differences. Each scanline takes the filter type whose filtered bytes, read as signed two's-complement values, have the smallest sum of absolute values. The lowest type wins a tie, which makes the choice a pure function of the scanline and the previous scanline of its pass. They use zlib level 6, a 15-bit window, memory level 8, and `Z_FILTERED`.
- The PNG correctness specimens cycle through the five filter types. Scanline k of pass p, both counted from zero, takes type (k + p) mod 5. Every type therefore appears in each color, depth, and interlace combination, and each type also starts an Adam7 pass, where the previous scanline is zero. They use `Z_DEFAULT_STRATEGY`.
- The small authored rasters behind the other PNG specimens keep filter type 0 and `Z_DEFAULT_STRATEGY`. Verification requires type 0 in every scanline.

`qualification/fault/png-invalid-filter.png` keeps intact chunks and zlib data, but one scanline declares filter type 5, which the PNG specification does not define.

In PNG entries, `filtered_scanlines_sha256` is the SHA-256 of the inflated image data: each scanline's filter-type byte followed by its filtered bytes. `serialized_samples_sha256` is the SHA-256 of the unfiltered scanlines without filter-type bytes, in pass order. `scanline_filter_counts` counts the scanlines of filter types 0 to 4. `unused_idat_bytes` declares bytes that follow the zlib stream in the final IDAT; verification requires the stream to end exactly there.

PNG hold offsets follow a complete IDAT chunk after a row-aligned zlib synchronization flush. The prefix holds the filtered scanlines of the declared rows. Their filters refer only to earlier rows, so the prefix alone reconstructs them. Later IDAT payload encodes required remaining rows. Verification reconstructs the declared prefix and remaining sample counts. This is not proof that a decoder presents useful pixels early.

JPEG and WebP reference patches come from the encoded specimen, not the original raster. On the timed pages, one 16 × 16 patch samples the first region of each texture, below its label and nearest the texture's center, and one samples the inside of each corner marker. Each texture patch varies by at least 16 code values, twice the lossy p99 tolerance. The flat and corner patches are uniform colors whose channels differ by at least 16, so they also expose chroma errors. The manifest names each patch's region and content, and verification checks that each patch stays inside its declared area and clear of labels. The generator enforces the contrast rule; verification checks patch content only through the byte-for-byte regeneration. Encoder and reference decoder share codec ancestry. Report this limitation when evaluating another decoder.

The verifier reverses all five PNG filter types itself: None, Sub, Up, Average, and Paeth. The byte distance of Sub, Average, and Paeth is one complete pixel, at least one byte for depths below 8, and each Adam7 pass starts with a zero previous scanline. The verifier uses only the Python standard library and shares no code with libpng. Its inflate is zlib, like the encoder and the candidate native PNG decoder, so corpus verification does not test that decoder's inflate independently. The manifest records this ancestry. Verification decodes every PNG except the payload faults and the header-only admission specimens, and requires the filter-5 fault to carry exactly its one declared invalid scanline. It checks chunk integrity, row extents, valid filter types, each group's filter rule, the timed density band, sample hashes, and the declared pattern identity. It re-derives the adaptive filter choice on every 32nd timed scanline with scalar code. No correctness specimen reaches a Paeth tie between distinct predictors, and only the 16-bit Adam7 and 4-bit palette specimens select the upper-left predictor. The timed RGB8 PNGs cover those cases with a 3-byte filter unit, so other filter units get no Paeth tie coverage. Verification is not an independent production decoder test.

The color reference tool reads the serialized matrix/TRC profile and evaluates it with 50-digit decimal arithmetic. It uses the D50-adapted sRGB matrix, media-relative colorimetry, clipping, and final 8-bit quantization without black-point compensation. LittleCMS floating conversion independently cross-checks the selected patch outputs within two code values. The reference covers only its declared fixture profiles, not arbitrary ICC input.

Parametric functions 1–4 use nondegenerate coefficients and patches immediately below and above their serialized branch thresholds. Verification checks that each channel detects defaulted coefficients, ignored threshold branches, collapsed function forms, and swapped function-4 offsets beyond the two-code-value tolerance. These mutated transforms test patch sensitivity; they do not extend the admitted ICC subset. Verification also matches each embedded profile to the profile used by its oracle.

Small PNG sample oracles reverse the scanline filters and reconstruct Adam7 coordinates and packed samples before decimal depth reduction. They are separate from Android scaling. JPEG and WebP color-transport patches apply the color oracle to reference-decoded pixels. The finite gAMA/cHRM oracle accepts only the authored sRGB-primary cases. Admission and fault specimens declare expected policy outcomes rather than successful image output. Do not claim decoder color correctness from corpus verification. Read the manifest gaps; actual Android visual inspection remains explicit qualification work.
