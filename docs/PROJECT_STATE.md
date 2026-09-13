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
- Branch: `revision/1.0.6-progress-scrollbar`
- PR: #16
- Version: `1.0.6`, versionCode 8
- minSdk 26, targetSdk 35, compileSdk 35
- Based on the tested 1.0.5 foreground-analysis branch.

## Real-backup finding behind 1.0.6
A user-provided trimmed `.mrpro` sample was inspected transiently and was not added to source, tests, logs or artifacts.

The archive index `_names.list` identifies `shared_prefs/positions10.xml` as a Moon+ backup item. The trimmed sample omitted the corresponding payload because most later `.tag` files were intentionally removed, but another included shared-preferences file confirmed Moon+'s standard Android SharedPreferences representation.

This exposed the main progress regression: the old parser expected XML elements carrying `filename`/`position` attributes. Moon+ `positions10.xml` instead stores position values as Android SharedPreferences strings, conceptually:
`<map><string name="book/path.epub">timestamp*chapter@section#offset:percent%</string></map>`.

1.0.6 therefore parses this form directly and keeps `.po` parsing as an additional fallback.

## 1.0.6 behavior
- `positions10.xml` string entries are converted through the existing defensive Moon+ position parser.
- Raw Moon+ values are preserved. EPUB-style values can preserve timestamp, chapter, section, character offset and percentage; PDF-style values preserve page and percentage when present.
- Full path, basename and filename-stem aliases are used for matching to the analyzed book database, including removal of Moon+'s leading `?` path marker.
- If multiple position sources exist, the newest timestamp wins.
- A synthetic test covers the SharedPreferences structure without real backup data.
- Large book lists have a draggable/tappable alphabet scrollbar for fast jumps by initial letter.
- Tapping the whole book card toggles selection; the checkbox footprint and surrounding horizontal space are reduced.
- Books that have Moon+ progress but no reliable ebook/document identity show a direct per-book SAF picker. The recovered Moon+ progress is preserved when a file is assigned.
- Connection testing shows visible inline status for testing, success or failure.

## Conversion rules
### KOSync / CWA / BookLore
- Recovered Moon+ percentage is normalized from 0..100 to KOReader-compatible `percentage` 0..1.
- The readable progress string remains a percentage.
- KOReader-compatible `partialMD5` from the matching ebook identifies an already existing target book.
- Ebook files are never uploaded through KOSync/CWA/BookLore.

### Readest
- Moon+ markings continue to be exported as `.mrexpt` for Readest's Moon+ import path.
- Raw and percentage reading progress remain available for reporting/fallback.
- An exact EPUB CFI must not be invented. Exact structural conversion requires the actual EPUB and a justified mapping from Moon+'s chapter/section/offset to the target structure.

## 1.0.5 retained behavior
- `.mrpro` analysis runs in a foreground data-sync service and continues when the Activity is normally backgrounded/closed.
- An ongoing Android notification shows analysis status and progress.
- Light system bars use readable dark icons.
- Book cards show cover/title/author and progress information.
- Embedded EPUB metadata streams from the original backup without persistent ebook cache copies.
- Numeric/hash-like filenames remain opaque unless reliable metadata reconstructs the title.

## Hard boundaries
- KOSync/CWA/BookLore transfer authentication, document identity and reading progress only.
- Moon+ raw position values are preserved.
- Exact target positions must never be invented.
- `.an` markings are preserved and exported through Moon+/Readest-compatible `.mrexpt` handling.

## Security and privacy
- No telemetry, analytics, ads or cloud crash reporting.
- No real user backup data in source, tests, logs or CI artifacts.
- SAF only for user files. No broad storage permissions.
- `android:allowBackup="false"` and cleartext traffic disabled.
- Foreground service is non-exported and uses `dataSync` type.
- No credential/Auth-header logging.

## Still incomplete / next priorities
1. Validate 1.0.6 against the user's complete backup, especially `positions10.xml` matching counts and target transfer.
2. Add explicit position-quality model `EXACT`, `FALLBACK`, `UNRESOLVED` and structural EPUB translation where possible.
3. Verify Readest file/folder output end-to-end against real Readest imports and report converted/unconverted progress/markings.
4. Harden per-book CWA/BookLore/generic KOSync results.
5. Complete localization/responsive UI and final release/signing/security audit.
6. WebDAV only after the above is stable.

## Development rule
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. Do not merge open revision PRs without explicit user approval.
