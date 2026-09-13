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
- Branch: `revision/1.0.13-exporter-hash`
- PR: #28, open and not merged
- Version: `1.0.13`, versionCode 15
- Tested app-code head: `cad4b92e2d47b77d88365db6e26e1c68cb923a33`
- Android CI run `34776081949`: success, including privacy scan, unit tests, lint, debug APK, manifest/permission audit and artifact upload
- CodeQL run `34776081790`: success
- Debug artifact: `MoonExporter-1.0.13-debug`, artifact ID `10323043567`
- Artifact ZIP digest: `sha256:b289081e8ece0c36c217189e9adda3ff9416d8ba2e974eaffc5b707fed02f8d9`
- Verified APK SHA256: `7907a6254436723d4b31315d07c9c6d9d98b080b9d4b378cb8da129560a06cde`

## 1.0.13 deterministic Readest validation
Real-device feedback showed that 1.0.12 could still report the same generic validation error after a successful-looking write.

Binding behavior:
- A single-book export records its attempt start time.
- After `ReadestDirectExporter` finishes, Moon Exporter scans only valid 32-hex Readest folders and accepts a recently touched folder only when its `config.json` contains the same folder `bookHash`, its `updatedAt` is from the current attempt, and `library.json` contains the same hash.
- This identifies the folder written by the current export even when the folder already existed before the attempt and therefore cannot appear as a newly-created directory.
- Persisted known hash and newly-created hash remain preferred when available.
- Metadata matching and exact source partialMD5 remain fallback identity paths, not primary proof.
- Strict validation still checks ebook presence/size, recomputed Readest partialMD5, valid config, matching config bookHash and matching library row.
- Validation errors now identify the actual missing stage instead of returning only the previous generic message.
- Regression tests cover precedence of the recently committed hash over metadata/source fallbacks.

## Retained reliability behavior
- Direct Readest library export runs in a dedicated non-exported foreground `dataSync` service and is not owned by the Activity coroutine.
- `START_REDELIVER_INTENT` plus app-start recovery resumes unfinished sessions.
- Transfer sessions and per-book checkpoints live in app-private SQLite.
- A book becomes `DONE` only after ebook, `config.json` and `library.json` row validate together.
- Completed checkpoints are revalidated at the target before being skipped.
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
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. PR #28 is authoritative for 1.0.13 and must not be merged without explicit user approval.
