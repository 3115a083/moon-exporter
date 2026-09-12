# Moon Exporter project state

Updated: 2026-09-12

## Product scope

Moon Exporter is an Android migration tool for historical Moon+ Reader data. It is not an ebook reader and not a general-purpose sync server.

Primary user flow:
1. Select one Moon+ Reader backup file, especially `.mrpro`.
2. Reliably discover books, reading progress, annotations and notes.
3. Select the books to transfer.
4. Choose exactly one destination:
   - Readest-compatible file/folder export through SAF for Moon+ annotations, optionally with book files.
   - Generic KOSync-compatible server for reading progress.
   - Calibre-Web Automated through its `/kosync` endpoint for reading progress.
   - BookLore through its `/api/koreader` endpoint for reading progress.

WebDAV/online backup import is deliberately deferred. It must not compete with stability work on the local backup-file workflow.

## Hard scope boundaries

- KOSync/CWA/BookLore may receive authentication data, document identifiers and reading-progress data only.
- Moon Exporter must never upload ebook files through the KOSync/CWA/BookLore flow.
- KOReader-compatible `partialMD5` is an identifier for matching a book that already exists on the target server.
- Exact EPUB CFI must never be invented. Use `FALLBACK` or `UNRESOLVED` when exact structural mapping is not justified.

## Current code baseline

Repository: `3115a083/moon-exporter`
Default branch: `main`
Application ID / namespace: `de.moonexporter.app`
Current revision branch: `revision/1.0.3-cache-ui-import`
Revision version: `1.0.3` / versionCode 5
Pull request: #12, open and not merged
Minimum Android SDK: 26
Target Android SDK: 35
UI: Jetpack Compose

Implemented areas:
- Storage Access Framework based local input/output.
- Moon+ folder scanning for position and annotation data.
- `.mrpro` ZIP/container processing.
- SQLite metadata extraction from Moon+ backup databases.
- `.po` / `.an` fallback extraction from `.mrpro`.
- KOReader-compatible partial MD5 calculation.
- One vertically scrolling migration UI.
- Book selection and dropdown filtering.
- Readest marking export.
- Generic KOSync, CWA and BookLore progress transfer.
- Cancellation checks in long coroutine paths.

## Revision 1.0.3

### UI
- Main interaction flow is now `backup -> select books -> choose destination -> transfer`.
- The complete screen uses one `LazyColumn`, so transfer/export actions remain reachable on small screens.
- Book filtering uses one dropdown instead of multiple chips.
- Readest, generic KOSync, CWA and BookLore are destination choices in one target selector instead of duplicated export/sync sections.
- Readest shows only file-export controls.
- Server targets show only server URL, username, password, connection test and progress-send controls.
- The technical device-id field and regular diagnostics toggle are removed from the primary user flow.

### Import and cache
- `.mrpro` import no longer persists extracted ebook copies in `cacheDir`.
- Embedded book entries are streamed during analysis. The app retains only metadata, size, `partialMD5`, backup URI and archive-entry name.
- Full Readest export reopens the original `.mrpro` and streams only the requested book entry directly to the SAF destination.
- The temporary Moon+ SQLite database is deleted in `finally`, including failure and cancellation paths.
- Stale `mrpro-*` cache directories are removed before a new `.mrpro` import.
- `.po` and `.an` data can recover books when the database is unavailable or incompatible.
- Database parsing detects compatible book tables and common column variants rather than depending on one exact schema.
- Non-empty original Moon+ annotation text is considered exportable even if annotation counting cannot parse it.

### KOSync, CWA and BookLore
- Corrected KOReader partial-MD5 sampling offsets: 512, 2048, 8192, 32768, ... through 2147483648 bytes.
- Generic KOSync uses the configured base URL and standard KOReader auth headers.
- CWA uses `<base>/kosync` with its Basic-auth integration.
- BookLore uses `<base>/api/koreader` and standard KOReader auth headers.
- None of these server outputs upload ebook files.

## Build status 1.0.3

Head commit: `6587ff7c5ebb6fdd345b4bf03fd9c5ee204bf609`.

GitHub Actions Android run `34701162320` completed successfully:
1. privacy source scan
2. unit tests
3. Android lint
4. debug APK build
5. manifest/permission audit
6. APK artifact upload

