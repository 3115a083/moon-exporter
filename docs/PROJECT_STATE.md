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
- Branch: `revision/1.0.7-home-http-filter-nav`
- PR: #19, open and not merged
- Version: `1.0.7`, versionCode 9
- minSdk 26, targetSdk 35, compileSdk 35

## 1.0.7 behavior
- HTTP is accepted only for private/home-network targets. Public cleartext URLs are rejected by `KoSyncClient.normalizeBaseUrl` before a network request is made.
- Accepted private cleartext targets include RFC1918 IPv4, loopback/link-local, local IPv6 ranges, single-label LAN hosts and `.local`, `.lan` or `.home.arpa` names. HTTPS remains accepted everywhere.
- Android cleartext transport is enabled only because the app performs the stricter target validation itself. Redirects are not automatically followed.
- The alphabet rail has an up arrow above A-Z/# for jumping to the top and a down arrow below it for jumping to the final destination section.
- Book filters now include `Ohne Buchdatei` / `Without book file` in addition to all existing filters.
- Server UI explicitly explains that HTTPS is recommended and HTTP is restricted to private home-network addresses.

## Retained 1.0.6 behavior
- Full `.mrpro` backups recover reading progress primarily from Android SharedPreferences `shared_prefs/positions10.xml` entries shaped like `<string name="book-path">Moon-position</string>`.
- Timestamp-free Moon+ positions such as `chapter@section#offset:percent%` and PDF `page:percent%` are parsed; timestamped `.po` remains an additional fallback.
- The raw Moon+ position and percentage are preserved. KOSync/CWA/BookLore receive normalized percentage `0.0..1.0`; exact KOReader xpointer values are never fabricated.
- A draggable/tappable alphabet rail supports fast navigation through large libraries.
- The entire book card toggles selection and the checkbox footprint is compact.
- If progress exists but no reliable book/document identity exists, that book card offers manual EPUB/PDF assignment via SAF.
- Connection testing shows success/failure feedback inline.
- `.mrpro` analysis runs in a foreground `dataSync` service and continues when the Activity is normally backgrounded/closed.
- An ongoing Android notification shows analysis status.
- Covers/title/author/ISBN are enriched cache-free from the original backup where available.

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
- `android:allowBackup="false"`.
- Cleartext traffic is transport-enabled for private LAN compatibility, but application validation rejects public HTTP targets and unit tests cover this boundary.
- Foreground service is non-exported and uses `dataSync` type.
- No credential/Auth-header logging.

## Still incomplete / next priorities
1. Validate 1.0.7 against the user's real home-network KOSync/CWA/BookLore setup and large-library navigation.
2. Continue validating reading-progress recovery against complete Moon+ backups, especially path variants and duplicate filenames.
3. Improve exact book identity matching when Moon+ uses cryptic identifiers, using all available backup metadata before requiring manual assignment.
4. Add explicit position-quality model `EXACT`, `FALLBACK`, `UNRESOLVED` and structural EPUB translation where possible.
5. Verify Readest file/folder output end-to-end against real Readest imports and report converted/unconverted markings.
6. Harden per-book server transfer results and complete localization/responsive UI plus final release/signing/security audit.
7. WebDAV only after the above is stable.

## Development rule
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. Do not merge open revision PRs without explicit user approval.
