# Changelog

All notable Moon Exporter revisions are recorded here so future development can start from the documented state instead of re-auditing the full codebase. Older entries are intentionally concise; `docs/PROJECT_STATE.md` and the private handoff contain the current binding implementation rules.

## 1.0.15 - 2026-09-13

### Preserve the user's explicit export selection
- Fixed a real-device case where an unselected book without a usable book source could still enter a Readest export.
- Root cause: the selection map lived only in Compose `remember` state. Android may recreate the Activity while the SAF folder picker is open, after which the book list could repopulate and rebuild defaults before the picker callback ran.
- The exact selected book keys are now snapshotted immediately before opening the Readest folder picker.
- That snapshot uses `rememberSaveable`, so Activity recreation cannot broaden the export set.
- On picker return, only the snapshotted keys are resolved against the current book list or restored analysis.
- Books with no book file, no reading progress and no annotations are no longer selected by default after a fresh/restored analysis.

### Missing-source hardening
- `ExportService` no longer aborts the entire transfer when a source-less item is present.
- Such items are recorded as `SKIPPED_NO_SOURCE` and the remaining books continue.
- If a source-less item contains reading progress or annotations, the UI reports that it was skipped because no book file is assigned; it is not silently reported as transferred.
- A source-less item without any transferable user data is skipped losslessly.
- Added regression tests for source-less books with and without user data.

### Verification
- Tested app-code head: `03e33cad1f41735af4379de07f954c6dabdc3ca3`.
- Android CI run `34784119249`: success, including privacy scan, unit tests, Android lint, debug APK build, manifest/permission audit and artifact upload.
- CodeQL run `34784119262`: success.
- Debug artifact: `MoonExporter-1.0.15-debug`, artifact ID `10326485715`.
- Artifact ZIP digest: `sha256:bc6ed4c1a3408851175a54e476f00400c58325dbabf365eeb979f387e9f474de`.
- APK SHA256: `7e2c1c42b832dab077a242f82b460816967b21dfbc1669dd8bdbd9c955fdcd57`.

## 1.0.14 - 2026-09-13

### Fresh transfer sources on manual retry
- Fixed the real-device error `Keine Buchdatei für ... gespeichert` after retrying an interrupted Readest export.
- Root cause: a manual retry on the same Readest target reused the old unfinished session payload unchanged. That payload could still contain an older `BookItem` with `epub = null` even when the current analysis had already resolved the correct book source.
- A deliberate manual retry to the same target now marks the stale session `SUPERSEDED` and creates a fresh session from the current selection and current source references.
- Previously committed books are still preserved through `completed_books`, revalidated against the target, and skipped when unchanged.
- App-start automatic resume now requires every persisted transfer item to still contain a usable book source. Legacy source-less sessions are not auto-started into the same failure loop.
- A pending session for another target remains blocked.
- Added regression tests for same-target session replacement, different-target blocking, and source requirements for automatic resume.

### Export status UI
- Step 4 no longer shows `Export beendet` after a failed or cancelled export simply because the UI coroutine stopped.
- The footer now distinguishes running, failed/interrupted, interrupted, and successfully completed states.

### Verification
- Tested app-code head: `c8dd5d8bc5d3317087bbd199677b44e4502e700c`.
- Android CI run `34781062706`: success, including privacy scan, unit tests, Android lint, debug APK build, manifest/permission audit and artifact upload.
- CodeQL run `34781062693`: success.
- Debug artifact: `MoonExporter-1.0.14-debug`, artifact ID `10325086650`.
- Artifact ZIP digest: `sha256:e4c4698b65e7363ba9618d41edf72b6af962bf249624eb19ff8490773bb7f868`.
- APK SHA256: `29a64d15e0e9e337274e62b211a655730cc52f36564e856c1aff7ac7d8f1f1df`.

## 1.0.13 - 2026-09-13

### Deterministic Readest validation
- Fixed the repeated real-device validation failure that still occurred after 1.0.12.
- The expected Readest partialMD5 is now calculated before the book write begins and stored in the book checkpoint immediately.
- The whole book transaction uses that exact expected hash. The service no longer needs to rediscover the just-exported target folder after a successful write.
- Post-export validation checks exactly that hash directory and still requires the ebook identity, valid `config.json`, valid `library.json` and matching library row.
- If the source hash cannot be calculated before export, the operation stops with a specific source-identity error instead of writing first and returning an ambiguous target-validation error.
- If strict target validation fails, the UI now reports the actual failed check and book title, such as missing library row, invalid config or ebook hash mismatch.
- Existing foreground-service, checkpoint, retry and orphan-repair behavior from 1.0.11 remains enabled.