CodeQL run `34701162292` also completed successfully.

Artifact:
- `MoonExporter-1.0.3-debug`
- artifact ID `10300043458`
- APK SHA256 `70ff93142af798c2f6730556ba9f64f91b87243e7d3c164419f0752f2dbef725`

## Core requirements still missing or incomplete

- Validate 1.0.3 with the user's real backup and verify that the previously missing books now appear.
- Improve manual per-book EPUB/PDF assignment through SAF when automatic matching is missing or ambiguous. Current UI supports selecting multiple files for automatic matching.
- Explicit position quality classification: `EXACT`, `FALLBACK`, `UNRESOLVED`.
- Exact EPUB CFI only when the actual EPUB is available and the position can be resolved structurally.
- Verify Readest output against real Readest behavior and add a migration report for converted/fallback/unresolved data.
- Validate KOSync, CWA and BookLore against real servers and additional failure cases.
- Optimize large `.mrpro` import speed without reintroducing persistent ebook caching.
- Complete German/English localization and responsive layout verification.
- Full final release audit and signed APK verification, beyond debug CI.

## Import rules

Moon+ position raw values must be preserved. EPUB and PDF variants must be parsed defensively. Unknown forms are retained rather than silently discarded.

Annotation path:
`.an` -> zlib decompress -> Moon+ textual annotation format -> `.mrexpt` -> Readest Moon+ import.

Book identity must never be guessed when evidence is weak. Use assignment-required/equivalent state instead.

For `.mrpro`:
- `_names.list` may help map numbered `.tag` entries but must not be the sole database detector.
- SQLite signature detection using `SQLite format 3` is required as a fallback.
- Missing/unexpected schemas must be diagnosed or fall back to recoverable `.po`/`.an` data instead of silently returning an empty result.
- Keep archive entry, file size, total work and traversal protections.
- Do not retain a full second copy of the user's library in app cache.

## Output rules

### Readest
- Export through SAF as one clear user-selected destination.
- Preserve annotations/notes without silent loss.
- Optional full export may include source book files by streaming them from the original backup or selected SAF URI.
- Preserve original progress information and clearly distinguish exact, percentage fallback and unresolved positions once position classification is implemented.

### KOSync / CWA / BookLore
- Match an already existing target book using KOReader-compatible document identity such as `partialMD5`.
- Transfer reading progress only.
- Never upload the source ebook.
- Normalize target URLs and authentication per supported server type.
- Treat failures clearly without exposing credentials.

## Deferred functionality

WebDAV/online-backup import, custom HTTP headers and the broader WebDAV security/UI surface remain valid future requirements but are lower priority than stable local backup import and stable Readest/KOSync/CWA/BookLore output.

## Security and privacy invariants

Keep these requirements intact in every revision:
- No analytics, telemetry, ads or cloud crash reporting.
- No real Moon+ backup data in source, tests, logs, screenshots or CI artifacts.
- No credential or Authorization header logging.
- No broad Android storage permission. Use SAF.
- `android:allowBackup="false"`.
- Reject or safely ignore archive traversal attempts.
- Bound untrusted archive/XML/database input.
- Treat DOCTYPE and ENTITY declarations as unsafe input.
- Existing user files must never be silently overwritten or deleted.

## Validation

Every test revision should pass at least:
1. `:app:testDebugUnitTest`
2. `:app:lintDebug`
3. `:app:assembleDebug`
4. Manifest/permission audit
5. Repository privacy scan
6. CodeQL where configured

Before a final release APK additionally perform release build/signing, `apksigner verify`, exported-components/dependency/secret/PII/network/archive/XML/SAF audits.

## Engineering backlog

Priority order after 1.0.3:
1. Validate import against user feedback and fix any remaining real `.mrpro` compatibility gaps.
2. Verify and stabilize Readest output, including annotation reporting and position quality.
3. Validate KOSync/CWA/BookLore against real targets and improve per-book error reporting.
4. Complete manual book assignment and position-quality handling.
5. Improve import performance, localization, responsive UI and cancellation details.
6. Implement WebDAV only after the above paths are stable.

## Handoff rule

Future coding sessions must read this file, `CHANGELOG.md` and the private handoff before making scope decisions. Do not infer priority solely from features that already happen to exist in the current code.
