# Controlled reader images

These first-party fixtures contain authored patterns, labels, color patches, transparency ramps, and asymmetric corners. They contain no manga, personal data, third-party images, or installed-font output. The repository license applies.

`manifest.json` records specimen identity, provenance, expected outcomes, sample references, and remaining qualification gaps. Use the tools in `src/reader/fixtures/` to verify or explicitly regenerate the corpus. Do not modify generated image bytes independently of their recipe and manifest.

Package only `journey/` in the Debug application. Keep `qualification/`, reference patches, and fault specimens out of ordinary application packaging. Do not package any of this corpus in Release by default.

The timed corpus contains ordinary 1600 × 2400 and large 4096 × 6144 images in PNG, baseline/progressive JPEG, and lossy/lossless WebP, plus one 1440 × 40000 PNG. Small specimens cover selected coding, orientation, metadata, and failure cases without multiplying every large geometry.

The PNG prefix hold points withhold actual later-row payload. A successful fixture verification does not prove Android early display, readability, bounded decoding, performance, or production codec support.

Expected failures are application test policies, not claims that every general-purpose image viewer rejects the same inputs. Admission-header specimens are deliberately incomplete and do not claim successful full-image decoding.

## Visual inspection scope

Desktop inspection found the ordinary PNG/JPEG/WebP labels `R0000` through `R0006` and asymmetric corners legible in 1280 × 1920 previews. Long-master region previews covered source rows 0–2559, 18720–21279, and 37440–39999 at 1152 × 2048 preview size. Their visible label groups were `R0000`–`R0006`, `R0049`–`R0055`, and `R0098`–`R0104`. These previews are not the 1080-pixel Android qualification viewport.

The generator omits label panels that would cross an image boundary. Verification checks the declared labels, at least 16 display pixels of glyph height, and at least 2 display pixels of stroke width at the required fit-width scale. Actual target scaling, alpha compositing, adjacent-output seams, and presentation remain unverified.