### Verification
- Tested app-code head: `6918cfdc6483069b5742e0aa28cf6d8fe413af25`.
- Android CI run `34775655809`: success, including privacy scan, unit tests, lint, debug APK build, manifest/permission audit and artifact upload.
- CodeQL run `34775655830`: success.
- Debug artifact: `MoonExporter-1.0.13-debug`, artifact ID `10323835806`.
- Artifact ZIP digest: `sha256:a51d0ab9b32034224a92915f62724be6adeb1daf7ac1df24a2d538260e7c0f5b`.
- APK SHA256: `6a548c315d87a6b355c902bed1768c03f99ba7a5fcf1826f2d6db514fd855306`.

## 1.0.12 - 2026-09-13

### Readest target validation
- Fixed the real-device error `Readest-Ziel konnte nach dem Export nicht eindeutig validiert werden` when the correct Readest hash folder already existed before the current export attempt.
- Root cause: the service could discover a newly created hash folder or reuse a persisted hash, but if neither existed it fell back to title/ISBN metadata matching. That lookup can legitimately fail even though the exported book itself is valid.
- Added `ReadestIdentity` as an exact fallback. It computes the Readest partialMD5 from the original source ebook only when the normal fast identity paths cannot resolve the target.
- Embedded `.mrpro` books reopen only their required archive entry for this fallback and use a temporary app-cache file that is deleted immediately.
- The exact source hash is used only to identify the target folder. All strict 1.0.11 validation checks still run afterwards, including ebook identity, config validity and the matching `library.json` row.
- Applied the same exact identity fallback to normal post-export validation and interrupted-export recovery.
- Added regression tests for the existing-target fallback and hash-resolution precedence.

### Verification
- Tested app-code head: `774b86f116288c89d8a76d273a206ea9531846f5`.
- Android CI run `34773493180`: success, including privacy scan, unit tests, lint, debug APK build, manifest/permission audit and artifact upload.
- CodeQL run `34773493256`: success.
- Debug artifact: `MoonExporter-1.0.12-debug`, artifact ID `10323056571`.
- Artifact ZIP digest: `sha256:4b4c21620f52c96b732cc5c0e1c18bd43d23e6374671ae16c45d7d6127b3db96`.
- APK SHA256: `214b13021cf72a724c471ffe7b63a804410aa750aed65e4d84c9ebbb96ab0e9e`.

## 1.0.11 - 2026-09-13

### Resumable background Readest export
- Moved full direct Readest export from the Activity-owned coroutine into a dedicated non-exported foreground `dataSync` service.
- Added `START_REDELIVER_INTENT` plus visible-app startup recovery so an unfinished persisted transfer can resume after service/process interruption.
- App switching no longer owns or cancels the export process.
- Added per-book transfer sessions/checkpoints in app-private SQLite. A book is committed only after the target ebook, `config.json` and `library.json` row validate together.
- Reuses an unfinished session for the same target instead of creating a duplicate parallel job.
- Previously completed fingerprints are still revalidated against the Readest target before they are skipped.
- Changed Moon+ book/progress/annotation data changes the fingerprint and forces the affected work to be processed again.

### Target integrity and orphan recovery
- Added a Readest target audit for 32-hex book directories, expected ebook presence/size, Readest partialMD5, valid per-book config, valid library JSON and a matching library row.
- If a transfer creates a new Readest hash directory and then fails, that directory can be associated with the current book as an orphan candidate for retry/resume.
- Partial or wrong ebook files are rejected after size/hash validation and rebuilt from the saved transfer item instead of being trusted because a file exists.
- Corrupt `config.json` and `library.json` are restored from Moon Exporter recovery backups when available.
- If the very first JSON write was interrupted and no previous file existed, the invalid new config/library file can be reset to an empty valid object/array and rebuilt from checkpoints.
- Each failed book receives one immediate repair retry before the session remains `INTERRUPTED` for later resume.
- Native/unrelated Readest files and library rows are never removed based on uncertain matching.

### Persistent analysis cache
- Added an app-private cache for the last completed `.mrpro` analysis.
- Cache reuse requires the same persisted source URI, file size and last-modified value; a changed backup is analyzed again.
- Cached source/progress/annotation metadata survives an app process crash so the whole backup does not need to be reparsed when unchanged.

