# Moon Exporter project state

Updated: 2026-09-12

## Product scope

Moon Exporter is an Android migration tool for Moon+ Reader data. It is not an ebook reader and not a general-purpose sync server.

Supported product direction:
- Import Moon+ Reader folder data and `.mrpro` backups.
- Parse reading positions and annotations.
- Match books and inspect EPUB metadata.
- Export annotations in Readest-compatible form.
- Optionally transfer reading progress to standard KOSync or Calibre-Web Automated.

Hard scope boundary:
- KOSync/CWA may receive reading-progress data only.
- Moon Exporter must not upload ebook files through the KOSync/CWA flow.

## Current code baseline

Repository: `3115a083/moon-exporter`
Default branch: `main`
Application ID / namespace: `de.moonexporter.app`
Current revision: `1.0.1`
Minimum Android SDK: 26
Target Android SDK: 35
UI: Jetpack Compose

Implemented areas:
- Storage Access Framework based input/output.
- Moon+ folder scanning for position and annotation data.
- `.mrpro` ZIP/container processing.
- SQLite metadata extraction from Moon+ backup databases.
- EPUB metadata and cover extraction.
- KOReader-compatible partial MD5 calculation.
- Book list, filtering, selection and fast scrolling.
- Readest marking export.
- Standard KOSync and CWA progress transfer.
- Cancellation checks in long-running coroutine paths.

## Security and privacy invariants

Keep these requirements intact in every revision:
- No analytics, telemetry or ads.
- No real Moon+ backup data in source, tests, logs or CI artifacts.
- No credential or Authorization header logging.
- No broad Android storage permission. Use SAF.
- `android:allowBackup="false"`.
- `android:usesCleartextTraffic="false"`.
- Reject or safely ignore traversal attempts in archive paths.
- Bound untrusted archive/XML/database input where practical.
- Treat DOCTYPE and ENTITY declarations as unsafe input.

## Required validation before merging

Every revision should pass:
1. `:app:testDebugUnitTest`
2. `:app:lintDebug`
3. `:app:assembleDebug`
4. Manifest/permission audit
5. Repository privacy scan

The debug APK from GitHub Actions is the test artifact to provide after each revision.

## Engineering backlog

Highest priority:
1. Large `.mrpro` robustness and bounded processing.
2. More synthetic parser tests for malformed and hostile input.
3. Readest `.mrexpt` compatibility validation.
4. KOSync/CWA protocol and HTTP failure tests.
5. Import/export/network cancellation UX verification.

Secondary:
- Refine metadata matching and missing-book diagnostics.
- Continue improving book-list performance for very large libraries.
- Keep German and English UI strings aligned.

## Handoff rule

Future coding sessions should read this file and `CHANGELOG.md` first. Only inspect the full codebase when the requested change touches undocumented behavior or when tests indicate that the documented state is stale.
