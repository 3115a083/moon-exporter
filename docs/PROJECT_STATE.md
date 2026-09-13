# Moon Exporter project state

Updated: 2026-09-13

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
- Branch: `revision/1.0.7-lan-scroll-filter`
- PR: #18, open and not merged
- Version: `1.0.7`, versionCode 9
- Tested app-code head: `941a56e2808ec203ca3cd93bca22afd68821918e`
- minSdk 26, targetSdk 35, compileSdk 35
- Android CI run `34752712899`: success
- CodeQL run `34752712889`: success
- Debug artifact: `MoonExporter-1.0.7-debug`, artifact ID `10316652062`
- Verified APK SHA256: `4bbd6cc58a344231b0816728e6223a340788a03042429b1c70158b046cd63bcd`

## 1.0.7 behavior
- HTTP is allowed for local/home-network sync servers only. Accepted cleartext targets include private IPv4 ranges, loopback, link-local, `.local`, single-label LAN hostnames and local IPv6 ranges. Public HTTP hosts are rejected by `KoSyncClient`; HTTPS remains accepted for all valid public/local hosts.
- Android cleartext transport is enabled at manifest level only because the app enforces the local-host restriction before opening the connection. Do not remove the app-side gate.
- The alphabet rail has an up arrow to jump to the top and a down arrow to jump to the bottom, while retaining tap/drag A-Z navigation.
- Book filtering includes `Ohne Buchdatei` / `Without book file` in addition to existing progress/book filters.
- Connection-test feedback remains visible inline in the destination section.

## Retained 1.0.6 behavior
- Full `.mrpro` backups recover reading progress primarily from Android SharedPreferences `shared_prefs/positions10.xml`, using `<string name="book-path">position</string>` entries.
- Timestamp-free Moon+ position values such as EPUB `chapter@section#offset:percent%` and PDF `page:percent%` are supported. `.po` remains a secondary fallback for other backup variants.
- Original Moon+ raw position values are preserved.
- Book cards show title, author, cover/placeholder, percentage, visual progress bar, position calculation method, marking count and document identity.
- Entire book cards toggle selection. If progress exists but a reliable target book identity is missing, the card offers manual EPUB/PDF assignment through SAF.
- Alphabetical navigation is optimized for large book lists.

## Background analysis and UI
- `.mrpro` analysis runs in a foreground data-sync service and continues when the Activity is normally backgrounded/closed.
- An ongoing Android notification shows analysis status/progress.
- Light system bars use readable dark icons where supported.
- Embedded EPUB cover/title/author/ISBN enrichment streams from the original backup without persistent ebook cache copies.
- Numeric/hash-like filenames are treated as opaque identifiers. Metadata is preferred; uncertain names are not guessed.

## Hard boundaries
- KOSync/CWA/BookLore transfer authentication, document identity and reading progress only. Ebook files are never uploaded through these APIs.
- KOReader-compatible `partialMD5` is used only to identify a book already present at the destination.
- Moon+ percent `0..100` is normalized to KOSync `percentage` `0..1`.
- Exact target positions must never be invented. Percentage fallback/unresolved states remain valid until structural translation is justified.
- `.an` markings are preserved and exported through Moon+/Readest-compatible `.mrexpt` handling.

## Security and privacy
- No telemetry, analytics, ads or cloud crash reporting.
- No real user backup data in source, tests, logs or CI artifacts.
- SAF only for user files. No broad storage permissions.
- `android:allowBackup="false"`.
- Cleartext transport is manifest-enabled only to support explicitly requested local-network HTTP. Public HTTP is blocked in `KoSyncClient`.
- Foreground service is non-exported and uses `dataSync` type.
- No credential/Auth-header logging.

## Still incomplete / next priorities
1. Continue validating progress recovery against complete real backups.
2. Improve exact book identity matching when Moon+ uses cryptic identifiers, using all available backup metadata before requiring manual assignment.
3. Add explicit position-quality model `EXACT`, `FALLBACK`, `UNRESOLVED` and structural EPUB translation where possible.
4. Verify Readest file/folder output end-to-end against real Readest imports and report converted/unconverted markings.
5. Harden CWA/BookLore/generic KOSync error handling and per-book results.
6. Complete localization/responsive UI and final release/signing/security audit.
7. WebDAV only after the above is stable.

## Development rule
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. Do not merge open revision PRs without explicit user approval.
