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
- Branch: `revision/1.0.15-selection-snapshot`
- PR: #31, open and not merged
- Version: `1.0.15`, versionCode 17
- Tested app-code head: `03e33cad1f41735af4379de07f954c6dabdc3ca3`
- Android CI run `34784119249`: success, including privacy scan, unit tests, Android lint, debug APK build, manifest/permission audit and artifact upload
- CodeQL run `34784119262`: success
- Debug artifact: `MoonExporter-1.0.15-debug`, artifact ID `10326485715`
- Artifact ZIP digest: `sha256:bc6ed4c1a3408851175a54e476f00400c58325dbabf365eeb979f387e9f474de`
- Verified APK SHA256: `7e2c1c42b832dab077a242f82b460816967b21dfbc1669dd8bdbd9c955fdcd57`
- Later commits after the tested app-code head update documentation/changelog only and do not alter the APK.

## 1.0.15 explicit selection preservation
Real-device feedback showed that a book without a usable book file and without reading progress still entered the export although the user had deselected it.

Root cause:
- the UI selection map lived only in Compose `remember` state
- opening Android's SAF folder picker can recreate the Activity
- after recreation the analyzed book list can repopulate and rebuild selection defaults before the folder picker result callback runs
- the callback previously read the then-current selection rather than the set the user had explicitly selected before opening SAF

Binding behavior from 1.0.15:
- the exact selected book keys are snapshotted immediately before opening the Readest folder picker
- that snapshot uses `rememberSaveable`, so it survives Activity recreation while SAF is open
- the picker result resolves only those stored keys against the current book list, or the restored analysis list if the local list has not repopulated yet
- the callback no longer broadens the export based on a rebuilt selection map
- books with no book file, no reading progress and no annotations are no longer selected by default after a fresh/restored analysis
- a source-less transfer item is non-fatal in `ExportService`: it is recorded as `SKIPPED_NO_SOURCE`, progress continues, and the rest of the session remains valid
- if a source-less item contains progress or annotations, the app reports that it was skipped because no book file is assigned; it is never silently reported as transferred
- source-less items without any transferable user data are skipped losslessly
- regression tests cover missing-source books with and without user data

## 1.0.14 stale transfer source recovery retained
- A deliberate manual export retry to the same Readest target never reuses an old unfinished payload verbatim.
- The old session is marked `SUPERSEDED` and a fresh session is created from the current selected books and current book sources.
- `completed_books` remains separate and authoritative for already committed fingerprints, so previously validated books can still be revalidated and skipped rather than blindly recopied.
- App-start automatic resume only starts when every persisted transfer item still has a usable book source.
- Legacy source-less sessions are left for a manual fresh retry instead of immediately failing again.
- A pending session for a different target remains blocked.
- Step 4 distinguishes running, interrupted/failed and successfully completed export states.

## Deterministic Readest validation retained
- Before writing a book, `ExportService` calculates the exact Readest partialMD5 from the persisted source ebook.
- That expected hash is stored in the transfer item and remains the authoritative target identity for the whole book transaction.
- Post-export validation validates exactly `Readest/Books/<expectedHash>/`.
- Validation requires ebook identity, optional known size, valid `config.json`, valid `library.json`, and a matching library row.
- Exact validation failures report the book title and concrete failed check.

## Retained resumable export behavior
- Direct Readest export runs in a dedicated non-exported foreground `dataSync` service.
- `START_REDELIVER_INTENT` plus app-start recovery resumes valid unfinished sessions.
- Transfer sessions and per-book checkpoints live in app-private SQLite.
- A book becomes `DONE` only after target validation succeeds.
- Completed checkpoints are revalidated before being skipped.
- Changed Moon+ progress/annotations/source metadata changes the fingerprint and forces reprocessing.
- Partial/wrong ebook files are rejected by size/hash checks and rebuilt.
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
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. PR #31 is authoritative for 1.0.15 and must not be merged without explicit user approval.
