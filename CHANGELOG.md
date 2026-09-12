# Changelog

All notable Moon Exporter revisions are recorded here so future development can start from the documented state instead of re-auditing the full codebase.

## 1.0.3 - 2026-09-12

### Changed
- Reworked the main UI around one straight migration flow: Moon+ backup -> book selection -> destination -> transfer.
- Replaced filter chips with one filter dropdown.
- Replaced separate export and sync areas with one destination selector for Readest, KOSync, Calibre-Web Automated or BookLore.
- Readest now shows only file-export options. Server destinations show only connection and reading-progress controls.
- Removed the technical Device ID and regular diagnostics toggle from the primary flow.
- The complete screen uses one vertically scrolling list so export and transfer actions remain reachable on small displays.

### Fixed
- `.mrpro` import no longer keeps extracted ebook copies in `cacheDir`.
- Embedded ebook entries are hashed while streaming and retained only as references to the original backup entry.
- Full Readest export reopens the original `.mrpro` and streams the requested ebook directly to the chosen SAF destination.
- Temporary Moon+ database files are deleted after every import, including errors and cancellation, and stale `mrpro-*` cache directories are cleaned before a new import.
- Added fallback book recovery from `.po` and `.an` data when a database schema cannot be read.
- Database parsing now detects compatible book tables by columns instead of requiring one exact table shape.
- Corrected KOReader `partialMD5` sampling offsets to 512, 2048, 8192, 32768, ... through 2147483648 bytes.
- Added native BookLore KOReader-sync endpoint handling at `/api/koreader` using standard KOReader auth headers.
- CWA continues to use its `/kosync` endpoint while generic KOSync uses the configured base URL directly.

### Tests
- Updated the partialMD5 regression test to the KOReader-compatible sampling offsets.
- Added BookLore URL normalization and endpoint-root tests.
- Existing privacy, unit, lint, manifest and permission checks remain mandatory before APK delivery.

### Product behavior
- Readest transfer covers Moon+ markings through `.mrexpt`; book files can optionally be exported alongside them.
- KOSync, CWA and BookLore transfer reading progress only and never upload ebook files.
- A matching local or backup-contained ebook is used only to derive the KOReader-compatible document hash.

## 1.0.2 - 2026-09-12

### Fixed
- Added safe drawing insets so important UI no longer renders below status, navigation or cutout areas.
- Export and KOSync/CWA settings remain visible even when no books were imported.
- Export and progress-send actions stay disabled until a usable book selection exists.
- Made the backup-file flow the primary entry point while keeping folder import available as a secondary path.
- Hardened `.mrpro` numbered `.tag` processing against fragile `_names.list` assumptions.
- Added SQLite header detection (`SQLite format 3`) so the Moon+ database can be recovered even when tag-name mapping is missing or shifted.
- Added defensive support for both one-based and zero-based tag-name candidates.
- Added clearer diagnostics when a backup contains no readable Moon+ database, positions or books.
- Added an explicit archive entry-count limit during `.mrpro` processing.

### Tests
- Added synthetic tests for one-based/zero-based `.tag` mapping candidates.
- Added a synthetic SQLite-signature detection regression test.
- Existing unit, lint, manifest, permission and privacy checks remain required before APK delivery.

### Product priority
- Primary workflow: Moon+ backup file -> Readest export or KOSync progress transfer to CWA/BookLore-compatible endpoints.
- WebDAV import is intentionally deferred until the local backup, Readest and KOSync paths are stable.
- KOSync/CWA must never upload ebook files.

## 1.0.1 - 2026-09-12

### Changed
- Established the repository itself as the authoritative handoff source for future coding sessions.
- Added explicit project scope and development-state documentation under `docs/`.
- Extended the CI privacy scan to the project documentation and changelog.
- Versioned the debug APK artifact as `MoonExporter-1.0.1-debug`.

### Verified existing state
- Standalone package and namespace are `de.moonexporter.app`.
- `.mrpro` and Moon+ folder import paths exist.
- Readest marking export exists.
- KOSync and Calibre-Web Automated progress transfer exists and must never upload ebook files.
- CI performs unit tests, Android lint, APK build, manifest checks and repository privacy checks.

## 1.0.0 - 2026-09-11

- Reworked Moon Exporter as a standalone Android migration app.
- Added `.mrpro` processing, book metadata handling, Readest export and KOSync/CWA functionality.
- Restored strict Android CI and security checks.
