# Moon Exporter project state

Updated: 2026-09-12

## Product scope
Moon Exporter is a local Android migration tool for Moon+ Reader data.

Binding flow:
1. Analyze one Moon+ Reader backup, especially `.mrpro`.
2. Review analyzed books, reading progress and markings, then select individual books.
3. Choose Readest, generic KOSync, Calibre-Web Automated or BookLore as destination.
4. Export/transfer the selected books.

WebDAV remains deferred until this local flow is reliable.

## Current revision
- Repository: `3115a083/moon-exporter`
- Branch: `revision/1.0.5-analysis-progress`
- PR: #14
- Version: `1.0.5`, versionCode 7
- minSdk 26, targetSdk 35, compileSdk 35
- Android CI run `34704581391`: success
- CodeQL run `34704581363`: success
- Debug artifact: `MoonExporter-1.0.5-debug`

## 1.0.5 behavior
- `.mrpro` analysis runs in a foreground data-sync service and continues when the Activity is normally backgrounded/closed.
- An ongoing Android notification shows analysis status and a running progress indicator.
- Light status bar uses dark icons. Android 8.0 uses a dark navigation bar because light navigation icons are unavailable there; API 27+ uses a light navigation bar with dark icons.
- A defensive second `.po` pass recovers Moon+ reading progress using normalized full-path, filename and stem aliases plus adaptive zero-/one-based `.tag` mapping.
- Book cards show title, author, cover/placeholder, percentage, a visual progress bar, position calculation method, marking count and target document identity.
- A book with Moon+ progress but no reliable ebook/document identity offers per-book manual EPUB/PDF assignment through SAF. The original Moon+ progress is preserved.
- Numeric/hash-like filenames are treated as opaque identifiers. EPUB metadata is preferred for reconstructing title/author/cover; otherwise the item remains explicitly unknown rather than guessed.
- Embedded EPUB cover/title/author/ISBN enrichment streams from the original backup without persistent ebook cache copies.
- Readest export type and destination use explicit radio selections rather than button-like selectors.

## Hard boundaries
- KOSync/CWA/BookLore transfer authentication, document identity and reading progress only. Ebook files are never uploaded through these APIs.
- KOReader-compatible `partialMD5` is used only to identify a book already present at the destination.
- Moon+ raw position values are preserved.
- Exact target positions must never be invented. Percentage fallback/unresolved states remain valid until structural translation is justified.
- `.an` markings are preserved and exported through Moon+/Readest-compatible `.mrexpt` handling.

## Security and privacy
- No telemetry, analytics, ads or cloud crash reporting.
- No real user backup data in source, tests, logs or CI artifacts.
- SAF only for user files. No broad storage permissions.
- `android:allowBackup="false"` and cleartext traffic disabled.
- Foreground service is non-exported and uses `dataSync` type.
- No credential/Auth-header logging.

## Still incomplete / next priorities
1. Validate 1.0.5 progress recovery and cover/title reconstruction against the user's real backup.
2. Improve exact book identity matching when Moon+ uses cryptic identifiers, using all available backup metadata before requiring manual assignment.
3. Add explicit position-quality model `EXACT`, `FALLBACK`, `UNRESOLVED` and structural EPUB translation where possible.
4. Verify Readest file/folder output end-to-end against real Readest imports and report converted/unconverted markings.
5. Harden CWA/BookLore/generic KOSync error handling and per-book results.
6. Complete localization/responsive UI and final release/signing/security audit.
7. WebDAV only after the above is stable.

## Development rule
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. Do not merge open revision PRs without explicit user approval.
