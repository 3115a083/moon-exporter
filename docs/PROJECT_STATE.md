# Moon Exporter project state

Updated: 2026-09-12

## Product scope

Moon Exporter is an Android migration tool for historical Moon+ Reader data. It is not an ebook reader and not a general-purpose sync server.

Primary user flow:
1. Select one Moon+ Reader backup file, especially `.mrpro`.
2. Reliably discover books, reading progress, annotations and notes.
3. Keep uncertain book/position mappings visible instead of guessing.
4. Send the result through one supported output path:
   - Readest-compatible file/folder export through SAF.
   - Direct reading-progress transfer to CWA or BookLore through a KOReader/KOSync-compatible API.

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
Current revision branch: `revision/1.0.2-stability`
Revision version: `1.0.2` / versionCode 4
Minimum Android SDK: 26
Target Android SDK: 35
UI: Jetpack Compose

Implemented areas:
- Storage Access Framework based local input/output.
- Moon+ folder scanning for position and annotation data.
- `.mrpro` ZIP/container processing.
- SQLite metadata extraction from Moon+ backup databases.
- EPUB metadata and cover extraction.
- KOReader-compatible partial MD5 calculation.
- Book list, filtering, selection and fast scrolling.
- Readest marking export.
- Standard KOSync and CWA progress transfer.
- Cancellation checks in several coroutine paths.

## Revision 1.0.2 fixes

- Root UI consumes `WindowInsets.safeDrawing`, preventing important content from being placed below system bars and display cutouts.
- Export and server/KOSync controls are no longer conditional on a non-empty book list. They remain visible after a failed or empty import.
- Export/send actions are disabled until at least one book is selected.
- Backup-file import is the primary visible import action; folder import remains secondary.
- Numbered `.tag` entries support both one-based and zero-based `_names.list` candidates.
- Numbered `.tag` entries are additionally inspected for the SQLite header `SQLite format 3`, so `mrbooks.db` can be recovered independently of filename mapping.
- `.mrpro` processing enforces the global archive entry count limit.
- Empty/failed imports now distinguish between no database/positions, recognized-but-unreadable database and recognized database with no readable books.
- Synthetic regression tests cover tag mapping and SQLite signature detection.

## Core requirements still missing or incomplete

- Manual per-book EPUB/PDF assignment through SAF when a book file is missing or ambiguous.
- Explicit position quality classification: `EXACT`, `FALLBACK`, `UNRESOLVED`.
- Exact EPUB CFI only when the actual EPUB is available and the position can be resolved structurally.
- Reliable Readest output as a clear file/folder structure containing the supported annotations/progress/report data.
- Reliable CWA and BookLore progress transfer over KOSync with target-specific authentication and useful HTTP errors.
- Visible import/export/sync progress with real cancellation across all long operations.
- Complete German/English localization and responsive layout verification.
- Full final release audit and signed APK verification, beyond debug CI.

## Import rules

Moon+ position raw values must be preserved. EPUB and PDF variants must be parsed defensively. Unknown forms are retained rather than silently discarded.

Annotation path:
`.an` -> zlib decompress -> Moon+ textual annotation format -> `.mrexpt` -> Readest Moon+ import.

Book identity must never be guessed when evidence is weak. Use `assignment required`/equivalent state instead.

For `.mrpro`:
- `_names.list` may help map numbered `.tag` entries but must not be the sole database detector.
- SQLite signature detection using `SQLite format 3` is required as a fallback.
- Missing/unexpected schemas must be diagnosed instead of silently returning an empty result.
- Keep archive entry, file size, total work and traversal protections.

## Output rules

### Readest
- Export through SAF as one clear user-selected destination, either a compatible file or a structured export folder as required by the selected export mode.
- Preserve annotations/notes without silent loss.
- Preserve original progress information and clearly distinguish exact, percentage fallback and unresolved positions.
- Include a migration report describing success, fallback and unresolved items.

### CWA / BookLore via KOSync
- Match an already existing target book using KOReader-compatible document identity such as `partialMD5`.
- Transfer reading progress only.
- Never upload the source ebook.
- Normalize target URLs and authentication per supported server type.
- Treat per-book failures separately and report them clearly without exposing credentials.

## Deferred functionality

WebDAV/online-backup import, custom HTTP headers and the broader WebDAV security/UI surface remain valid future requirements but are lower priority than stable local backup import and stable Readest/CWA/BookLore output.

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

Before a final release APK additionally perform release build/signing, `apksigner verify`, exported-components/dependency/secret/PII/network/archive/XML/SAF audits.

## Engineering backlog

Priority order after 1.0.2:
1. Validate 1.0.2 against synthetic `.mrpro` containers and user test feedback.
2. Verify and stabilize Readest export.
3. Stabilize CWA/BookLore KOSync output, including `partialMD5`, URL/auth handling and failure cases.
4. Complete manual book assignment and position-quality handling.
5. Improve UI/performance/localization and long-operation cancellation.
6. Implement WebDAV only after the above paths are stable.

## Handoff rule

Future coding sessions must read this file, `CHANGELOG.md` and the private handoff before making scope decisions. Do not infer priority solely from features that already happen to exist in the current code.
