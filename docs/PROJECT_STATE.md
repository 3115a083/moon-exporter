# Moon Exporter project state

Updated: 2026-09-13

## Product scope
Moon Exporter is a local Android migration tool for Moon+ Reader data.

Binding flow:
1. Analyze one Moon+ Reader backup, especially `.mrpro`.
2. Review analyzed books, reading progress and markings, then select individual books.
3. Choose Readest, generic KOSync, Calibre-Web Automated or BookLore as destination.
4. Export/transfer the selected books and show transfer progress at the bottom.

WebDAV remains deferred until this local flow is reliable.

## Current revision
- Repository: `3115a083/moon-exporter`
- Branch: `revision/1.0.11-resumable-export`
- PR: #24, open and not merged
- Version: `1.0.11`, versionCode 13
- minSdk 26, targetSdk 35, compileSdk 35
- Final CI/CodeQL IDs and APK hash are recorded after the final app-code head succeeds.

## 1.0.11 reliability goal
Real-device testing showed that direct Readest export could stop when the activity left the foreground. A process interruption could leave a copied book folder without a matching `library.json` row or leave a file only partly written.

Binding behavior from 1.0.11:
- Direct Readest library export runs in a dedicated non-exported foreground `dataSync` service and is not owned by the Activity coroutine.
- The service uses `START_REDELIVER_INTENT`; the next visible app start also resumes a persisted unfinished session.
- Export state is checkpointed per book in app-private SQLite before and after relevant steps.
- A book becomes `DONE` only after its Readest book file, `config.json` and `library.json` row validate together.
- A previously completed book is never skipped solely because the local DB says DONE. The Readest target is revalidated first.
- If the serialized Moon+ book/progress/annotation source fingerprint changes, the old completed checkpoint is not reused.
- Only one unfinished direct-Readest session is reused for a target, preventing parallel duplicate sessions.

## Target integrity and orphan repair
Before and during a resumed export Moon Exporter audits the selected Readest `Books` directory.

Checks include:
- expected 32-hex Readest book directory
- EPUB/PDF existence
- expected source size when known
- recomputed Readest partialMD5 matching the target directory hash
- valid per-book `config.json`
- valid `library.json`
- matching `library.json` row for the Readest book hash
- Moon Exporter temporary `.part` files

Repair behavior:
- A partial/wrong ebook is removed only after its size/hash fails the known expected identity, then the saved book job is rerun.
- Corrupt `config.json` or `library.json` is restored from the Moon Exporter recovery backup when one exists.
- If the very first JSON write was interrupted and no previous file therefore existed, corrupt first-write `config.json`/`library.json` can be reset to `{}`/`[]` and rebuilt from the persisted book checkpoint.
- A new hash directory appearing during a failed book attempt is associated with that current transfer as an orphan candidate and checked on retry/resume.
- Each book gets an immediate second repair attempt after cleanup before the whole session is left in `INTERRUPTED` state.
- Native/unrelated Readest files and rows are never guessed or deleted.

## Recovery artifacts and cleanup
- `config.moon-exporter.bak.json` and `library.moon-exporter.bak.json` are temporary recovery files while a session is not fully committed.
- Moon Exporter `.part` files are temporary and removed by target audit.
- After every book has been verified and the session commits successfully, those recovery artifacts are removed.
- `moon-export.mrexpt` remains an intentional annotation fallback, not a temporary artifact.
- The local checkpoint database is stored only in the app-private data directory and is removed automatically by Android when the app is uninstalled.
- Android cannot guarantee a final cleanup callback if the app is uninstalled exactly while an external SAF file is being written. Therefore external writes must be self-validating and repairable on a later run rather than relying on uninstall cleanup.

## Persistent backup analysis
- The last completed `.mrpro` analysis is stored in the same app-private SQLite database.
- Cache identity uses the granted source URI plus size and last-modified timestamp.
- If that source identity is unchanged, the book list can be restored without re-running the full backup scan.
- If it changed, the backup is analyzed again.
- Cached covers are not persisted as durable image blobs; restored books may initially show placeholders while retaining the important source/progress/annotation metadata.

## 1.0.10 behavior retained
- Existing Moon Exporter Readest annotations are preserved until a better exact replacement exists.
- Real EPUB XHTML is parsed tolerantly with bounded Jsoup parsing.
- Exact Readest range CFI is preferred; safe chapter fallback is retained when exact text resolution fails.
- Moon+ progress >= 99.95% writes Readest `readingStatus: "finished"` plus `readingStatusUpdatedAt`.
- Embedded ebooks are prepared only once per book export.

## Direct Readest structure
- `Readest/Books/library.json` is the local library index.
- Managed books live under `Readest/Books/<Readest bookHash>/`.
- Moon Exporter may write EPUB/PDF, `cover.png`, `config.json`, and `moon-export.mrexpt` fallback.
- `nav.json` is a derived Readest cache and is not fabricated.
- Readest `bookHash` uses Readest's own partialMD5 sampling and is distinct from KOReader/KOSync partialMD5.
- Existing book files with the same verified Readest hash are not duplicated.
- Percentage progress is a transparent fallback, not a real Readest page count.

## Moon+ reading progress
- Full `.mrpro` backups recover progress primarily from `shared_prefs/positions10.xml` SharedPreferences entries.
- EPUB positions commonly use `chapter@section#characterOffset:percent%`; PDF uses `page:percent%`.
- Timestamped `.po` remains a fallback for other Moon+ variants.
- Original raw positions are always preserved.

## KOSync/CWA/BookLore
- Transfer authentication, document identity and reading progress only. Ebook files are never uploaded through these APIs.
- KOReader-compatible `partialMD5` identifies a book already present at the target.
- Moon+ percent `0..100` is normalized to KOSync `percentage` `0..1`.
- HTTP is allowed only for private/local home-network targets; public cleartext HTTP remains blocked.
- Never invent KOReader XPointer values.

## Security and privacy
- No telemetry, analytics, ads or cloud crash reporting.
- No real user backup, Readest or ebook data in source, tests, logs or CI artifacts.
- SAF only for user files. No broad storage permissions.
- `android:allowBackup="false"`.
- No credential/Auth-header logging.
- App-private SQLite/checkpoint state disappears on uninstall.

## Next real-device checks
1. Start a multi-book Readest export, switch to another app for several minutes and verify transfer continues from the notification/service.
2. Force-close/kill during ebook copy, then reopen and verify the partial target is detected and repaired without duplicating the book.
3. Kill after the book directory exists but before the library row commits, then reopen and verify the row is reconstructed.
4. Kill during `config.json`/`library.json` writing and verify backup/reset recovery plus checkpoint replay.
5. Repeat export unchanged and verify already validated books are skipped; then change Moon+ progress/annotations and verify only changed fingerprint work is replayed.
6. Verify no `.part` or recovery `.bak.json` files remain after a fully successful session.

## Development rule
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. PR #24 is authoritative for 1.0.11 and must not be merged without explicit user approval.
