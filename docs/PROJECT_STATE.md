# Moon Exporter project state

Updated: 2026-09-13

## Product scope
Moon Exporter is a local Android migration tool for Moon+ Reader data.

Binding flow:
1. Analyze one Moon+ Reader backup, especially `.mrpro`.
2. Review books, progress and markings, then select individual books.
3. Choose Readest, generic KOSync, Calibre-Web Automated or BookLore.
4. Export/transfer selected data with visible progress.

WebDAV remains deferred until the local backup, Readest and KOSync paths are reliable.

## Current revision
- Repository: `3115a083/moon-exporter`
- Branch: `revision/1.0.12-readest-validation`
- PR: #26, open and not merged
- Version: `1.0.12`, versionCode 14
- Tested app-code head: `774b86f116288c89d8a76d273a206ea9531846f5`
- Android CI run `34773493180`: success, including privacy scan, unit tests, lint, debug APK, manifest/permission audit and artifact upload
- CodeQL run `34773493256`: success
- Debug artifact: `MoonExporter-1.0.12-debug`, artifact ID `10323056571`
- Artifact ZIP digest: `sha256:4b4c21620f52c96b732cc5c0e1c18bd43d23e6374671ae16c45d7d6127b3db96`
- Verified APK SHA256: `214b13021cf72a724c471ffe7b63a804410aa750aed65e4d84c9ebbb96ab0e9e`

## 1.0.12 Readest target validation fix
Real-device feedback from 1.0.11 produced `Readest-Ziel konnte nach dem Export nicht eindeutig validiert werden` although the copy itself could already be correct.

Root cause:
- `ReadestDirectExporter` calculates the exact Readest book hash while exporting.
- `ExportService` did not retain that exact identity.
- If the corresponding hash folder already existed before the current attempt, `discoverSingleNewHash()` correctly found no newly-created folder.
- Metadata lookup by title/ISBN can legitimately fail or be ambiguous.
- The service then had no hash to validate and reported the generic validation error.

1.0.12 behavior:
- Existing fast identity paths remain first: newly-created hash, persisted known hash, unambiguous library metadata match.
- If all of those are unavailable, `ReadestIdentity` calculates the exact Readest partialMD5 from the original source ebook.
- For embedded `.mrpro` books this fallback reopens only the required archive entry and uses a temporary app-cache file that is deleted immediately.
- The exact source hash is used only to identify the target folder. All strict 1.0.11 checks still run afterwards.
- The same fallback is used in normal post-export validation and interrupted-export recovery.
- No validation rule was weakened and no title-only guess is accepted as proof of book identity.
- Regression tests verify that the exact source hash is selected when a valid existing target was not newly created and metadata lookup cannot identify it.

## 1.0.11 retained reliability behavior
- Direct Readest library export runs in a dedicated non-exported foreground `dataSync` service and is not owned by the Activity coroutine.
- `START_REDELIVER_INTENT` plus app-start recovery resumes unfinished sessions.
- Transfer sessions and per-book checkpoints live in app-private SQLite.
- A book becomes `DONE` only after ebook, `config.json` and `library.json` row validate together.
- A completed checkpoint is revalidated at the target before it is skipped.
- Changed Moon+ progress/annotations/source metadata changes the fingerprint and forces reprocessing.
- Partial/wrong ebook files are rejected by known size/hash checks and rebuilt.
- Corrupt `config.json`/`library.json` can be restored from recovery backups or rebuilt after interrupted first creation.
- Each failed book gets one immediate repair retry before the session remains interrupted.
- Recovery `.bak.json` and Moon Exporter `.part` files are removed after a fully verified session.
- The last completed `.mrpro` analysis is cached app-privately and reused only while URI/size/lastModified remain unchanged.
- App-private DB/cache state disappears on uninstall.

## Readest integrity rules
- `Readest/Books/library.json` is the local library index.
- Managed books live under `Readest/Books/<Readest bookHash>/`.
- Readest bookHash uses Readest partialMD5 and is distinct from KOReader/KOSync partialMD5.
- Target validation checks folder hash, EPUB/PDF existence, size when known, recomputed Readest hash, valid `config.json`, valid `library.json`, and matching library row.
- Native/unrelated Readest files and rows are never guessed or deleted.
- `nav.json` is derived by Readest and is not fabricated.
- `moon-export.mrexpt` is an intentional lossless annotation fallback, not a temporary artifact.

## Security and privacy
- No telemetry, analytics, ads or cloud crash reporting.
- No real user backup, Readest or ebook data in source, tests, logs or CI artifacts.
- SAF only for user files. No broad storage permissions.
- `android:allowBackup="false"`.
- No credential/Auth-header logging.
- Android cannot guarantee a final external SAF cleanup callback if uninstall happens exactly during a write. External writes must therefore remain detectable and repairable rather than relying on uninstall cleanup.

## Development rule
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. PR #26 is authoritative for 1.0.12 and must not be merged without explicit user approval.
