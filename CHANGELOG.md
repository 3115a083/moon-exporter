# Changelog

All notable Moon Exporter revisions are recorded here so future development can start from the documented state instead of re-auditing the full codebase.

## 1.0.10 - 2026-09-13

### Readest highlight recovery
- Fixed the 1.0.9 regression where real-world EPUB XHTML could cause all direct Readest highlights to be dropped.
- Replaced strict Java XML DOM parsing for EPUB content documents with tolerant bounded Jsoup XHTML/HTML parsing.
- Kept Readest/Foliate-compatible CFI child-node indexing and real range CFIs with start/end offsets.
- Text matching now normalizes non-breaking spaces, common smart quotes, dash variants and soft hyphens while preserving the source DOM offsets used for the final CFI.
- Added regression coverage for `&nbsp;` and inline markup inside highlighted text.
- Unresolved highlights are still never assigned invented positions and remain preserved in `moon-export.mrexpt`.

### Finished reading status
- Moon+ books with an explicit 100% reading position are now mapped to Readest `readingStatus: "finished"` with `readingStatusUpdatedAt` in addition to progress `[100,100]`.
- Finished-status matching uses ISBN first, then unique title/author matching against the just-written Readest library entry.
- Ambiguous matches are not guessed and are surfaced in the final export status.
- The status pass does not reopen or rehash the ebook, so the 1.0.9 export-speed improvement remains intact.

### Verification
- Version: 1.0.10 / versionCode 12.
- Tested app-code head: `05211c62d5600cbdd6d87b078b87787264a713df`.
- Android CI run `34765408400`: success, including privacy scan, unit tests, Android lint, debug APK build and manifest/permission audit.
- CodeQL run `34765408386`: success.
- Debug artifact: `MoonExporter-1.0.10-debug`, artifact ID `10320725510`.
- APK SHA256: `85f5119185ba07e6ec031d67dd286912c617caffcfd23eecc38907a4f30ea5fc`.

## 1.0.9 - 2026-09-13

### Readest export performance
- Direct Readest export now prepares each selected ebook source only once. An EPUB embedded in `.mrpro` therefore requires one backup scan instead of separate full scans for Readest hash, EPUB metadata and final copy.
- The prepared ebook is stored only in a temporary per-export cache file, reused for hash/metadata/highlight resolution/copy, and deleted in `finally` immediately after that book is processed.
- Local copying uses larger buffered streams to reduce SAF/ZIP I/O overhead.
- The EPUB ZIP used for highlight resolution stays open while annotations of the same book are resolved instead of reopening the archive per chapter lookup.

### Export progress UI
- Local export progress is no longer shown as the generic progress bar in the backup card.
- Added a dedicated `4. Exportfortschritt` section at the bottom of the screen with a determinate progress bar.
- Direct Readest export reports seven named stages for every book: prepare source, calculate Readest ID, inspect EPUB, resolve highlights, copy ebook, write `config.json`, update library entry.
- The current book number, total book count and current stage are shown while the export runs.
- The alphabet rail down-arrow now targets the actual final list item so the bottom progress section remains reachable.

### Exact Readest highlights
- Fixed the 1.0.8 behavior that wrote chapter-start-only CFIs for Moon+ annotations. Those notes appeared in Readest but were not visibly highlighted and navigated only to the beginning of the chapter.
- Added `ReadestCfiResolver`, which resolves the original Moon+ highlighted text against the actual EPUB XHTML and generates a real Readest/Foliate EPUB CFI range with start and end offsets.
- The CFI node indexing follows Readest's current `foliate-js/epubcfi.js` semantics, including virtual text chunks, `cfi-inert` and `cfi-skip` handling.
- Moon+ chapter information is used as the preferred spine location. If the highlighted text occurs more than once, the Moon+ source position is used to prefer the closest occurrence.
- If a highlight cannot be resolved reliably, Moon Exporter does not invent a range. The original annotation remains preserved in `moon-export.mrexpt`.
- Re-export removes previous Moon Exporter notes with the same stable IDs before inserting corrected range-CFI versions, while preserving unrelated/native Readest notes.
- XHTML parsing remains bounded and disables external entities/DTD loading.

### Verification
- Added a synthetic EPUB regression test proving that `highlighted phrase` becomes a range CFI with explicit start/end offsets instead of a chapter-only CFI.
- Tested app-code head: `a2c0e95c83a18b23ce15bd9a3f555761e47c49fe`.
- Android CI run `34763052900`: success, including privacy scan, unit tests, lint, debug APK build and manifest/permission audit.
- CodeQL run `34763052880`: success.
- Debug artifact: `MoonExporter-1.0.9-debug`, artifact ID `10319672483`.
- APK SHA256: `1ef495695eac655e89a55a4ef75d4d79601320e0854716c8266cff9fa260fc79`.

