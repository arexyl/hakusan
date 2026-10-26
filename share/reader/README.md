# Controlled reader images

These first-party fixtures contain authored patterns, labels, color patches, transparency ramps, comic-page textures, deterministic noise, and asymmetric corners. They contain no manga, personal data, third-party images, or installed-font output. The repository license applies.

`manifest.json` records specimen identity, provenance, PNG encoder settings, expected outcomes, sample references, and remaining qualification gaps. Use the tools in `src/reader/fixtures/` to verify or explicitly regenerate the corpus. Do not modify generated image bytes independently of their recipe and manifest.

Package only `journey/` in the Debug application. Keep `qualification/`, reference patches, and fault specimens out of ordinary application packaging. Do not package any of this corpus in Release by default.

The timed corpus contains ordinary 1600 × 2400 and large 4096 × 6144 images in PNG, baseline/progressive JPEG, and lossy/lossless WebP, plus one 1440 × 40000 PNG. Each geometry is one composite page: bordered panels of flat color, antialiased line art, screentone, hatching, deterministic noise, and text-like strokes, each with a unique label. Its PNG encodes at 1 to 3 bits per pixel with per-row adaptive filtering, and the JPEG and WebP variants encode the same raster. Small specimens cover selected coding, orientation, metadata, and failure cases without multiplying every large geometry. The PNG correctness specimens use all five filter types in each color, depth, and interlace combination, and one fault specimen declares the undefined filter type 5.

The PNG prefix hold points withhold actual later-row payload. A successful fixture verification does not prove Android early display, readability, bounded decoding, performance, or production codec support.

Expected failures are application test policies, not claims that every general-purpose image viewer rejects the same inputs. Admission-header specimens are deliberately incomplete and do not claim successful full-image decoding.

## Visual inspection scope

Inspect these regions of the timed PNG rasters at 1:1 and at the 1080-pixel fit-width display scale. The manifest records each region's rectangle and label box.

- Ordinary and large pages: the whole page at 1080 × 1620, every label from `R0000` through `R0011`, the four corner markers, and one region of each texture: line art `R0000`, screentone `R0001`, noise `R0002`, hatching `R0003`, text `R0004`, and flat `R0005`.
- Long segment: 1080 × 1920 display viewports at source rows 0–2559, 18720–21279, and 37440–39999, whose complete panels carry the labels `R0000`–`R0011`, `R0104`–`R0115`, and `R0210`–`R0221`; those labels; the four corner markers; and the same six texture regions as the pages.

The generator keeps every label box inside its panel. Verification checks the declared labels, at least 16 display pixels of glyph height, and at least 2 display pixels of stroke width at the required fit-width scale. Actual target scaling, alpha compositing, adjacent-output seams, and presentation remain unverified.

Desktop inspection, 2026-10-26: every listed label reads without zoom at the display scale; the corner markers are red at the top left, green at the top right, blue at the bottom left, and yellow at the bottom right; the text strokes show digits only; and each texture matches its description.
