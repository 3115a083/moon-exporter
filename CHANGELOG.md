# Changelog

All notable Moon Exporter revisions are recorded here so future development can start from the documented state instead of re-auditing the full codebase.

## 1.0.6 - 2026-09-13

### Fixed
- Corrected the primary Moon+ reading-progress extraction path after inspecting a trimmed real `.mrpro` backup. Moon+ `positions10.xml` is an Android SharedPreferences XML file and stores positions as `<string name="book key">Moon position</string>` entries. The importer now parses that representation instead of expecting filename/position attributes.
- Kept `.po` recovery as an additional fallback and prefers the newest timestamp when multiple Moon+ position sources exist.
- Position matching normalizes full path, basename and filename stem aliases and removes Moon+'s leading `?` path marker.
- Added a synthetic regression test for the observed `positions10.xml` SharedPreferences structure without including real user data.
- Connection testing now shows an inline testing/success/error result in the destination section.

### Changed
- Added a draggable alphabet scrollbar on the right side of the book list. Available initial letters are shown and can be tapped or dragged to jump through large libraries.
- Tapping anywhere on a book card toggles selection.
- Reduced checkbox footprint and horizontal spacing in book cards.
- The per-book ebook picker is shown only when Moon+ progress exists but the app still lacks a reliable ebook/document identity needed for target conversion.

### Conversion behavior
- Moon+ raw values remain preserved, including timestamp, chapter/page, section, character offset and percentage when present.
- KOSync/CWA/BookLore use the recovered percentage as KOReader-compatible `percentage` in the 0..1 range and retain the human-readable percentage progress string. A matching ebook supplies the KOReader-compatible document `partialMD5`; the ebook itself is never uploaded.
- Readest markings continue to use Moon+ `.mrexpt`. Reading progress is preserved for reporting/fallback and must not be represented by an invented EPUB CFI when structural resolution is unavailable.

## 1.0.5 - 2026-09-12

### Changed
- Restored the straight workflow to: 1. analyze backup, 2. review/select books, 3. choose destination, 4. transfer.
- Step 3 is visible before analysis completes when the book list is still empty, so server credentials can already be entered.
- Each book with recovered Moon+ progress but no reliable target book identity offers a direct per-book EPUB/PDF picker.
- Reading progress is displayed both as percentage text and a determinate progress bar.
- Numeric/hash-like source names are treated as opaque identifiers and replaced by EPUB metadata when available instead of being presented as a plausible title.

### Fixed
- Light status/navigation bars now explicitly use dark system icons, preventing white-on-white notification/status icons.
- Backup analysis now runs in a foreground service with an ongoing Android notification and progress indicator, so normal backgrounding/closing of the activity does not stop analysis.
- Added a defensive second pass that recovers `.po` reading positions using normalized path/base/stem aliases and adaptive zero-/one-based `.tag` mapping.
- Fixed cache-free nested EPUB metadata extraction so closing the nested ZIP parser does not close the outer `.mrpro` stream.
- Cover lookup now handles OPF manifest attributes in any order and resolves relative cover paths such as `../Images/cover.jpg` safely.
- Manual book assignment preserves the recovered Moon+ reading position while adding title/cover/document hash data needed for target conversion.

### Security and storage
- Foreground analysis uses only Android data-sync service permissions and the existing SAF URI permission.
- No ebook is permanently extracted to cache by the background analysis.
- Notification content contains progress text only and no credentials or book contents.

## 1.0.4 - 2026-09-12

### Changed
- Destination configuration is always visible before any backup is imported, so server credentials can be entered while analysis is still running.
- The book-review section is always present and shows a clear empty/loading state before results arrive.
- Every analyzed book is rendered as its own selectable card after import.
- Book cards now show cover or file-type placeholder, title, author, reading progress, position calculation method, highlight count, document identity and Moon+ source entry.
- Target and Readest export-type choices are radio selections instead of button-like selectors.
- The book filter remains a dropdown as requested.
- Selection controls explicitly support selecting or deselecting all currently visible books.

### Fixed
- The analyzed book list is no longer visually hidden behind destination/export controls.
- EPUB cover/title/author/ISBN metadata can be enriched directly from the original `.mrpro` in a second streaming pass without retaining extracted ebook copies in cache.
- Initial book results are published before cover enrichment, so the list can appear before the metadata pass completes.

### Product behavior
- The primary screen now stays usable during analysis: backup status, destination settings and credentials are independent from the book-analysis state.
- Readest export and server transfer actions remain disabled until analyzed books are selected.

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
