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
- Branch: `revision/1.0.13-export-result-hash`
- PR: #27, open and not merged
- Version: `1.0.13`, versionCode 15
- Tested app-code head: `6918cfdc6483069b5742e0aa28cf6d8fe413af25`
- Android CI run `34775655809`: success, including privacy scan, unit tests, lint, debug APK, manifest/permission audit and artifact upload
- CodeQL run `34775655830`: success
- Debug artifact: `MoonExporter-1.0.13-debug`, artifact ID `10323835806`
- Artifact ZIP digest: `sha256:a51d0ab9b32034224a92915f62724be6adeb1daf7ac1df24a2d538260e7c0f5b`
- Verified APK SHA256: `6a548c315d87a6b355c902bed1768c03f99ba7a5fcf1826f2d6db514fd855306`

## 1.0.13 deterministic Readest validation
Real-device feedback showed that 1.0.12 could still report `Readest-Ziel konnte nach dem Export nicht eindeutig validiert werden`.

Binding behavior:
- Before writing a book, `ExportService` calculates the exact Readest partialMD5 from the persisted source ebook.
- That expected hash is stored in the transfer item immediately and remains the authoritative target identity for the whole book transaction.
- Post-export validation no longer tries to rediscover the book that was just written. It validates exactly the expected hash folder.
- If the source hash cannot be calculated before writing, the export stops before target modification and reports a specific source-identity error.
- If strict validation fails, the UI reports the exact failed target check and book title instead of the former generic ambiguity message.
- Strict checks remain unchanged: ebook exists, optional known size matches, recomputed Readest partialMD5 matches the hash folder, `config.json` is valid, `library.json` is valid and contains the matching hash row.
- No validation rule is weakened and no title/ISBN guess is used to commit the just-exported book.

## Retained resumable export behavior
- Direct Readest export runs in a dedicated non-exported foreground `dataSync` service.
- `START_REDELIVER_INTENT` plus app-start recovery resumes unfinished sessions.
- Transfer sessions and per-book checkpoints live in app-private SQLite.
- A book becomes `DONE` only after target validation succeeds.
- Completed checkpoints are revalidated before being skipped.
- Changed Moon+ progress/annotations/source metadata changes the fingerprint and forces reprocessing.
- Partial/wrong ebook files are rejected by known size/hash checks and rebuilt.
- Corrupt `config.json`/`library.json` can be restored from recovery backups or rebuilt after interrupted first creation.
- Each failed book gets one immediate repair retry before the session remains interrupted.
- Recovery `.bak.json` and Moon Exporter `.part` files are removed after a fully verified session.
- Last completed `.mrpro` analysis is cached app-privately and reused only while URI/size/lastModified remain unchanged.
- App-private DB/cache state disappears on uninstall.

## Readest integrity rules
- `Readest/Books/library.json` is the local library index.
- Managed books live under `Readest/Books/<Readest bookHash>/`.
- Readest bookHash uses Readest partialMD5 and is distinct from KOReader/KOSync partialMD5.
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
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. PR #27 is authoritative for 1.0.13 and must not be merged without explicit user approval.