## 1.0.8 - 2026-09-13

### Readest direct export
- Added a direct Readest library export that writes selected books into `Readest/Books` and merges `library.json` plus per-book `config.json`.
- Readest book directories use Readest's own partialMD5 sampling scheme, which is intentionally separate from KOReader/KOSync partialMD5.
- EPUB/PDF files, cover metadata, reading progress and safely mapped annotations are written without fabricating `nav.json`; Readest can rebuild that derived cache itself.
- Existing `library.json` and per-book `config.json` are backed up once before Moon Exporter changes them.
- Existing Readest book files are not duplicated when the same Readest book hash already exists.
- Moon+ annotations that cannot be mapped safely remain preserved as `moon-export.mrexpt` instead of receiving invented CFIs.

### Cryptic Moon+ filenames
- Cryptic numeric/hash filenames are detected and shown explicitly in the UI.
- For Readest direct export the user can choose to reconstruct and normalize output filenames.
- Reconstruction prefers EPUB title/author/identifier metadata, then the title/author/ISBN already recovered from Moon+ database and backup metadata.
- Only the new Readest copy receives the reconstructed filename. The Moon+ backup and original ebook are never renamed or modified.

### Identity and progress safety
- `metaHash` now follows Readest's identifier preference order (UUID, Calibre, ISBN) instead of treating ISBN as the only identifier source.
- Existing Readest progress is preserved unless no target progress exists or Moon+ has a newer explicit timestamp.
- Percentage fallback is stored without claiming an exact text position.

### Tests
- Added a synthetic regression test for Readest partialMD5 sampling.
- Existing privacy, unit, lint, manifest and permission checks remain mandatory before APK delivery.

## 1.0.7 - 2026-09-13

### Network
- Added explicitly requested HTTP support for home/local-network KOSync, CWA and BookLore servers.
- Cleartext HTTP is accepted only for local targets: private IPv4 ranges, loopback, link-local, `.local`, single-label LAN hostnames and local IPv6 ranges.
- Public `http://` targets remain rejected by `KoSyncClient`; HTTPS remains supported for public and local servers.
- Android cleartext transport is enabled at manifest level only so the validated LAN HTTP connection can be opened. The application-level local-host gate remains mandatory.
- Added regression tests that accept local HTTP and reject public HTTP.

### UI
- Added an up-arrow above the alphabet rail to jump to the top of the screen.
- Added a down-arrow below the alphabet rail to jump to the bottom/destination section.
- Kept tap and drag A-Z navigation for large book lists.
- Added the book filter `Ohne Buchdatei` / `Without book file`.
- Updated server URL help text to distinguish recommended HTTPS from local-network HTTP.

### Verification
- Tested app-code head: `941a56e2808ec203ca3cd93bca22afd68821918e`.
- Android CI run `34752712899`: success, including privacy scan, unit tests, lint, APK build and manifest audit.
- CodeQL run `34752712889`: success.
- Debug artifact: `MoonExporter-1.0.7-debug`, artifact ID `10316652062`.
- APK SHA256: `4bbd6cc58a344231b0816728e6223a340788a03042429b1c70158b046cd63bcd`.

## 1.0.6 - 2026-09-13

### Reading progress
- Identified the primary reading-progress source in full Moon+ `.mrpro` backups as Android SharedPreferences `shared_prefs/positions10.xml`.
- Added a dedicated parser for `<string name="book-path">position</string>` entries in `positions10.xml`.
- Added support for Moon+ position values without a timestamp (`chapter@section#offset:percent%`) as used by `positions10.xml`, while retaining timestamped cloud `.po` values.
- Progress matching now normalizes full path, basename and stem aliases and avoids ambiguous basename-only matches.
- Original Moon+ raw position values remain preserved for later structural conversion work.
- Added regression tests for `positions10.xml`, XML entity decoding, timestamp-free EPUB/PDF positions and unsafe XML declarations.

### UI
- Added a draggable and tappable alphabetical A-Z rail for quickly jumping through long book lists.
- The entire book card toggles selection; the checkbox footprint and surrounding spacing are more compact.
- Per-book manual ebook assignment remains available when progress was found but the target document identity is missing.
- The connection-test result is shown directly below the KOSync/CWA/BookLore controls instead of only changing the global status text.
- Readest copy now distinguishes documented `.mrexpt` annotation import from reading-progress transfer.

### Format handling
- KOSync/CWA/BookLore continue to receive the normalized percentage value (`0.0..1.0`) plus the preserved source-position context. Exact KOReader EPUB xpointer conversion is not fabricated when the Moon+ structural position cannot be mapped safely.
- The sample backup used for analysis is not committed to the repository, tests or artifacts.

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
