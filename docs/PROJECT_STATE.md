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
- Branch: `revision/1.0.14-refresh-session-sources`
- PR: #29, open and not merged
- Version: `1.0.14`, versionCode 16
- Tested app-code head: `c8dd5d8bc5d3317087bbd199677b44e4502e700c`
- Android CI run `34781062706`: success, including privacy scan, unit tests, Android lint, debug APK build, manifest/permission audit and artifact upload
- CodeQL run `34781062693`: success
- Debug artifact: `MoonExporter-1.0.14-debug`, artifact ID `10325086650`
- Artifact ZIP digest: `sha256:e4c4698b65e7363ba9618d41edf72b6af962bf249624eb19ff8490773bb7f868`
- Verified APK SHA256: `29a64d15e0e9e337274e62b211a655730cc52f36564e856c1aff7ac7d8f1f1df`
- Later commits after the tested app-code head update documentation/changelog only and do not alter the APK.

## 1.0.14 stale transfer source recovery
Real-device feedback from 1.0.13 reported a missing stored book source while the current analyzed book list already contained the book.

Root cause:
- `Exporter` reused an unfinished transfer session for the same Readest target without refreshing its persisted BookItem payload.
- An older interrupted session could therefore still contain `epub = null` from an earlier app/version state.
- The current UI analysis could have a valid source while the background service kept reading the stale session copy.

Binding behavior from 1.0.14:
- A deliberate manual export retry to the same Readest target never reuses the old unfinished payload verbatim.
- The old session is marked `SUPERSEDED` and a fresh session is created from the current selected books and current book sources.
- `completed_books` remains separate and authoritative for already committed fingerprints, so previously validated books can still be revalidated and skipped rather than blindly recopied.
- App-start automatic resume only starts when every persisted transfer item still has a usable book source.
- Legacy source-less sessions are left for a manual fresh retry instead of immediately failing again.
- A pending session for a different target is still blocked to avoid cross-target confusion.
- Regression tests cover same-target supersession, different-target blocking and auto-resume source requirements.

## 1.0.14 export status UI
- Step 4 no longer reports success just because the Activity-side coroutine stopped.
- While running, the verbose sub-step explanation remains visible.
- On failure/interruption it shows `Export unterbrochen oder fehlgeschlagen.` or `Export unterbrochen.` as appropriate.
- `Export erfolgreich beendet.` is shown only after a completed export with full progress.

## 1.0.13 deterministic Readest validation retained
- Before writing a book, `ExportService` calculates the exact Readest partialMD5 from the persisted source ebook.
- That expected hash is stored in the transfer item and remains the authoritative target identity for the whole book transaction.
- Post-export validation validates exactly `Readest/Books/<expectedHash>/`.
- Validation still requires ebook identity, optional known size, valid `config.json`, valid `library.json`, and a matching library row.
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
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. PR #29 is authoritative for 1.0.14 and must not be merged without explicit user approval.
