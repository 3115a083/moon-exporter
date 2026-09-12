# Moon Exporter project state

Updated: 2026-09-12

## Product scope

Moon Exporter is an Android migration tool for historical Moon+ Reader data. It is not an ebook reader and not a general-purpose sync server.

The primary user flow is now explicitly:
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
Current revision branch: `revision/1.0.1-handoff`
Revision version: `1.0.1`
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

## Confirmed regressions from the standalone rework

The large rework from commit `278178d` to `1147e207` introduced functional regressions. Revision 1.0.1 itself did not cause them.

Confirmed issues:
1. The Compose root layout does not correctly consume status-bar/navigation-bar/display-cutout insets. Content can appear behind system bars.
2. Export and sync/server settings are rendered only inside `if (books.isNotEmpty())`. A failed/empty import therefore makes major app areas disappear.
3. A valid real `.mrpro` can spend a long time being analyzed and still produce zero books. The importer relies too heavily on `_names.list` mapping for numbered `.tag` entries and lacks a robust SQLite signature fallback.
4. Empty imports do not provide enough diagnostics about discovered backup components, database detection, tables or fallback sources.

Keep the current visual color direction while repairing these regressions.

## Core requirements still missing or incomplete

- Robust `.mrpro` backup-file import across format variants without relying on a single filename mapping mechanism.
- Manual per-book EPUB/PDF assignment through SAF when a book file is missing or ambiguous.
- Explicit position quality classification: `EXACT`, `FALLBACK`, `UNRESOLVED`.
- Exact EPUB CFI only when the actual EPUB is available and the position can be resolved structurally.
- Reliable Readest output as a clear file/folder structure containing the supported annotations/progress/report data.
- Reliable CWA and BookLore progress transfer over KOSync with target-specific authentication and useful HTTP errors.
- Visible import/export/sync progress with real cancellation.
- Complete German/English localization and responsive layout with correct system insets.
- Full final release audit and signed APK verification, beyond debug CI.

## Import rules

Moon+ position raw values must be preserved. EPUB and PDF variants must be parsed defensively. Unknown forms are retained rather than silently discarded.

Annotation path:
`.an` -> zlib decompress -> Moon+ textual annotation format -> `.mrexpt` -> Readest Moon+ import.

Book identity must never be guessed when evidence is weak. Use `assignment required`/equivalent state instead.

For `.mrpro`:
- `_names.list` may help map numbered `.tag` entries but must not be the sole database detector.
- Add SQLite signature detection using `SQLite format 3`.
- Diagnose missing/unexpected schemas instead of silently returning an empty result.
- Keep archive entry, file size, total work and traversal protections.
- Prefer an inexpensive backup inventory before expensive EPUB/hash processing.

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

Before a final release APK additionally perform:
- release build and signing
- `apksigner verify`
- exported-components audit
- dependency audit
- source and APK secret/PII scans
- network security audit
- archive/XML/SAF audit

## Engineering backlog

Priority order:
1. Fix system-inset and hidden-controls regressions.
2. Repair `.mrpro` detection and add synthetic SQLite-`.tag` regression tests.
3. Verify and stabilize Readest export.
4. Stabilize CWA/BookLore KOSync output, including `partialMD5`, URL/auth handling and failure cases.
5. Complete manual book assignment and position-quality handling.
6. Improve UI/performance/localization and long-operation cancellation.
7. Implement WebDAV only after the above paths are stable.

## Handoff rule

Future coding sessions must read this file, `CHANGELOG.md` and the private handoff before making scope decisions. Do not infer priority solely from features that already happen to exist in the current code.
