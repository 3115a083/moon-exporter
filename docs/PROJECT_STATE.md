# Moon Exporter project state

Updated: 2026-09-12

## Product scope

Moon Exporter is an Android migration tool for moving historical Moon+ Reader data primarily to Readest. It is not an ebook reader and not a general-purpose sync server.

Original product priority:
- P0: local Moon+ migration from `.po`/`.an`, book identity, manual book assignment, `.mrexpt`, reading progress, export progress/cancellation, privacy.
- P1: WebDAV online backup, custom headers, cover/index matching, `.mrpro` recovery.
- P2: KOSync/CWA/BookLore adapters and advanced matching.

Readest is the primary V1 target. KOSync/CWA is optional and must not displace the core Readest migration path.

Hard scope boundary:
- KOSync/CWA may receive authentication, document identifiers and reading-progress data only.
- Moon Exporter must never upload ebook files through the KOSync/CWA flow.

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

## Original requirements still missing or incomplete

- Manual per-book EPUB/PDF assignment through SAF when a book file is missing or ambiguous.
- Explicit position quality classification: `EXACT`, `FALLBACK`, `UNRESOLVED`.
- Exact EPUB CFI only when the actual EPUB is available and the position can be resolved structurally. Never invent a CFI.
- WebDAV online backup with server URL, username, password and custom HTTP headers.
- WebDAV security: HTTPS-first, redirect controls, no URL credentials, timeout/retry/size limits, CR/LF header validation, LAN/private-host opt-in, safe PROPFIND XML parsing.
- Secure credential storage using Android Keystore/encrypted local storage.
- Complete System/Light/Dark/Dynamic Color theme support.
- Complete German/English localization without mixed-language views.
- Responsive layout at 360 dp minimum width with proper system-bar/cutout handling.
- Migration/export report with found/converted/not-converted/warnings plus visible progress and real cancellation.
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
3. Complete original P0 local migration requirements, especially manual book assignment and position quality states.
4. Verify Readest `.mrexpt` and progress migration behavior.
5. Implement secure WebDAV P1 flow.
6. Continue KOSync/CWA P2 hardening only after the Readest core is stable.

## Handoff rule

Future coding sessions must read this file, `CHANGELOG.md` and the private handoff before making scope decisions. Do not infer priority solely from features that already happen to exist in the current code.