### Cleanup and uninstall behavior
- Recovery `.bak.json` files and Moon Exporter `.part` files are retained only while needed for repair and removed after a fully verified transfer session.
- `moon-export.mrexpt` remains an intentional lossless annotation fallback and is not treated as a temporary artifact.
- SQLite checkpoints and cached analysis live only in app-private storage and are removed by Android when the app is uninstalled.
- Android cannot guarantee a final callback when uninstall happens exactly during an external SAF write, so external intermediate state is designed to be self-validating and repairable on a later run rather than relying on uninstall cleanup.

## 1.0.10 - 2026-09-13

### Readest highlights and finished state
- Preserved existing Moon Exporter notes until an exact replacement range CFI has actually been resolved.
- Added tolerant bounded Jsoup XHTML parsing for real EPUB content, including non-breaking spaces, inline markup, smart quotes, dash variants and soft hyphens.
- Exact range CFIs remain preferred; a safe chapter fallback is retained rather than silently dropping an unresolved annotation.
- Kept the original annotation data in `moon-export.mrexpt` as a lossless fallback.
- Moon+ progress at the finish threshold now writes Readest `readingStatus: "finished"` plus `readingStatusUpdatedAt`, not only `[100,100]` progress.

## 1.0.9 - 2026-09-13

### Readest performance and exact positions
- Prepared each embedded ebook once per export so hash, EPUB metadata, highlight resolution and final copy reuse the same temporary local source instead of rescanning `.mrpro` repeatedly.
- Added a dedicated bottom export-progress section with determinate per-book phases.
- Added `ReadestCfiResolver` to generate real Readest/Foliate EPUB range CFIs from actual EPUB XHTML where the highlighted text can be resolved safely.
- Reused one open EPUB ZIP while resolving annotations for a book.

## 1.0.8 - 2026-09-13

### Direct Readest library export
- Added direct export into `Readest/Books`, including managed book directories, `library.json`, per-book `config.json`, covers and annotation fallback.
- Added Readest's own partialMD5 sampling, separate from KOReader/KOSync partialMD5.
- Did not fabricate derived `nav.json`; Readest rebuilds that cache.
- Added cryptic filename detection and optional reconstructed output filenames based on reliable ebook/Moon metadata.
- Existing Readest progress is preserved unless source data is explicitly newer or the target has no progress.

## 1.0.7 - 2026-09-13

### Network and navigation
- Allowed cleartext HTTP only for local/private LAN targets while retaining HTTPS support and rejecting public HTTP.
- Added top/bottom arrows around the draggable A-Z rail.
- Added the `Ohne Buchdatei` / `Without book file` filter.

## 1.0.6 - 2026-09-13

### Moon+ reading progress
- Identified `shared_prefs/positions10.xml` as the primary progress source in full `.mrpro` backups.
- Added timestamp-free EPUB/PDF Moon position parsing and safe SharedPreferences XML handling.
- Added normalized path/base/stem matching without ambiguous guessing.
- Retained original raw Moon position values.

## 1.0.5 - 2026-09-12

### Background analysis and recovery
- Moved backup analysis into an Android foreground `dataSync` service with notification progress.
- Added defensive progress recovery, manual ebook assignment and better cryptic-title replacement.
- Fixed nested EPUB metadata extraction and cover lookup from `.mrpro`.
- Kept temporary analysis data out of durable storage.

## 1.0.4 - 2026-09-12

### Book review flow
- Made destination settings available while analysis runs.
- Added selectable per-book cards with cover/title/author/progress/method/marking/file information.
- Added streaming metadata enrichment from the original `.mrpro` without retaining durable ebook copies.

## 1.0.3 - 2026-09-12

### Primary migration workflow
- Reworked the screen around `Backup -> Bücher -> Ziel -> Übertragung`.
- Added Readest/KOSync/CWA/BookLore destination selection.
- Removed persistent extracted ebook cache and streamed embedded ebooks from the original backup when needed.
- Corrected KOReader partialMD5 sampling and added BookLore/CWA endpoint handling.

## 1.0.2 - 2026-09-12

### Import and UI hardening
- Added safe system insets and kept destination controls reachable before import.
- Hardened `.mrpro` tag mapping and SQLite detection for shifted/partial backups.
- Added archive-entry limits and synthetic mapping/signature tests.

## 1.0.1 - 2026-09-12

### Project handoff discipline
- Established repository documentation and changelog as authoritative development handoff sources.
- Extended CI privacy checks to documentation.
- Versioned debug APK artifacts.

## 1.0.0 - 2026-09-11

- Reworked Moon Exporter as a standalone Android migration app.
- Added `.mrpro` processing, book metadata handling, Readest export and KOSync/CWA functionality.
- Restored strict Android CI and security checks.
